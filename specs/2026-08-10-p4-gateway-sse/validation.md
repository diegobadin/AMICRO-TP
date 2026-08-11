# Validation — P4: Gateway + Realtime Fan-out (SSE)

> One executable check per acceptance criterion. Phase gates and drill transcripts get recorded
> here as F1–F8 run.

## Verification matrix

| AC | Check | Pass condition |
|----|-------|----------------|
| AC-P4.1 | Unset `UNOARENA_ROOMS_URL`; run `register`, `login`, `room create/list/join/leave`, `play --casual`, `bot --casual`, `logout` with only `UNOARENA_API_URL=http://localhost:30080`. `grep -r UNOARENA_ROOMS_URL` over `clients/` and the docs. | Every command works; `grep` returns nothing outside `CHANGELOG-design.md` history. `kubectl get svc -n unoarena-staging` shows one `NodePort` (gateway 30080), identity and room-gameplay `ClusterIP`. |
| AC-P4.2 | `kubectl port-forward svc/room-gameplay 8081:80`, then `GET /rooms` with no headers, with a bare JWT, and with **both** trust headers (`Auth.kt` requires `X-Player-Id` *and* `X-Session-Id`; either alone is no credentials). Then, **through the gateway**, send a valid token for alice plus `X-Player-Id: bob`. Grep the config and the sealed secret for the JWT key. | No headers → `401`; bare JWT → `401` (it is not validated any more); both headers → `200`. Through the gateway the request acts as **alice** — the injected header wins and the client's is discarded. `IDENTITY_JWT_SECRET` appears nowhere in `services/room-gameplay/**` or `gitops/secrets/staging/room-gameplay-secrets.yaml`. |
| AC-P4.3 | Re-run P3's concurrency checks **through the gateway**: stale `If-Match`, missing `If-Match`, out-of-turn move, `Idempotency-Key` replay on `POST /rooms`, `If-None-Match` on the game read. | `412` (with the reconcilable body), `428`, `409`, the replay returns the original room, `304` with no body. `ETag` values identical to a direct call. |
| AC-P4.4 | `curl -N` the stream in one terminal while posting moves in another; timestamp both. | One frame per committed event, `id:` equal to the `sequenceNumber`, delivered < 1 s after the move's HTTP response. No token → `401`; a non-member's token → `403`. |
| AC-P4.5 | Note `id: N`, kill the connection, post two moves, reconnect with `Last-Event-ID: N`. Then `XTRIM room:{id}:events MAXLEN 1` and reconnect with the same stale id. Separately: post a move in the window between the CLI's baseline read and its subscribe. | First: exactly the two missed frames replay, in order, then the tail resumes. Second: a `resync` frame arrives instead of a silent gap. Third: the move is **not** lost — the CLI subscribes with the baseline's seq as `Last-Event-ID` (D15), so it replays. |
| AC-P4.6 | Post two moves back to back (`+2` then the forced draw resolution), watch the feed. | Two feed lines in commit order. `grep -n "feed" clients/cli/src/board.ts` finds nothing — the function is deleted, not orphaned. |
| AC-P4.7 | With a live `play` session, log in again as the same player from a second session file. | The old stream receives `event: session-invalidated` and closes within 1 s; the next REST call on the old token is `401`; the CLI prints `session_superseded` and exits non-zero. |
| AC-P4.8 | `bot --casual` twice against one room, then two bots + one human. | A completed game, one §6 JSON line per action with the full field set, a final summary line, exit `0`. A forced failure (bad URL) exits non-zero. |
| AC-P4.9 | `kind delete cluster` → `install.sh` → probes below → two CLI processes play a full game through the gateway. | All three services `Synced/Healthy`, gateway pinned by digest, `gateway-secrets` decrypted, gateway target scraped, a winner and `GameCompleted` in the log. |
| AC-P4.10 | The `main` pipeline of the P4 merge. | Green; stage list identical to P3's; `deploy-staging:gateway` is a real GitOps deploy; `integration-staging:identity` passes with **no edit to that job**. |

## Probes (used by AC-P4.9)

