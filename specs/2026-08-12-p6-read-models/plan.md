# Plan — P6: Read models (ranking, spectator, analytics)

> Ten task groups, each one commit on `feat/p6-read-models`, each validated before the next starts.
> Groups 2–5 are the four services and are independent of one another once group 1 lands — but they
> are pushed **one at a time** so change detection runs each service's jobs alone and the digest can
> be verified to have moved.
>
> Implementer decisions are flagged **D-n — confirm in review**. Locked user decisions are E1–E8 in
> [`requirements.md`](./requirements.md).

---

## 1. Platform seam — roles, secrets, and the contract's new consumer

Nothing consumable ships here; this is the ground the four services stand on.

1.1 Add `ranking` and `analytics` login roles to `gitops/platform/postgres/cluster.yaml`, mirroring
    the `identity` / `room_gameplay` entries (`ensure: present`, `passwordSecret` naming
    `ranking-db-role` / `analytics-db-role`).

1.2 Change the two `Database` resources in `databases.yaml` from `owner: app` to their own roles,
    and update the file's header comment — it currently says these stay on `app` "until their
    phase", and this is that phase.

1.3 Append `generate RANKING_DB_PASSWORD 24` and `generate ANALYTICS_DB_PASSWORD 24` to
    `gitops/secrets/seal.sh`. **Append only** — the function already returns early on an existing
    key, and regenerating `IDENTITY_DB_PASSWORD` or `ROOM_GAMEPLAY_DB_PASSWORD` would lock every
    live cluster out of its own database.

1.4 Seal `ranking-secrets.yaml` and `analytics-secrets.yaml` into `gitops/secrets/staging/`, plus
    the two `*-db-role` secrets CNPG reads. `spectator` gets **no** secret: Redis and Kafka carry no
    auth on this platform, so a `secretName` for it would be an empty ceremony.

1.5 Add `spectator` to `CONSUMER_REQUIRED` in `ci/contracts/validate.py` with the fields it actually
    reads from `GameCompleted` (`type`, `roomId`, `sequenceNumber`) — the handoff's rule: a new
    consumer is a deliberate edit here, which is what makes the check a leak detector rather than
    decoration.

**D1 — confirm in review.** Migrations are owned per service and run at startup, mirroring
`Migrate.kt`: an advisory lock, idempotent `create table if not exists`, and **exit on failure**.
That is identity's posture, not the Go workers' — and it is right for a service that owns its schema,
where the consume *loop* backs off (delta §10.11) but a service that cannot create its own tables has
nothing to back off toward.

**Done when** `kubectl -n postgres get database ranking analytics` shows both owned by their own
role, and the contract check is green with three consumers listed.

*Note on re-sealing:* `kubeseal` draws a fresh session key per run, so re-sealing an **unchanged**
plaintext still rewrites its ciphertext. Every existing `SealedSecret` therefore shows a diff after
`seal.sh`, and that diff means nothing. Restore the ones whose plaintext did not change
(`git checkout --`) and commit only the new files — otherwise the commit claims to have touched
credentials it did not.

---

## 2. `ranking` — Elo, history, leaderboard (Python)

2.1 `requirements.txt` with pinned `confluent-kafka` and `psycopg[binary]`; `pyproject.toml`
    `dependencies` filled in; Dockerfile gains a build stage that `pip install`s into a venv and a
    runtime stage that copies it. First Python image in this repo with a dependency layer — keep it
    non-root and keep `PORT` from the environment.

2.2 `schema.py` — `player_ratings(player_id pk, rating int, games int, updated_at)`,
    `rating_changes(id, player_id, room_id, game_number, before, after, delta, card_points, at)`,
    and `consumed_events(source, event_key, consumed_at, primary key (source, event_key))` copied
    from `Migrate.kt:76`.

