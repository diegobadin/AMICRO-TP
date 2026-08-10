# Roadmap — Final Delivery Program

> Phases ordered by de-risk: the empty-cluster mechanic and the casual loop are the exam's
> hardest gates, so they come first. Each phase ships deployable and gets its own dated triad
> spec when it starts. Grounded in [`north-star.md`](./north-star.md).

## P0 — DevOps checkpoint closure — **SHIPPED (2026-07-26)**

Ships: drills evidence, ADRs, manual prod gates. Triad:
[`../2026-07-26-prod-promotion-closure/`](../2026-07-26-prod-promotion-closure/) — see its
`ESTADO-FINAL.md` and the evidence table in `devops-checkpoint/README.md` §9.

## P1 — Platform infra from an empty cluster — **SHIPPED 2026-07-27**

Triad: [`../2026-07-26-p1-platform-infra/`](../2026-07-26-p1-platform-infra/) — all six ACs
green (kind drills, EKS rehearsal with empty sweep, pipeline unaffected); evidence in its
`validation.md`, closure in its `ESTADO-FINAL.md`.

- Ships: Kafka (single-broker Strimzi or Redpanda), Postgres, Redis as GitOps apps under the
  app-of-apps; bootstrap split into *create-cluster* (kind for rehearsal, EKS on the educational
  AWS account for the exam — reproducible IaC, N2/R1) and *install-into-kubeconfig* (works
  against either); observability stack (Prometheus + Grafana) installed here, dashboards later.
- Why first: "empty cluster → everything Healthy" is the demo's opening move; it must become a
  boring, repeatable drill before any real service exists.
- Infra it debuts: everything. Depends on: P0 pipeline.

## P2 — Identity for real — **SHIPPED 2026-08-08**

Triad: [`../2026-08-08-p2-identity-auth/`](../2026-08-08-p2-identity-auth/) — all eight ACs green
(empty-cluster drill, both invalidation transports observed, degradation drill); evidence in its
`validation.md`, closure in its `ESTADO-FINAL.md`. The live SSE kill waits for P4's gateway, which
is where the connections will be.

- Ships: Postgres-backed accounts, JWT sessions, single-active-session kill via Redis pub/sub
  (the domain non-negotiable), `logout`, `seed --count N`.
- CLI: `login`/`logout`/`seed` join the existing `register`/`whoami`.
- Why now: every other flow authenticates through it; smallest real service, already fully wired.
- Depends on: P1 (Postgres, Redis).

## P3 — Uno engine + room-gameplay core — **SHIPPED 2026-08-10**

Triad + closure: [`../2026-08-08-p3-room-gameplay/`](../2026-08-08-p3-room-gameplay/) — decisions
E1–E8, all nine ACs green, evidence in
[`validation.md`](../2026-08-08-p3-room-gameplay/validation.md) and
[`ESTADO-FINAL.md`](../2026-08-08-p3-room-gameplay/ESTADO-FINAL.md).

**The casual gate is open**: two CLI processes played a complete casual game of Uno to a winner
against a cluster deployed from empty — wild colour declaration, draws, an Uno! call and a
successful challenge, closing a 245-event log with `GameCompleted` → `RoomCompleted`. P4 now
delivers the *quality* of that experience (a real live feed, one entry point, a bot) rather than
its existence.

- Ships (a): rooms — create/join/list/leave, membership REST per Architecture §2.3.1, Postgres
  event store scaffold. (b): the game — pure Uno rules engine (Kotlin module, property +
  log-replay tests), moves collection with sequence numbers / `If-Match` → `412`/`409`,
  log-before-broadcast into the event store + outbox table.
- CLI: `room create/join/list/leave`, `play --casual` entry.
- Why now: the biggest unknown (R2); everything downstream consumes its events.
- Depends on: P2 (auth), P1 (Postgres).

## P4 — Realtime fan-out (SSE) — **next**

- Ships: room state → Redis Streams delta patches; gateway/broadcaster SSE tier; per-room
  ordering, reconnect + resync from last seq.
- CLI: interactive `play` view (live feed, turn board, play-by-index, wild colors, `draw`/`uno`/
  `challenge`/`pass`/`state`), 409 reconciliation surfaced; `bot --casual` random-valid-move.
- Gate: **met in P3** — a full casual game is playable through the CLI against a from-empty
  cluster. P4 replaces the polling stand-in with the real stream, collapses the two NodePorts into
  one gateway (removing the JWT secret room-gameplay and identity currently share), and adds the
  bot.
- Depends on: P3.

## P5 — Async spine: outbox relay + timers

- Ships: outbox-relay (Go) tailing the outbox to Kafka (`GameCompleted` for real, then the rest
  of the catalog); timer-worker (Go) driving the durable deadlines — Uno! 5s window, 60s
  reconnection, turn timeout, room expiry — as commands back into room-gameplay.
- Contract seam graduates: the CI schema check now validates the real producer.
- Depends on: P4 gate, P1 (Kafka).

## P6 — Ranking, spectator, analytics

- Ships: ranking Elo consumer (casual-only, non-abandoned filter); spectator projection with the
  privacy boundary (public info only — no hands, ever) behind `spectate <roomId>`;
  analytics-workers CQRS projections + analytics-api reads (stats, brackets store).
- CLI: `spectate`.
- Depends on: P5 (events flowing).

## P7 — Tournaments

- Ships: tournament orchestrator — registration with a **low configurable test threshold**,
  bracket state machine, round saga on `room.completed`, room provisioning through the same
  room-gameplay API, best-of-three series, top-3 advancement, deterministic tiebreaks.
- CLI: `tournament register/status`, `bot --tournament` registers and plays whatever it is
  assigned, rounds end to end.
- Degradable per N3: if the clock wins, what works/what doesn't is documented in the README
  without touching P1–P6.
- Depends on: P5 (saga events), P6 (bracket read model useful but not blocking).

## P8 — Observability consolidation (≥3 business metrics)

- Ships: ServiceMonitors for every real service, one Grafana dashboard with the business metrics
  (candidates: games completed/min, active rooms, moves/s, registered users, Elo updates/min —
  pick ≥3), correlationId traceable CLI → gateway → service logs.
- Small by design: the stack exists since P1 and services instrumented as they shipped.
- Depends on: whatever shipped; runs in parallel with P7 if needed.

## P9 — Demo rehearsal + presentation

- Ships: timed empty-cluster runbook (the exact demo script), CLI functional pass (the faculty's
  test, self-run), presentation deck (final architecture + key decisions, from the ADRs and
  `docs/architecture/`), README final pass, 48h-before checklist, date coordinated with faculty,
  and at least one full rehearsal on a freshly created AWS cluster (R1).
- Depends on: everything shipped by then; rehearse at least twice.

## Dependency sketch

```
P0 → P1 → P2 → P3 → P4 ═(casual gate)═> P5 → P6 → P7 → P9
                                          └────→ P8 ──↗
```
