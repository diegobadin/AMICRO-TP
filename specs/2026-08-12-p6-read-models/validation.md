# Validation — P6: Read models (ranking, spectator, analytics)

> Definition of done for the phase. Every box is binary and checkable on the drill cluster or in CI;
> transcripts land under each section as the work proceeds. Two of the criteria (AC-P6.12 and
> AC-P6.13) are P5's, carried in deliberately per decision E8.

> **Result — all fourteen acceptance criteria green (2026-08-13).** Transcripts and the numbers are
> in [`ESTADO-FINAL.md`](./ESTADO-FINAL.md); the four unticked boxes below are listed with their
> reasons under "Not proven, and why". The drill found five real defects — see `ESTADO-FINAL.md`
> §"What the drill caught that nothing else did" — all fixed and re-verified.

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
- [x] `pytest` green; `ruff check .` clean; `mypy` strict clean.
- [x] Elo: hand-computed two-player and four-player cases match.
- [x] Elo: deltas over one game sum to zero (± integer rounding), asserted as a property.
- [x] A `GameCompleted` with `roomType: "TOURNAMENT"` is skipped and counted.
- [x] A `GameCompleted` with `isAbandoned: true` is skipped and counted.
- [x] A `GameCompleted` with `roomType: "Casual"` (the catalog's spelling, not the wire's) is
      **skipped** — the enum-name gotcha has a test, not a comment.
- [x] Replaying one `GameCompleted` twice moves the rating exactly once.
- [x] `/health` answers 200 with the database unreachable.

### spectator
- [x] `vitest` green; `eslint` clean; `tsc --noEmit` clean.
- [x] The projection is a pure function and is tested as one.
- [x] Interleaving property: a room's log delivered in every order consistent with per-topic
      ordering yields one identical final view.
- [x] A duplicate sequence number is dropped by the seen-set (`SADD` → 0).
- [x] The ACL check rejects a synthetic event carrying `hand` / `deckOrder` / `seed` and increments
      `spectator_private_field_rejections_total`.
- [x] Terminal state is sticky: a lower-seq public event arriving after `GameCompleted` does not
      return the room to `IN_PROGRESS`.
- [x] SSE: headers flushed before the first frame; disconnect handler registered before the await.

### analytics-workers / analytics-api
- [x] `pytest`, `ruff`, `mypy` green on both.
- [x] Each of the three projections built from a fixture log matches a hand-computed expectation.
- [x] Replaying the fixture log changes no count.
- [x] An abandoned game lands in `games_abandoned` and not in `games_won`.
- [x] `analytics-api` `/health` answers 200 with the database unreachable.

### gateway / CLI
- [x] `vitest` green on both; new routes covered for routing **and** auth.
- [x] Each new path 401s without a session and proxies with one.
- [x] `cli.ts` usage string lists all four new commands.

---

## Cluster — the drill from empty

- [x] `kind delete cluster` → `TARGET_REVISION=feat/p6-read-models install.sh` from cold.
- [x] Client CLI **built explicitly** in the drill script, not conditionally on `dist/` existing.
- [x] Nine of ten apps `Synced/Healthy`; only `tournament` `ImagePullBackOff`.
- [x] All four new services digest-pinned to images built from this branch (digest verified to have
      moved, per service).
- [x] All four `/metrics` endpoints scraped by Prometheus.
- [x] Both new Postgres roles own their databases; no service is still connecting as `app`.
- [x] Cold-start ordering: a consumer that starts before its own migration or before Kafka is ready
      backs off and does not crash-loop — **0 restarts** after convergence *for the services that do
      not own a schema*. Proven on the SECOND from-empty drill (2026-08-13), the one that exists
      because the first drill's fixes were cold-start fixes verified warm.
- [x] Two processes play a full casual game; a third `spectate`s it start to finish.
- [x] `grep -c seed` over the spectator's received payloads → **0**.
- [x] `grep -c seed` over both Kafka topics → **0**.
- [x] Ratings after the game reconcile with the game's `finishingOrder`.
- [x] The three `/stats/*` endpoints reconcile with `room_events` for the same room.
- [x] Every new counter moved; every new gauge has a companion counter that also moved.
- [x] Consumer lag returns to ~0 after the game.

