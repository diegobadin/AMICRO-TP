# Plan — P5: Async Spine (outbox relay + timer worker)

> Companion to [`requirements.md`](./requirements.md) and [`validation.md`](./validation.md).
> Rationale lives in requirements; this file is ordered and imperative.

## Design decisions (confirm in review)

Implementer choices, not the user's. Each is small enough to reverse and each gets questioned in the
self-review pass at the end of the phase.

| # | Decision | Why |
|---|----------|-----|
| D1 | **`Tick` is an engine `Command`**, not a bypass on `Rooms` | `decide()` already runs `expireOverdue` before every command, so `Tick` is `log.accept()` and one line in the `when`. It reuses `submit`'s retry loop, conflict handling, transactional append, projection write, outbox row and stream publish **unchanged** — a `Rooms.tick()` that called `uno.expire()` directly would be a second copy of that path. Precedent: `DisconnectPlayer` is already a command raised by a consumer rather than a player |
| D2 | **`next_deadline` is computed in the projection writer** and written in the same transaction as the events | The column can then never disagree with the log — the same argument that puts the outbox row in that transaction. It is a cache of `min(turnTimerDeadline, challengeWindow.expiresAt, disconnected deadlines, waiting expiry)`, and nothing reads it except the worker's due query |
| D3 | **The `WAITING` expiry is anchored on `max(players.joinedAt) ?: createdAt`**, not on `createdAt` alone | Both fields already exist on `RoomState`, so no new state and no migration; and a room whose last joiner arrived a minute ago gets a full window rather than dying under them. A room that nobody ever joins still expires on its creation time, which is the drill case |
| D4 | **The consecutive-timeout counter is replayed, not stored** | `EventStore.load` replays every event from `room_events` with no snapshot, so a derived field on `Game` costs nothing to add and cannot go stale. It increments on `TurnTimedOut` and resets **only** on events a player command can produce (R2) |
| D5 | **`system:`-prefixed player ids are fenced**: the internal tick route requires one, the player-facing routes reject one | The gateway builds `X-Player-Id` from the JWT `sub` (a UUID from identity), so this cannot happen today — the whitelist is the control. This is the second lock on the same door, and it costs one predicate |
| D6 | **Kafka client: `segmentio/kafka-go`** (pure Go) | No cgo, so `CGO_ENABLED=0` and the distroless *static* base stay exactly as they are. `confluent-kafka-go` would drag in librdkafka and change both Dockerfiles' base images to justify one producer |
| D7 | **`outboxrelay_lag_seconds` is read from Postgres** — `now() - min(created_at) where published_at is null` — never from an in-memory cursor | P4/F8's lesson one layer down: a heartbeat that reads this process's own idea of progress keeps ticking happily while the thing it measures has stopped. The number has to come from the source of truth. *Corrected in review*: this plan first said the query would run "on a slower timer". It runs on every poll, and should — both aggregates are served by the partial index over unpublished rows, which is empty in the steady state, so the throttle would have been complexity guarding against nothing |
| D8 | **The worker's `X-Session-Id` is a per-pod UUID generated at start**, logged once | Makes a tick traceable to the pod that sent it, and satisfies `Auth.kt`'s requirement for both headers with something honest rather than a constant string |
| D9 | **The two Go services stay two independent modules** with their own tiny health/metrics/log shape | They are ~300 lines each and deploy independently; a shared module would be a fourth thing to version for the sake of forty duplicated lines, and it would couple two services the pipeline treats as independent |
| D10 | **Metric names follow architecture §5** (`rows_published`, `publish_failure`, `lag_seconds`) with a service prefix, and tests assert the **exact exposed strings** | P3's lesson: Prometheus rewrites names it dislikes (`_created` is reserved by OpenMetrics), so "some metrics came back" is not an assertion |
| D11 | **The CLI countdown displays, it never decides** | It renders seconds left from the `turnDeadline` the view already carries. The client holding an opinion about when a deadline passed is exactly the local-projection mistake P4 deleted; the server remains the only judge |

