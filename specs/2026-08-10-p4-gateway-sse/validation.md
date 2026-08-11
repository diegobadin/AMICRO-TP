# Validation — P4: Gateway + Realtime Fan-out (SSE)

> One executable check per acceptance criterion. Phase gates and drill transcripts get recorded
> here as F1–F8 run.

## Verification matrix

| AC | Check | Pass condition |
|----|-------|----------------|
| AC-P4.1 | Unset `UNOARENA_ROOMS_URL`; run `register`, `login`, `room create/list/join/leave`, `play --casual`, `bot --casual`, `logout` with only `UNOARENA_API_URL=http://localhost:30080`. `grep -r UNOARENA_ROOMS_URL` over `clients/` and the docs. | Every command works; `grep` returns nothing outside `CHANGELOG-design.md` history. `kubectl get svc -n unoarena-staging` shows one `NodePort` (gateway 30080), identity and room-gameplay `ClusterIP`. |
| AC-P4.2 | `kubectl port-forward svc/room-gameplay 8081:80`, then `GET /rooms` with no headers, with a bare JWT, and with `X-Player-Id`. Then, **through the gateway**, send a valid token for alice plus `X-Player-Id: bob`. Grep the config and the sealed secret for the JWT key. | No headers → `401`; bare JWT → `401` (it is not validated any more); `X-Player-Id` → `200`. Through the gateway the request acts as **alice** — the injected header wins and the client's is discarded. `IDENTITY_JWT_SECRET` appears nowhere in `services/room-gameplay/**` or `gitops/secrets/staging/room-gameplay-secrets.yaml`. |
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

**F7 gate, met locally against the full stack (gateway → identity + room-gameplay → Redis), 2026-08-11.**

| Run | Result |
|-----|--------|
| `bot-drill.js 2` | both exit `0`; 36 and 34 actions, `errors: 0`; one `"outcome":"won"`, one `"lost"`; every stdout line parses as §6 with the full field set |
| `bot-drill.js 4` | two tables of two formed without a coordinator; four exits of `0` |
| `DRILL_TABLE=3 bot-drill.js 3` | one three-player game, three exits of `0` |
| two bots + one human (`ROOM_MIN_PLAYERS=3`) | one room, three players, `game over - a30d443f wins` on the human's screen, both bots exit `0`, zero `409`s |
| second login while a bot waits | `session rejected (401)` within 50 ms, summary line emitted, exit `1` |
| bad URL | `error_code: "unreachable"`, summary line emitted, exit `1` |
| `casual-drill.js` (P3's harness, re-run after the `enterGame` refactor) | game completed; wild, draw, uno and challenge all observed; zero `409`s |

A challenge only happens when somebody forgets to call, so the drill **reports** which actions a run
exercised rather than requiring them — one run in four saw `challenge_uno` succeed.

## Bite tests — does the harness actually bite?

Presence is not proof. Each of these breaks something on purpose and names the alarm that must go
off; a test that stays green here is a test that was never protecting anything.

| Break | Expected bite |
|-------|---------------|
| Stop injecting `X-Player-Id` in the gateway's proxy (comment the line). | The gateway's proxy test fails, **and** every room call returns `401` — proving room-gameplay refuses rather than trusting a bare token. |
| Forward the client's `Authorization` header downstream instead of the injected headers. | room-gameplay still answers `401`. If it answers `200`, it is secretly still validating JWTs and F6 did not land. |
| Send `X-Player-Id: <someone else>` through the gateway with a valid token. | The request acts as the token's subject, and a unit test on the header whitelist fails if the client's value survives. This is the bypass the whole trust flip stands or falls on. |
| `kubectl -n redis scale deploy/redis --replicas=0` mid-game. | Streams drop and the CLI says so; REST play keeps working (moves still accepted, `412` still correct); when Redis returns, the next heartbeat/resync repairs the view. No silent wrong board. |
| Point a bot at a room whose other member has walked away. | It stops at `--timeout` with `error_code: "timeout"` and a summary line, exit non-zero — never a process that hangs silently. (Found for real: a drill killed halfway leaves a `WAITING` room, the next `--casual` player joins it and the game auto-starts with nobody on the other side. Deadlines are lazy until P5's timer worker, so **a drill needs a clean room list**.) |
| `XTRIM room:{id}:events MAXLEN 1`, then reconnect with an old `Last-Event-ID`. | A `resync` frame — never a silent gap, never a replay that starts mid-game. |
| Publish an event without `XADD` (comment the publisher) and post one move. | The move commits, `roomgameplay_stream_publish_failures_total` stays 0 but the frame never arrives; the heartbeat's `seq` is ahead of the client's within 15 s and the CLI resyncs. Proves D6 is the safety net it claims to be. |
| Publish the raw event encoding instead of `publicPayload(event)`. | The `leaksPrivateData` test fails: `GameStarted`/`DeckRecycled` carry the RNG seed, and a seed on the stream is the deck order handed to every player. |
| Rename a gateway metric (e.g. drop the `gateway_` prefix). | The metric-name test fails on the **exact exposed string** — the trap that hid `roomgameplay_rooms_created_total` in P3. |
| Break one gateway unit test on the branch. | `test:gateway` red, `build:gateway` never runs, pipeline red — the fail-fast wiring, per service. |

## Definition of done

- [ ] AC-P4.1 — one `NodePort`, one CLI base URL, `UNOARENA_ROOMS_URL` gone from code and docs
- [ ] AC-P4.2 — room-gameplay has no JWT secret and refuses header-less requests
- [ ] AC-P4.3 — the REST contract survives the proxy unchanged
- [ ] AC-P4.4 — one frame per committed event, `id` = `sequenceNumber`, < 1 s
- [ ] AC-P4.5 — ordering, `Last-Event-ID` replay, explicit `resync`
- [ ] AC-P4.6 — the feed is observed, `feed()` deleted
- [ ] AC-P4.7 — a superseded session dies on the wire and on the next REST call
- [ ] AC-P4.8 — `bot --casual` finishes a game with the §6 output contract
- [ ] AC-P4.9 — empty cluster → everything Healthy → a full game through the gateway
- [ ] AC-P4.10 — pipeline shape unchanged, green, `integration-staging:identity` untouched
- [ ] No frame on any stream contains a `seed` field (the P6 privacy boundary, checked here)
- [ ] All nine bite tests bit
- [ ] `CHANGELOG-design.md` §9 records every P4 delta; `clients/cli/README.md` and `README.md`
      describe one entry point and the stream contract
- [ ] `ESTADO-FINAL.md` written; roadmap marks P4 **SHIPPED** and P5 **next** with a handoff block

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
| F8 | AC-P4.9 recorded here with transcripts, then `main` green (AC-P4.10) |

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