```bash
# One door, and only one
kubectl -n unoarena-staging get svc -o custom-columns=NAME:.metadata.name,TYPE:.spec.type,NODEPORT:.spec.ports[0].nodePort
#   → gateway NodePort 30080 ; identity ClusterIP <none> ; room-gameplay ClusterIP <none>

# Image pinned by digest, secret decrypted
kubectl -n unoarena-staging get pod -l app=gateway \
  -o jsonpath='{.items[0].status.containerStatuses[0].imageID}'
kubectl -n unoarena-staging get secret gateway-secrets

# The key left room-gameplay
kubectl -n unoarena-staging get secret room-gameplay-secrets -o jsonpath='{.data}' | grep -c IDENTITY_JWT_SECRET   # → 0

# Metrics scraped (exact names, not "some metrics came back")
kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9090:9090 &
curl -s 'http://localhost:9090/api/v1/targets?state=active' | grep gateway
curl -s http://localhost:30080/metrics | grep -E '^gateway_(sse_connections_active|sse_events_delivered_total|requests_total|sessions_revoked_total)'

# The stream is really in Redis, and its ids are the sequence numbers
kubectl -n redis exec deploy/redis -- redis-cli XRANGE "room:$ROOM:events" - + COUNT 3
```

## The stream drill (AC-P4.4 / AC-P4.5)

```bash
export UNOARENA_API_URL=http://localhost:30080
TOKEN=$(jq -r .token /tmp/a.json)

# raw frames, no client in the way
curl -N -H "Authorization: Bearer $TOKEN" \
  "$UNOARENA_API_URL/rooms/$ROOM/stream" | tee /tmp/stream.log

# resume from a known point — the same call the CLI makes on FIRST connect, with the seq of the
# baseline GET it just did (D15). One mechanism, so the connect race cannot exist.
curl -N -H "Authorization: Bearer $TOKEN" -H "Last-Event-ID: 12" \
  "$UNOARENA_API_URL/rooms/$ROOM/stream"
```

The frames are the artifact worth keeping: `id`, `event`, `data` for a whole game, next to the
`room_events` rows for the same room. If the two disagree in count or order, the phase is not done.

## The casual game drill (AC-P4.9)

Two terminals, one URL, no polling.

```bash
export UNOARENA_API_URL=http://localhost:30080

# terminal 1
UNOARENA_SESSION=/tmp/a.json unoarena register --user alice --pass pw
UNOARENA_SESSION=/tmp/a.json unoarena play --casual

# terminal 2  (started at the same moment — never one after the other)
UNOARENA_SESSION=/tmp/b.json unoarena register --user bob --pass pw
UNOARENA_SESSION=/tmp/b.json unoarena play --casual

# afterwards: the log, the stream and the feed must tell one story
kubectl -n postgres exec unoarena-pg-1 -c postgres -- \
  psql -U postgres -d room_gameplay -c \
  "select sequence_number, type from room_events where room_id='$ROOM' order by sequence_number"
kubectl -n redis exec deploy/redis -- redis-cli XLEN "room:$ROOM:events"
```

## The bot drill (AC-P4.8)

```bash
export UNOARENA_API_URL=http://localhost:30080

# two bots, started at the same moment, judged on their own output
node clients/cli/scripts/bot-drill.js 2

# the mixed case: one human at a terminal, two bots at the same table
#   (ROOM_MIN_PLAYERS=3 in the room-gameplay overlay — the lever plan D11 left for exactly this)
UNOARENA_SESSION=/tmp/a.json unoarena play --casual
unoarena bot --casual --user mixed-1 --pass mixed-pw --seed 501
unoarena bot --casual --user mixed-2 --pass mixed-pw --seed 502

# the failure the contract also asks for
UNOARENA_API_URL=http://localhost:9 unoarena bot --casual --user load-1 --pass load-pw ; echo $?
```

**F7 gate, met locally against the full stack (gateway → identity + room-gameplay → Redis), 2026-08-11,
re-run in full after the review pass.**

