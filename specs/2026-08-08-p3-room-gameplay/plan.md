# Plan — P3: Uno Engine + Room-Gameplay Core

> How [`requirements.md`](./requirements.md) gets built, in commit-sized phases ordered by
> de-risk: the platform seam first (so nothing is ever blocked on plumbing), then the **pure
> engine** — the program's biggest unknown — before a single line of HTTP touches it. Design
> decisions are the implementer's (D-n); flag objections early, they harden as they ship.

## Design decisions (confirm in review)

- **D1 — The engine is a separate Gradle module with zero framework dependencies.**
  `engine/` exposes two pure functions — `decide(state, command): Result<List<Event>>` and
  `evolve(state, event): State` — and knows nothing about HTTP, SQL or Kafka. That separation is
  what makes AC-P3.2 possible at all: property tests and log replay run in milliseconds with no
  container, and the rules can be argued about without reading infrastructure code.
- **D2 — Determinism is a first-class requirement.** The only randomness is a server-generated
  seed recorded in `GameStarted` and in every `DeckRecycled`. `evolve` reconstructs the deck from
  the seed, so replaying the log reproduces the exact deck order. No `Random()` anywhere else in
  the module — a property test asserts that replaying any generated game twice gives identical
  state.
- **D3 — The clock is a port.** `decide` takes the current instant as a parameter. Deadline
  behaviour (E2) is then testable without sleeping, and the production wiring passes
  `Instant.now()` at the edge.
- **D4 — Command and event names match `docs/design/04-commands-events.md` exactly.** They become
  the Kafka payloads in P5 and the consumers' contract in P6/P7; renaming later is a breaking
  change disguised as a refactor.
- **D5 — Four tables.** `room_events(room_id, sequence_number, type, payload jsonb,
  correlation_id, created_at)` with a unique index on `(room_id, sequence_number)` — that index is
  E6's mechanism, not a nicety; `outbox` (same transaction, privacy-filtered payloads);
  `rooms` as a small projection updated in the same transaction so `GET /rooms` does not replay
  the world; `idempotency_keys` for `POST /rooms`, swept after 24 h per persistence-layer §1.4.
- **D5b — `GET /rooms` is a delta.** The architecture's resource table has `POST /rooms` and
  `GET /rooms/{id}` but no collection read; `room list` needs one. It is additive and read-only,
  and goes in `CHANGELOG-design.md` with the auto-start delta.
- **D6 — Per-service credentials, exactly like identity's.** A CNPG managed role `room_gameplay`
  owning the `room_gameplay` database, password sealed by `gitops/secrets/seal.sh`. Copying the
  pattern rather than inventing a second one is the point.
- **D7 — Metrics** (business first, per the instrument-as-you-go rule):
  `roomgameplay_rooms_created_total`, `_games_started_total`, `_games_completed_total`,
  `_moves_total{type,result}`, `_engine_rejections_total{reason}`,
  `_command_duration_seconds{route,status}`. `moves_total` and `games_completed_total` are two of
  P8's three required business metrics.
- **D8 — The session-events consumer is idempotent by `oldSessionId`.** A redelivered
  `SessionInvalidated` must not re-open a reconnection window that already expired; the aggregate
  ignores a disconnect for a player who is already disconnected or gone.
- **D9 — The JWT secret is shared with identity through the same sealed secret.** Documented as a
  P4 hand-off: once the gateway owns validation, room-gameplay drops the secret and trusts
  `X-Player-Id`/`X-Session-Id` inside the trust boundary. Until then, sharing a symmetric secret
  between two services is a real (small, internal) coupling and it is written down rather than
  hidden.
- **D10 — The CLI polls with `If-None-Match` at ~1 s.** The `304` path keeps it cheap; the polling
  loop lives behind the same interface the SSE stream will implement in P4, so `play` does not get
  rewritten, only re-wired.
- **D11 — `minPlayers` and the turn timer are configuration, not constants** (`ROOM_MIN_PLAYERS`
  default 2, `TURN_TIMEOUT_SECONDS` default 30). Drills and the exam demo need to change them
  without a rebuild, and the tournament threshold in P7 will want the same lever.

## Phases (one commit each)

| Phase | Delivers | Validated by |
|-------|----------|--------------|
| F0 | This triad | review |
| F1 | Platform seam: CNPG role + `room_gameplay` ownership, sealed secrets, chart env/probes/ServiceMonitor, digest pin | fresh kind: `psql -U room_gameplay`, pod pulls by digest |
| F2 | Ktor skeleton: JWT auth filter, `/health`, `/metrics`, structured logs with `correlationId` | `curl` with and without a token through the NodePort |
| F3 | `engine/`: cards, seeded deck, hands, turn order, direction — pure, property-tested | property suite green, zero framework deps in the module |
| F4 | `engine/`: the full command set + all 14 invariants + first-card rule + deck recycle | property + log-replay suites green |
| F5 | Event store: `room_events` + `outbox` in one transaction, optimistic concurrency, idempotency keys, startup migration | failure-injection test leaves no rows; concurrent-writer test |
| F6 | Rooms REST + `rooms` projection + auto-start at `minPlayers` | AC-P3.1 checks against the cluster |
| F7 | Moves REST: `If-Match`/`412`/`428`/`409`, player-scoped state with `ETag` | AC-P3.4 checks; hand never appears in another player's view |
| F8 | Lazy deadline expiry (Uno! window, turn timeout, reconnection) + `identity.session-events` consumer | AC-P3.5, AC-P3.6 drills |
| F9 | CLI: `room create/join/list/leave`, `play --casual`, interactive loop over polling | a full game played by hand, locally |
| F10 | Empty-cluster drill (AC-P3.7/8) + transcripts + README/CHANGELOG deltas + `ESTADO-FINAL.md` | recorded in `validation.md` |

