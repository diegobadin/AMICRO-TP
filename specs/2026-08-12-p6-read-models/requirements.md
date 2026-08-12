# Requirements — P6: Read models (ranking, spectator, analytics)

> Sixth phase of the final-delivery program
> ([`../2026-07-26-final-delivery-northstar/`](../2026-07-26-final-delivery-northstar/)). P5 gave the
> events somewhere to go: both topics carry CloudEvents, `published_at IS NULL` reached 0, and the
> envelope is contract-checked against a sample the producer writes. **Nothing consumes any of it.**
> P6 is the other end of that wire — the phase where the system finally reads what it publishes.

## Objective

The events **arrive somewhere and mean something**. A finished casual game moves two ratings; a
stranger with a session can watch a room in progress and see everything except a hand; and the
system can answer "how many games, by whom, how did they end" from a projection instead of from the
event log. Four placeholders become real services, taking the count from five of ten to **nine of
ten** — only `tournament` is left canned for P7.

## Context — what P5 handed over

From the roadmap's "Handoff from P5" block, verified against the repo today:

| Inherited fact | Evidence | What P6 must do with it |
|---|---|---|
| `ce-id` = `{roomId}:{sequenceNumber}` is the log's primary key | delta §10.5, `envelope.go` | **This is the dedup key.** Every consumer keys its idempotency on it, nothing else |
| At-least-once is real, not theoretical | the relay publishes *then* marks (`relay.go`) | A crash redelivers. Dedup is load-bearing, not decoration |
| The body is `publicPayload(event)` + `roomId` + `sequenceNumber`, flat | delta §10.5 | No wrapper to unwrap. `JSON.parse` and read fields |
| The privacy filter ran **before** the row was written | `Outbox.kt`, same transaction as the event | The spectator boundary is already true on the wire. P6 **builds on it, does not rebuild it** |
| Per-room order holds because the relay drains in `id` order with `roomId` as the key | delta §10.7 | A consumer that re-orders within a room breaks a guarantee nothing else restores |
| `roomType` travels as the Kotlin enum name (`CASUAL`) | delta §10.5, schema `enum` | Ranking filters on `"CASUAL"`, never `"Casual"` |
| `consumed_events` exists, keyed `(source, event_key)` | `Migrate.kt:76` | The pattern to copy — this is the phase it was waiting for |
| The schema has `additionalProperties: false` on purpose | `game-completed.schema.json` | A new field is a deliberate two-file change. Adding a consumer means adding its fields to `CONSUMER_REQUIRED` |
| The two Go workers are the shape to copy | delta §10.11 | `/health` says the process is alive, never that a dependency is; a success **counter** beside every gauge; back off on the loop, never crash |

Four things found while grounding this spec, all load-bearing:

- **A room's log is split across two topics, and there is no ordering between them.** `topicFor`
  sends `RoomCreated`/`GameCompleted`/`RoomCompleted`/`RoomExpired` to `room.lifecycle.events` and
  everything else to `room.public.events`. Per-room ordering is a *per-topic* guarantee. A consumer
  reading both — spectator and analytics both must — can genuinely see room R's `GameCompleted`
  (seq 90) before its `CardPlayed` (seq 88). **This is why the dedup has to be a set and not a
  high-water mark**, and it is what makes E3's choice the right one rather than the tidy one.
- **The architecture has spectator consuming `room.public.events` only**
  (`01-service-architecture.md:146-147`), which means the spectator view would never learn that the
  game ended — `GameCompleted` is a lifecycle event. The room would simply stop updating and expire
  by TTL. P6 has spectator consume **both** topics, and records the delta.
- **The Python services are stdlib-only by construction.** `pyproject.toml` carries
  `dependencies = []` and the Dockerfile says "no pip install, no deps". Ranking and both analytics
  services need a Kafka client and a Postgres driver, so they become the first Python images in this
  repo with a dependency layer.
