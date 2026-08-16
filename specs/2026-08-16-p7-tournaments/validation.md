# P7 — Tournaments — validation

> Every item is binary and checkable against a real cluster or a real pipeline run. Evidence
> (transcripts, counters, overlay contents) is pasted under each AC as it is obtained, the way
> P5's and P6's validation files carry theirs.

## Acceptance criteria

| AC | Statement | How it is proved |
|---|---|---|
| **AC-P7.1** | A player registers for a tournament through the CLI and nothing else is needed. | Four `unoarena tournament register` processes; no other command typed. |
| **AC-P7.2** | Crossing the threshold starts the tournament by itself. | The fourth registration produces `TournamentStarted` + `RoundStarted` with no operator action. |
| **AC-P7.3** | Rounds provision real rooms, and the same request twice provisions one. | `rooms` projection shows `room_type = TOURNAMENT`; a replayed `POST /internal/rooms` returns the same room id and creates no second row. |
| **AC-P7.4** | A tournament room plays a best-of-three and reports one result. | `room_events` for the room: `GameCompleted ×2–3 → MatchCompleted → RoomCompleted`, exactly one `MatchCompleted`. |
| **AC-P7.5** | The match ends early when the outcome cannot change. | A 2–0 room emits `MatchCompleted` after game 2 and never starts game 3. |
| **AC-P7.6** | Advancement is deterministic, including on a total tie. | Unit + property tests over generated `matchScores`, including all-equal, assert a total order and `advancingPlayers.size <= advanceCount`. |
| **AC-P7.7** | A round advances only when **every** room has reported. | With one room still in progress, no `RoundCompleted`; when the last reports, exactly one fires. |
| **AC-P7.8** | An abandoned or expired tournament room does not hang the round. | Room expires → `MatchCompleted` with empty `advancingPlayers` → the round still completes. |
| **AC-P7.9** | The tournament reaches a champion. | `TournamentCompleted` with `champion` + `finalPlacements`; CLI prints it. |
| **AC-P7.10** | The tournament's events are on the wire, correctly enveloped. | `kcat` on `tournament.lifecycle.events`: `ce-source=/tournament`, `ce-type=com.unoarena.tournament.*.v1`, `ce-id={tournamentId}:{seq}`, key = `tournamentId`. |
| **AC-P7.11** | The generalised relay did not move the room-gameplay format by one byte. | Default-envelope test green **and** `outboxrelay_published_total` on the room instance unchanged while the tournament instance publishes. |
| **AC-P7.12** | A finished tournament moves placement ratings and leaves Elo alone. | `placement_changes` has one row per finalist; `player_ratings.rating` (Elo) identical before/after; `rating` CLI shows both. |
| **AC-P7.13** | Both rating writes survive concurrency. | The two-transaction test passes, and **bites** when the old read-modify-write is restored. |
| **AC-P7.14** | The bracket is queryable by anyone with a session. | `GET /tournaments/{id}/bracket` through the gateway: every round, room, player and advancer. |
| **AC-P7.15** | A replay changes nothing. | Both groups reset to `earliest`: every projection count byte-identical, no rating moves twice, dedup counters equal the redelivered count. |
| **AC-P7.16** | One door, session required. | Every new gateway surface 401s without a session; `/internal/rooms` is **not routed** (404 through the gateway) and 401s in-cluster without the token. |
| **AC-P7.17** | The casual game is untouched. | The unmodified P6 casual drill: game plays, finishes, scores, is watched, is counted. Run **before** the tournament drill. |
| **AC-P7.18** | Ten of ten from empty. | Fresh `kind` cluster → all ten `Synced/Healthy`, real digests, every target scraped. |
| **AC-P7.19** | The contract check has bite with the new schemas. | Green with the tournament consumers declared; red on a hand-edit in **both** directions. |
| **AC-P7.20** | Pipelines green, digests real. | Every changed service's digest verified to have moved; the tournament staging overlay holds a `sha256:`. |

## Checklists

### Local — room-gameplay
- [ ] `POST /rooms` with no `roomType` creates a `CASUAL` room, unchanged from P6.
- [ ] `POST /rooms` with `roomType: "TOURNAMENT"` is refused with `400`.
- [ ] `POST /internal/rooms` without `X-Internal-Token` → `401`; with a wrong token → `401`.
- [ ] `POST /internal/rooms` seats every assigned player and starts game 1 in one transaction.
- [ ] The same `tournamentId + roundNumber + roomIndex` twice → one room, same id returned.
- [ ] A casual room never gains `matchScores` and never emits `MatchCompleted`.
- [ ] `MatchCompleted` lands on `room.lifecycle.events` (via `topicFor`, no relay change).
- [ ] `ranking`, `spectator` and `analytics-workers` each **skip `MatchCompleted` with a counted reason** — verified in their metrics, not assumed from the code.

### Local — tournament service
- [ ] Migration failure exits the process (it owns its schema — delta §11.12).
- [ ] The consumer retries its own startup for ever; `tournament_consumer_starts_total` increments per attempt.
- [ ] The lag gauge read is inside its own `try` and cannot stop the poll loop.
- [ ] Classification is on the body's `type`; a `ce-type`-based comparison appears nowhere.
- [ ] Dedup is a **set** (`consumed_events`), never a high-water mark.
- [ ] Terminal status is sticky: a late `MatchCompleted` never reopens a completed round or tournament.
- [ ] Round generation is deterministic — same registrations, same bracket.
- [ ] Health probes are **not** logged.
- [ ] `/metrics` exposes registrations, tournaments by status, rounds advanced, rooms provisioned, consumer starts, skips by reason — asserted as exact exposed strings (Prometheus rewrites names it dislikes).