## Phases (one commit each)

| Phase | Delivers | Done when |
|-------|----------|-----------|
| F0 | This triad + the roadmap pointer. No `kickoff.md`: implementation starts in the same session, and that file exists to bridge a spec that closes before its code | reviewed |
| F1 | **Engine rules that give a room an end**: the consecutive-timeout counter on `Game`, forfeit after K (config, default 3), and `RoomExpired` for a stale `WAITING` room | `./gradlew :engine:test` green with new tests for the streak, its reset rule, the two-absent-players ending, and a `WAITING` room expiring exactly once |
| F2 | **room-gameplay's seam**: `next_deadline` column + index, the projection write, the `Tick` command, `POST /internal/rooms/{roomId}/tick`, the `system:` fence, config knobs and metrics | suite green; against a real Postgres, playing a game moves `next_deadline`, and `curl` of the internal route with both headers flushes an overdue deadline while one header alone is a `401` |
| F3 | **`timer-worker` becomes real**: due-query poll loop, tick client with both headers, backoff, `/health`, `/metrics`, structured logs; chart + overlay | `go vet` + `go test` green; run locally against a port-forwarded Postgres and room-gameplay, an idle turn times out on its own within one tick interval |
| F4 | **`outbox-relay` becomes real**: unpublished-rows poll, CloudEvents publish keyed by `roomId`, publish-then-mark, backoff, lag/backlog metrics, `/health`; chart + overlay | `go vet` + `go test` green; against a real Kafka the 596-row backlog drains, `published_at is null` reaches 0, and `kafka-console-consumer` shows the rows on both topics |
| F5 | **Contract seam graduates**: a room-gameplay test writes the golden `GameCompleted` sample from the real encoder; `game-completed.schema.json` and `validate.py` corrected to the producer's real shape; check's `rules:changes` covers the relay | `python ci/contracts/validate.py` green against the golden file, and red when the sample is hand-edited |
| F6 | **Gateway + CLI**: `PATCH` added to the players route entry, CLI countdown and narration, reconnect call after a stream drop, `bot --idle` | gateway suite green including a PATCH routing test; a hand-played game shows the countdown and narrates a timeout; `bot --idle` produces one on demand |
| F7 | **Platform + CI**: both charts get `imagePullSecrets` / `envFrom` / ServiceMonitor, overlays get their config, both `deploy-staging` jobs graduate from manual stub to real GitOps deploys; one push per changed service | each service's pipeline green running only its own jobs, and **each pinned digest verified to have moved** |
| F8 | **Empty-cluster drill**, bite tests, transcripts, `CHANGELOG-design.md` §10 deltas, README updates, `ESTADO-FINAL.md`, self-review pass | every AC in `validation.md` checked, with the review-pass table added to this file |

F1–F5 are additive and independently deployable: until F7 pins their digests, both Go services keep
serving the placeholder image and the cluster behaves exactly as it does today. There is no
transitional state in which a drill cannot play a game.

**F1 must land before F3 reaches a cluster.** A timer worker deployed against an engine that has no
terminal rule turns every abandoned room into a permanent event source (R1). The ordering is the
mitigation; nothing else enforces it.

### F1 — engine: the rules that end a room

1.1 Add `consecutiveTimeouts: Map<String, Int>` to `Game` (default empty), maintained in `evolve`:
`TurnTimedOut` increments for the timed-out player; `CardPlayed`, `UnoCallMade`,
`UnoChallengeIssued`, `PlayerReconnected` and `PlayerJoined` reset that player's entry to 0.
**`CardDrawn` and `TurnPassed` do not reset it** — `TurnTimedOut` emits both, so a reset on either
would clear the counter the timeout just set and K would never be reached (R2).

1.2 Add `idleTimeoutsBeforeForfeit: Int = 3` and `waitingRoomExpirySeconds: Long = 900` to
`EngineConfig`, threaded from `Config.kt` like `turnTimeoutSeconds` already is.

