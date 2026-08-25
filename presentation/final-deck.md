---
marp: true
theme: default
paginate: true
header: 'UnoArena — Arquitectura de Microservicios · ITBA 73.40'
---

# UnoArena

### Plataforma de Uno en tiempo real y torneos masivos

Entrega final — arquitectura y decisiones

<!--
El sistema completo corre desde un cluster vacío con un solo comando. Diez servicios
independientes, veinticuatro aplicaciones de Argo, observabilidad incluida.
Números para tener a mano: de cluster vacío a 24/24 en 8m53s sobre EKS — el target del examen —
y entre 12 y 18 minutos en kind. La diferencia es descarga de imágenes, no trabajo: 798 de los
1068 segundos de la medición más lenta en kind fueron pulls, y dos nodos EC2 en us-east-1 bajan
en paralelo. 11 targets de scrape, pipeline de 44 jobs.
-->

---

## El dominio

- **Casual:** salas de 2–4 jugadores, una partida de Uno con todas sus reglas
  — comodines, `+2`, `+4`, el llamado de **UNO!** y el desafío.
- **Torneos:** N jugadores, rondas, mejor de tres, un campeón.
- **Espectadores:** cualquiera mira una sala en vivo — y **nunca** ve una mano.
- **Ranking y analytics:** Elo sobre partidas casuales, proyecciones de lectura.

> El cliente es la CLI de la cátedra: es la que maneja el sistema.

<!--
El alcance salió de Consigna.md (marzo) y se cerró contra docs/final/consigna.md.
La CLI no es un artefacto que entregamos por separado: es el arnés con el que la cátedra
evalúa el backend. Todo lo que no se puede manejar desde la CLI, no se puede evaluar.
-->

---

## Arquitectura final — diez desplegables

| | |
|---|---|
| `gateway` | **la única puerta**: valida el token, enruta, sirve el SSE |
| `identity` | cuentas, sesión única activa, JWT |
| `room-gameplay` | el núcleo event-sourced: salas, jugadas, el log inmutable |
| `outbox-relay` | drena el outbox a Kafka como CloudEvents (**corre dos veces**) |
| `timer-worker` | el reloj detrás de los deadlines durables |
| `tournament` | el orquestador: log propio, outbox propio, saga, reconciliador |
| `ranking` | Elo + rating de posición |
| `spectator` | proyección pública de una sala en vivo |
| `analytics-workers` + `analytics-api` | tres read models CQRS y su API |

<!--
Cada uno con su lenguaje: TypeScript, Kotlin, Python, Go. No es decoración — prueba que
build y test son genuinamente independientes, no una imagen base compartida disfrazada.
Seis grupos de consumidores, tres pares de contrato verificados en CI.
-->

---

## Una sola puerta

- Todo lo externo entra por `gateway` en **30080**. `identity` y `room-gameplay`
  son `ClusterIP`: no se alcanzan desde afuera.
- `room-gameplay` **no tiene la clave de firma**. Confía en `X-Player-Id` y
  `X-Session-Id`, que el gateway **sobrescribe en cada request**.

> Ese "sobrescribe" es el control sobre el que se apoya todo el límite de confianza:
> un cliente no puede aportar sus propias cabeceras.

<!--
ADR-05. La alternativa era cliente-a-servicio directo, que obliga a validar el token en
cada servicio y multiplica el lugar donde vive la clave. Acá la clave vive en identity y
en el gateway, y en ningún otro lado.
Un `/internal` existe para el timer y para la provisión de salas, cerrado con un token sellado.
-->

---

## El log es la autoridad

Cada jugada aceptada se **appendea a `room_events` antes** de que nadie vea el resultado,
en la **misma transacción** que las filas del outbox.

- Si un agregado reconstruido discrepa del estado servido, **gana el log**.
- `publicPayload(event)` saca la semilla del RNG **dentro de esa transacción** —
  para cuando algo llega a Kafka, el mazo ya no está.

```
decide(state, command) → events    evolve(state, event) → state
```

<!--
ADR-01 y ADR-02. El outbox transaccional es lo que evita el dual-write: no hay un momento
en que la fila esté escrita y el evento no, ni al revés.
El motor de reglas no tiene framework en el classpath: las suites de propiedades corren miles
de partidas generadas sin base de datos ni contenedor.
`grep -c seed` sobre los dos topics, el SSE del espectador y la salida de la CLI da 0.
-->

---

## La espina asíncrona

- **Kafka** con envoltorio **CloudEvents** (`ce-type` es una URI reverse-DNS, no el nombre).
- **Al menos una vez**, en orden por sala — el relay drena en orden de `id`.
- **Seis grupos de consumidores**, **tres pares de contrato** verificados en CI.
- El stream de **Redis** es un segundo camino, transitorio, para el feed en vivo.
  Nunca se construye un consumidor sobre él: **el durable es Kafka**.

<!--
ADR-04 y ADR-10. El relay es agnóstico del origen desde P7: la misma imagen, el mismo digest,
dos Deployments — uno drena el outbox de room-gameplay, el otro el de tournament.
Por eso los paneles del relay se parten `by (job)`: sumados, uno puede dejar de drenar sin que se note.
-->

---

## Torneos: coreografía **y** una transacción

- El avance de ronda **es** una saga coreografiada: la sala decide el mejor de tres
  y publica `MatchCompleted` con `advancingPlayers`.
- Pero la **provisión de salas es síncrona**, por `POST /internal/rooms`.

