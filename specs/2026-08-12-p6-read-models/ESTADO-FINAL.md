# ESTADO FINAL — P6: Read models (ranking, spectator, analytics)

> Drilled 2026-08-12 → 13 on `feat/p6-read-models`, **twice** — the second run because every
> defect the first found was a cold-start defect, and their fixes had only been seen on a warm
> cluster. Fourteen acceptance criteria, **all green**, two
> of them P5's carried in deliberately and closed live for the first time. **The events are read**:
> a finished casual game moves two ratings, a stranger with a session watches a live game and sees
> no hand, and three projections answer for every room and player. **Nine of ten deployables are
> real** — only `tournament` is still canned.

## What shipped

- **`ranking/`** — the Elo consumer (Python). Consumer group `ranking-elo` on
  `room.lifecycle.events`, the §4.5 scope rules at the entry point (casual only, non-abandoned
  only, deduplicated), a pure multiplayer Elo, and `rating` / `rating-history` / `leaderboard`.
  Sixth fully-wired service.
- **`spectator/`** — the privacy-filtered projection (Node/TS). Consumer group `spectator-view` over
  **both** room topics into `spectator:room:{id}` in Redis, plus its own SSE whose first frame is
  the whole board. Seventh.
- **`analytics-workers/`** — three CQRS read models (Python) from both topics: per-player stats,
  per-room games, and the global counters P8 will graph. Eighth.
- **`analytics-api/`** — the read side over that schema, and nothing else. Ninth.
- **The platform seam** — `ranking` and `analytics` Postgres login roles with sealed passwords,
  their databases moved off the bootstrap `app` role, and `spectator` added to the contract check's
  `CONSUMER_REQUIRED` with its CI fragment gated by that check.
- **Gateway + CLI** — seven new routes behind the one door, all session-required, including a
  *second* streaming route that is deliberately not the first one; and `spectate`, `rating`,
  `leaderboard`, `stats`.

## Evidence

`validation.md` carries the transcripts. The short version:

| AC | Verdict |
|---|---|
| AC-P6.1 a finished game moves ratings | Leaderboard after 3 games: **1017 / 983**, with a `rating_changes` row per player per game. |
| AC-P6.2 tournament and abandoned games score nothing | Both filters at the consumer entry point, each with its own skip reason; `"Casual"` (the catalog spelling) is skipped too, and has a test. |
| AC-P6.3 a replay changes nothing | **216 events redelivered, 216 deduped, 0 errors, every projection count byte-identical.** Proved live by resetting the consumer group to `earliest`. |
| AC-P6.4 a third session can watch | 31 boards from WAITING through COMPLETED, real card counts, finishing order; 68 raw frames through the gateway for the same game. |
| AC-P6.5 nothing private reaches a spectator | `grep -c seed` → **0** on both topics, on the SSE capture and on the CLI output. The ACL check fired **1** on a deliberately leaked event and refused to project it. |
| AC-P6.6 interleaving does not corrupt a projection | Property over 200 generated cross-topic orders (spectator) and 20 (analytics); a late `GameCompleted` never reopens a room. |
| AC-P6.7 analytics reconciles | Sampled room: **12 cards played, 1 drawn, 33 events** — identical to `room_events`. |
| AC-P6.8 one door, session required | Every new surface 401s without a session and proxies with one; `session_superseded` propagates to the read models. |
| AC-P6.9 the CLI does all four | `spectate`, `rating`, `leaderboard`, `stats` — no part of P6 needs `curl` to demonstrate. |
| AC-P6.10 from empty | Nine of ten `Synced/Healthy`, digest-pinned, all four new targets scraped; `tournament` alone in `ImagePullBackOff`. |
| AC-P6.11 the contract check | Green with three consumers; red on a hand-edit in both directions. |
| AC-P6.12 **(P5 carry-over)** the aggregate backstop | Argo root suspended, `timer-worker` held at 0 and **verified still 0 after 75 s**. Deadline lapsed 12:48:43, unresolved for >2 min, and the first command at 12:51:07 made the aggregate emit `TurnTimedOut` → `CardDrawn` → `TurnPassed` itself. **Closed at last.** |
| AC-P6.13 **(P5 carry-over)** live reconnect | Superseded session → `PlayerDisconnected`; `PATCH` inside the window → `PlayerReconnected`, **0 forfeits**. |
| AC-P6.14 pipelines | Final run 17/17 green; every changed digest verified to have moved. |

## What the drill caught that nothing else did

**`ce-type` is a reverse-DNS URI, not the event name.** The relay writes
`com.unoarena.room.GameCompleted.v1`; the classifier compared it to `"GameCompleted"` and skipped
**everything**. Ranking read four lifecycle events and scored none, with no error anywhere. The
body's `type` is the catalog name and the one the contract schema pins — classify on that. Found in
a single look only because `ranking_events_skipped_total{reason="not_game_completed"}` existed.

