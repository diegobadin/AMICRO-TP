# Validation — P6: Read models (ranking, spectator, analytics)

> Definition of done for the phase. Every box is binary and checkable on the drill cluster or in CI;
> transcripts land under each section as the work proceeds. Two of the criteria (AC-P6.12 and
> AC-P6.13) are P5's, carried in deliberately per decision E8.

## Acceptance criteria

| AC | Statement |
|---|---|
| AC-P6.1 | A finished casual game moves the ratings of everyone in `finishingOrder`, and the leaderboard shows them in order. |
| AC-P6.2 | A tournament game and an abandoned game move **no** rating, and each is counted under its own skip reason. |
| AC-P6.3 | Replaying an event a consumer has already seen changes nothing — no rating moves, no counter double-counts, no projection field changes. |
| AC-P6.4 | A third session can watch a room in progress and the view tracks the board. |
| AC-P6.5 | Nothing private reaches a spectator: `grep -c seed` over the spectator payload stream and over both Kafka topics returns **0**, and the view has no field that could hold a hand. |
| AC-P6.6 | Cross-topic interleaving does not corrupt the projection: the same room's log delivered in different orders produces the same final view. |
| AC-P6.7 | The three analytics projections answer through `analytics-api`, and the numbers reconcile with `room_events`. |
| AC-P6.8 | Every new surface is reachable **only** through the gateway, and 401s without a session. |
| AC-P6.9 | The CLI can do all four: `spectate`, `rating`, `leaderboard`, `stats`. |
| AC-P6.10 | From an empty cluster: nine of ten services `Synced/Healthy` and digest-pinned; `tournament` alone in `ImagePullBackOff`. |
| AC-P6.11 | The contract check is green with three consumers and goes red on a hand-edit in both directions. |
| AC-P6.12 | *(P5 carry-over)* With `timer-worker` genuinely held at 0 replicas, a lapsed deadline is still resolved by the aggregate when the next command arrives. |
| AC-P6.13 | *(P5 carry-over)* A client that drops and returns inside the 60 s window reconnects: `PlayerReconnected` in `room_events`, no forfeit. |
| AC-P6.14 | All pipelines green, and every changed digest verified to have actually moved. |

---

## Local — services

### ranking
- [ ] `pytest` green; `ruff check .` clean; `mypy` strict clean.
- [ ] Elo: hand-computed two-player and four-player cases match.
- [ ] Elo: deltas over one game sum to zero (± integer rounding), asserted as a property.
- [ ] A `GameCompleted` with `roomType: "TOURNAMENT"` is skipped and counted.
- [ ] A `GameCompleted` with `isAbandoned: true` is skipped and counted.
- [ ] A `GameCompleted` with `roomType: "Casual"` (the catalog's spelling, not the wire's) is
      **skipped** — the enum-name gotcha has a test, not a comment.
- [ ] Replaying one `GameCompleted` twice moves the rating exactly once.
- [ ] `/health` answers 200 with the database unreachable.

### spectator
- [ ] `vitest` green; `eslint` clean; `tsc --noEmit` clean.
- [ ] The projection is a pure function and is tested as one.
- [ ] Interleaving property: a room's log delivered in every order consistent with per-topic
      ordering yields one identical final view.
- [ ] A duplicate sequence number is dropped by the seen-set (`SADD` → 0).
- [ ] The ACL check rejects a synthetic event carrying `hand` / `deckOrder` / `seed` and increments
      `spectator_private_field_rejections_total`.
- [ ] Terminal state is sticky: a lower-seq public event arriving after `GameCompleted` does not
      return the room to `IN_PROGRESS`.
- [ ] SSE: headers flushed before the first frame; disconnect handler registered before the await.

### analytics-workers / analytics-api
- [ ] `pytest`, `ruff`, `mypy` green on both.
- [ ] Each of the three projections built from a fixture log matches a hand-computed expectation.
- [ ] Replaying the fixture log changes no count.
- [ ] An abandoned game lands in `games_abandoned` and not in `games_won`.
- [ ] `analytics-api` `/health` answers 200 with the database unreachable.

### gateway / CLI
- [ ] `vitest` green on both; new routes covered for routing **and** auth.
- [ ] Each new path 401s without a session and proxies with one.
- [ ] `cli.ts` usage string lists all four new commands.

---

## Cluster — the drill from empty

- [ ] `kind delete cluster` → `TARGET_REVISION=feat/p6-read-models install.sh` from cold.
- [ ] Client CLI **built explicitly** in the drill script, not conditionally on `dist/` existing.
- [ ] Nine of ten apps `Synced/Healthy`; only `tournament` `ImagePullBackOff`.
- [ ] All four new services digest-pinned to images built from this branch (digest verified to have
      moved, per service).
- [ ] All four `/metrics` endpoints scraped by Prometheus.
- [ ] Both new Postgres roles own their databases; no service is still connecting as `app`.
- [ ] Cold-start ordering: a consumer that starts before its own migration or before Kafka is ready
      backs off and does not crash-loop — **0 restarts** after convergence.