1.3 In `expireOverdue`, after emitting `TurnTimedOut`, forfeit the player whose count has reached the
limit — reuse `forfeitPlayer(playerId, "idle", now, config)`, which already runs
`endGameIfTooFewPlayers`, so a two-player room ends as `GameCompleted(isAbandoned = true)` →
`RoomCompleted` with no new event type.

1.4 Add `RoomExpired(reason, at)` to `Events.kt` and to `evolve` (`status = COMPLETED`, terminal),
and emit it from `expireOverdue` when `status == WAITING` and
`now > (players.maxOf { joinedAt } ?: createdAt) + waitingRoomExpirySeconds` (D3). Lift the current
early return so `WAITING` is reachable; `IN_PROGRESS` keeps today's path exactly.

1.5 `topicFor(RoomExpired)` → `LIFECYCLE_TOPIC` in `Outbox.kt`: it is a room outcome, and architecture
SG3 has the tournament consuming it as an all-forfeit room.

1.6 Tests in `DeadlinesTest.kt` / `RulesTest.kt`: K timeouts forfeit and no fewer; a real play resets
the streak; two absent players end the game abandoned and **the next `expire` emits nothing**; a
`WAITING` room expires once and a second pass is empty; a late joiner extends the window.

**Done when** the engine suite is green and a property/replay test shows a room reaching a terminal
state from any sequence of ticks alone.

### F2 — room-gameplay: the seam the worker pokes

2.1 `Migrate.kt`: append `alter table rooms add column if not exists next_deadline timestamptz` and
`create index if not exists rooms_deadline_idx on rooms (next_deadline) where next_deadline is not
null`. Additive and idempotent, like every statement in that list.

2.2 `EventStore`'s projection upsert writes `next_deadline` from a `nextDeadline(state)` helper — the
minimum of the turn timer, the challenge window, every disconnected player's deadline and the
`WAITING` expiry; `null` for a `COMPLETED` room (D2).

2.3 Engine: `data object Tick : Command` and one `is Tick -> log.accept()` arm in `decide` (D1).

2.4 `Routes.kt`: `post("/internal/rooms/{roomId}/tick")` submitting `Tick`, responding `200` with the
room's sequence number and the number of events the tick produced, `404` for an unknown room. Not in
the gateway's route table — the whitelist has no pattern that matches, which validation proves with a
`404` from `:30080`.

2.5 `Auth.kt`: accept a `system:`-prefixed `X-Player-Id` **only** on `/internal/**`, and reject one on
every player-facing route (D5).

2.6 `Config.kt`: `IDLE_TIMEOUTS_BEFORE_FORFEIT` (3), `WAITING_ROOM_EXPIRY_SECONDS` (900) — levers a
drill or the demo can turn without a rebuild, exactly as `TURN_TIMEOUT_SECONDS` is.

2.7 `Metrics.kt`: `roomgameplay_timer_ticks_total{result}`, and business counters for idle forfeits
and expired rooms counted **from the committed events**, not from the request (the P3 lesson: a
metric counted at the request that "obviously" causes it misses the indirect paths).

2.8 Tests: the internal route with both headers, with one, with none; a `system:` id refused on
`/rooms/**`; `next_deadline` written and cleared across a game's lifecycle against a real Postgres.

**Done when** an overdue deadline can be flushed by an HTTP call that carries no player identity of
its own, and the column tracks the game.

### F3 — timer-worker

3.1 Replace `main.go`: poll
`select room_id, next_deadline from rooms where next_deadline <= now() and status <> 'COMPLETED'
order by next_deadline limit $1` on a `TICK_INTERVAL` (default 1 s), and POST the tick for each row.
The database's clock is the only clock (R4).

3.2 Tick client: `X-Player-Id: system:timer-worker`, `X-Session-Id: <per-pod UUID>` (D8),
`X-Correlation-Id` per tick, an explicit request timeout, and exponential backoff with jitter on
failure. A tick that fails is simply retried on the next poll — the row is still due.

