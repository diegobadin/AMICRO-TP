# Validation — P5: Async Spine (outbox relay + timer worker)

> Every item is binary and checkable on the real branch. Transcripts go inline as they are produced.
> Acceptance criteria are `AC-P5.n`; the bite tests prove the safety nets have teeth, not just presence.

## Acceptance criteria

| AC | Statement |
|---|---|
| AC-P5.1 | **The outbox drains.** After a full game, `select count(*) from outbox where published_at is null` is **0**, and the message count delivered on `room.public.events` + `room.lifecycle.events` equals `select count(*) from outbox` |
| AC-P5.2 | **A dead broker costs delivery, never gameplay.** With Kafka at zero replicas: moves keep returning `201`, the backlog grows, `outboxrelay_lag_seconds` climbs, `outboxrelay_publish_failures_total` counts; on recovery the backlog drains to 0 with no missing row |
| AC-P5.3 | **The envelope is the one the catalog specifies, and the privacy filter survived the second transport.** `ce-specversion`/`ce-id`/`ce-type`/`ce-time`/`ce-subject`/`ce-correlationid` present, key = `roomId`, body carries `roomId` + `sequenceNumber` — and **no message on either topic contains a `seed` field** |
| AC-P5.4 | **Per-room order holds.** For each room, the `sequenceNumber`s arrive strictly increasing on each topic |
| AC-P5.5 | **A turn ends without anyone touching it.** With no command sent, `TurnTimedOut` appears within `TURN_TIMEOUT_SECONDS + TICK_INTERVAL`, and the CLI narrates it on the other player's screen |
| AC-P5.6 | **A walked-away room reaches a terminal state and stops.** K consecutive timeouts forfeit the idle player, the game ends `isAbandoned = true` → `RoomCompleted`, and `select count(*) from room_events where room_id = …` taken **twice, ≥5 minutes apart**, is identical |
| AC-P5.7 | **A stale `WAITING` room expires.** `RoomExpired` is emitted once, the room leaves `GET /rooms`, and a fresh `--casual` client creates a new room instead of joining the ghost |
| AC-P5.8 | **The aggregate is still the backstop.** With `timer-worker` scaled to 0, deadlines stop firing promptly but the next real command resolves them exactly as it did in P4 — belt and suspenders, both verified |
| AC-P5.9 | **The trust boundary holds.** The internal tick needs `X-Player-Id` **and** `X-Session-Id`; either alone is `401`; `curl :30080/internal/rooms/{id}/tick` is `404` at the gateway; a `system:`-prefixed id on `/rooms/**` is refused |
| AC-P5.10 | **Reconnection has both halves.** A client that drops and returns inside 60 s cancels its window through `PATCH` and keeps playing; one that stays away is forfeited at the deadline |
| AC-P5.11 | **The contract check validates the real producer.** The golden sample is written by a room-gameplay test from the engine's own encoder; the schema names `gameNumber`; a hand-edit of the sample turns the job red |
| AC-P5.12 | **From empty.** `kind delete cluster` → `install.sh` → five real services `Synced/Healthy`, digest-pinned, secrets decrypted, both new targets scraped; a bot game plays through and drains to Kafka |
| AC-P5.13 | **Green `main` pipeline**, triggered explicitly after the `[skip ci]` closure commit |

## Evidence (drill, 2026-08-11 → 12)

Cluster rebuilt from empty on `feat/p5-async-spine`. **Five real services `Synced/Healthy`** where P4
had three; the remaining five canned placeholders sit `Degraded`, as always.