F3 and F4 touch only `engine/`, so they run `test:room-gameplay` and nothing else. The CLI phase
(F9) is the first one that can break `integration-staging`, and it lands before the merge.

## Changes by file

**Service**

- `services/room-gameplay/settings.gradle.kts` — add the `engine` module.
- `services/room-gameplay/engine/` — `Cards.kt`, `Deck.kt`, `Game.kt`, `Room.kt`, `Commands.kt`,
  `Events.kt`, `Decide.kt`, `Evolve.kt` + `src/test/kotlin/` property and replay suites.
- `services/room-gameplay/src/main/kotlin/` — `Main.kt` (Ktor wiring), `Auth.kt` (JWT + claims),
  `Routes.kt`, `EventStore.kt` (JDBC, one transaction), `Migrate.kt`, `Projections.kt`,
  `SessionEvents.kt` (Kafka consumer), `Metrics.kt`.
- `services/room-gameplay/build.gradle.kts` — Ktor, kotlinx.serialization, HikariCP, postgresql,
  kafka-clients, micrometer-prometheus, a JWT library, and a property-testing library.
- `services/room-gameplay/chart/` — env, `imagePullSecrets`, `startupProbe`, `ServiceMonitor`
  (identity's chart is the template to copy).

**Cluster**

- `gitops/platform/postgres/cluster.yaml` — a second managed role, `room_gameplay`.
- `gitops/platform/postgres/databases.yaml` — `room-gameplay` owner flips to its own role.
- `gitops/secrets/seal.sh` + `gitops/secrets/staging/room-gameplay-secrets.yaml` — DB password and
  the shared JWT secret.
- `gitops/apps/room-gameplay/overlays/staging/values.yaml` — endpoints, secret, ServiceMonitor,
  pinned digest, `NodePort` 30081.
- `gitops/bootstrap/kind-cluster.yaml` — a second `extraPortMapping` for 30081. **Port mappings are
  fixed at cluster creation**, so this lands in F1 and every later drill recreates the cluster
  anyway; a cluster created before F1 will not reach room-gameplay.
- `gitops/platform/kafka/topics.yaml` — `room.public.events` and `room.lifecycle.events` declared
  now, so P5 has somewhere to publish and the catalog stays in git.

**Client / docs**

- `clients/cli/src/` — room commands, `play --casual`, the interactive loop and the board renderer
  (canonical card notation from Client-Checkpoint §5.F: `R5`, `BSKIP`, `Y+2`, `WILD+4`), plus
  `UNOARENA_ROOMS_URL` alongside `UNOARENA_API_URL`. Two targets is a P3-only shape: P4's gateway
  collapses them, and the README says so rather than leaving the faculty to guess.
- `clients/cli/README.md`, `README.md`, `CHANGELOG-design.md` — the auto-start delta, the polling
  stand-in for SSE, and the shared-JWT-secret hand-off.

## Risks

- **R1 — Engine correctness is the program's biggest unknown** (north-star R2). Mitigation is the
  whole shape of F3/F4: a pure module, property tests over generated games, log replay as an
  equality check, and then a real human-played game in F9. If the property suite cannot express an
  invariant, that is a signal the model is wrong — not a reason to skip the test.
- **R2 — Scope creep into tournaments.** `roomType` exists in the model and only `Casual` is
  implemented. Best-of-three, `matchScores` and advancement stay untouched in P7's column.
- **R3 — Gradle build time.** `test:room-gameplay` is already ~75 s and the dependency set grows.
  If the job drifts past ~3 min, the property-test iteration count moves to a CI-only lower bound
  before anything else is cut — and that gets logged, not silently reduced.
- **R4 — Another JVM on kind.** Explicit `-Xmx`, small requests, `replicas: 1`. P1's stack plus two
  JVMs is the tightest the laptop has been; if it does not fit, the monitoring stack's retention
  drops before any service does.
- **R5 — The shared JWT secret** couples identity and room-gameplay until P4. Written down in D9
  and in `CHANGELOG-design.md` rather than quietly normalised.
- **R6 — CI minutes and pin ping-pong.** Work on `feat/p3-room-gameplay`, first push with
  `git push -o ci.skip`, `git pull --rebase` before every local commit (CI pushes digest pins back
  to the branch), and batch any `ci/templates/**` change — it pulls all ten services into the
  pipeline. One fast-forward merge to `main` at the end.