| Run | Result |
|-----|--------|
| `bot-drill.js 2` | both exit `0`, `errors: 0`, one `"outcome":"won"` and one `"lost"`; every stdout line parses as §6 with the full field set |
| `bot-drill.js 4` | two tables of two formed without a coordinator; four exits of `0`, each bot in and out inside 3 s |
| `DRILL_TABLE=3 bot-drill.js 3` | one three-player game, three exits of `0` |
| two bots + one human (`ROOM_MIN_PLAYERS=3`) | one room, three players, `game over - a30d443f wins` on the human's screen, both bots exit `0`, zero `409`s |
| second login while a bot waits | `session rejected (401)` within 50 ms, summary line emitted, exit `1` |
| bad URL | `error_code: "unreachable"`, summary line emitted, exit `1` |
| `--timeout 12` with nobody to play against | gives up at 12.2 s with `error_code: "timeout"` — the flag bounds the wait for a table, not just the game |
| the drill against a port that accepts and never answers | the guard reports `BOT DRILL TIMED OUT` and exits `1` rather than hanging on a `fetch` that has no timeout |
| the drill killed mid-run (`SIGTERM`) | reports `interrupted by SIGTERM`, zero surviving bot processes |
| `casual-drill.js` (P3's harness, re-run after the `enterGame` refactor) | game completed; wild, draw, uno and challenge all observed; zero `409`s |

A challenge only happens when somebody forgets to call, so the drill **reports** which actions a run
exercised rather than requiring them: `challenge_uno` appeared in most runs, and a run without one
is luck rather than a defect.

## Bite tests — does the harness actually bite?

Presence is not proof. Each of these breaks something on purpose and names the alarm that must go
off; a test that stays green here is a test that was never protecting anything.

| Break | Expected bite |
|-------|---------------|
| Stop injecting `X-Player-Id` in the gateway's proxy (comment the line). | The gateway's proxy test fails, **and** every room call returns `401` — proving room-gameplay refuses rather than trusting a bare token. |
| Forward the client's `Authorization` header downstream instead of the injected headers. | room-gameplay still answers `401`. If it answers `200`, it is secretly still validating JWTs and F6 did not land. |
| Send `X-Player-Id: <someone else>` through the gateway with a valid token. | The request acts as the token's subject, and a unit test on the header whitelist fails if the client's value survives. This is the bypass the whole trust flip stands or falls on. |
| `kubectl -n redis scale deploy/redis --replicas=0` mid-game. | A `resync` frame reaches every open stream within a second, and exactly one per outage — the client goes back to the REST read, which still works. REST play keeps working (moves still accepted, `412` still correct); when Redis returns the tail resumes and the missing sequence numbers are visible as a gap. **This is the row F8 rewrote**: it used to say "streams drop and the CLI says so", and the first drill found that they do not — see the F8 evidence below. |
| Point a bot at a room whose other member has walked away. | It stops at `--timeout` with `error_code: "timeout"` and a summary line, exit non-zero — never a process that hangs silently. (Found for real: a drill killed halfway leaves a `WAITING` room, the next `--casual` player joins it and the game auto-starts with nobody on the other side. Deadlines are lazy until P5's timer worker, so **a drill needs a clean room list**.) |
| `XTRIM room:{id}:events MAXLEN 1`, then reconnect with an old `Last-Event-ID`. | A `resync` frame — never a silent gap, never a replay that starts mid-game. |
| Publish an event without `XADD` (comment the publisher) and post one move. | The move commits, `roomgameplay_stream_publish_failures_total` stays 0 but the frame never arrives; the heartbeat's `seq` is ahead of the client's within 15 s and the CLI resyncs. Proves D6 is the safety net it claims to be. |
| Publish the raw event encoding instead of `publicPayload(event)`. | The `leaksPrivateData` test fails: `GameStarted`/`DeckRecycled` carry the RNG seed, and a seed on the stream is the deck order handed to every player. |
| Rename a gateway metric (e.g. drop the `gateway_` prefix). | The metric-name test fails on the **exact exposed string** — the trap that hid `roomgameplay_rooms_created_total` in P3. |
| Break one gateway unit test on the branch. | `test:gateway` red, `build:gateway` never runs, pipeline red — the fail-fast wiring, per service. |

## F8 — the empty-cluster drill (2026-08-11)

`kind delete cluster --name unoarena-staging`, then
`TARGET_REVISION=feat/p4-gateway-sse gitops/bootstrap/install.sh`. Empty kind to all three services
`Synced/Healthy` in **9 minutes** (the ~35 min in the kickoff assumed a cold image cache; the
platform layers were still in the host's registry cache). The sealing key backup restored
`sealed-secrets-key84bcb`, so every committed SealedSecret decrypted on a cluster that had never
seen them.

**The push had to come first, and it was bigger than "build the gateway".** `gateway` had
`digest: ""`, but `room-gameplay` was worse: pinned to `sha256:139c6992`, an image built from
`main`. That is P3's binary — no `Streams.kt` publisher and the old JWT-validating `Auth.kt` — so a
drill cluster would have answered `401` to every gateway-injected header and published no frame at
all, and it would have read like a code defect in the gateway. Both were rebuilt from the branch:
one `-o ci.skip` push to create the branch (pipeline `2751390305`, *skipped*, no jobs burned), then
one push per service so `rules:changes` ran that service's jobs alone (`2751392837`, 4 jobs;
`2751407819`, 5 jobs — the four plus the contract check that room-gameplay's path also triggers).
room-gameplay's P4 suite had never run in CI before this; it passed.

### Probes

```
$ kubectl -n unoarena-staging get svc -o custom-columns=NAME:...,TYPE:...,NODEPORT:...
gateway            NodePort    30080          <- the only door
identity           ClusterIP   <none>
room-gameplay      ClusterIP   <none>

$ kubectl -n unoarena-staging get pod -l app=gateway -o jsonpath='{...imageID}'
.../unoarena/gateway@sha256:d752cf6d692d5f1a544d1ef7078024e3133daa990300b22ae337f8d6fc60d22d
$ ... -l app=room-gameplay
.../unoarena/room-gameplay@sha256:16d1be724142b175c6c0419f6ba453fdae76789fe960f8e1932a54b85602cedf

$ kubectl -n unoarena-staging get secret gateway-secrets      -o jsonpath='{.data}' | keys
['IDENTITY_JWT_SECRET']
$ kubectl -n unoarena-staging get secret room-gameplay-secrets -o jsonpath='{.data}' | keys
['ROOM_GAMEPLAY_DB_PASSWORD']            <- the key left the service

$ curl -s http://localhost:30080/metrics | grep '^# TYPE gateway_'
gateway_requests_total counter
gateway_sse_connections_active gauge
gateway_sse_events_delivered_total counter
gateway_sessions_revoked_total counter
gateway_http_request_duration_seconds histogram      <- all five, exact names, nothing rewritten

$ prometheus /api/v1/targets            gateway up | identity up | room-gameplay up
$ prometheus /api/v1/query              gateway_sse_events_delivered_total  ->  315
```

### AC-P4.2 — the bypass, which is the whole trust flip

Direct to room-gameplay over a port-forward: no headers → `401`, a bare JWT → `401`, both trust
headers → `200 []`. Then through the gateway, alice's token carrying a forged `X-Player-Id: bob`:

```
HTTP/1.1 201 Created   etag: "2"   location: /rooms/d73c8e20-...
{"players":[{"playerId":"ce8e997f-...","connection":"connected"}]}    <- alice, not bob
```

The gateway's own log line for that request reads `"player":"ce8e997f-..."`. The client's header was
discarded, not merged.

### AC-P4.3 — P3's contract through the proxy

| Check | Result |
|-------|--------|
| `Idempotency-Key` replay on `POST /rooms` | `201` then `200`, same `roomId` |
| `If-None-Match` on the game read | `304`, **0 bytes** (`ETag: "4"`) |
| move with no `If-Match` | `428 {"error":"if_match_required"}` |
| move with stale `If-Match: "2"` | `412`, body is the current state (the reconcilable one) |
| move out of turn | `409 {"error":"not_your_turn"}` |
| `ETag` gateway vs direct port-forward | `"11"` and `"11"` |

The out-of-turn check needed three attempts, and the two failures are worth recording because they
are not defects: `TURN_TIMEOUT_SECONDS=30` and **deadlines are only evaluated when a command
arrives**, so a probe sent more than 30 s after the previous move makes the arriving command resolve
the lapsed deadline first — the mover is force-drawn, the turn moves on, and the "out of turn" move
is now legitimately in turn. Both requests have to sit inside one turn window. That lazy evaluation
is P5's timer worker, seen from the outside.

### AC-P4.9 — two CLI processes, one full game

`casual-drill.js /tmp/a.json /tmp/b.json`, exit `0`:

```
[alice] > play 1
[bob]     ce8e997f played Y2
[alice]   you played Y2
[bob]     game over - ce8e997f wins
[alice]   game over - you win!

== game completed. observed: wild, draw, uno, challenge, completed
```

And the three stores agree, which is the part worth more than the transcript:

```
room 22ea51ab-2dc3-4515-9aaa-aacf3629929e
  room_events   count 163, max(sequence_number) 163
  ending        158 ChallengeWindowClosed .. 162 GameCompleted, 163 RoomCompleted
  XLEN          163            <- one frame per committed event, exactly
  TTL           21561          <- refreshed on every write, not -1
  publish failures  0
```

**The privacy boundary holds.** The stored `GameStarted` contains the RNG seed; `grep -ci seed` over
every published frame of the whole game returns **0**. `publicPayload()` is the one filter, and the
deck order never reaches a player.

### AC-P4.7 — the session kill, on all three surfaces

```
18:00:15.893  second login as alice, from a different session file
17:59:39.259  (raw-stream run) login
17:59:39.435  event: session-invalidated / data: {"reason":"superseded"}   <- 176 ms, then closed
              old token on the next REST call -> 401 {"error":"session_superseded"}
              the CLI: `session rejected (401) - log in again`, summary line, exit 1
```

### AC-P4.8 — bots

| Run | Result |
|-----|--------|
| `bot-drill.js 2` | both exit `0`, `errors: 0`, one `"outcome":"won"` and one `"lost"`, every line a §6 object with the full field set; game closed in 1.3 s |
| two bots + one human, `ROOM_MIN_PLAYERS=3` | one table `16cdb433-...`, three players, `game over - you win!` on the human's screen, both bots exit `0`, **zero** `409`s |
| bad URL (`:9`) | `error_code: "unreachable"`, summary line, exit `1` |

The `ROOM_MIN_PLAYERS` lever went to `"3"` through the overlay and back to `"2"` afterwards
(`f11e16c`, `4c1ec5c`) — a live patch would have been reverted by Argo within seconds.

### The Redis-outage drill, which found a real hole

Suspending **both** `unoarena-platform-root` and `redis` first, then `scale deploy/redis
--replicas=0`. Three of the four claims held on the first run:

- REST play kept working — `201` on a create, `200` + `ETag` on a read, with Redis at zero replicas.
- Publication stayed best-effort and counted: `roomgameplay_stream_publish_failures_total` went to
  `2` and no command failed (D5).
- The view repaired on return: the frame arrived the same second Redis came back, as `id: 4`, while
  the client's cursor was still `2` — so the missing seq `3` was visible and the gap check fires.

The fourth did not. **The stream never dropped and the client was never told.**
`stream-tail-failed` was logged **0 times**: `ioredis` queued the blocking `XREAD` in its offline
queue instead of rejecting it, so the tail never errored, and the heartbeat — which reads the
gateway's own in-memory cursor, not Redis — went on ticking every 15 s with a frozen `seq: 2` while
the room had genuinely moved to `3`. A player watching a room that advances during the outage is
told nothing and believes they are current.

Fixed in `cb5754b`: the tail connection stops queueing (`enableOfflineQueue: false` — its loop is
already a retry loop, so a parked read is only an outage nobody can see), and the first failed read
of an outage broadcasts the `resync` frame the client already knows how to handle. Re-drilled
against the rebuilt image `sha256:2c9b7c70`:

```
18:34:17  redis scaled to 0
18:34:18  event: resync / data: {"reason":"stream-unavailable"}    <- one second
18:34:29  event: heartbeat  {"seq":2}      (connection alive, cursor frozen — as expected)
   ...    45 s of outage, and exactly ONE resync, not one per second
18:35:51  id: 3  event: PlayerLeft                                 <- the tail resumed
18:35:59  event: heartbeat  {"seq":3}                              <- cursor moving again
18:36:05  event: resync / data: {"reason":"stream-unavailable"}    <- second outage, re-armed
```

The unit test bites: reverted, `tells every client once per outage` fails on the first assertion.
Gateway suite 44 → **45**.

### AC-P4.10 — the `main` pipeline

FF merge `ea1ab15..dbc12d2`, 25 commits. The closure commit carries `[skip ci]`, so the push itself
produced a *skipped* pipeline (`2751588218`) — the closure run has to be asked for:
`POST /projects/83816735/pipeline?ref=main`, where every job's
`if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH` rule matches and the whole set runs.

**`2751590088` — 35 success + 8 manual**, stage list unchanged from P3's:
`test → build → deliver → deploy-staging → integration-staging → deliver-production`. 43 jobs, up
from P3's 36 because the gateway is now a fully-wired service with its own four. `deploy-staging:gateway`
is a real GitOps deploy, and **`integration-staging:identity` passed with no edit to that job** —
the collapse of identity's NodePort did not reach it, because it stands up its own kind cluster and
drives the CLI against what it deployed.

The first attempt failed on `build:gateway`, and it is worth recording as *not* a defect:

```
error building image: unable to complete operation after 0 attempts, last error:
GET https://gcr.io/v2/token?scope=repository:distroless/nodejs20-debian12:pull&service=gcr.io:
UNAUTHORIZED: authentication failed
```

kaniko could not fetch an **anonymous pull token from gcr.io** for the distroless base image. The
same Dockerfile and the same base built green minutes earlier on the branch (`2751543258`); a retry
of the same pipeline went green with nothing changed. A registry flake, not the repo — but the kind
of red that costs an hour if it is read as one.

### Cluster left as found

All apps `Synced/Healthy`, `ROOM_MIN_PLAYERS` back to `"2"`, no joinable rooms, no stray client
processes. The seven canned placeholders sit in `ImagePullBackOff` with `digest: ""`, exactly as
they do on `main` — they have never been built, and P4 did not change that.

## Definition of done

- [x] AC-P4.1 — one `NodePort`, one CLI base URL, `UNOARENA_ROOMS_URL` gone from code and docs
- [x] AC-P4.2 — room-gameplay has no JWT secret and refuses header-less requests
- [x] AC-P4.3 — the REST contract survives the proxy unchanged
- [x] AC-P4.4 — one frame per committed event, `id` = `sequenceNumber`, < 1 s
- [x] AC-P4.5 — ordering, `Last-Event-ID` replay, explicit `resync`
- [x] AC-P4.6 — the feed is observed, `feed()` deleted
- [x] AC-P4.7 — a superseded session dies on the wire and on the next REST call
- [x] AC-P4.8 — `bot --casual` finishes a game with the §6 output contract
- [x] AC-P4.9 — empty cluster → everything Healthy → a full game through the gateway
- [x] AC-P4.10 — pipeline shape unchanged, green, `integration-staging:identity` untouched
- [x] No frame on any stream contains a `seed` field (the P6 privacy boundary, checked here)
- [x] All nine bite tests bit — the Redis one bit hardest, and its row above was rewritten to what
      the system actually does once it did
- [x] `CHANGELOG-design.md` §9 records every P4 delta; `clients/cli/README.md` and `README.md`
      describe one entry point and the stream contract
- [x] `ESTADO-FINAL.md` written; roadmap marks P4 **SHIPPED** and P5 **next** with a handoff block

## Phase gates

| Phase | Gate before the next one starts |
|-------|--------------------------------|
| F1 | `gateway-staging` Healthy with a digest-pinned image; `/health` and `/metrics` answer through a port-forward |
| F2 | `/auth/**` works through the gateway against the real identity, and a second login turns the first token into `401 session_superseded` **at the gateway**. AC-P4.3 moves to F6: the token strip and the header trust are two halves of one flip |
| F3 | `XRANGE` shows one entry per committed event with ids equal to the sequence numbers |
| F4 | `curl -N` shows live frames; reconnect replays exactly the missed ones; `resync` on a trimmed stream |
| F5 | A full game played by hand through the stream, locally |
| F6 | AC-P4.1 + AC-P4.2 + AC-P4.3 on a drill cluster; the CLI works with one URL |
| F7 | Two bots finish a game; every line parses as §6 JSON |
| F8 | AC-P4.9 recorded here with transcripts, then `main` green (AC-P4.10) — **met**, with one code change the drill forced (`cb5754b`) |

## Out-of-scope confirmation (must **not** appear in the diff)

- [ ] No outbox draining, no Kafka producer for room events, no timer-worker
- [ ] No `/spectate/**` route, no spectator projection
- [ ] No rate limiting (per-IP, per-user, per-room), no TLS termination at the gateway
- [ ] No RS256/JWKS, no separate session-scoped control channel, no per-request introspection
- [ ] No changes to P3's event names, sequence numbering, or `GET …/games/{n}`
- [ ] No new pipeline stage, no 11th deployable

## Mission check

Two questions decide whether P4 moved the program forward:

1. Can the faculty be handed **one URL** and drive the entire casual loop — register, room, play,
   live feed, reconciliation, bot — without being told about a second port or a second variable?
2. When two players are in a game, does one player's move appear on the other's screen because the
   server **pushed** it, and can that be shown to be true (frame ids matching the event log) rather
   than asserted?

If both are yes, the exam's functional pass is a demo instead of a walkthrough, and P5 can start on
the async spine without anything in P1–P4 needing to move.