| AC | Verdict |
|---|---|
| AC-P5.1 | Two bots played a full game through the gateway (0 errors). `select count(*) filter (where published_at is null) from outbox` → **0**, against 160 rows total. The relay's own counters reconcile exactly: `rows_published_total{room.public.events} 147` + `{room.lifecycle.events} 13` = 160. |
| AC-P5.2 | Proved twice. In-cluster the relay met a Postgres with no schema yet and backed off without crashing (below). Against a real broker it met an unreachable Kafka: **22/22 writes refused, 0 rows marked published**, backoff 853 → 1958 → 4301 → 9451 → 21288 ms, and the rows drained intact once a broker existed. |
| AC-P5.3 | `ce-specversion:1.0, ce-id:7fbbde98…:92, ce-source:/room-gameplay, ce-type:com.unoarena.room.GameCompleted.v1, ce-time:…, ce-subject:…, ce-correlationid:…`, key = `roomId`. **`grep -c seed` over the full dump of both topics → 0**, and `GameStarted` on the wire carries `gameNumber`/`initialColor`/`playerOrder` and no seed. |
| AC-P5.4 | Grouped by room, not flattened — the first flat check was wrong because two rooms interleave across three partitions. Per room: `7fbbde98` 90 events increasing, `ed63330d` 2 events increasing. |
| AC-P5.5 | `bot --casual` against `bot --casual --idle`: the idle seat's turns lapsed at sequence 6, 11 and 15, each auto-drawn and passed, with nobody sending a command. |
| AC-P5.6 | The third lapse produced `PlayerForfeited` reason **`idle`** (seq 18) → `GameCompleted isAbandoned=true` (19) → `RoomCompleted` (20). The opponent's real plays never reset the idle player's streak. Counted **twice, 19 minutes apart: 20 events both times**, `next_deadline` NULL. The room is finished, not ticking. |
| AC-P5.7 | Two rooms expired on their own. `ed63330d`: `RoomExpired waiting_timeout` — but the tick arrived **2699 s late** (see below). `d8312e79`, created deliberately as a control: due 02:16:31, ticked 02:16:31, **0.26 s late**. |
| AC-P5.8 | **Covered by suite, not by the cluster — and the reason is worth keeping.** Holding the worker down is not possible on this cluster: `kubectl scale --replicas=0` is undone by Argo's `selfHeal` well inside the 30 s turn deadline, and patching the child `Application` does not help because the app-of-apps restores its sync policy. Two attempts both produced a fired deadline (5→8 and 6→9 events) — the worker was back before the probe could observe its absence. The backstop itself is proved by P3's `DeadlinesTest` (unchanged, green) and by `TimerTickTest`, both against a real Postgres. The GitOps behaviour is a feature on demo day and an obstacle to this one probe. |
| AC-P5.9 | `POST :30080/internal/rooms/{id}/tick` → **404** at the gateway, as does any internal path. Direct over a port-forward: no headers **401**, player-id only **401**, session-id only **401**, both **200**. A real player id on the internal route → **401**; a `system:` id on `/rooms` → **401**. The fence holds in both directions. |
| AC-P5.10 | **Half proved.** The routing half is closed: the gateway suite asserts `PATCH` reaches room-gameplay on the membership path, and the reconnect call is wired to the stream's `onReconnect`. The live half — drop a client, return inside 60 s — was not run, because the window only opens on a session invalidation and staging that on top of an in-flight game is a probe in its own right. **Open, and named as such.** |
| AC-P5.11 | `python ci/contracts/validate.py` green on the generated sample. Red on a hand-edit, in both directions: `'gameNumber' is a required property` **and** `Additional properties are not allowed ('gameId' was unexpected)` — the leak detector works. The producer-side golden test goes red on the same edit. |
| AC-P5.12 | `kind delete cluster` → `TARGET_REVISION=feat/p5-async-spine install.sh` → gateway, identity, room-gameplay, outbox-relay, timer-worker all `Synced/Healthy`, digest-pinned, secrets decrypted, both new targets scraped. |
| AC-P5.13 | Branch pipelines green: room-gameplay (5 jobs), timer-worker (3), outbox-relay (3), and the combined run (**23 jobs, all green**). Every changed service's pinned digest verified to differ from `main`'s; identity's, untouched, is identical. |

### What the drill caught that nothing else did

**Two services that read a schema they do not own must tolerate its absence.** Both Go workers came
up before room-gameplay had migrated and spent the cold start logging
`relation "outbox" does not exist` / `relation "rooms" does not exist`. They backed off and recovered
on their own — **0 restarts each** — and the backlog and due queries were answering by the time the
first game was played. Nothing in the repo was wrong; the ordering simply is not guaranteed, and only
an empty cluster asks that question.

**One tick arrived 2699 seconds late, and it is not yet explained.** `ed63330d` was due at 01:09:41
and ticked at 01:54:40 — the worker's *first ever* tick. `timerworker_sweep_failures_total` stayed at
12 throughout that window, so the poller was not failing; it simply returned no due rooms for
45 minutes. The control room created afterwards fired 0.26 s late on the same code path, and the
three turn timeouts in the idle game were all sub-second, so the mechanism is right and this was a
one-off. It is recorded rather than explained because the evidence does not support a cause: the row
is gone, and `lateBySeconds` (2699) and the SQL predicate disagree about when it became due.

