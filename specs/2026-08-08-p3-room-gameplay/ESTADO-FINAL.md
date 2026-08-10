# ESTADO FINAL — P3: Uno Engine + Room-Gameplay Core

> Closed 2026-08-10. All nine acceptance criteria green. `room-gameplay` is no longer a
> placeholder: the rules are a property-tested module, every accepted move is durably appended to
> an immutable log before anyone sees it, and **two CLI processes play a complete casual game of
> Uno to a winner against a cluster deployed from empty**. That is the casual-first gate the
> program was built around, and it is open.

## What shipped

- **`services/room-gameplay/engine/`** — the rules, as a Gradle module with nothing but the Kotlin
  stdlib and kotlinx.serialization on its runtime classpath. Two pure functions,
  `decide(state, command, now, seed)` and `evolve(state, event)`, covering all 14 Room invariants of
  §3.2.1: the first-card rule, action cards, wild colour declaration, the Uno! call with its
  five-second challenge window, the deck recycle, disconnection, forfeit and the last-player-standing
  ending. The clock and the shuffle seed are parameters, never ambient — which is what makes
  deadlines testable without sleeping and replay reproduce the exact deck order.
- **An event-sourced core** — `room_events` (the immutable game log), `outbox`, the `rooms`
  projection and the idempotency map, written in **one transaction**, with the HTTP `2xx` sent only
  after it commits. The primary key on `(room_id, sequence_number)` is the concurrency mechanism
  itself: two writers that computed the same event from the same state race there, and exactly one
  wins.
- **REST per Architecture §2.3.1** — `/rooms`, the membership resource (join, leave, reconnect),
  `/rooms/{id}/games` and the move log, with `If-Match` mandatory on moves (`428` when absent,
  `412` when stale, carrying the current state so the loser reconciles from the response) and
  `If-None-Match` → `304` on the player-scoped read.
- **Session integration without a gateway** — the JWT is validated locally with identity's key, and
  `identity.session-events` is consumed so a superseded session disconnects the player inside the
  room and opens the 60-second window. Idempotent by `oldSessionId`, because Kafka is at-least-once.
- **Platform seam mirroring identity's** — its own CNPG role owning `room_gameplay`, sealed secrets,
  a pinned digest, a `startupProbe`, a scraped `ServiceMonitor`, and a second NodePort published by
  kind.
- **Client CLI** — `room create/join/list/leave` and a playable `play --casual`: the turn board,
  the numbered hand with the legal cards marked by the *server's* own check, and the canonical
  §5.F notation printed exactly as the backend sent it.

## Evidence

`validation.md` carries the full transcripts. The short version:

| AC | Verdict |
|---|---|
| AC-P3.1 rooms are resources | Idempotent create replays as `200` with the same room; join twice `409`; leave twice `204`; someone else's membership `403`. |
| AC-P3.2 the engine is provably right | 1000 generated games per property: the card multiset is conserved at **every** transition, at most one challenge window and only on a one-card hand, the ring always valid, every table size 2–10 plays through. Replay reproduces the served aggregate exactly, deck order included. |
| AC-P3.3 log before broadcast | A trigger raising on any outbox insert leaves zero events, zero outbox rows, no projection row and the sequence number unconsumed — against a real Postgres, in CI and on kind. |
| AC-P3.4 concurrency is honest | `428` / `412` / `409` / `304` on the cluster; two writers on one sequence number → one `201`, one `412`. |
| AC-P3.5 deadlines without a scheduler | Past the turn timer, a move that would have been out of turn is accepted, because `TurnTimedOut` is settled and logged first. |
| AC-P3.6 a superseded session disconnects | A second login produced `PlayerDisconnected` + `TurnSkipped` in the room, over the topic P2 already published. |
| AC-P3.7 a full game through the CLI | Two `play --casual` processes, from-empty cluster: wild with a declared colour, draws, an Uno! call, a **successful challenge** (2-card penalty), and `GameCompleted` → `RoomCompleted` closing a 245-event log. |
| AC-P3.8 it comes up from empty | `install.sh` alone: `Synced/Healthy`, own DB role, decrypted secrets, migrated schema, `/metrics` scraped, image pulled by digest. |
| AC-P3.9 pipeline unchanged and green | Same stages, same job kinds. `test:room-gameplay` runs the property and replay suites against an ephemeral Postgres. |

## What the drills caught that tests did not

The empty-cluster drill earned its place again, and so did running two real CLI processes:

1. **`install.sh` aborted before registering anything.** Its `argocd-server` rollout wait was capped
   at 180 s; on a machine with a cold image cache the rollout took ~6 minutes, so `set -e` killed
   the script *before* the app-of-apps were applied. Argo came up with nothing to reconcile. P2's
   drill passed only because the images were already local — the faculty's machine will not have
   them. Now 600 s.
2. **Two `play --casual` processes started together each created a room** and waited for each other
   forever. They converge on the lowest room id now; and the projection stopped listing rooms with
   nobody in them, which is what an abandoned room becomes.
