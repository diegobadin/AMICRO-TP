# ESTADO FINAL — P5: Async Spine (outbox relay + timer worker)

> Drilled 2026-08-11 → 12 on `feat/p5-async-spine`. Eleven of thirteen acceptance criteria green,
> one covered by suite instead of cluster with the reason recorded, one half-open and named. **The
> outbox drains** — `published_at IS NULL` reached 0 for the first time since P3 wrote the first row
> — and **time passes on its own**: a game where one player walks away now ends by itself, and a room
> nobody joined closes on the clock instead of waiting forever for a player who is never coming.

## What shipped

- **`outbox-relay/`** — the transactional outbox's second half (Go). Polls unpublished rows in `id`
  order, publishes each to the topic the row itself names, and marks it only after the broker acks.
  CloudEvents in binary mode: `ce-id` = `{roomId}:{sequenceNumber}`, key = `roomId`, body =
  `publicPayload(event)` with the room and sequence merged in. Fourth fully-wired service.
- **`timer-worker/`** — the clock the aggregate never had (Go). Polls `rooms.next_deadline <= now()`
  and POSTs `/internal/rooms/{id}/tick` with both trust headers. It holds no game state and asserts
  nothing: a tick that is late, duplicated or unnecessary is an empty no-op. Fifth fully-wired service.
- **The seam in room-gameplay** — `next_deadline` on the `rooms` projection, written in the same
  transaction as the events; a `Tick` command that decides nothing and lets the existing
  `expireOverdue` run; the internal route; and a `system:` prefix fence that swings both ways.
- **Two rules that give a room an end** — three consecutive lapsed turns give up the seat
  (`PlayerForfeited`, reason `idle`), which routes into invariant 7 and ends the game abandoned with
  no new event type; and `RoomExpired` closes a `WAITING` room that never filled.
- **The contract seam graduated** — the `GameCompleted` sample the CI check validates is now written
  by room-gameplay's own suite from the event class and the privacy filter.
- **Gateway + CLI** — `PATCH` added to the membership route (one array element), the turn deadline
  rendered on the board, `TurnTimedOut`/`RoomExpired` narrated, and `bot --idle`.

## Evidence

`validation.md` carries the transcripts. The short version:

| AC | Verdict |
|---|---|
| AC-P5.1 the outbox drains | 160 rows, **0 unpublished**; relay counters 147 + 13 = 160, reconciling with the database per topic. |
| AC-P5.2 a dead broker costs delivery, never gameplay | 22/22 refused, **0 rows marked**, backoff 853→1958→4301→9451→21288 ms, full recovery. |
| AC-P5.3 the envelope | All six `ce-*` headers, key `roomId`. **`grep -c seed` over both topics → 0.** |
| AC-P5.4 per-room order | Per room, not flattened: 90 and 2 events, both strictly increasing. |
| AC-P5.5 a turn ends untouched | Lapses at seq 6, 11, 15 with nobody sending a command. |
| AC-P5.6 the room stops | `PlayerForfeited(idle)` → `GameCompleted(isAbandoned)` → `RoomCompleted`; **20 events, twice, 19 minutes apart**. |
| AC-P5.7 a stale room expires | Control room: due 02:16:31, ticked 02:16:31, **0.26 s late**. |
| AC-P5.8 the aggregate backstop | Covered by suite; Argo's self-heal makes the worker impossible to hold down (below). |
| AC-P5.9 the boundary | Gateway **404** for anything internal; both headers required; the `system:` fence refuses a player on the internal route and a system id on `/rooms`. |
| AC-P5.10 reconnection | Routing half green; the live drop-and-return probe not run. **Open.** |
| AC-P5.11 the contract check | Green on the generated sample, red on a hand-edit in **both** directions. |
| AC-P5.12 from empty | Five real services `Synced/Healthy`, digest-pinned, both new targets scraped. |
| AC-P5.13 pipelines | 5 + 3 + 3 + **23 jobs**, all green; every changed digest verified to have moved. |

## What the drill caught that nothing else did

**A service that reads a schema it does not own must tolerate its absence.** Both Go workers came up
before room-gameplay had migrated and spent the cold start logging `relation "outbox" does not exist`
and `relation "rooms" does not exist`. They backed off, never crashed — **0 restarts each** — and were
answering by the time the first game was played. Nothing in the repo was wrong; the ordering simply is
not guaranteed, and only an empty cluster asks the question.