2.3 `elo.py` — the pure function, no I/O. Multiplayer generalisation over `finishingOrder`: every
    player is scored against every other, `E = 1/(1+10^((Rb-Ra)/400))`, actual 1 for finishing ahead,
    delta `= K/(N-1) · Σ(S−E)`, `K = 32`, initial rating **1000** (the number the placeholder has
    been returning since the DevOps checkpoint — continuity worth keeping).

2.4 `consumer.py` — consumer group `ranking-elo` on `room.lifecycle.events`. For each message: read
    `ce-id` from the headers, skip anything that is not `GameCompleted`, then in **one transaction**
    insert into `consumed_events` and, if the insert was new *and* the filters pass, apply the deltas
    and write the history rows. A skip is still recorded, so a redelivery of a skipped event costs
    one insert attempt.

2.5 The two filters, at the consumer entry point exactly as architecture §4.5 draws them:
    `roomType != "CASUAL"` → skip, `isAbandoned` → skip. **`"CASUAL"`, the Kotlin enum name** — the
    handoff's named gotcha, and the one place in this phase where a plausible-looking `"Casual"`
    would silently rank nothing.

2.6 `app.py` — the reads: `GET /players/{id}/rating`, `/players/{id}/rating-history?limit=`,
    `/leaderboard?limit=`, and `/health` reporting only that the process is alive (delta §10.11).
    Keep the existing pure-router shape so the tests stay socket-free.

2.7 `metrics.py` — `ranking_events_consumed_total`, `ranking_events_skipped_total{reason}`,
    `ranking_elo_updates_total`, `ranking_consumer_lag` **paired with** a success counter. P8 will
    want `ranking_elo_updates_total` as a business metric; it is free here.

2.8 Tests: the Elo function against hand-computed two- and four-player cases; a zero-sum property
    (deltas over a game sum to 0 ± rounding); the two filters; **replay the same event and assert
    the rating does not move**; a `"Casual"`-instead-of-`"CASUAL"` case asserting it is skipped, so
    the enum-name gotcha has a test rather than a comment.

2.9 Chart overlay `gitops/apps/ranking/overlays/staging/values.yaml` — database env, `secretName:
    ranking-secrets`, `KAFKA_BROKERS`, `serviceMonitor: enabled`, `ClusterIP`. CI fragment upgraded
    to mirror `services/outbox-relay/.gitlab-ci.yml`: `deploy-staging` stops being
    `manual`/`allow_failure` and becomes a real gate.

**D2 — confirm in review.** `cardPointTotals` is stored on the history row but does **not** enter
the Elo formula — `finishingOrder` already carries the outcome, and letting the margin move the
rating is a design choice the architecture never made. It stays in `CONSUMER_REQUIRED` because
ranking genuinely reads it; the history row is where it lands.

**Done when** a `GameCompleted` fed into the consumer moves two ratings, a replay of it moves
nothing, and `GET /leaderboard` returns them in order.

---

## 3. `spectator` — the projection and its SSE (Node/TS)

3.1 `package.json` gains `kafkajs` and `ioredis`. Two Redis connections, decided per connection and
    not per service — that is the P4/F8 lesson in its exact form: **off**line queue disabled on any
    connection inside a retrying loop, left on for a one-shot.

3.2 `view.ts` — the projection, a pure function `(view, event) → view`. Fields, and only these:
    `roomId`, `roomType`, `status`, `maxPlayers`, `gameNumber`, `playerOrder`,
    `players[{id, cardCount, isConnected, calledUno, forfeited}]`, `topCard`, `color`, `direction`,
    `currentTurn`, `deckSize`, `finishingOrder`, `lastSequence`, `spectatorCount`. There is
    physically nowhere to put a hand, which is architecture §6's third privacy layer and the reason
    it is a layer rather than a check.

    **Two refinements against the field list this plan first carried**, both the same trap — a rule
    that belongs to another context being copied into this one, which is precisely the defect P5's
    review pass found:

    - **`turnDeadline` is not a spectator field.** No public event carries it. Deriving it from
      `GameStarted.turnTimeoutSeconds` plus the last event's `at` would put room-gameplay's deadline
      rule in a second place, free to drift. The *player's* board still shows it, because
      room-gameplay serves it there from the projection that owns it.
    - **`cardCount` starts `null`, not 7.** The deal size is nowhere in `GameStarted`'s public
      payload, and hardcoding it here would be the same second copy. Counts fill themselves in as
      players act — `CardPlayed` carries `playerCardCount`, `CardDrawn` `newCardCount`, `ForcedDraw`
      `newHandSize` — so a spectator who joins at the first move sees real numbers, and one who
      joins at the deal sees that they are not yet known. Honest beats plausible.