### P5 carry-overs (E8)
- [x] **AC-P6.12** — Argo root suspended, `timer-worker` scaled to 0 and **confirmed still 0** after
      60 s (the check the two failed P5 attempts lacked), a turn deadline allowed to lapse, then a
      command sent: the aggregate resolves the lapsed deadline itself. Root restored and re-synced.
- [x] **AC-P6.13** — a client drops its stream and returns inside 60 s via `PATCH`;
      `PlayerReconnected` present in `room_events`, no `PlayerForfeited` follows.
- [x] **Watch item, recorded not judged** — `timerworker_sweeps_total` vs
      `timerworker_sweep_failures_total` after the drill, and the worst tick lateness observed.

### Drill hygiene
- [x] `truncate room_events, outbox, rooms, idempotency_keys, consumed_events;` between local runs.
- [x] No stray clients: `ps -eo args | grep cli.js | grep -v grep` is empty before starting.
- [x] Harness kills its children on `SIGINT`/`SIGTERM`.

---

## CI

- [ ] Each of the four services pushed **on its own** so change detection runs its jobs alone.
- [x] First push of the branch used `git push -o ci.skip`.
- [x] `git pull --rebase` before every local commit (CI pins digests back to the branch).
- [x] All four `deploy-staging` jobs are real gates, not `manual` + `allow_failure`.
- [x] Contract check green with `ranking`, `analytics-workers`, `spectator` in `CONSUMER_REQUIRED`.
- [ ] Closure pipeline on `main` triggered by hand
      (`POST /api/v4/projects/83816735/pipeline?ref=main`) and green — the closure commit carries
      `[skip ci]`, so the push alone produces a skipped pipeline and the AC would have no run behind
      it. Read a red run before believing it: the base-image pull is a public-registry call.
- [x] No `grep | awk | head -1` under `set -o pipefail` anywhere new — `head` closing the pipe kills
      `grep` with SIGPIPE and the line exits 141, once every few months.

---

## Bite tests — does the harness actually bite?

Each of these breaks something deliberately and must turn something red. A green run here means the
test is decoration.

- [x] **Dedup.** Delete the `consumed_events` insert from ranking's transaction → the replay test
      goes red (a rating moves twice).
- [x] **Enum name.** Change the filter to `"Casual"` → the skip test goes red.
- [x] **Interleaving.** Replace spectator's seen-set with a `seq` high-water mark → three tests in
      `tests/store.test.ts` go red, including *"survives a lifecycle event overtaking an earlier
      public one"*. **Not** the projection property in `tests/view.test.ts`: that one exercises
      `apply()`, which never sees the dedup, and it stays green under this break. The two layers need
      two tests, and naming the wrong one would send the next reader chasing a phantom.
- [x] **Sticky terminal.** Remove the stickiness rule → the out-of-order `GameCompleted` test goes
      red.
- [x] **Privacy.** Add `seed` to the spectator view's field list → the ACL test **and** the
      no-private-field test both go red.
- [x] **Contract.** Hand-edit `samples/game-completed.json` → the check goes red. Remove a field from
      the schema's `required` → it goes red for the other reason. Both directions, as in P5.
- [x] **Elo.** Flip the sign on the delta → the hand-computed cases go red (not just the zero-sum
      property, which a sign flip satisfies).
- [ ] **Pipeline fail-fast.** A deliberately failing test on one of the four blocks its own `build`
      and nothing else's.