> Si las salas se crearan por evento, `RoundStarted` sería una promesa: nombraría salas
> que quizá todavía no existen. Creándolas antes, **no puede** nombrar una sala que nadie creó.

<!--
Esto es una desviación consciente respecto de ADR-06, registrada en CHANGELOG-design.md 12.1.
Vale decirla en voz alta: convierte el contador "rooms_created / rooms_expected" de una
reconciliación en una transacción. La dirección Customer-Supplier del context map no cambia:
el torneo pide, room-gameplay sigue siendo dueño de qué es una sala.
-->

---

## Entrega: construir una vez, promover por digest

```
test → build → deliver → deploy-staging → integration-staging → (prod)
```

- **Detección de cambios por path**: tocar un servicio corre **solo** ese servicio.
- `deliver` captura el `@sha256:…`; un commit del bot lo escribe en el overlay.
  Producción recibe **el mismo digest** — nunca un rebuild.
- **GitOps con Argo CD**: el pipeline nunca hace `kubectl apply`. Escribe git; Argo reconcilia.

<!--
El runner no tiene credenciales de cluster: Argo tira, el pipeline sólo commitea un digest.
Pipeline más ancho: 44 jobs, 43 success + 1 manual — 43 en un push normal, porque GitLab evalúa
`changes:` como siempre verdadero fuera de un push y el job del espejo entra en una corrida
disparada a mano.
Desde P9 las bases distroless están espejadas en el registry del proyecto: gcr.io rechazó a los
runners seis veces entre P6 y P8, y kaniko no reintenta el pull de la base ("after 0 attempts").
-->

---

## Observabilidad — del mismo install

- **Tres tableros como JSON commiteado**: negocio, golden signals, espina asíncrona.
- **Nueve reglas de alerta**; cada una es una falla que este proyecto tuvo de verdad.
- **Loki + Alloy**: un `correlationId` devuelve **cinco servicios** en una query.

**Las tres métricas de negocio** — nombradas en el tablero mismo:

`roomgameplay_games_completed_total` · `tournament_tournaments_completed_total` ·
`identity_registrations_total`

<!--
Cada una se cuenta desde un EVENTO DE DOMINIO COMMITEADO, no desde el request que parecía causarla:
una partida que termina por abandono igual cuenta.
Un detalle para decir si preguntan por qué no hay un tablero de "todo": un panel que consulta una
métrica que nadie emite dibuja un gráfico vacío, y un gráfico vacío se lee como una caída.
Preferimos un panel que nombre la ausencia.
-->

---

## Los números

| | |
|---|---|
| Desplegables reales | **10** (ninguno con `digest: ""`) |
| Aplicaciones de Argo | **24** |
| De cluster vacío a 24/24 | **8 m 53 s** _(EKS, R1)_ — kind: 12–18 min, dominado por pulls |
| Targets de scrape | **11** |
| Servicios en una query de `correlationId` | **5** |
| Pipeline más ancho | **44 jobs** — 43 success + 1 manual |
| Eventos que publica un demo entero | **179** |

<!--
Ese último número es el que enseñó la lección más cara de P8: dos de las nueve alertas tenían
umbrales de producción — una de ellas 500 de lag de consumidor — sobre un sistema que publica 179
eventos en todo un demo. No podían dispararse nunca. Un umbral es una medición, no una opinión.
-->

---

## Lo que **no** hicimos

- **Sin backend de tracing.** Los logs con `correlationId` responden la misma
  pregunta acá; los spans de OpenTelemetry quedan como opción declinada.
- **Siete de nueve reglas de alerta nunca se vieron disparar** — están listadas
  como no probadas, no como cobertura.
- **Sin receivers de alertas.** El plano de alerta existe; nadie paginea un canal que nadie lee.
- **Sin almacenamiento persistente** para observabilidad.

> Todo esto está escrito en el repo, no dicho acá por primera vez.

<!--
La honestidad es criterio de corrección explícito (§8 del Client-Checkpoint: un hueco documentado
es mejor que uno silencioso). Está en specs/2026-08-18-p8-observability/ESTADO-FINAL.md y en
CHANGELOG-design.md, que registra cada lugar donde el sistema difiere del diseño y por qué.
-->

---

## Qué haríamos después

- **Receivers y SLOs**: las alertas existen; falta a quién avisarle y contra qué objetivo.
- **Tracing distribuido**, cuando haya más de un salto asíncrono que seguir.
- **Escalar `ranking`**: sus dos escrituras de rating ya son seguras ante concurrencia,
  así que las réplicas volvieron a ser una decisión de capacidad.
- **Multi-región** — hoy explícitamente fuera de alcance.

<!--
Cerrar acá: el sistema está completo y probado desde vacío; lo que sigue es operación, no diseño.
Probado sobre el target real: creamos un cluster EKS desde cero, instalamos el sistema entero en
8m53s, manejamos la CLI de la cátedra contra él y lo destruimos por unos US$0,30 — el ensayo R1,
en specs/2026-08-20-p9-rehearsal-presentation/.
Si preguntan por límites de recursos: los diez contenedores declaran requests y un límite de
memoria desde P9, dimensionados desde uso medido, y los dos servicios Kotlin fijan el heap de la
JVM junto con ese límite — porque una JVM lee el límite del contenedor y por defecto toma el 25%.
-->

---

# Gracias

**Repo:** `gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp`

Runbook del demo: `docs/demo-runbook.md`
Runbook de observabilidad: `docs/observability-runbook.md`
Desviaciones de diseño: `CHANGELOG-design.md`