3.3 `consumer.ts` — consumer group `spectator-view` on **both** `room.public.events` and
    `room.lifecycle.events` (the delta: architecture §1 has it on the public topic alone, which
    would leave the view never learning the game ended). Per message: `ce-id` → `SADD
    spectator:room:{id}:seen {seq}`; a return of `0` means already applied, drop it. Then apply,
    write the hash, and **refresh the TTL on every write** — not on the tidy ending, which reclaims
    nothing (P4's abandoned-stream lesson).

3.4 The ACL check architecture §6 asks for, kept because defence in depth across a service boundary
    is worth one `if`: reject and count any event whose body carries `hand`, `cards`, `deckOrder`,
    `rngSeed` or `seed`. It should never fire — `publicPayload` ran before the row was written — so
    `spectator_private_field_rejections_total` firing at all is an alert, not a nuisance.

3.5 `sse.ts` — `GET /rooms/{id}/spectate`. **First frame is the snapshot** of the whole view, then
    incremental frames as the projection advances, frame `id` = the room's sequence number so
    `Last-Event-ID` works the way it does for players. `res.flushHeaders()` before anything (a
    subscriber with nothing to replay otherwise leaves the client's `fetch` hanging on a working
    connection), and the disconnect handler registered **before** the first await.

3.6 `spectatorCount` maintained on connect/disconnect through `spectator:room:{id}:subs`, which is
    what E4's session requirement buys — an identified spectator can be counted and evicted.

3.7 Metrics: `spectator_events_projected_total`, `spectator_events_deduped_total`,
    `spectator_active_streams` (gauge) **with** `spectator_streams_opened_total` beside it,
    `spectator_private_field_rejections_total`.

3.8 Tests: the projection as a pure function; **an interleaving property** — generate a room's event
    log, deliver it in orders consistent with per-topic ordering but arbitrary across topics, assert
    the final view is identical every time; the seen-set dropping a replay; the ACL check firing on
    a synthetic leaked field; SSE frame encoding.

**D3 — confirm in review.** Terminal state is **sticky**: once `status` is `COMPLETED`, a
later-arriving lower-seq public event still updates history-shaped fields but never reopens the room.
Without this, cross-topic interleaving can present a finished game as live for as long as the lag.

**Done when** two CLI processes play a game while a third watches, the watcher's view tracks the
board, and killing the spectator pod and restarting it rebuilds nothing it should not — the seen-set
and the TTL survive it.

---

## 4. `analytics-workers` — three projections (Python)

4.1 Same dependency treatment as group 2. Consumer group `analytics-projections` over **both**
    topics.

4.2 `schema.py` — `player_stats(player_id pk, games_played, games_won, games_abandoned,
    total_card_points, last_played_at)`; `room_games(room_id, game_number, room_type, is_abandoned,
    finishing_order jsonb, completed_at, primary key (room_id, game_number))`;
    `room_activity(room_id pk, ...)`; `overview(metric pk, value)` for the global counters; and
    `consumed_events`.

    **Refinement: `move_count` cannot live on `room_games`.** This plan first put it there, but the
    public events that would feed it — `CardPlayed`, `CardDrawn` — carry no `gameNumber`, so a move
    cannot be attributed to a game without re-deriving "which game was open at sequence N", which is
    room-gameplay's state and not analytics'. Per-**room** is the honest granularity the events
    actually support, so activity counters move to `room_activity` and `room_games` keeps only what
    `GameCompleted` states. `/stats/rooms/{id}` returns both.

4.3 `project.py` — the apply functions, pure where they can be. `GameCompleted` feeds player stats
    and `room_games`; the public topic's card events feed `move_count` and the overview's move
    counter; `RoomCreated`/`RoomExpired`/`RoomCompleted` feed room counters.

4.4 The same transaction rule as ranking: `consumed_events` insert and every projection write in one
    transaction, so at-least-once cannot double-count a move.

4.5 Metrics: `analytics_events_projected_total{topic}`, `analytics_projection_writes_total`,
    `analytics_consumer_lag` with its success counter.

4.6 Tests: each projection from a fixture log; replay-does-not-double-count; a game with an
    abandoned ending lands in `games_abandoned` and not `games_won`.

4.7 Chart overlay + CI fragment, as 2.9. No `NodePort` — it is a worker with a `/health` and a
    `/metrics`, which is what the `Service` in its chart is for.

**D4 — confirm in review.** The bracket store P7 needs is **not** created here, not even empty. A
table nobody writes is the dead-code shape the drill lessons name (`sweepIdempotencyKeys`: written,
tested, never called). P7 adds it when it has a writer.

**Done when** a played game shows up in all three projections and a replay of its events changes no
count.

---

## 5. `analytics-api` — read-only over those projections (Python)

5.1 `GET /stats/players/{id}`, `GET /stats/rooms/{id}`, `GET /stats/overview`, `GET /health`.
    Read-only — the same database user could be read-only and should be; the API never writes.

5.2 Keep the pure-router shape; add a thin query layer with `psycopg`. No ORM.

5.3 Tests against a seeded database in CI, and the router tested without sockets as today.

5.4 Chart overlay + CI fragment as 2.9.

**Done when** the three endpoints answer from data `analytics-workers` wrote, and `/health` stays
green while the database is down (delta §10.11 — a liveness probe wired to a dependency turns an
outage into a restart loop).

---

## 6. Gateway — four surfaces, one door

6.1 `config.ts` gains `spectatorUrl`, `rankingUrl`, `analyticsUrl`, defaulting to localhost as the
    others do.

6.2 `app.ts` route table, **all `auth: true`** (E4):
    - `GET /rooms/:id/spectate` → spectator, `stream: true`
    - `GET /players/:id/rating`, `GET /players/:id/rating-history` → ranking
    - `GET /leaderboard` → ranking
    - `GET /stats/players/:id`, `GET /stats/rooms/:id`, `GET /stats/overview` → analytics-api

6.3 Metric labels stay the bounded `label` strings the table already uses — the comment at
    `app.ts:15` is explicit that a raw URL would let anyone grow the cardinality by inventing paths.

6.4 The spectate route is a **second** streaming route. P4's SSE plumbing is player-shaped (it tails
    Redis directly); this one proxies an upstream SSE. Keep them separate rather than generalising
    one into both — the guards differ, and a shared abstraction here would be the "purpose changed
    after an earlier fix" shape the review convention says to look hardest at.