3.3 `/health`, `/metrics`, one structured JSON log line per tick batch.
`timerworker_ticks_total{result}`, `timerworker_tick_lag_seconds` (`now - next_deadline` when it
fires), `timerworker_due_rooms` gauge (D10).

3.4 Config from env: `DATABASE_*`, `ROOM_GAMEPLAY_URL`, `TICK_INTERVAL_MS`, `TICK_BATCH_SIZE`,
`HTTP_TIMEOUT_MS`.

3.5 `go test`: the due-query builder, the backoff, an `httptest` server asserting **both** headers are
present (the P4 lesson made permanent), and that a non-2xx leaves the row to be retried.

3.6 Chart + overlay: `imagePullSecrets`, `envFrom: room-gameplay-secrets`, the env block, ServiceMonitor,
`ClusterIP` (metrics only — it is never a client of anything outside).

**Done when** an idle turn ends on its own against a locally-run stack, and killing the worker leaves
the next real command still resolving the deadline (the aggregate backstop).

### F4 — outbox-relay

4.1 Replace `main.go`: poll
`select id, room_id, sequence_number, topic, event_type, payload, correlation_id, created_at
from outbox where published_at is null order by id limit $1`, publish each row, then
`update outbox set published_at = now() where id = any($1)`. Publish first, mark second (E6).

4.2 Message shape (E3): key = `room_id`; headers `ce-specversion: 1.0`, `ce-id:
{roomId}:{sequenceNumber}`, `ce-source: /room-gameplay`, `ce-type:
com.unoarena.room.{eventType}.v1`, `ce-time`, `ce-subject: {roomId}`, `ce-correlationid`; value =
the stored `payload` (already `publicPayload`) with `roomId` and `sequenceNumber` merged in.

4.3 The row's own `topic` decides the destination — the relay has no event-type table (E5).

4.4 Backoff with jitter on publish failure, unlimited retries, no row ever marked on failure;
`outboxrelay_publish_failures_total` counts. A dead broker grows the backlog and leaves gameplay
untouched, which is the outbox's entire purpose.

4.5 Metrics (D7/D10): `outboxrelay_rows_published_total{topic}`,
`outboxrelay_publish_failures_total`, `outboxrelay_lag_seconds`, `outboxrelay_backlog_rows` — the
last two queried from Postgres on a slower timer, never from a cursor this process holds.

4.6 `go.mod`/`go.sum` gain `segmentio/kafka-go` (D6); the Dockerfile gets an explicit
`go mod download` between `COPY go.mod go.sum ./` and the build, and `COPY . .` moves after it so the
module layer caches.

4.7 `go test`: envelope construction (headers and merged body) against a golden fixture, topic
routing straight from the row, batch marking, and that a publish error leaves `published_at` NULL.

4.8 Chart + overlay: as F3, plus `KAFKA_BROKERS`.

**Done when** the backlog drains to zero against a real Kafka and the delivered count equals the row
count on both topics.

### F5 — the contract seam graduates

5.1 A room-gameplay test builds a real `GameCompleted` through the engine, runs it through
`publicPayload` and the relay's envelope shape, and writes
`ci/contracts/samples/game-completed.json`; the test **fails if the committed file differs**, so a
producer change that breaks the contract turns the Kotlin suite red as well as the Python job.

5.2 `game-completed.schema.json` corrected to the real producer and catalog §10: `roomId`,
`roomType`, `gameNumber`, `finishingOrder`, `cardPointTotals`, `isAbandoned`, `completedAt`,
`sequenceNumber`. The placeholder-era `gameId` and `eventId` go — neither exists in the system.

5.3 `validate.py` loads the golden sample from disk instead of its inline `PRODUCER_SAMPLE`, and
`CONSUMER_REQUIRED` is restated against fields that exist.

5.4 `contract.gitlab-ci.yml`: add `services/outbox-relay/**/*` to `rules: changes` — the relay is now
part of the producer side.

**Done when** the check validates something room-gameplay actually emits, and editing the golden file
by hand turns it red.

### F6 — gateway and CLI