3. **The CLI called Uno! on the player's behalf**, which makes every player permanently safe and
   deletes the entire call-and-challenge mechanic. Calling is the player's job.
4. **The board offered `pass` before drawing**, which is a `409`.
5. **The Kotlin compile daemon raced kaniko** — the same shape as P1's `hsperfdata` bug: a JVM temp
   file deleted while the snapshot is taken, failing the image build *after* a successful compile.
   `gradle --no-daemon` does not cover it, because the Kotlin compiler forks a daemon of its own.

And three more from the review pass, reading the branch back as if it were someone else's PR:

6. **The idempotency sweep was never called.** `Migrate.kt` defined it, a test exercised it, and
   nothing in `main()` ran it — so the table grew for the life of the deployment despite D5
   promising 24-hour retention. Dead code that looked like a feature.
7. **Business metrics were counted on four of the six mutating routes.** Leaving a two-player game
   forfeits, which ends the game under invariant 7 and emits a `GameCompleted` nobody requested —
   and `games_completed_total`, one of P8's three required business metrics, never saw it. There is
   now a test that fails without the fix.
8. **`play --room <id>` inherited the casual convergence logic** and could quietly move the player
   to a different room. A player who names a room means that room.

Two more came from tests that were themselves wrong, which is worth recording:

- The first property suite was **green while running nothing**: `checkAll` returns a
  `PropertyContext`, so `fun x() = runBlocking { checkAll(...) }` is not a valid JUnit test and was
  silently skipped. The suites now assert their own attempt count.
- `roomgameplay_rooms_created_total` was silently exposed as `roomgameplay_rooms_total`: OpenMetrics
  reserves the `_created` suffix and the Prometheus client rewrites the name. The metrics test now
  asserts the exact strings P8 will chart.
- identity's `stores a hash, never the password` registered with the password `pw` and asserted the
  stored hash does not contain `pw`. Salt and derived key are base64, so those two letters turn up
  by chance: measured over 5000 hashes, **3.5% of runs**. It took `main` red on a pipeline that
  changed nothing in identity. P2 code, found by P3's pipeline.

## What the review pass removed

Reading the branch back as a reviewer took out more code than it added (250 inserted, 286 deleted):

- **Creating a room stopped being `submit` with extra arguments.** A fresh id cannot lose a
  sequence race, so the retry loop made three futile attempts and reported the result as `Stale`,
  which it never was. `Rooms.create` now says what it does — the only possible conflict is another
  request having used the same `Idempotency-Key`, and then that request's response *is* the answer.
- **Three membership verbs shared five lines of identical guard**, and nine call sites built the
  same `ETag` by hand. One helper each.
- **Four hand-rolled JSON log writers became one**, built with the JSON library the service already
  depends on. The consumer's version did not escape, so a `reason` arriving from Kafka with a quote
  in it would have broken the line an operator reads under pressure.
- **Four test classes each rebuilt the same pool, migration, truncate and HTTP helpers.** One
  fixture now, and `MovesHttpTest`'s hand-written play loop went with it.
- `Outcome.Stale` no longer takes an `events` parameter it could never carry.

## Decisions worth carrying forward

- **D15 (deviation from the plan's dependency list):** the engine takes kotlinx.serialization. The
  events *are* the persisted and published contract, and hand-writing a codec for twenty-two types
  would put replay correctness in three hundred lines of mapping instead of in the compiler. No
  Ktor, no JDBC, no Kafka there — the separation that matters is intact.
- **One sequence number per event**, not per command (`CHANGELOG-design.md` §8.5). That is what
  makes "exactly one writer commits" a database guarantee instead of a convention.
- **`CardPlayed` names the player who acts next**, and `evolve` moves the turn to that seat
  absolutely, so the `TurnSkipped` that follows sets the same seat again rather than moving relative
  to it. The two cannot disagree by an off-by-one — which is the kind of thing that silently breaks
  replay.
- **A rejected command still returns the deadline events that expired before it was judged.** Time
  passing is a real state change; dropping it would mean a player never learns their turn timed out.
- **The shared JWT secret between identity and room-gameplay is a real coupling** and is written
  down (§8.9), not normalised. P4's gateway removes it.

## Known gaps, deliberate

- **Nothing drains the outbox.** It fills correctly from P3 on; P5's relay is the only thing to add.
  Every row is `published_at IS NULL` on purpose.
- **The live feed is inferred from polling**, so two events inside one interval collapse into one
  line. P4's SSE replaces the loop over the same endpoint.
- **Deadlines only fire when a command arrives** (E2). A player who walks away is penalised the
  moment anyone else moves, not before. P5's timer worker closes it; the CLI README says so rather
  than leaving the faculty to discover it.
- **Tournament rooms are modelled but not implemented** — `roomType` exists, only `Casual` plays.
- **The restricted audit read** (`GET …/moves` with mTLS/RBAC, §2.9) is not exposed.

## Next

**P4 — the gateway and SSE.** It collapses the two NodePorts into one entry point, takes JWT
validation off room-gameplay (removing the shared secret), turns the polling loop into a stream,
and adds `bot --casual`. The casual gate is open, so the program can proceed past it.