The review pass acted on the part that *was* actionable: `timerworker_due_rooms` reads `0` both when
a sweep found nothing and when no sweep has ever run, which is precisely the ambiguity that made the
above hard to diagnose. `timerworker_sweeps_total` now distinguishes them.

## Local backend

- [ ] `./gradlew test` green (engine + room-gameplay), including the new deadline tests
- [ ] `cd services/outbox-relay && go vet ./... && go test ./...` green
- [ ] `cd services/timer-worker && go vet ./... && go test ./...` green
- [ ] `cd services/gateway && npm test` green, including the `PATCH` routing test
- [ ] `cd clients/cli && npm test` green
- [ ] The engine test that asserts the streak reset rule states the **attempt count** (not just that a
      forfeit eventually happened) — `checkAll` in an expression body is silently never run
- [ ] Metric assertions compare **exact exposed strings** (`outboxrelay_rows_published_total`,
      `timerworker_ticks_total`, …), not "some metrics came back"
- [ ] `grep -rn "sweep\|Tick" services/room-gameplay/src/main/kotlin/Main.kt` shows the new code has a
      **call site**, not just a definition

## Cluster — the async spine

- [ ] `kubectl -n unoarena-staging get deploy outbox-relay timer-worker` both `1/1`
- [ ] Both `Application`s `Synced/Healthy` in Argo, image pinned by digest
- [ ] Both `/metrics` endpoints scraped: they appear in Prometheus targets as `up == 1`
- [ ] `select count(*) filter (where published_at is null) from outbox` → **0** (AC-P5.1)
- [ ] `kafka-console-consumer --from-beginning --timeout-ms 15000` on each topic; message counts
      reconcile against the row counts per topic (AC-P5.1)
- [ ] Headers inspected with `--property print.headers=true`; key with `--property print.key=true` (AC-P5.3)
- [ ] `grep -c seed` over the full consumer dump on both topics → **0** (AC-P5.3)
- [ ] Per-room `sequenceNumber`s strictly increasing in the dump (AC-P5.4)

## Cluster — the timers

- [ ] Two CLI clients start a game; one stops acting. `TurnTimedOut` arrives on the active player's
      feed within the turn timeout + one tick, and the CLI narrates it (AC-P5.5)
- [ ] The idle player is forfeited on the K-th timeout; the game ends `isAbandoned = true` (AC-P5.6)
- [ ] Event count for that room, taken twice ≥5 minutes apart, is **identical** — the room is finished,
      not ticking (AC-P5.6)
- [ ] A room created and left `WAITING` past its window emits exactly one `RoomExpired` and disappears
      from `GET /rooms` (AC-P5.7)
- [ ] `kubectl scale deploy/timer-worker --replicas=0`: a deadline lapses without an event; the next
      real command resolves it, as in P4. Scale back to 1 and it fires on its own again (AC-P5.8)
- [ ] `bot --idle` produces a timeout on demand, and its §6 output lines still parse

## Cluster — the boundary

- [ ] `curl -s -o /dev/null -w '%{http_code}' -X POST :30080/internal/rooms/<id>/tick` → **404** (AC-P5.9)
- [ ] Direct over a port-forward: tick with both headers → `200`; `X-Player-Id` only → `401`;
      `X-Session-Id` only → `401`; neither → `401` (AC-P5.9)
- [ ] `X-Player-Id: system:timer-worker` on `POST /rooms` over the port-forward → refused (AC-P5.9)
- [ ] A player token forged with `X-Player-Id: system:timer-worker` through the gateway is overwritten,
      as P4 proved for any client-supplied value
- [ ] Drop a client mid-game, restart it inside 60 s: `PlayerReconnected` is emitted and play continues
      (AC-P5.10). Repeat without returning: `PlayerForfeited` at the deadline

## Empty-cluster drill (AC-P5.12)

