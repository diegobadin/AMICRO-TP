# Requirements — P5: Async Spine (outbox relay + timer worker)

> Fifth phase of the final-delivery program
> ([`../2026-07-26-final-delivery-northstar/`](../2026-07-26-final-delivery-northstar/)). P4 closed
> the casual loop's *quality*: one door, one trust boundary, a real event stream. P5 closes its two
> **time-and-delivery** gaps — the outbox that has never been drained, and the deadlines that only
> fire when a command happens to arrive.

## Objective

Committed events **leave** room-gameplay: the relay drains the outbox to Kafka at-least-once, in
order, and the number of unpublished rows returns to zero. And time **passes on its own**: a turn
that runs out ends without anyone touching the keyboard, a room whose players walked away reaches a
terminal state instead of ticking forever, and the aggregate stays the correctness backstop it
already is.

## Context — what P4 handed over

From the roadmap's "Handoff from P4" block, verified against the running cluster today:

| Inherited fact | Evidence | What P5 must do with it |
|---|---|---|
| The outbox is 100% undrained | `select count(*) filter (where published_at is null) from outbox` → **596 of 596** | Drain it. This is the headline number of the phase. |
| The Redis stream is a **second, transient** path | `Streams.kt`, delta §9.1 | The relay is **not** built on it and does not replace it. Two transports, one filter. |
| room-gameplay holds no signing key | `Auth.kt` trusts `X-Player-Id` **and** `X-Session-Id` | The timer worker is inside the trust boundary and sets **both** headers. One alone is a `401` — that is correct behaviour, not a bug. |
| `publicPayload(event)` is the shared privacy filter | `Outbox.kt` | Whatever reaches Kafka goes through it. `GameStarted`/`DeckRecycled` carry the RNG seed. |
| Deadlines are evaluated only when a command arrives | `Decide.kt:27` | This is the gap the phase exists to close — and the source of three false alarms in P4's drills. |

Two things found while grounding this spec, both load-bearing:

- **`uno.expire(state, now, config)` already exists** as a standalone entry point ("so the HTTP layer
  can flush deadlines on a read"). The engine is already the belt-and-suspenders backstop
  architecture §1 promises; what is missing is a clock that pokes it. P5 adds the clock, not a second
  rule engine.
- **A room everybody walked away from has no terminal state.** `expireOverdue` auto-draws and passes
  on a turn timeout, and a game ends abandoned only when *active* players fall below two
  (`endGameIfTooFewPlayers`, invariant 7) — but an absent player is still active. Today that room is
  frozen; the moment a timer worker exists it ticks **forever**, one event every 30 s, growing the
  log and the outbox without end. Fixing the deadline gap without fixing this trades a stuck room for
  a runaway one.

## Locked decisions (session 2026-08-11)

| # | Decision | Chosen |
|---|----------|--------|
| E1 | How the timer worker learns a deadline is due | **room-gameplay writes `next_deadline` onto the existing `rooms` projection; the worker polls Postgres for overdue rooms and POSTs an internal tick** with both trust headers. The deadline stays in the aggregate — one column, one partial index, no cancel/reschedule semantics, and a duplicate tick is a no-op by construction |
| E2 | What ends a room nobody is playing | **Forfeit streak + room expiry.** K consecutive turn timeouts (default 3) forfeits that player through the existing `PlayerForfeited`, so invariant 7 ends the game as abandoned with no new event; a `WAITING` room that never fills within its window emits a new **`RoomExpired`**. Closes both drill wounds — the parked turn *and* the leftover `WAITING` room the next `--casual` player joins |
| E3 | What the relay publishes | **CloudEvents headers + flat body**, per catalog §10: `ce-*` Kafka headers (`ce-id` = `{roomId}:{sequenceNumber}`, `ce-type`, `ce-time`, `ce-subject`, `ce-correlationid`), value = `publicPayload(event)` plus `roomId` and `sequenceNumber`, message key = `roomId`. The contract check graduates to a **golden sample dumped by the real producer**, and the schema is corrected to `gameNumber` |
| E4 | How the timers show up client-side | **Countdown + `bot --idle`.** The room view already carries `turnDeadline`: the CLI shows the seconds left and narrates `TurnTimedOut`; the bot gains `--idle` so a timeout can be demonstrated on purpose instead of waited for |
| E5 | How much of the catalog the relay publishes | **Everything, both topics.** Each row goes to the topic it already names, so the relay carries no event-type knowledge and P6/P7 find their topics flowing |
| E6 | Relay delivery posture | **Single replica, strict `id` order, publish-then-mark.** Per-room ordering is a consequence of global id order; a crash re-publishes at most one batch (consumers dedupe by `roomId` + `sequenceNumber`, as architecture O1 already specifies). Horizontal scale-out is documented as a deliberate gap, not built |
| E7 | What the done-criteria rest on | **Reconciled counts + lag metric.** In the empty-cluster drill: delivered Kafka messages equal outbox rows, `published_at is null` reaches **0**, `outboxrelay_lag_seconds` is scraped — and a room left idle ends by itself, visible in the CLI and in `room_events` |
| E8 | The unreachable reconnect command | **Make it reachable and used.** `ReconnectPlayer` is `PATCH /rooms/{id}/players/{playerId}` in room-gameplay, but the gateway's whitelist entry for that path allows only `POST`/`DELETE` (`app.ts:45`) and no client sends a PATCH — so since P4 the 60 s window can only ever *expire*. P5 adds `PATCH` to that one entry and has the CLI call it after a stream drop |

## In scope

- **`outbox-relay` becomes real** (Go) — polls `outbox where published_at is null` in `id` order,
  publishes each row to the topic the row names with CloudEvents headers and `roomId` as key, marks
  rows published only after the broker acks, and exposes `/health`, `/metrics` and structured logs.
  Fourth fully-wired service; no new deployable.
- **`timer-worker` becomes real** (Go) — polls the `rooms` projection for `next_deadline <= now()`,
  POSTs `/internal/rooms/{roomId}/tick` to room-gameplay with `X-Player-Id` **and** `X-Session-Id`,
  and exposes the same health/metrics/log surface. Fifth fully-wired service.
- **room-gameplay grows the seam the worker needs** — a `next_deadline` column on the `rooms`
  projection written inside the same transaction as the events; a `Tick` command that decides nothing
  and lets the existing `expireOverdue` run; the internal route that raises it; and a guard that a
  `system:`-prefixed player id can only arrive on that route.
- **Two engine rules that give a room an end** — the consecutive-timeout forfeit streak and
  `RoomExpired` for a stale `WAITING` room, both additive.
- **Contract seam graduates** — the `GameCompleted` sample the CI check validates is written by a
  room-gameplay test from the real encoder, and the schema matches the real producer and catalog §10.
- **Gateway** — `PATCH` added to the players route entry (E8). One array element; same auth, same
  header injection, same tests.
- **CLI** — turn countdown from `turnDeadline`, narration for `TurnTimedOut` / `PlayerForfeited` /
  `RoomExpired`, a reconnect call after a stream drop, and `bot --idle`.
- **Platform seam** — both Go charts gain what the real services' charts already have
  (`imagePullSecrets`, `envFrom` the existing `room-gameplay-secrets`, ServiceMonitor), overlays gain
  their config, and both `deploy-staging` jobs graduate from manual stub to real GitOps deploys.

## Out of scope

Named so it cannot creep in. Each belongs to a later phase or to a documented gap.

- **Any Kafka consumer.** Ranking's Elo, the spectator projection and the analytics workers are P6;
  the tournament saga is P7. P5 proves delivery by reconciliation, not by a consumer.
- **Debezium / CDC.** Architecture O1 offers "CDC **or** polling"; polling is the half this phase
  builds, and an operator plus a connector is infrastructure with no demo behind it.
- **Relay horizontal scale-out** (`for update skip locked`, one relay per shard) — E6 documents it.
- **Timer-worker leader election** — one replica, same reasoning; the aggregate makes a missed tick a
  latency problem, never a correctness one.
- **Exactly-once / Kafka transactions.** At-least-once with consumer-side dedupe is the architecture's
  own answer (O1, A1–A5).
- **Retiring the Redis stream.** It stays exactly as P4 built it; the relay is not a replacement.
- **New timer types.** The four the roadmap names and nothing more: turn timeout, Uno! challenge
  window, reconnection window, room expiry.
- **Rate limiting, TLS, WAF policy** at the gateway (P7's L1–L4) — untouched.
- **Building the seven canned placeholders.** They keep `digest: ""` and stay `Degraded`.

## Constraints from tech-stack

| Constraint | Consequence for P5 |
|---|---|
| `outbox-relay` and `timer-worker` are **Go**, tested with `go test`, linted with `go vet` (§2) | Both stay Go; no rewrite in a language that already has a Kafka client here |
| Pipeline shape is **frozen** (north-star program rules) | Both services keep their existing `test → build → deliver → deploy-staging` fragments; the stub `deploy-staging` becomes real, no stage is added |
| **Promotion = digest pin**, build once (§5) | Each service pushed on its own so change detection runs its jobs alone, and the pinned digest verified to have actually moved (P4's drill lesson) |
| **Sealed Secrets**, no plaintext in the repo (§6) | The relay and the worker reuse the existing `room-gameplay-secrets` — same bounded context, same DB role (architecture §1: the event store's direct read belongs to the relay). No new sealing, no new key material |
| **One illustrative contract check** (§7) | The check stays one pair; what changes is that its producer sample stops being hand-written |
| **Instrument as you go** (program rule) | Both services expose `/metrics` from their first real phase; P8 consolidates dashboards and does not retrofit |
| **Additive growth** (program rule) | `RoomExpired` is a new event type, `next_deadline` a new nullable column, the timeout streak a replayed derivation. Nothing rewrites an existing event or its meaning |

## Risks & mitigations

| # | Risk | Mitigation |
|---|---|---|
| R1 | **The runaway room.** A timer worker on a dormant room emits an event every 30 s forever, growing `room_events` and the outbox without bound | E2 is a hard requirement of this phase, not a nice-to-have: validation asserts a walked-away room reaches a terminal state and then **stops producing events** — a count taken twice, minutes apart |
| R2 | **The streak that never accumulates.** `TurnTimedOut` itself emits `CardDrawn` and `TurnPassed`; a reset keyed on either would clear the counter the timeout just incremented, and K would never be reached | The counter resets only on events a *player command* can produce (`CardPlayed`, `UnoCallMade`, `UnoChallengeIssued`, `PlayerReconnected`, `PlayerJoined`), with a test that asserts the attempt count. This is P3's "a rule the client always satisfies is a rule that is not tested" in reverse |
| R3 | **Tick storm on an upgraded cluster.** Backfilling `next_deadline` for the 19 existing rooms would fire the worker at all of them at once | The column is added `NULL` and written only by the next projection write; a room with no `next_deadline` is invisible to the worker. On a drill cluster the table is empty anyway |
| R4 | **Two clocks.** The worker's `now()` and Postgres' `now()` drifting means a deadline fires early or never | The due query compares against the database's own clock (`next_deadline <= now()`); the worker never computes a due time, and the engine re-judges with its own `Instant.now()` on arrival |
| R5 | **A dead broker looking like a healthy relay** — P4's exact lesson, one layer down | The relay never marks a row published before an ack, `outboxrelay_publish_failures_total` counts, and `outboxrelay_lag_seconds` is derived from the **oldest unpublished row's `created_at` in Postgres**, not from an in-memory cursor. The Kafka-down bite test is a required validation item |
| R6 | **First Go dependency.** Both services currently have empty `go.mod`s and build with `CGO_ENABLED=0` onto distroless static | A pure-Go Kafka client (no cgo, no librdkafka), `go.sum` committed, and `go mod download` added explicitly to the Dockerfile — verified by a kaniko build, since that is where a missing module surfaces |
| R7 | **A phase that changes five services** (room-gameplay, both Go services, gateway, CLI) | P4's costliest lesson: push once per service and verify each pinned digest actually moved before drilling. The pre-drill check is a validation item, not a habit |
| R8 | **A probe sent late measures the deadline, not the thing under test** — the false alarm that cost P4 three attempts | Every timing probe in validation is chained inside one turn window in a single script, and states the window it assumes |

## Mission alignment

The north-star's demo requirement is a live system deployed from an empty cluster whose functional
behaviour is verified **through the CLI**. P5 is the phase where the architecture's asynchronous
claims stop being a diagram: the transactional outbox actually bridges to Kafka (making P6's ranking
and spectator, and P7's tournament saga, plug-in work rather than plumbing work), and the durable
timers actually fire, so a game finishes without a human babysitting it. It also removes the
operational tax the drills have been paying since P3 — the leftover `WAITING` room that strands the
next run — which the drill-lessons log records as the cause of three false alarms in a single
session. Both changes land inside the frozen pipeline shape, on two deployables that have existed as
placeholders since the DevOps checkpoint, so architecture coverage improves without the deployable
count moving.