6.1 `app.ts:45`: `methods: ["POST", "DELETE", "PATCH"]`. One element; the route, its auth and its
header injection are unchanged. A routing test per verb.

6.2 CLI: render `turnDeadline` as a live countdown on the player's own turn (D11), and narrate
`TurnTimedOut`, `PlayerForfeited` and `RoomExpired` in the feed's existing voice.

6.3 CLI: after the stream reconnects following a drop, `PATCH /rooms/{id}/players/{playerId}` to
cancel the reconnection window, then re-read state — the resync path already exists, this adds the
command that makes `PlayerReconnected` emittable (E8).

6.4 `bot --idle`: skips its turn deliberately (still emitting its §6 line, with an `idle` action) so a
timeout is demonstrable on purpose. Composes with `--seed`.

**Done when** a hand-played game shows the countdown, a timeout narrates itself, and a killed-and-
restarted client reconnects inside the window instead of being forfeited.

### F7 — platform and CI

7.1 Both charts: `imagePullSecrets`, `envFrom` the existing `room-gameplay-secrets`, ServiceMonitor,
and the `startupProbe`/probe shape the real services use. No new SealedSecret — same bounded context,
same DB role, so no re-sealing and no new key material.

7.2 Both overlays: `replicas: 1`, `logLevel`, DB host/name/user, `KAFKA_BROKERS` (relay),
`ROOM_GAMEPLAY_URL` (worker), the tunable intervals.

7.3 Both `.gitlab-ci.yml` fragments: `deploy-staging` loses `when: manual` / `allow_failure` and
becomes the real GitOps deploy, matching `deploy-staging:gateway`.

7.4 Push order: `git push -o ci.skip` for the branch's first push, then **one push per changed
service** so change detection runs that service's jobs alone; `git pull --rebase` before each local
commit because CI pins digests back to the branch. Then **verify each digest actually moved** in the
overlay before drilling (R7).

**Done when** five services have green pipelines, each running only its own jobs, and five overlays
carry digests that differ from `main`'s.

### F8 — drill, docs, closure

8.1 `kind delete cluster` → `TARGET_REVISION=feat/p5-async-spine install.sh` → all five real services
`Synced/Healthy` (the seven placeholders stay `Degraded`, as always).

8.2 Run every AC in `validation.md`, including the bite tests, and record transcripts there.

8.3 `CHANGELOG-design.md` §10 — the P5 deltas (at minimum: the timer-discovery mechanism vs.
architecture T1–T4, the idle-forfeit rule, `RoomExpired` as a new event, the CloudEvents envelope,
single-replica relay and worker).

8.4 README + `clients/cli/README.md` updates; `ESTADO-FINAL.md`.

8.5 **Self-review pass** over the whole branch, as a PR reviewer: question every decision, delete
overengineering, look hardest at code whose purpose changed after an earlier fix and at promises made
in a README. Its table goes in this file, including a "not changed, and why" list.

8.6 Close per the roadmap's "Closing a phase": repoint both Argo roots at `main` **before** deleting
the branch, FF-merge, then **ask for the closure pipeline explicitly**
(`POST /api/v4/projects/83816735/pipeline?ref=main`) — the closure commit is `[skip ci]`, so the push
produces a skipped pipeline and the green-`main` criterion would otherwise have no run behind it.

**Done when** `validation.md` is fully checked and `ESTADO-FINAL.md` is written.

## Changes by file

**Engine** (`services/room-gameplay/engine/src/main/kotlin/uno/`)
- `State.kt` — `Game.consecutiveTimeouts`; `EngineConfig.idleTimeoutsBeforeForfeit`,
  `.waitingRoomExpirySeconds`
- `Events.kt` — `RoomExpired`
- `Commands.kt` — `Tick`
- `Evolve.kt` — counter maintenance, `RoomExpired` → `COMPLETED`
- `Decide.kt` — `Tick` arm; `expireOverdue` gains the forfeit-on-streak and the `WAITING` branch