### Local — relay
- [ ] Defaults produce a byte-identical envelope for a known row.
- [ ] `OUTBOX_KEY_COLUMN` failing `^[a-z_]+$` refuses to start.
- [ ] `DATABASE_PASSWORD` absent falls back to `ROOM_GAMEPLAY_DB_PASSWORD`, so the shipped Deployment needs no edit.

### Local — ranking & analytics
- [ ] Placement deltas sum to zero over one tournament.
- [ ] A tournament processed twice moves no rating twice.
- [ ] Every bracket write is an atomic upsert (the property that keeps analytics scalable).
- [ ] `analytics-api` writes nothing; the writer/reader schema test still binds them.

### Local — CLI
- [ ] `tournament register` waits, joins its assigned room, plays, and continues into the next round.
- [ ] `bot --tournament` needs no other flag to play a whole tournament.
- [ ] Both use `api.ts`'s exported `playerId()` — no second answer to "who am I" (P6's only user-facing bug).
- [ ] The CLI is built explicitly in every drill script; no conditional `npm run build` (P5's stale-`dist/` lesson).

### Cluster — from empty
- [ ] Ten of ten `Synced/Healthy`; no service left with `digest: ""`.
- [ ] Both relay Deployments running, each against its own database and login role.
- [ ] Four consumer groups plus `tournament-saga`, all present.
- [ ] `tournament` restarts on cold start are the expected 5–6 (owns its schema) — not a crash loop after Postgres is up.
- [ ] A **second** from-empty drill if any finding is startup-shaped.

### CI / GitOps
- [ ] `deploy-staging:tournament` `needs:` the **build** job (`$IMAGE`/`$IMAGE_DIGEST` do not chain through `deliver`).
- [ ] The staging overlay is read after the first real deploy — `repository` non-empty, `digest` a real `sha256:`.
- [ ] `ci/templates/**` and `ci/contracts/**` edits are batched into one commit.
- [ ] No code change rides a `[skip ci]` docs commit.
- [ ] `git pull --rebase` before every local commit (the pin bot pushes to the branch).

### Docs
- [ ] `CHANGELOG-design.md` §12 records every delta, including the two topics **not** created.
- [ ] The roadmap marks P7 **SHIPPED** and carries a "Handoff from P7".
- [ ] `ESTADO-FINAL.md` written.
- [ ] The README documents the degradable state per N3 — what works and what does not, if any group was cut.

## Bite tests

A safety net that has never failed has not been tested. Break each of these deliberately and confirm
the named check goes red.

| Break | Must go red |
|---|---|
| Classify the tournament consumer on `ce-type` instead of the body's `type` | The classification test — and `tournament_events_skipped_total` climbing while nothing advances. **This is P6's most expensive bug; it must be caught by a test this time.** |
| Replace the dedup set with a high-water mark on `sequenceNumber` | The cross-topic interleaving property (a lifecycle event overtaking a public one). |
| Let a late event reopen a completed round | The sticky-terminal property. |
| Restore ranking's `_rating_of` + write-back | The concurrency test in F6.5. |
| Drop `advanceCount` from the internal creation call | The advancement test — not a `null` at runtime. |
| Point the tournament relay at the room-gameplay database | The drill's assertion that the room relay's published counter does not move. |
| Register the internal route in the gateway table | The gateway test asserting `/internal/rooms` has no route. |
| Remove a field a tournament consumer requires from the schema | The contract check, in both directions (add and remove). |
| Make the tournament consumer's start `.catch` merely log | The startup-retry test, and `*_consumer_starts_total` staying at 0 behind a `Healthy` pod. |

Every new **property** test gets bite-checked too, not just unit tests: P5's deadline property passed
while the cache was visibly broken because it probed too far out. Assert the earliest boundary.

## Out-of-scope confirmation

None of these may appear in the diff:

- [ ] No Round Kickoff Workers, no `tournament.room-creation` topic.
- [ ] No admin role, no auth change in identity.
- [ ] No stale-room detector, no compensating read from tournament into room-gameplay.
- [ ] No `EloUpdated`, no `ranking.events` topic.
- [ ] No rendered/ASCII bracket art.
- [ ] No Grafana dashboard (P8 owns consolidation).
- [ ] No shared Python consumer package (D6) and no shared Kotlin module (D8).
- [ ] No replica count raised above 1 on any consumer.
- [ ] No new pipeline stage — the spine is frozen.
- [ ] No rewrite of a P1–P6 data model or event. Every change is a new column, table, event or topic.

## Mission check

1. **Can the faculty's functional test be driven end to end through the CLI, tournaments included?**
   Registration, rounds, best-of-three, advancement and a champion — with no `curl`, no `psql`, and
   no operator step. If yes, the last functional requirement in the north-star's §"What the exam
   requires" is answered by running code instead of a canned string.
2. **Are all ten deployables real, from an empty cluster?** P7 is the phase that finishes the
   architecture, not just the feature list: the tenth service, the second producer, the fourth
   consumer group and the bracket read model each prove a seam was a pattern rather than a one-off.
3. **Did the casual game survive?** The gate that has held since P4 is the one thing P7 could
   plausibly break, and AC-P7.17 runs before the tournament drill for exactly that reason.