- [ ] Two processes play a full casual game; a third `spectate`s it start to finish.
- [ ] `grep -c seed` over the spectator's received payloads → **0**.
- [ ] `grep -c seed` over both Kafka topics → **0**.
- [ ] Ratings after the game reconcile with the game's `finishingOrder`.
- [ ] The three `/stats/*` endpoints reconcile with `room_events` for the same room.
- [ ] Every new counter moved; every new gauge has a companion counter that also moved.
- [ ] Consumer lag returns to ~0 after the game.

### P5 carry-overs (E8)
- [ ] **AC-P6.12** — Argo root suspended, `timer-worker` scaled to 0 and **confirmed still 0** after
      60 s (the check the two failed P5 attempts lacked), a turn deadline allowed to lapse, then a
      command sent: the aggregate resolves the lapsed deadline itself. Root restored and re-synced.
- [ ] **AC-P6.13** — a client drops its stream and returns inside 60 s via `PATCH`;
      `PlayerReconnected` present in `room_events`, no `PlayerForfeited` follows.
- [ ] **Watch item, recorded not judged** — `timerworker_sweeps_total` vs
      `timerworker_sweep_failures_total` after the drill, and the worst tick lateness observed.

### Drill hygiene
- [ ] `truncate room_events, outbox, rooms, idempotency_keys, consumed_events;` between local runs.
- [ ] No stray clients: `ps -eo args | grep cli.js | grep -v grep` is empty before starting.
- [ ] Harness kills its children on `SIGINT`/`SIGTERM`.

---

## CI

- [ ] Each of the four services pushed **on its own** so change detection runs its jobs alone.
- [ ] First push of the branch used `git push -o ci.skip`.
- [ ] `git pull --rebase` before every local commit (CI pins digests back to the branch).
- [ ] All four `deploy-staging` jobs are real gates, not `manual` + `allow_failure`.
- [ ] Contract check green with `ranking`, `analytics-workers`, `spectator` in `CONSUMER_REQUIRED`.
- [ ] Closure pipeline on `main` triggered by hand
      (`POST /api/v4/projects/83816735/pipeline?ref=main`) and green — the closure commit carries
      `[skip ci]`, so the push alone produces a skipped pipeline and the AC would have no run behind
      it. Read a red run before believing it: the base-image pull is a public-registry call.
- [ ] No `grep | awk | head -1` under `set -o pipefail` anywhere new — `head` closing the pipe kills
      `grep` with SIGPIPE and the line exits 141, once every few months.

---

## Bite tests — does the harness actually bite?

Each of these breaks something deliberately and must turn something red. A green run here means the
test is decoration.

- [ ] **Dedup.** Delete the `consumed_events` insert from ranking's transaction → the replay test
      goes red (a rating moves twice).
- [ ] **Enum name.** Change the filter to `"Casual"` → the skip test goes red.
- [ ] **Interleaving.** Replace spectator's seen-set with a `seq` high-water mark → the interleaving
      property goes red. *This one is the point of E3's refinement; if it stays green the property is
      not probing the cross-topic case.*
- [ ] **Sticky terminal.** Remove the stickiness rule → the out-of-order `GameCompleted` test goes
      red.
- [ ] **Privacy.** Add `seed` to the spectator view's field list → the ACL test **and** the
      no-private-field test both go red.
- [ ] **Contract.** Hand-edit `samples/game-completed.json` → the check goes red. Remove a field from
      the schema's `required` → it goes red for the other reason. Both directions, as in P5.
- [ ] **Elo.** Flip the sign on the delta → the hand-computed cases go red (not just the zero-sum
      property, which a sign flip satisfies).
- [ ] **Pipeline fail-fast.** A deliberately failing test on one of the four blocks its own `build`
      and nothing else's.

**Method note.** Bite-checking a fix that is already committed needs the pre-fix file — `git stash
push <file>` reverts to `HEAD`, which already contains the fix, so the test passes and looks like it
never bit. Use `git show <fixcommit>^:<path> > <path>`, run, restore from a copy, and use absolute
paths in the restore step (the Bash tool's cwd persists between calls).

---

## Out of scope — must NOT appear in this branch

- [ ] No `ranking.events` topic, no `EloUpdated` event, no ranking-side outbox.
- [ ] No bracket table, no tournament projection, no `TournamentCompleted` handler — not even an
      empty schema.
- [ ] No Grafana dashboard, no alert rule, no ServiceMonitor consolidation.
- [ ] No ClickHouse.
- [ ] No second replica of any consumer, no leader election, no partition-count change.
- [ ] No change to the player feed path (`services/gateway/src/sse.ts` tail of `room:{id}:events`).
- [ ] No shared library across services in any language.
- [ ] No new event type, no new topic, no change to the envelope.
- [ ] No code in the `[skip ci]` docs/closure commit.

---

## Mission check

Two questions. If both are yes, the phase moved the mission forward:

1. **Is the decomposition real?** Four services, two languages, three stores, four independent
   consumer groups reading one log and answering four different questions — and none of them able to
   reach another's state. Change one, and only its pipeline runs.
2. **Is the privacy boundary demonstrable rather than asserted?** A stranger with a session watches a
   live game through the gateway, and `grep seed` over everything they received returns nothing —
   because the filter ran in the same transaction that wrote the event, three services upstream.