**You cannot switch off a GitOps-managed service to test its absence.** `kubectl scale --replicas=0`
was undone by Argo's `selfHeal` in well under the 30-second turn deadline, and patching the child
`Application` did not help because the app-of-apps restores its sync policy. Both attempts at AC-P5.8
produced a fired deadline because the worker was back before the probe could see it gone. Worth
knowing before the exam demo, where it is a feature.

**One tick arrived 2699 seconds late and is not explained.** The first room ever to become due was
ticked 45 minutes after its deadline, while `timerworker_sweep_failures_total` stayed flat at 12 —
so the poller was not failing, it simply returned nothing. The control room afterwards fired 0.26 s
late on the same path, and the idle game's three timeouts were all sub-second. Recorded rather than
explained, because the evidence does not support a cause: the row is gone, and the reported lateness
and the SQL predicate disagree about when it became due. The review pass fixed the part that *was*
actionable — `timerworker_due_rooms` read `0` both when a sweep found nothing and when no sweep had
ever run, which is exactly what made this hard to diagnose. `timerworker_sweeps_total` now separates
them.

## Decisions worth carrying forward

- **The tick asserts nothing.** Architecture T4 has the worker naming which timer expired; saying only
  "this room's clock has run out" leaves every judgement in the aggregate, which is what makes a late
  or duplicated tick free. There is nothing to be idempotent about.
- **Publish, then mark.** The other order would let a crash leave rows recorded as delivered that no
  broker ever saw. This way the failure is a duplicate, which consumers already dedupe on
  `roomId:sequenceNumber` — the pair that is the log's primary key and the `ce-id`.
- **Read the lag from the source of truth.** `outboxrelay_lag_seconds` comes from
  `now() - min(created_at)` in Postgres, never from a cursor this process holds. That is P4's Redis
  outage one layer down: a number a process derives from its own progress cannot report that it has
  stopped.
- **A timeout must not look like the player acting.** `TurnTimedOut` draws and passes *for* the
  player, so a streak reset keyed on those events would clear the counter the timeout just set and
  the third strike would never land. The reset fires only on events a player command can produce.
- **Giving a room a clock without giving it an ending is worse than leaving it stuck.** A dormant room
  with a timer worker produces an event every turn forever.

## Known gaps, deliberate

- **One replica each.** No relay sharding, no `for update skip locked`, no leader election. Per-room
  ordering is a consequence of draining in `id` order; a missed tick is latency, never correctness.
- **No Kafka consumer anywhere.** Delivery is proved by reconciliation. Ranking, spectator and
  analytics are P6; the tournament saga is P7.
- **No CDC.** Architecture O1 offers "CDC **or** polling"; this is the polling half.
- **AC-P5.10's live half is unrun** — the drop-and-return probe inside the 60-second window.
- **The 2699-second outlier is unexplained**, with a new counter in place to make the next occurrence
  diagnosable.
- **Five canned placeholders remain** (`analytics-api`, `analytics-workers`, `ranking`, `spectator`,
  `tournament`) — down from seven. They still carry `digest: ""` and sit in `ImagePullBackOff`.

## After closure — the second review pass (2026-08-12)

Re-read as a reviewer once the phase was already merged, which is where the standing convention says
the value is. It found the defect the first pass missed: **the `WAITING` expiry rule was written out
twice**, once as the decision and once as the cache the timer worker polls, with nothing forcing the
two to agree. Fixed by making the decision ask the cache's own function, and generalised into a
property over generated games — the advertised deadline must be the *earliest* the engine would act.
That property **did not bite on its first version** (it probed 100 000 s out, which any deadline
satisfies) and was tightened until deleting a deadline from the cache turned it red.

It also found `outboxrelay_lag_seconds` and `backlog_rows` reading `0` when the backlog query had
never succeeded — the healthiest possible reading from a relay that cannot reach its database — and a
SIGPIPE flake in `integration-staging:identity` that had nothing to do with P5 and would have bitten
on demo day. Both fixed. Full table in `plan.md`; deltas 10.11 in `CHANGELOG-design.md`.

## Next

**P6 — ranking, spectator, analytics.** Every one of them is now a consumer group away rather than a
phase away: the topics are flowing, the envelope is specified and checked, and `publicPayload` has
been the filter on both transports since P4. The casual gate stayed open throughout — the same
two-process game plays, and now finishes whether or not both players stay at the keyboard.