**room-gameplay** (`services/room-gameplay/src/main/kotlin/`)
- `Migrate.kt` (column + index), `EventStore.kt` (`nextDeadline`, upsert), `Routes.kt` (internal
  route), `Auth.kt` (`system:` fence), `Config.kt`, `Metrics.kt`, `Outbox.kt` (`topicFor`)

**Go services**
- `services/outbox-relay/{main.go,relay.go,kafka.go,*_test.go,go.mod,go.sum,Dockerfile,chart/**}`
- `services/timer-worker/{main.go,poller.go,tick.go,*_test.go,go.mod,go.sum,chart/**}`

**Contract** — `ci/contracts/{game-completed.schema.json,validate.py,samples/game-completed.json}`,
`ci/contracts/contract.gitlab-ci.yml`

**Gateway / CLI** — `services/gateway/src/app.ts` (+ test); `clients/cli/src/{board.ts,cli.ts,bot.ts,stream.ts}`

**GitOps** — `gitops/apps/{outbox-relay,timer-worker}/overlays/staging/values.yaml`,
both `services/*/chart/**`, both `services/*/.gitlab-ci.yml`

**Docs** — `CHANGELOG-design.md` §10, `README.md`, `clients/cli/README.md`, this spec directory

## What this plan deliberately does *not* include

- **No Kafka consumer of any kind.** Not ranking, not spectator, not analytics, not a saga. Delivery
  is proven by reconciliation (E7); consuming is P6/P7.
- **No Debezium, no CDC connector.** Polling is the half of architecture O1 this phase builds.
- **No `for update skip locked`, no relay sharding, no leader election.** One replica each, with the
  scale-out path documented in `ESTADO-FINAL.md` rather than built.
- **No Kafka transactions or exactly-once.** At-least-once with consumer-side dedupe is the
  architecture's own answer.
- **No change to the Redis stream tier.** P4's four guards, the TTL and the publisher are untouched.
- **No fifth timer type**, no configurable timer catalogue, no per-room timer overrides.
- **No client-side deadline authority.** The CLI displays seconds; it never decides that one passed.
- **No rate limiting, TLS termination or WAF policy** at the gateway.
- **No new deployable and no new stage.** Two placeholders become real inside the frozen pipeline
  shape; the count stays at ten.
- **No building of the seven canned placeholders.** They keep `digest: ""`.

## Review pass (2026-08-12, after the drill)

Reading the branch back as a reviewer, per the standing convention. Five findings, three acted on.

| # | Finding | Done |
|---|---------|------|
| R1 | **`timerworker_due_rooms` cannot distinguish "swept, none due" from "never swept"** — a Prometheus gauge that has never been `Set` reads `0`. This is exactly the ambiguity that made the 2699 s outlier hard to diagnose: the reading looked like a healthy idle worker either way. | Added `timerworker_sweeps_total`, asserted in the metrics test with the reason written down. |
| R2 | **`drain` reports an error after a successful publish** when `markPublished` fails, and the caller logs it as `drain-failed` — but the rows *are* on the broker, and the consequence is a duplicate, not a loss. The log line invites the opposite conclusion at 3am. | Not changed. The return already carries `len(rows)` so the caller can tell, the code comment says which failure it is, and inventing a second error type for a case that has never fired is speculative. Revisit if it ever does. |
| R3 | **Two near-identical `backoff` functions and two `run` loops**, one per Go service. | Not changed, and D9 is the reason: they are ~300-line services the pipeline treats as independently deployable, and a shared module would be a fourth thing to version for forty duplicated lines. |
| R4 | **`relay.observeBacklog` swallows its error silently** — if the backlog query starts failing, `lag_seconds` and `backlog_rows` freeze at their last value rather than going stale visibly. That is the P4 Redis lesson in miniature: a metric that stops moving looks like a system that stopped changing. | Not changed *yet*, and named here rather than fixed quietly: the drain loop that runs beside it reports the same database being unreachable, so the failure is never silent overall. Worth a `stale` flag if P8 alerts on the gauge. |
| R5 | **The `--idle` bot was tested against a stale `dist/`** during the drill, and appeared to play a normal game. The build is not part of the drill script. | Corrected in the run, and it is the reason `npm run build` is now called out explicitly in the drill checklist rather than assumed. Exactly the "verify the running binary is the one just built" lesson, arriving on schedule. |