- **`ranking` and `analytics` databases already exist but are owned by the bootstrap `app` role**
  (`gitops/platform/postgres/databases.yaml`, and the comment there says exactly this: "the rest stay
  on the bootstrap `app` role until their phase"). This is their phase — they get their own login
  roles and sealed passwords, as identity did in P2 and room-gameplay in P3.

## Locked decisions (session 2026-08-12)

| # | Decision | Chosen |
|---|----------|--------|
| E1 | Where the spectator's live feed comes from | **Kafka → Redis projection → its own SSE.** `spectator` consumes both room topics, maintains `spectator:room:{id}` in Redis (persistence §5), and serves SSE that the gateway routes at `/rooms/{id}/spectate`. Players keep P4's path untouched — the gateway still tails `room:{id}:events` for them. This is the phase's whole point: the first Kafka consumer in the system |
| E2 | How wide the phase is | **All four placeholders become real, in full** — `ranking`, `spectator`, `analytics-workers`, `analytics-api`. Nine of ten deployables. P7 inherits one service; P8 inherits business metrics that already exist |
| E3 | How consumers stay idempotent | **`consumed_events` everywhere**, keyed `(source, event_key)` with `event_key` = `ce-id`, written in the **same transaction** as the projection. One pattern, and the one the handoff names. *Mechanism refined for spectator below* |
| E4 | Who may watch and who may read | **A session for all of it.** `spectate`, ratings, leaderboard and stats all require a session, like every non-auth route since P4. A spectator is a logged-in user who is not seated at the table, which is what makes a spectator count meaningful |
| E5 | How deep ranking goes | **Rating + history + leaderboard.** `GET /players/{id}/rating`, `/players/{id}/rating-history`, `/leaderboard`. A rating-change row is the natural write, and it is what makes "why is my Elo 987" answerable. **No `ranking.events` topic** — nothing would consume `EloUpdated` until P8, which can read the database |
| E6 | What analytics projects | **Three read models from both topics** — per-player stats, per-room game history, global overview — served as `/stats/players/{id}`, `/stats/rooms/{id}`, `/stats/overview`. Brackets stay P7's to add to a schema that will already exist |
| E7 | What reaches the CLI | **`spectate`, `rating`, `leaderboard`, `stats`.** The CLI is the harness the exam is driven through, so no part of P6 needs `curl` to demonstrate |
| E8 | P5's two open ACs | **Both close in P6.** AC-P5.8 by suspending the Argo root — the way `ESTADO-FINAL` names after scale-to-0 and child-app patching both lost to `selfHeal` — and AC-P5.10 by a live drop-and-return probe inside the 60 s window, which P6's spectator drill already has clients connecting and dropping for |

### E3, refined for spectator — the outcome, not the table

The decision's outcome is *every consumer is idempotent against redelivery, on the `ce-id` key, with
one pattern*. Ranking and both analytics services get the literal `consumed_events` table in their
own database. Spectator **has no database and must not acquire one**: persistence §5 makes the whole
context a Redis read model, and `databases.yaml` says so in a comment. Its dedup is therefore the
same key in the store it does have — a per-room Redis **set** of applied sequence numbers,
`SADD`-then-apply, sharing the room's TTL.

What it is *not* is `spectator:room:{roomId}:seq`, the "last processed sequence number" persistence
§5 specifies. A high-water mark is wrong here for the reason above: the room's log arrives on two
topics with no ordering between them, so a lifecycle event at seq 90 landing before a public event at
seq 88 would push the mark past 88 and drop a real frame forever. Recorded as a delta.

## In scope

- **`ranking` becomes real** (Python) — a `room.lifecycle.events` consumer group that filters
  `roomType == "CASUAL" && isAbandoned == false`, applies a multiplayer Elo from `finishingOrder`,
  and serves rating, rating-history and leaderboard reads. Sixth fully-wired service.
- **`spectator` becomes real** (Node/TS) — a consumer group over both room topics maintaining
  `spectator:room:{id}` in Redis, plus an SSE endpoint whose first frame is the snapshot. Seventh.
- **`analytics-workers` becomes real** (Python) — a consumer group over both topics writing three
  projections into the `analytics` database. Eighth.
- **`analytics-api` becomes real** (Python) — read-only HTTP over those projections. Ninth.
- **Gateway routes** for all four surfaces, session-required, one door as always.
- **CLI**: `spectate <roomId>`, `rating [player]`, `leaderboard`, `stats`.
- **Platform**: `ranking` and `analytics` Postgres login roles + sealed passwords; the two databases
  change owner off `app`.
- **Contract check**: `spectator` joins `CONSUMER_REQUIRED`; the schema stays `additionalProperties:
  false` and the sample stays producer-generated.
- **P5's two open ACs**, closed live (E8).

## Out of scope

- **No `ranking.events` topic and no `EloUpdated` event** (E5). A second producer needs its own
  outbox to be honest about log-before-broadcast, and nothing would consume it until P8.
- **No tournament anything** — no bracket projections, no `TournamentCompleted` handling, no
  placement rating. P7's, on a schema P6 leaves ready.
- **No Grafana dashboards and no alert rules.** Services expose metrics; P8 consolidates them.
- **No ClickHouse.** Architecture §7 offers "ClickHouse / PostgreSQL"; this is the PostgreSQL half,
  and the `analytics` database already exists for it.
- **No consumer scale-out** — one replica per consumer, no partition rebalancing story beyond what
  the client library does by default. Same deliberate gap P5 took for the relay.
- **No spectator chat, no spectator-visible player list beyond what the view holds**, no replay of a
  finished room beyond its TTL.
- **No change to how players receive their feed.** P4's gateway tail of `room:{id}:events` is
  untouched. Two transports, one filter — the spectator path is a third reader of the same events,
  not a replacement for either.

## Constraints from tech-stack

- **Languages are fixed** (`tech-stack.md` §2): `ranking`, `analytics-workers`, `analytics-api` in
  Python (pytest + ruff + mypy strict), `spectator` in Node/TS (vitest + eslint + tsc). No new
  language, no shared library across services.
- **One chart per service, digest-pinned promotion, Argo reconciles** — the four charts already
  exist and only need overlays with real values.
- **Sealed Secrets for every credential.** `seal.sh`'s `generate` is append-only by design: adding a
  password must not regenerate an existing one, or every live cluster stops authenticating.
- **Change detection is path-based**, so each service is pushed on its own to keep pipelines honest.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| **Cross-topic interleaving corrupts a projection** — seq 90 applied before seq 88 | Dedup is a *set*, never a high-water mark (E3 refinement). Terminal state is sticky: once a room is `COMPLETED` a later-arriving lower-seq event updates history but never reopens it. A property test generates interleavings and asserts the final projection is order-independent |
| **Elo applied twice moves a rating twice** | `consumed_events` insert and the rating write are one transaction. Bite-checked by replaying the same `GameCompleted` and asserting the rating does not move |
| **A gauge that never ran reads healthy** — the failure that has now appeared three times | Every gauge ships with a success counter: `ranking_events_consumed_total`, `spectator_events_projected_total`, `analytics_projection_writes_total`. A drill check asserts the counters moved, not that the gauges look fine |
| **Python images gain a dependency layer** and kaniko builds each service from its own directory | Pin exact versions in `requirements.txt`; no shared module across services (the handoff warns about the build context). First push per service verifies the digest actually moved |
| **Changing a CNPG database owner off `app`** | Both databases are empty — nothing to lose if CNPG needs the `Database` recreated. Verified on the drill cluster before the closure |
| **The spectator SSE repeats P4's four stream bugs** | P4's lessons are explicit: `flushHeaders()`, disconnect handler registered *before* the await, TTL refreshed on every write, and `enableOfflineQueue` decided per connection — off for a loop that retries, on for a one-shot subscribe |
| **A projection that lags looks like a broken projection** | Expose consumer lag from the broker's own view, not from a cursor the process holds — P5's `outboxrelay_lag_seconds` lesson one layer down |

## Mission alignment

The mission's grading lens is *pipeline shape vs. architecture decomposition*, and the north-star
raised the bar to a real system deployed from an empty cluster with ≥3 business metrics driven
through the Client CLI. P6 is where the decomposition stops being a claim: four services in two
languages, each with its own database or store, each an independent consumer group reading the same
log and answering a different question — and none of them able to reach into another's state.
`identity` was once the only service that did anything real; after P6 nine of ten do, and the one
that does not is next. The spectator surface in particular is the mission's privacy argument made
demonstrable: a stranger can watch the game, and `grep seed` over what they receive returns nothing.