6.5 Tests: routing and auth for each new path; a 401 without a session on `spectate`; the streaming
    proxy passing frames through and closing cleanly when the client goes.

**Done when** every new surface answers through `:30080` with a session and 401s without one.

---

## 7. CLI — `spectate`, `rating`, `leaderboard`, `stats`

7.1 `spectate <roomId>` — connects to the SSE, renders the snapshot frame as a board, then narrates
    incremental frames. **Renders only from what the server sent** (P4's lesson: a client that
    applies events to its own board draws a lie). No hands anywhere in the rendering path, because
    there are none in the payload.

7.2 `rating [playerId]` (defaults to the session's own player), `leaderboard [--limit N]`,
    `stats [--player <id> | --room <id>]` defaulting to the overview.

7.3 Update the usage string at `cli.ts:60` — it is the command surface Client-Checkpoint.md
    promises, and a command that exists but is not listed does not exist.

7.4 Tests in the existing style, exercising the same functions the commands use.

**Done when** a third terminal running `spectate` follows a game two other terminals are playing.

---

## 8. Drill from empty — and P5's two open ACs (E8)

8.1 `kind delete cluster` → `TARGET_REVISION=feat/p6-read-models install.sh`. **Build explicitly**
    in the drill — never conditionally, the stale-`dist/` lesson has bitten twice.

8.2 Verify nine of ten `Synced/Healthy`, all digest-pinned, all four new targets scraped. Only
    `tournament` should be `ImagePullBackOff`, and that is its normal state.

8.3 Two processes play a full casual game; a third spectates. Then: `grep -c seed` over what the
    spectator received → **0**, and over both topics → **0**.

8.4 **AC-P5.8, the aggregate backstop.** Suspend the Argo root (`argocd app set <root>
    --sync-policy none`, or the equivalent patch on the app-of-apps), scale `timer-worker` to 0,
    confirm it *stays* at 0, let a turn deadline lapse, then send a command and assert the aggregate
    resolved the lapsed deadline itself. Restore the root afterwards and confirm it re-syncs. This is
    the one manoeuvre `ESTADO-FINAL` names after `kubectl scale` and child-app patching both lost to
    `selfHeal` inside 30 s.

8.5 **AC-P5.10, the live half of reconnect.** A client drops its stream and returns inside the 60 s
    window via the `PATCH` that delta §10.9 made reachable; assert `PlayerReconnected` in
    `room_events` and that no forfeit followed.

8.6 **Watch item, not an AC:** after the drill, compare `timerworker_sweeps_total` against
    `timerworker_sweep_failures_total` and record the worst observed tick lateness. The 2699 s
    outlier is unexplained; the counter exists so a recurrence is diagnosable. Record the numbers
    whatever they say.

8.7 Truncate between local runs and check for stray CLI processes with `ps -eo args | grep cli.js |
    grep -v grep` — `pgrep -f` matches the shell running it. Three false alarms in P4 came from here.

**Done when** every check above has a transcript in `validation.md`, including the two P5 ACs.

---

## 9. Docs — the drift, the markers, the closure

9.1 `CHANGELOG-design.md` **§11**, the P6 deltas. At minimum: spectator consuming both topics rather
    than the public one alone (§1's consumer table); the dedup being a set rather than persistence
    §5's `spectator:room:{id}:seq` high-water mark, with the two-topic reason; sticky terminal state;
    Elo scope and the `cardPointTotals` decision; no `ranking.events` topic; PostgreSQL rather than
    ClickHouse for analytics; and the Python services acquiring a dependency layer.

9.2 Roadmap: P6 → **SHIPPED**, P7 → **next**, and a "Handoff from P6" block written for whoever
    starts P7 — consumer group names, the interleaving rule, where the bracket store is *not*, and
    what the contract check now guards.

9.3 README coverage matrix: nine of ten wired.

9.4 `ESTADO-FINAL.md` for this spec.

**Done when** the docs commit carries `[skip ci]` and **contains no code** — P5 slipped a metric into
its closure commit and the branch's pinned image did not contain it.

---

## 10. Self-review pass

Question every decision as a PR reviewer would; remove overengineering; write the findings as a table
in this file, including a **"not changed, and why"** list. Both P4 and P5 found their worst defect
here, after the phase looked done. Look hardest at:

- **Code whose purpose changed after an earlier fix** — the standing rule.
- **The two SSE implementations** (6.4). If they have converged, one of them is wrong.
- **Every new gauge**: does a success counter sit beside it? This failure has now appeared three
  times.
- **Every new property test**: break the code deliberately and watch it go red. P5's deadline
  property probed 100 000 s out and proved nothing while passing.
- **Promises made in a README** — grep for a call site, not a definition.

**Done when** the table is written and the pass has either landed fixes or recorded why not.

---

## What this plan deliberately does *not* include

- No `ranking.events` topic, no `EloUpdated`, no ranking-side outbox.
- No bracket table, no tournament projections, no `TournamentCompleted` handling — not even an empty
  schema (D4).
- No Grafana dashboards, no alert rules, no ServiceMonitor consolidation. P8.
- No ClickHouse.
- No consumer scale-out, no partition-count changes, no leader election.
- No change to the player's own feed. P4's gateway tail stays exactly as it is.
- No shared library across services, in any language.
- No new topic, no new event type, no change to the envelope.

---

## 10. Self-review pass — findings (2026-08-13)

Run after the drill was green, which is where the standing convention says the value is. Both P4 and
P5 found their worst defect here; P6 found its worst *during* the drill instead, and this pass found
the same class of bug hiding in the two services the drill had not exercised the same way.

| # | Finding | Change |
|---|---|---|
| R1 | **`consumer.subscribe()` sat outside the retry loop in both Python consumers.** The spectator fix (delta §11.11) was applied where the drill found the bug and nowhere else — and these run in a `daemon` thread, so an exception escaping `run` ends it *silently* while the HTTP server keeps answering `/health` with 200. Identical to the thirteen-minute failure, one language over. Textbook "code whose purpose changed after an earlier fix". | Subscribe moved inside the loop and retried with backoff; `ranking_consumer_starts_total` and `analytics_consumer_starts_total` added beside the projection counters. Bite-checked with a fake broker that refuses twice: the loop retries and survives, and the test is now permanent (`tests/test_consumer_loop.py` in both services). |
| R2 | **`Broker.countFor` was written, exported and never called** — the `sweepIdempotencyKeys` shape the drill lessons name. The spectator count that reaches the view comes from Redis (`store.spectatorCount`), which is the correct source, so this was a second answer to a question already answered. | Deleted. |
| R3 | **`analytics-api/server.py` still opened with "Entrypoint: connect, then serve the reads"** after the cold-start fix moved the connection into `Reader`. A docstring that describes the previous design is worse than none. | Corrected. |
| R4 | **The interleaving bite test named the wrong suite** (see `validation.md`). | Corrected, and an end-to-end test added so the claim and the code agree. |
| R5 | **`plan.md` claimed `seal.sh` would produce no diff on unchanged secrets.** `kubeseal` draws a fresh session key per run, so it always rewrites the ciphertext. | Corrected in group 1, with the "restore the unchanged ones" instruction that follows from it. |

### Checked and NOT changed, and why

- **The two SSE implementations have not converged** — 277 lines in the gateway's player tail versus
  58 in the spectator's. They answer different questions (gap detection and resync against a log
  versus whole-view frames that need no reconstruction), and the review's own instruction was to
  look hardest at exactly this. They are still different, so they stay separate.
- **`ranking` and `analytics-workers` still exit when they cannot migrate**, and still show 5–6
  restarts on a cold start. That is identity's posture for a service that owns its schema, it is
  deliberate (D1), and it is now written down in delta §11.12 so the restart count is not read as a
  defect next time.
- **The Elo formula was not made configurable.** `K=32` and an initial 1000 are constants, not env
  vars. Nothing in the phase needs to turn them, and a lever nobody pulls is a lever to keep
  correct for free.
- **`analytics-api` still holds its own copy of `queries.py`** rather than importing the writer's.
  kaniko builds each service from its own directory; the coupling is proved by a test instead, which
  is the same trade the P5 handoff made for the Go workers.
- **The spectator's fan-out is still in-process.** A Redis pub/sub hop would make a second replica
  work, but nothing asks for a second replica and the note in `broker.ts` is what a future reader
  needs.
