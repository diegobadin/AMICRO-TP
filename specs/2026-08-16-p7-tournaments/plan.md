# P7 — Tournaments — plan

> Ten groups, each one small commit (or a tight series), each validated before the next starts.
> Decisions are in [`requirements.md`](./requirements.md); acceptance evidence goes in
> [`validation.md`](./validation.md). Push each group as it lands — never the whole branch at the
> end (P5's lesson: recovering the per-service push order needed a force-push the classifier blocks).

**Order rationale.** F1–F3 make the room able to *be* a tournament room before anything orchestrates
one. F4 builds the orchestrator against an outbox nobody drains yet — the same seam P3 left for P5.
F5 opens the wire. F6–F7 are the readers, which cannot exist before there is something to read. F8
is the human surface, F9 the delivery, F10 the proof.

---

## F1 — The platform seam

1.1 Add `gitops/platform/postgres/tournament-db-role.yaml` following `ranking-db-role.yaml`: a
`tournament` login role with a **sealed** password.
1.2 Move the `tournament` Database's `owner` from the bootstrap `app` role to `tournament` in
`databases.yaml`, and update that file's header comment — it currently ends "until P7".
1.3 Seal the password with the project key (`~/.amicro_sealing_key`); confirm the cert half is the
one already backed up, since a SealedSecret sealed with a lost key can never be opened on a new
cluster.
1.4 Wire the secret into the tournament chart's env as `TOURNAMENT_DB_PASSWORD`, and into the second
relay's env in F5.

**Done when:** `kubectl get database tournament -n postgres -o jsonpath='{.spec.owner}'` says
`tournament` on a live cluster, and `psql` as the `tournament` role can create a table in it.

---

## F2 — room-gameplay: `roomType` becomes real, and a door for the orchestrator

2.1 `Rooms.create(...)` takes a `RoomType` instead of hardcoding `CASUAL` (`Rooms.kt:131`), and
`Routes.kt`'s `POST /rooms` maps `CreateRoomBody.roomType` onto it — the field has been accepted and
ignored since P3. Unknown/absent ⇒ `CASUAL`.
2.2 A player-facing `POST /rooms` with `roomType: "TOURNAMENT"` is **refused** (`400`): a tournament
room belongs to a tournament. The field being real does not make it public.
2.3 Add `POST /internal/rooms`, taking `{ tournamentId, roundNumber, roomIndex, players[],
advanceCount, turnTimeoutSeconds? }`. It creates the room with every assigned player already seated
and game 1 started, in one transaction, and returns the room id.
2.4 Idempotency: the key is `tournamentId + roundNumber + roomIndex` (architecture §3.3.1's own
rule), reusing the existing `idempotency_keys` table. A retry returns the same room id, never a
second room.
2.5 Authentication (D1): a `X-Internal-Token` compared against a sealed shared secret, in constant
time. Absent or wrong ⇒ `401`. The route is registered outside `PLAYER_AUTH` — it must not be
reachable with a player session either. The token guards the **whole** `/internal` prefix (D1b), so
`timer-worker` sends it too; an unset token closes the routes rather than opening them.
2.6 ~~Ship a NetworkPolicy~~ — **not done, deliberately (D1)**. Neither kindnet nor the EKS VPC CNI
enforces NetworkPolicy without extra setup, so the manifest would apply in no environment this
project runs and would become load-bearing, untested, the first time one did. The reasoning goes in
the threat model and the §12 delta instead.
2.7 A gateway test asserts `route("POST", "/internal/rooms")` is `undefined`, so the endpoint cannot
be published by a later table edit without a red test.

**Done when:** a `curl` from inside the cluster with the token creates a 2-player tournament room
with game 1 in progress; the same call through the gateway 404s; the same call without the token
401s; and a casual room created through the CLI is byte-identical to before.

---

## F3 — room-gameplay: the best-of-three match

3.1 Add `matchScores: Map<PlayerId, MatchScore>?` to `RoomState` (null for casual rooms) and
`advanceCount` to the room's stored config. `MatchScore` = wins, losses, cumulative card points.
3.2 `Evolve.kt` folds `GameCompleted` into `matchScores`.
3.3 `Decide.kt`: when a game completes in a tournament room and the match is **not** resolved,
auto-start the next game (architecture §1: "tournament rooms auto-start"); when it **is** resolved,
emit `MatchCompleted` and then `RoomCompleted`.
3.4 Match resolution is "3 games played **or** the outcome can no longer change" (§3.2's game-count
boundary) — with the early exit written as a predicate over remaining games, not a special case for
2–0.
3.5 `advancingPlayers`: the top `advanceCount` by wins, tie-broken by cumulative card points
ascending, then by finishing position in the last game, then by player id — total and deterministic,
so §6.8.3's "all values identical" has an answer instead of a coin flip.
3.6 An all-forfeit or expired room emits `MatchCompleted` with an **empty** `advancingPlayers`
(§6.8.5): the room still reports, and the round can still complete.
3.7 `MatchCompleted` goes to `room.lifecycle.events` via `topicFor` — no relay change needed, the
row names its own topic.
3.8 Property test: over generated game sequences, `MatchCompleted` fires exactly once per tournament
room, never for a casual room, and `advancingPlayers.size <= advanceCount`.
3.9 **Before publishing it anywhere:** verify `ranking`, `spectator` and `analytics-workers` each
skip an unknown `type` explicitly and count the skip. Fix any that merely fall through.

**Done when:** a scripted tournament room plays three games start to finish, `room_events` shows
`GameCompleted ×3 → MatchCompleted → RoomCompleted`, and the three existing consumers each log a
counted skip for `MatchCompleted` and project everything else unchanged.

---

## F4 — The tournament service

4.1 Replace the placeholder `Main.kt` with a real Ktor service (tech-stack §2: Kotlin/JUnit5).
Keep `/health` answering exactly what it answers today until the chart's probes are re-pointed.
4.2 `Migrate.kt`-shaped schema under an advisory lock, owning: `tournaments`, `tournament_players`,
`rounds`, `round_rooms`, `tournament_events` (the log), `outbox`, `consumed_events`,
`idempotency_keys`. Migration failure **exits** — it owns this schema (delta §11.12's posture).
4.3 The aggregate as a pure decide/evolve pair mirroring `uno/`: `CreateTournament`,
`RegisterPlayer`, `UnregisterPlayer`, `StartTournament`, `RecordRoomResult`, `AdvanceRound`,
`CompleteTournament` — each idempotent by the guard the catalog names (§4.2).
4.4 Events, all appended to the log **and** the outbox in one transaction:
`TournamentCreated`, `PlayerRegistered`, `PlayerUnregistered`, `TournamentStarted`, `RoundStarted`,
`RoomResultRecorded`, `RoundCompleted`, `FinalRoomCreated`, `TournamentCompleted`. Topic
`tournament.lifecycle.events`, key `tournamentId` (architecture §3.3.2).
4.5 Round generation: partition the surviving players into rooms of `TOURNAMENT_ROOM_SIZE`, seed
deterministically (sorted by player id, so a replay produces the same bracket), and call
`POST /internal/rooms` per room with the idempotency key from F2.4. A room that fails to create is
retried with backoff; the round does not start until every room exists.
4.6 The `tournament-saga` consumer on `room.lifecycle.events` (D7): classify on the **body's**
`type` — never `ce-type`, which is `com.unoarena.room.MatchCompleted.v1` — act on `MatchCompleted`
and `RoomExpired`, skip everything else with a counted reason. Dedup through `consumed_events` on
`(source, ce-id)` in the same transaction as the state change.
4.7 Round-completion gate: `completedRooms == totalRooms` inside the aggregate (§7.4.3). Terminal
status is **sticky** — a late event never reopens a completed round or tournament (delta §11.3).
Rooms report exactly once; a duplicate `RecordRoomResult` for a known `roomId` is a no-op.
4.8 Advancement: survivors of round N seed round N+1. When `survivors <= TOURNAMENT_ROOM_SIZE`, the
next room is the **final** (`FinalRoomCreated`); its `MatchCompleted` produces `TournamentCompleted`
with `champion` and `finalPlacements`. Zero survivors ends the tournament with no champion rather
than hanging.
4.9 The consumer **retries its own startup for ever** with backoff (delta §11.11), and exposes
`tournament_consumer_starts_total` beside the projection counters. The lag gauge read sits in its
own `try` — observability must never stop the thing it observes.
4.10 HTTP: `POST /tournaments`, `POST /tournaments/{id}/register`, `DELETE
/tournaments/{id}/register`, `GET /tournaments/{id}`, `GET /tournaments/{id}/rounds/{n}`, plus
`GET /tournaments` (open registration list — the CLI needs a collection, the same additive read
`GET /rooms` needed in P3).
4.11 Do not log health probes (drill lesson) — the kubelet's `/health` lines drowned the one line
that mattered in P6.
4.12 `/metrics` from the first deploy: registrations, tournaments by status, rounds advanced, rooms
provisioned, consumer starts, skips by reason.

**Done when:** against a local Postgres + Kafka, four registrations start a tournament, two rooms
are provisioned in room-gameplay, and the outbox holds the full event sequence **unpublished** —
nothing drains it yet, which is the same seam P3 left for P5.

---

## F5 — The relay becomes source-agnostic, and runs twice

5.1 Introduce the four env knobs (D3) in `envelope.go`/`main.go`, defaulting to today's values:
`EVENT_SOURCE=/room-gameplay`, `CE_TYPE_PREFIX=com.unoarena.room.`, `OUTBOX_KEY_COLUMN=room_id`,
`BODY_ID_FIELD=roomId`. `DATABASE_PASSWORD` is read first, falling back to
`ROOM_GAMEPLAY_DB_PASSWORD` so the running Deployment needs no edit to keep working.
5.2 `store.go`'s query interpolates the key column, validated against `^[a-z_]+$` at startup, with
the process refusing to start otherwise.
5.3 A test asserts the **default** envelope is byte-identical to today's for a known row — headers,
order and body — because three consumers are reading that format (additive-growth rule).
5.4 A second test covers the tournament configuration: `ce-source=/tournament`,
`ce-type=com.unoarena.tournament.TournamentCompleted.v1`, `ce-id={tournamentId}:{seq}`, body merged
with `tournamentId` + `sequenceNumber`.
5.5 Metric names stay as they are; the two instances are distinguished by their pod labels, not by
renaming a shipped metric.
5.6 A second Deployment `tournament-relay` from the same chart (values-driven: env + secret +
service name), pointed at the tournament database with the `tournament` login role.

**Done when:** `kcat` on `tournament.lifecycle.events` shows the full event sequence for a finished
tournament with correct CloudEvents headers, **and** `outboxrelay_published_total` on the
room-gameplay instance has not moved while that happened.

---

## F6 — ranking: placement rating, and both writes made safe

6.1 Schema (additive, D4): `player_ratings` gains `placement_rating int not null default 1000` and
`tournaments int not null default 0`; new table `placement_changes (player_id, tournament_id,
placement, rating_before, rating_after, delta, at)`.
6.2 A second consumer on `tournament.lifecycle.events`, in ranking's existing group family but a new
group, acting only on `TournamentCompleted` (body `type`, never `ce-type`). `consumed_events` is
keyed `(source, event_key)` and the source differs, so the two streams cannot collide.
6.3 The placement delta is a pure function of `finalPlacements` (podium-shaped, top-heavy), unit
tested, with the deltas of one tournament summing to zero the way `elo.deltas` does.
6.4 **E4 — make both writes safe.** Replace `_rating_of` + write-back with, in one transaction:
insert-missing rows (`on conflict do nothing`), then
`select ... where player_id = any(%s) order by player_id for update` — a deterministic lock order,
so two replicas cannot deadlock and cannot lose an update — then apply. The N+1 select disappears as
a side effect, which is fine, but the reason for the change is correctness.
6.5 A test that **bites on the actual race**: two concurrent transactions applying deltas to an
overlapping player set, asserting both land. Bite-check by restoring the old read-modify-write and
watching it fail.
6.6 Rewrite the scaling comment P6's review pass corrected — it is now the description of a fixed
problem, and a comment that describes a constraint that no longer exists is the same defect in
reverse.
6.7 `GET /players/{id}/rating` carries both ratings; `rating-history` gains the placement rows.

**Done when:** a finished 4-player tournament moves four placement ratings, leaves Elo untouched,
and the concurrency test bites when the fix is reverted.

---

## F7 — analytics: the bracket read model

7.1 `analytics-workers` gains bracket tables in the schema it already owns: `tournaments`,
`tournament_rounds`, `tournament_rooms`, `tournament_placements`.
7.2 Project them from `tournament.lifecycle.events` in the existing `analytics-projections` group
(the same worker gains a topic; it does not gain a group — §7.2 forbids joining an existing group
only for a *new consumer*, and this is the same one).
7.3 Every write is an **atomic upsert**, preserving the property that makes this service the
scalable contrast to ranking. Terminal status is sticky (delta §11.3).
7.4 `analytics-api` serves `GET /tournaments/{id}/bracket` and `GET /tournaments` — reads only,
holding the CQRS split: the read side never writes.
7.5 Extend the writer/reader coupling test — the read side builds its schema from the writer's
`schema.py`, so a renamed bracket column turns the read side red.

**Done when:** `GET /tournaments/{id}/bracket` returns every round, every room, its players and its
advancers for a finished tournament, and a replay of the topic changes not one row.

---

## F8 — The human surface

8.1 Gateway routes, all `auth: true`, one door: `POST|GET /tournaments`,
`POST|DELETE /tournaments/{id}/register`, `GET /tournaments/{id}`,
`GET /tournaments/{id}/rounds/{n}`, `GET /tournaments/{id}/bracket` (→ analytics). Add `tournament`
to the `Target` union.
8.2 CLI `tournament register` — registers, then **waits**: polls status, and when the player is
assigned a room, drops into the same play loop `play --casual` already uses. The player types one
command for the whole tournament.
8.3 CLI `tournament status [--id]` — status, round, the player's room, the bracket.
8.4 `bot --tournament` — registers and plays whatever it is assigned, round after round, until
eliminated or champion. Reuses the existing bot loop; the only new logic is "which room am I in now".
8.5 Session identity comes from `api.ts`'s exported `playerId()` — the one place that owns it. This
is the P6 post-closure bug's exact shape and the new commands are where it would recur.
8.6 Only offer legal actions, and never let the client do the player's job (P3 drill lessons): the
CLI does not auto-register for a tournament it was not asked to join.

**Done when:** four terminals run `unoarena tournament register`, and every one of them plays its
matches through to a champion with no other command typed.

---

## F9 — Delivery: the tenth placeholder becomes real

9.1 `services/tournament/.gitlab-ci.yml` from `ranking`'s fragment. **`deploy-staging` must
`needs:` the BUILD job, not just `deliver`** — `$IMAGE`/`$IMAGE_DIGEST` come from the build's dotenv
and do not chain through `deliver`. Copying the stub's `needs` pins `repository: ""` **and goes
green**. This is the trap that bit `ranking` in P6.
9.2 Kotlin/kaniko flags in the Dockerfile from the first build: `-XX:-UsePerfData` and
`-Pkotlin.compiler.execution.strategy=in-process`. Both fail the image build *after* a successful
compile, so a green test job says nothing about them.
9.3 Chart: real probes, resources, env, the sealed secrets from F1, `ServiceMonitor`, and the
staging overlay ready to receive a digest.
9.4 The `tournament-relay` Deployment (F5.6) and its overlay.
9.5 Contract check: tournament event schemas + `CONSUMER_REQUIRED` entries for the tournament
consumers (`ranking` on `TournamentCompleted`, `analytics-workers` on the bracket events). Editing
`ci/contracts/**` and `ci/templates/**` pulls every service into the pipeline — **batch these into
one commit**.
9.6 Verify each service's digest actually moved after its push. The pin is per-service and change
detection is path-based; a phase that changes N services must rebuild all N (P4/F8's lesson).

**Done when:** ten of ten services are `Synced/Healthy` with real digests, and
`gitops/apps/tournament/overlays/staging/values.yaml` holds a `sha256:` — checked by reading the
overlay, not the job status.

---

## F10 — Prove it, then read it as a reviewer

10.1 **From-empty drill.** `kind delete cluster` → `install.sh` → wait. Ten of ten Healthy, eleven
Prometheus targets, four consumer groups + the new one, both relays draining their own outbox.
10.2 **The casual regression gate runs first**, unmodified: the two-process casual game plays,
finishes, scores, is watched and is counted exactly as it did in P6. P7 does not get to break P4.
10.3 The full tournament drill: four CLI processes, registration → rounds → final → champion, with
`grep -c seed` **0** on the new topic and on every new surface.
10.4 **A second from-empty drill if — and only if — the first one's findings are startup-shaped.**
Three of P6's five defects were cold-start-only and were "verified" by a rolling deploy onto a warm
cluster, which is the exact condition under which none of them can appear.
10.5 Replay proof: reset both consumer groups to `earliest`, confirm every projection count is
byte-identical and no rating moves twice.
10.6 `CHANGELOG-design.md` §12 — the P7 deltas. At minimum: the `tournament.room-creation` topic
deliberately not created (E1); `advanceCount` passed at room creation (D2); the relay generalised;
the Round Kickoff Workers not built; no admin role; both rating writes made concurrency-safe, which
**retires** the constraint §11's review-pass note described.
10.7 Roadmap: mark P7 **SHIPPED**, write the "Handoff from P7" block for P8, and write
`ESTADO-FINAL.md`.
10.8 **The self-review pass**, as a PR reviewer would read someone else's diff — including the
explicit re-examination of D6 (the ~75 duplicated Python lines) with the reasoning recorded, not
inherited. Its own table in this file, with a "checked and not changed, and why" list. The
post-closure pass is 3 for 3 at finding the phase's worst defect; budget for a follow-up commit on
`main` and a full pipeline.

**Done when:** every acceptance criterion in `validation.md` is green with evidence, and P7 is
closed by the roadmap's four-step procedure.

---

## What this plan deliberately does *not* include

- **Round Kickoff Workers** — the sharded surge pool for a 1M-player first round. At a threshold of
  four it is a pool of one; R3 says the scale story is told with the architecture's numbers.
- **The `tournament.room-creation` topic** — E1 provisions over HTTP. Recorded as a delta, not
  quietly skipped.
- **An admin role or admin-gated tournament creation** — identity has no roles and P7 does not
  invent one.
- **The stale-room detector and compensating reads** (§7.4.2) — the timer worker is the recovery
  path that exists.
- **Re-entry, unregistration after start, or rating-based seeding.**
- **`EloUpdated` / a `ranking.events` topic** — still deferred (P6/E5).
- **A rendered bracket** — JSON, read through the CLI.
- **Dashboards** — P8 consolidates; P7 only exposes.
- **A shared Python consumer package** — D6, revisited deliberately in F10.8 and kept.
- **A shared Kotlin module between room-gameplay and tournament** — D8, same kaniko build-context
  trade the Go workers and the CQRS pair already made.
- **Scaling any consumer past one replica.** F6 removes the correctness *barrier* in ranking; it
  does not turn the replica count up, and no drill claims partition scale-out.