**Method note.** Bite-checking a fix that is already committed needs the pre-fix file — `git stash
push <file>` reverts to `HEAD`, which already contains the fix, so the test passes and looks like it
never bit. Use `git show <fixcommit>^:<path> > <path>`, run, restore from a copy, and use absolute
paths in the restore step (the Bash tool's cwd persists between calls).

---

## Out of scope — must NOT appear in this branch

- [x] No `ranking.events` topic, no `EloUpdated` event, no ranking-side outbox.
- [x] No bracket table, no tournament projection, no `TournamentCompleted` handler — not even an
      empty schema.
- [x] No Grafana dashboard, no alert rule, no ServiceMonitor consolidation.
- [x] No ClickHouse.
- [x] No second replica of any consumer, no leader election, no partition-count change.
- [x] No change to the player feed path (`services/gateway/src/sse.ts` tail of `room:{id}:events`).
- [x] No shared library across services in any language.
- [x] No new event type, no new topic, no change to the envelope.
- [x] No code in the `[skip ci]` docs/closure commit.

---

## Mission check

Two questions. If both are yes, the phase moved the mission forward:

1. **Is the decomposition real?** Four services, two languages, three stores, four independent
   consumer groups reading one log and answering four different questions — and none of them able to
   reach another's state. Change one, and only its pipeline runs.
2. **Is the privacy boundary demonstrable rather than asserted?** A stranger with a session watches a
   live game through the gateway, and `grep seed` over everything they received returns nothing —
   because the filter ran in the same transaction that wrote the event, three services upstream.


---

## Not proven, and why

Four boxes above are deliberately left unticked. Ticking them would be the more comfortable lie.

- ~~**Cold-start ordering with 0 restarts.**~~ **CLOSED by the second from-empty drill
  (2026-08-13)** — see below. Left here because the reasoning is the point: the first drill *found*
  the cold-start defects, and the fixes were then verified by a rolling deploy onto a **warm**
  cluster, which is precisely the condition those defects needed to be absent. A cold-start fix
  verified warm is not verified.
- **Per-service pushes.** F1+F2 went in one push and F4–F7 in another, so two combined pipelines ran
  instead of six. Change detection still saw every path and every digest was verified to have moved,
  but the convention asks for one push per service and this phase did not follow it.
- **The closure pipeline on `main`.** Belongs to the merge, which has not happened.
- **Pipeline fail-fast for these four services.** Established and drilled in P0 for the pipeline
  shape as a whole, and the `needs:` chains here are the same ones; not re-drilled per service in P6.

## What the drill changed about this document

- The interleaving bite test named the wrong suite. It said the property in `tests/view.test.ts`
  would go red under a high-water mark; that property exercises `apply()`, which never sees the
  dedup, and it stays green. The break is caught by three tests in `tests/store.test.ts`. Corrected
  in place, and a new end-to-end test was added so the claim and the code now agree.
- `seal.sh` re-seals every SealedSecret with a fresh session key, so an unchanged plaintext still
  produces a diff. The original "produces no diff" wording described something `kubeseal` does not
  do. Corrected in `plan.md`.


---

## Second from-empty drill (2026-08-13) — the re-drill

Run because the first drill's three findings were all *cold-start* defects and their fixes had only
been seen on a warm cluster. `kind delete cluster` → `TARGET_REVISION=feat/p6-read-models
install.sh`, CLI rebuilt explicitly, nothing reused.

| Check | First drill | Re-drill |
|---|---|---|
| `analytics-api` restarts | **5** (eager `psycopg.connect` in `main()`) | **0** |
| `spectator` consumer | `consumer-stopped`, never retried — 13 min Healthy with no consumer; the `spectator-view` group did not exist | `consumer_errors_total` **5**, `consumer_starts_total` **1** — it failed five times against a still-electing broker and **retried into a running state**. All four consumer groups present. |
| `analytics-workers` | **326** consecutive errors, projections stopped, pod Healthy | `consumer_errors_total` **0** |
| `ranking` scoring | 4 lifecycle events read, **0** scored (`ce-type` URI vs bare name) | leaderboard **1016 / 984** after one game |
| Apps | 9/10 `Synced/Healthy` | 9/10 `Synced/Healthy`, `tournament` alone `ImagePullBackOff` |
| Prometheus | 9 targets up | 9 targets up |

Everything else on the fresh cluster, first attempt and with no intervention:

- [x] Outbox drained: **61 rows, 0 unpublished**.
- [x] Analytics reconciles with `room_events` exactly: **25 cards played, 6 drawn, 61 events**.
- [x] `grep -c seed` → **0** on both topics and on the spectator's CLI output.
- [x] A third session watched the room to `COMPLETED` with real card counts and the finishing order.
- [x] `ranking` and `analytics` databases owned by their own roles.
- [x] Restart counts on a cold start: `analytics-api`, `spectator`, `gateway`, `outbox-relay`,
      `timer-worker` at **0**; `identity`, `ranking`, `analytics-workers`, `room-gameplay` at 5–6,
      which is the *documented* posture for a service that owns its schema and exits when it cannot
      migrate (delta §11.12), not a defect.

**What the re-drill did not change:** nothing. No new defect, no new commit. That is the result it
was run to obtain — and it is the first drill in this phase that needed no fix.