- [ ] Pre-flight: **each of the five changed services' pinned digests differs from `main`'s** — the
      per-service push actually moved them (P4's costliest lesson)
- [ ] `kind delete cluster` → `TARGET_REVISION=feat/p5-async-spine … install.sh`, timed
- [ ] Five real services `Synced/Healthy`; the seven canned placeholders `Degraded` (normal, not a
      regression)
- [ ] Two CLI processes started **simultaneously** play a complete game to `GameCompleted` →
      `RoomCompleted`, and the outbox drains behind them
- [ ] No stray clients from a previous run: `ps -eo args | grep cli.js | grep -v grep` is empty
      (`pgrep -f` matches its own shell), and local runs `truncate room_events, outbox, rooms,
      idempotency_keys, consumed_events;` between attempts

## CI and contract

- [ ] Branch's first push used `git push -o ci.skip`; then one push per changed service
- [ ] Each service's pipeline ran **only** that service's jobs (change detection intact)
- [ ] `test:contract:game-completed` green on the golden sample (AC-P5.11)
- [ ] Both `deploy-staging` jobs are real GitOps deploys, not manual stubs, and reach `argocd app wait --health`
- [ ] Green `main` pipeline **explicitly triggered** after the `[skip ci]` closure commit —
      `POST /api/v4/projects/83816735/pipeline?ref=main` (AC-P5.13). Read a red run before believing it:
      the base-image pull is a public-registry call that can fail for reasons the repo did not cause

## Bite tests — does the net have teeth

Each one breaks something on purpose and requires the named signal. For a fix that is **already
committed**, restore the pre-fix file with `git show <fixcommit>^:<path> > <path>` — `git stash push`
reverts to `HEAD`, which already contains the fix, so the test passes and looks like it stopped
biting. Restore from a copy afterwards, with **absolute paths** (the Bash tool's cwd persists).

- [ ] **Kafka dies.** `kubectl scale strimzipodset … --replicas=0` (or delete the broker pod) under a
      live game: moves still `201`, `outboxrelay_publish_failures_total` **increments**,
      `outboxrelay_lag_seconds` climbs past 30. On recovery the backlog reaches 0 and no row is lost
- [ ] **Mark-then-publish.** Invert the order so rows are marked before the ack, kill the relay
      mid-batch: rows show `published_at` set with nothing in Kafka. Restore; the correct order loses
      nothing. (Proves the ordering is load-bearing, not stylistic)
- [ ] **Streak reset on `CardDrawn`.** Point the reset at `CardDrawn`: the idle-forfeit test must go
      **red**, because `TurnTimedOut` emits `CardDrawn` itself and the counter never reaches K (R2)
- [ ] **One header only.** Make the worker send `X-Player-Id` alone: its own test must fail, and a live
      tick must `401`
- [ ] **No `next_deadline` write.** Skip the column in the projection upsert: the worker finds nothing,
      no deadline fires on its own — and the next real command still resolves it (which is AC-P5.8
      arriving from the other direction)
- [ ] **Hand-edit the golden sample** (`gameNumber` → `gameId`): `test:contract:game-completed` goes red
      and the consumers' `build` jobs are blocked by `needs:`
- [ ] **A `seed` in the payload.** Publish a `GameStarted` without `publicPayload`: the privacy test in
      room-gameplay must fail (the marker-based assertion, not a hand-maintained event-name list)

## Out-of-scope confirmation — must NOT appear in the diff

- [ ] No Kafka **consumer** anywhere (no ranking Elo, no spectator projection, no analytics worker, no saga)
- [ ] No Debezium, no Kafka Connect, no CDC connector
- [ ] No `for update skip locked`, no relay sharding, no leader election, no lease table
- [ ] No Kafka transactions / exactly-once configuration
- [ ] No change to `Streams.kt`, the room stream TTL, or the gateway's four SSE guards
- [ ] No fifth timer type and no per-room timer overrides
- [ ] No client-side decision that a deadline has passed — the CLI renders seconds only
- [ ] No rate limiting, TLS termination or WAF policy at the gateway
- [ ] No new deployable, no new pipeline stage, no eleventh chart
- [ ] The seven canned placeholders still carry `digest: ""` and were not built

## Mission check

- [ ] **Is the architecture's async spine real?** The transactional outbox now bridges to Kafka with
      the envelope the catalog specifies, so P6's ranking and spectator and P7's tournament saga are
      plug-in work rather than plumbing work — a consumer group away, not a phase away.
- [ ] **Does the game run without a babysitter?** A casual game reaches a terminal state on its own,
      and the leftover `WAITING` room that stranded three P4 drills cannot outlive its window. The
      exam demo no longer depends on both humans staying at the keyboard.
