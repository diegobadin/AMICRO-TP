# P7 — Tournaments — requirements

> Phase **P7** of [`../2026-07-26-final-delivery-northstar/roadmap.md`](../2026-07-26-final-delivery-northstar/roadmap.md),
> the last canned placeholder. Read that roadmap's **"Handoff from P6"** block before this file: it
> carries the traps P6 paid for, and every one of them applies here.

## Context

P6 closed with **nine of ten deployables real**. `tournament` is the tenth: today it is
`services/tournament/src/main/kotlin/Main.kt`, a 50-line HTTP server answering
`{"status":"stub"}` on `/tournaments/sample`, carrying `digest: ""` and sitting in
`ImagePullBackOff` on every cluster — its normal state, not a regression.

What the repo already has, and what it does not:

| Already true | Evidence |
|---|---|
| The engine plays **up to 3 games** in a tournament room | `uno/State.kt`: `maxGames = if (roomType == CASUAL) 1 else 3`; `Decide.kt:535` gates `RoomCompleted` on `gamesPlayed >= maxGames` |
| `RoomType.TOURNAMENT` exists | `uno/State.kt:6` |
| A `tournament` database exists, on the bootstrap `app` role | `gitops/platform/postgres/databases.yaml` — its comment says "until P7" |
| The topic names are already specified | architecture §3.3.2: `tournament.lifecycle.events`, partition key `tournamentId` |
| `/internal/...` endpoints are an architecture-sanctioned shape | threat model **T4**: the timer-expiry callback is "not routed through the public gateway" |
| Three consumer groups exist | `ranking-elo`, `spectator-view`, `analytics-projections` |

| Not true yet | Evidence |
|---|---|
| A tournament room can be created at all | `Rooms.kt:131` hardcodes `RoomType.CASUAL`; `CreateRoomBody.roomType` (`Routes.kt:43`) is accepted and **silently ignored** — a dead field |
| `MatchCompleted` and `matchScores` exist | Neither appears anywhere under `services/room-gameplay/` |
| Anything but room-gameplay can publish | `outbox-relay/envelope.go` hardcodes `ceSource = "/room-gameplay"` and `ceTypePrefix = "com.unoarena.room."`; `main.go:62` reads `ROOM_GAMEPLAY_DB_PASSWORD` |
| A bracket store exists | Deliberately absent (P6/D4): a table nobody writes is dead code |
| Ranking's writes are safe to scale | Applying a rating is a read-modify-write; two replicas lose updates |
| Any NetworkPolicy exists in the repo | `grep -rl NetworkPolicy gitops/ services/` → nothing |

## Goal

A player registers for a tournament through the CLI, enough players register to cross a low
configurable threshold, and the tournament runs itself to a champion — generating its own rooms,
playing best-of-three matches in them, advancing winners round by round — with every step of that
visible as an event on Kafka, a bracket a stranger can query, and a placement rating on the podium.

## In scope

**Room Gameplay (the P3 core, additively)**
- `roomType` is honoured at creation; `CreateRoomBody.roomType` stops being a dead field.
- `POST /internal/rooms` — a ClusterIP-only provisioning endpoint that creates a tournament room
  with its assigned players already seated and game 1 started. **Not** in the gateway's route table.
- Match series: `matchScores` on the room state, `MatchCompleted` emitted when the best-of-three is
  resolved (3 games played, or the outcome can no longer change), auto-start of games 2 and 3,
  deterministic tiebreaks.

**Tournament Orchestration (the tenth deployable, Kotlin per tech-stack §2)**
- Registration (`RegisterPlayer` / `UnregisterPlayer`), a configurable low start threshold,
  `StartTournament`, round generation and seeding, room provisioning, `RecordRoomResult` on
  `MatchCompleted`, round-completion gating, advancement, final room, `TournamentCompleted`.
- Its own event log + transactional outbox in the `tournament` database, on its own login role —
  log-before-broadcast, exactly the P3 shape.
- A Kafka consumer on `room.lifecycle.events` in a **fourth** group, `tournament-saga`.
- HTTP reads: tournament status, round detail.

**The async spine**
- `outbox-relay` becomes source-agnostic (env-driven source, `ce-type` prefix, key column,
  database), with defaults that keep the room-gameplay envelope byte-identical.
- A second relay Deployment publishes `tournament.lifecycle.events`.
- Tournament event schemas join the contract check.

**Read models**
- `ranking`: placement rating from `TournamentCompleted`, as a **separate** rating from Elo; and
  **both** rating writes become concurrency-safe.
- `analytics-workers`: the bracket read model, in the schema it already owns; `analytics-api` serves it.

**Edges**
- Gateway routes for the tournament surfaces (all session-required, one door).
- CLI: `tournament register` / `tournament status`, and `bot --tournament`.
- CI/CD + GitOps: the placeholder promoted to a real service on the frozen pipeline spine.

## Out of scope