**A consumer that gives up is worse than one that crashes.** kafkajs exhausts its own retries during
a cold start — Kafka is still electing while the pod is already up — so `run()` rejected and a
`.catch` that only logged left spectator running **thirteen minutes, `Healthy`, `/health` 200, with
no consumer at all**. P5's "back off, never crash" has a second half: keep trying. The mirror image
appeared in Python the same day — a mislabelled lag gauge threw at the top of every loop iteration
*before* `poll()` was reached, stopping the projections for **326 consecutive loops** behind a
`Healthy` pod. Observability must never be able to stop the thing it observes, so the lag read now
sits in its own `try`.

**An eager `psycopg.connect()` in `main()` is a crash loop on an empty cluster.** `analytics-api`
restarted five times before Postgres was up, while its own docstring promised a 503. Third
appearance of "a service that reads a schema it does not own must tolerate its absence". The two
postures are now deliberate and different — see delta §11.12.

**Do not log the kubelet's probes.** They arrive every few seconds and drowned the single
`consumer-stopped` line that explained the whole failure; diagnosis needed
`grep -v '"action":"GET /health"'`.

**`kubectl apply -f gitops/root-app.yaml` silently repoints a drill cluster to `main`** (the file's
default; `install.sh` rewrites it with `sed`). Self-inflicted while restoring Argo after the
suspension. The symptom is not "wrong revision": the placeholder charts fall back to `tag: latest`,
so every service that had `digest: ""` on `main` went `ImagePullBackOff` with a **403 Forbidden**,
which reads exactly like broken registry credentials.

## The re-drill (2026-08-13)

Three of the five defects below only exist during a cold start: a connection opened too eagerly, a
consumer that gives up while the broker is still electing, a gauge that throws before the first
poll. All three were fixed and then verified by a rolling deploy — onto a **warm** cluster, which is
exactly the condition under which none of them can appear. A cold-start fix verified warm is not
verified, so the cluster was deleted and rebuilt from empty a second time.

| Check | First drill | Re-drill |
|---|---|---|
| `analytics-api` restarts | **5** | **0** |
| `spectator` consumer | gave up; 13 min Healthy with no consumer, and no `spectator-view` group | failed **5** times against a still-electing broker, then **retried into a running state**; all four groups present |
| `analytics-workers` | **326** consecutive errors, projections stopped | **0** errors |
| `ranking` scoring | 4 events read, **0** scored | leaderboard **1016 / 984** |

Everything else on the fresh cluster, first attempt, with no intervention: 9/10 `Synced/Healthy`,
9 Prometheus targets up, outbox **61 rows / 0 unpublished**, analytics reconciling with `room_events`
exactly (**25 played, 6 drawn, 61 events**), `grep -c seed` **0** everywhere, and a third session
watching the room to completion. **The re-drill produced no new defect and no new commit** — which
is the result it was run to obtain, and the first drill in this phase that needed no fix.

## Decisions worth carrying forward

- **The dedup is a set, because the log is split across two topics.** Per-room ordering is a
  per-*topic* guarantee; a lifecycle event can overtake an earlier public one from the same room. A
  high-water mark — which is what persistence-layer §5 specifies — would drop the earlier event
  silently and for ever. This is the single most load-bearing correction in the phase.
- **Terminal state is sticky; board state is not.** A late event may fill in a final card count
  (history arriving late) but must never hand a finished room a `currentTurn`.
- **Unknown beats plausible.** The spectator shows `?` for a card count until an event reveals it,
  and carries no turn deadline at all, because both would be a second copy of a rule room-gameplay
  owns — the exact defect P5's review pass found.
- **The CQRS split is held together by a test, not a shared module.** kaniko builds each service
  from its own directory, so `analytics-api`'s suite imports the *writer's* `schema.py` off the
  checkout: a column renamed on the write side turns the read side red.
- **Position scores; margin is recorded.** `cardPointTotals` lands on the history row and never
  moves a rating, because the architecture never said it should.

## Known gaps, deliberate

- **One replica per consumer.** No partition scale-out. The spectator's fan-out is in-process, so a
  second replica would serve spectators the partitions it does not consume — the Redis pub/sub hop
  that fixes it is described in `broker.ts` rather than built.
- **No `ranking.events` topic and no `EloUpdated`** (E5, delta §11.8).
- **No bracket store, not even an empty one** (D4). P7 adds it with its writer.
- **A consumer that commits an offset while skipping loses those events for good.** Ranking's first
  four lifecycle events were consumed under the `ce-type` bug and their offsets committed; the fix
  could not retroactively score that game, and recovering it would need a group reset. Correct
  at-least-once behaviour, and worth knowing before the exam.
- **`tournament` remains canned** — `digest: ""`, `ImagePullBackOff`, its normal state.

## Next

**P7 — tournaments.** The roadmap carries a full "Handoff from P6" block: the `ce-type` trap, the
two-topic interleaving rule, the three consumer-group names, where the bracket store is *not*, the
consumer-restart requirement, the two database postures, and the `deploy-staging` `needs:` gotcha
that pins an empty digest while going green. The casual gate stayed open throughout — the same
two-process game plays, finishes, and now also gets scored, watched and counted.