**Not changed, and why** — beyond R2/R3/R4: the `Tick` command stays an engine `Command` rather than
a bypass on `Rooms` (D1 earned its keep: the whole retry/append/projection/outbox/stream path is
reused unchanged); `nextDeadline` stays in the engine rather than in `EventStore`, so there is one
`EngineConfig` in the process and the projection cannot drift from the rules; and the CLI's
`remaining()` takes an injectable `now` purely so the render is pinnable in a test — the client still
never decides that a deadline passed.

## Second review pass (2026-08-12, post-merge)

A full re-read of the branch as a reviewer, on the standing "question every decision" prompt. Six
findings; four changed, two argued and left. The first is the one that mattered.

| # | Finding | Done |
|---|---------|------|
| R6 | **The `WAITING` expiry rule was written out twice** — once in `expireWaitingRoom` (the decision) and once in `nextDeadline` (the cache the timer worker polls). Two copies of one rule, where the whole point of the second is to be the same number as the first. Nothing forced them to agree, and if they ever drifted the worker would arrive early forever or never arrive at all. | `expireWaitingRoom` now asks `nextDeadline` instead of restating the rule. One definition, used both to advertise and to decide. |
| R7 | **Nothing tied the cache to the rule for the other three deadlines** either — turn timer, challenge window, reconnection. A deadline added to `expireOverdue` and forgotten in `nextDeadline` is a room that quietly stops. | New property over generated games: the advertised instant must be the *earliest* the engine would act — nothing expires a millisecond before it, something expires a millisecond after. **The first version of this test did not bite** (it probed 100 000 s out, which any deadline satisfies); tightened until removing the challenge-window term turned it red. |
| R8 | **`outboxrelay_lag_seconds` and `outboxrelay_backlog_rows` both read `0` when the backlog query has never succeeded** — "no backlog, no lag", the healthiest possible reading, from a relay that cannot reach its database. The same shape of lie as P4's Redis outage, and on the two numbers an alert would watch. | `outboxrelay_backlog_reads_total` separates "observed and empty" from "never observed", mirroring `timerworker_sweeps_total`. |
| R9 | **`body` shadowed `body`** in `envelope.go` — a local variable with the same name as the function producing it. Legal Go, confusing to read. | Renamed to `renderBody` / `value`. |
| R10 | **Two near-identical id generators** in the worker (`correlationID`, `newSessionID`). | Folded into `randomID(prefix, bytes)`. |
| R11 | **The relay drains immediately on start; the worker slept one interval first.** No reason for the difference. | Both start immediately — a replacement pod may already have overdue rooms waiting. |

**Argued and left unchanged.** The ~80 lines duplicated between the two Go services (`env`, `logLine`,
`backoff`, the health handler) stay duplicated: each service's image is built with kaniko from *its
own directory as the build context*, so a shared package would have to live inside both contexts or
be published as a versioned module — restructuring the build to remove forty lines of `os.Getenv`
wrappers is a worse trade than the duplication. And `drain` still reports an error after a successful
publish when `markPublished` fails; the return already carries the row count so the caller can tell,
and inventing an error type for a case that has never fired is speculative.

**A known edge, found by this pass and documented rather than fixed.** The `WAITING` deadline is
recomputed from configuration, while the projection caches the value computed when the row was last
written. *Raising* `WAITING_ROOM_EXPIRY_SECONDS` while a room is waiting therefore leaves a cached
deadline in the past that the engine will not act on yet, and the worker re-ticks that room every
second until the longer window passes. It is self-healing, bounded by the new window, costs a read
per tick, and shows up as a climb in `roomgameplay_timer_ticks_total{result="nothing_due"}`. Storing
the deadline in an event instead would remove it, at the cost of a non-additive event change — not
worth it for a lever that is turned by hand.