- **Round Kickoff Workers** (architecture §3.2's sharded surge pool). They exist for the 1M-player
  first-round burst; at a threshold of single digits they would be a pool of one. R3's rule applies —
  the scale story is told with the architecture's numbers, not reproduced live.
- **The `tournament.room-creation` topic.** E1 provisions over HTTP, so the topic is deliberately
  not created. Recorded as a delta.
- **Admin authorisation.** `CreateTournament` is "Admin/System" in the catalog; there is no admin
  role in identity and P7 does not invent one. Creation is open to any session, and said so.
- **The stale-room detector and compensating reads** (§7.4.2). The timer worker already ends
  abandoned rooms, which is the recovery path that exists.
- **Unregister after start, re-entry, and seeding by rating.** Registration closes at start; seeding
  is deterministic but not skill-based.
- **`EloUpdated` / a `ranking.events` topic** — still deferred (P6/E5).
- **Bracket *visualisation*.** A JSON bracket, not a rendered one.
- **P8's dashboards.** Metrics are exposed as they ship; consolidation is P8's phase.

## Decisions

### Locked by the user (E-decisions, 2026-08-16)

| # | Decision | Rationale |
|---|---|---|
| **E1** | **Room provisioning is an internal ClusterIP endpoint** on room-gameplay: `POST /internal/rooms`, taking `roomType`, assigned players, `advanceCount` and an idempotency key. Deliberately absent from the gateway route table. | The gateway overwriting `X-Player-Id`/`X-Session-Id` is the control the whole trust boundary rests on; a backend service minting those header pairs for players who hold no session would dissolve it. An `/internal/` endpoint is the shape the threat model already sanctions (T4), and it keeps the aggregate service free of a Kafka consumer. |
| **E2** | **Tournament is a real producer**: its own outbox written in the same transaction as its state, drained by a **generalised** `outbox-relay` running a second time against the tournament database. | Keeps log-before-broadcast true for the second producer instead of inventing a direct-publish path for it. It is what lets `ranking` consume `TournamentCompleted` (architecture §4) and `analytics-workers` project the bracket into the schema it already owns, which is where the P6 handoff put it. |
| **E3** | **The room emits `MatchCompleted`**: `matchScores` on the room state, auto-start of games 2 and 3, `advancingPlayers` computed by the room. | Design §3.2 argues this explicitly ("Why Room owns this, not Tournament"): all games in a match share one room and one player set. The alternative — the tournament counting wins from `GameCompleted` — is a second copy of a rule the Room owns, the exact defect P5 and P6 each caught once. |
| **E4** | **Both rating writes become concurrency-safe.** The placement rating and the existing Elo write stop being lost-update races; the replica count stops being a trap. | The handoff flagged that ranking cannot scale and that P7 edits ranking. Duplicating the landmine into a second write is the one outcome worth avoiding; the comment corrected by P6's review pass would otherwise be re-earned. |

### Implementer decisions (D-decisions — confirm in review)

| # | Decision | Why, and what would change it |
|---|---|---|
| **D1** | The internal endpoint is protected by a **sealed shared token** (`X-Internal-Token`), with a NetworkPolicy shipped alongside it and documented as the production-shaped half. | The architecture's stated control for `/internal/` is "not routed through the gateway + NetworkPolicy" (T4, D6). But **kind runs kindnet, which does not enforce NetworkPolicy**, and the repo has none today — so on the demo cluster that control is decorative. The token is the half that actually bites in the environment the exam runs in. |
| **D2** | `advanceCount` is passed to the room **at creation**; the room computes `advancingPlayers` from `matchScores` with a deterministic tiebreak. | §3.2 has the Room publishing `advancingPlayers` and §3.3 has `advancementCount` in `TournamentConfig`, but nothing says how the number reaches the room. Passing it per room keeps the rule in one place and matches `TournamentRoomRef` being per-room. |
| **D3** | The relay generalisation is **four env knobs with today's values as defaults** (`EVENT_SOURCE`, `CE_TYPE_PREFIX`, `OUTBOX_KEY_COLUMN`, `BODY_ID_FIELD`), plus a generic `DATABASE_PASSWORD` falling back to `ROOM_GAMEPLAY_DB_PASSWORD`. A test pins the default envelope byte-for-byte. | Additive growth: the room-gameplay wire format must not move by one byte, because three consumers are reading it. `OUTBOX_KEY_COLUMN` is validated against `^[a-z_]+$` at startup — it is config, not input, and it is interpolated into SQL. |
| **D4** | The placement rating is a **separate column and a separate history table** (`placement_rating`, `placement_changes`), not a second write into Elo's. | Design §3.3 lists `tournamentPlacementRating` as its own field, and `rating_changes` is `room_id uuid not null, game_number int not null` — a tournament placement has neither. |
| **D5** | Defaults: `TOURNAMENT_MIN_PLAYERS=4`, `TOURNAMENT_ROOM_SIZE=2`, `TOURNAMENT_ADVANCE_COUNT=1`, start **automatically** when the threshold is met. | The roadmap asks for "a low configurable test threshold". 4 players → two rooms of 2 → a final of 2 is the smallest shape that still exercises round generation, advancement *and* a final. Auto-start keeps the demo one CLI command per player, with no admin surface (see out of scope). |
| **D6** | The **~75 duplicated lines between `ranking/consumer.py` and `analytics-workers/consumer.py` stay duplicated**, re-examined rather than inherited. | `plan.md` §11 of P6 flagged this for a deliberate P7 revisit. P7 adds its consumer in **Kotlin**, so the duplication does not grow, and the kaniko per-service build context that made sharing expensive has not changed. Revisited in F10 with the reasoning written down; if P8 adds a fourth Python consumer, the trade flips. |
| **D7** | Consumer group `tournament-saga`, reading `earliest`, committing by hand after its transaction; dedup via `consumed_events` keyed `(source, ce-id)`. | Architecture §7.2: a new consumer must not join an existing group. This is the fourth. |
| **D8** | The tournament service **copies** room-gameplay's log+outbox shape rather than sharing a module with it. | Same kaniko build-context trade the P5 handoff made for the Go workers and P6 made for the CQRS pair. Named here so it is a decision, not an accident. |

## Constraints from tech-stack

- **`tournament` is Kotlin (JVM), JUnit5, ktlint/detekt** — tech-stack §2, non-negotiable per service.
  Both kaniko/JVM traps apply from the first build: `-XX:-UsePerfData` and
  `-Pkotlin.compiler.execution.strategy=in-process` (they fail the image build *after* a clean compile).
- **Pipeline shape is frozen** (north-star program rules): `test → build → deliver → deploy-staging →
  integration-staging`, one fragment per service, promotion by digest. Promoting a placeholder adds
  no stage.
- **Additive growth**: no phase rewrites a previous phase's data model or events. Everything here is
  a new column, a new table, a new event type or a new topic.
- **Always deployable**: P7 is done only when it comes up from an empty cluster.
- **Instrument as you go**: `/metrics` from the first real deploy, not retrofitted in P8.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| **P7 edits the P3 core and the casual gate is the program's cut line.** | The casual path takes no behaviour change: `roomType` defaults to `CASUAL`, `maxGames` stays 1, and `MatchCompleted` cannot be emitted by a casual room. The unmodified two-process casual drill is a **regression gate before** the tournament drill, not after. |
| **A new event type on `room.lifecycle.events` reaches three shipped consumers.** | Each already classifies on the body's `type` and skips what it does not know — but "should" is not "does". Each of the three is checked for an explicit skip path *and* a skip counter before `MatchCompleted` is first published. |
| **A second relay against the wrong database double-publishes every room event.** | Distinct login role, distinct `EVENT_SOURCE`, distinct metric labels; the drill asserts the room relay's published counter does not move while only the tournament relay is working. |
| **The `deploy-staging`/`needs:` trap** — copying the stub's `needs: ["deliver:tournament"]` pins `repository: ""` **and goes green**. | This is precisely the case that bit `ranking` in P6. The overlay is inspected after the first real deploy, not the job status. |
| **Cold-start defects hide behind a warm cluster.** | A fourth consumer, a second relay and a new service all start for the first time here. Startup-shaped findings ⇒ a **second** from-empty drill, per the standing rule. |
| **Touching shipped P6 code (Elo's write path).** | The concurrency fix gets a test that bites on the actual race, bite-checked by restoring the old statement — not a test that merely passes. |
| **A round that never completes leaves the tournament stuck forever.** | Round completion is gated on every room reporting; `RoomExpired` and an all-forfeit `MatchCompleted` both count as a room reporting with zero advancers (§6.8.5). A round with zero advancers ends the tournament rather than hanging. |
| **CI minutes.** | `ci/templates/**` edits pull every service into the pipeline — batched into one commit. Feature-branch pushes are incremental per service (P5's lesson), never one push at the end. |
| **N3 degradability.** | Each group below ships something demonstrable on its own; if the clock wins, the phase degrades at a group boundary and the README documents what works. |

## Mission alignment

The exam requires tournaments — "mandatory but degradable, low configurable test threshold"
(north-star §"What the exam requires"), and N3 planned them end-to-end with the casual core first.
That order held: the casual gate has been open since P4 and P7 is the last functional requirement
still answered by a canned string. It is also the phase that finishes the *architecture* story
rather than just the feature list — the tenth deployable becomes real, the second producer proves
the outbox spine was a pattern and not a one-off, the fourth consumer group proves the async seam
generalises, and the bracket read model lands in the CQRS schema P6 built for it. When P7 closes,
**ten of ten deployables are real** and every context in `docs/design/02-bounded-contexts.md` is
running code.
