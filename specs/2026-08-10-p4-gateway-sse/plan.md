# Plan — P4: Gateway + Realtime Fan-out (SSE)

> How [`requirements.md`](./requirements.md) gets built, in commit-sized phases ordered so the
> branch stays deployable: everything additive first (the gateway beside the two NodePorts, the
> publisher beside the polling loop), and the one commit that actually moves the trust boundary
> lands late, when both halves already work. Design decisions are the implementer's (D-n); flag
> objections early, they harden as they ship.

## Design decisions (confirm in review)

- **D1 — The gateway is a route table, not a framework.** `node:http` plus a pure
  `route(method, path)` that returns a target and a policy, mirroring identity's
  `handle(method, path)`. Unit tests exercise routing, header injection and revocation without a
  socket. The SSE handler is the one route that takes the raw response — it is the only place that
  needs it, and keeping it isolated is what keeps the rest testable.
- **D2 — Proxying is pass-through, not translation, and the header list is a whitelist.** Method,
  path, body and exactly these headers go downstream — `content-type`, `if-match`,
  `if-none-match`, `idempotency-key`, `x-correlation-id` — plus the two the gateway *sets itself*,
  `X-Player-Id` and `X-Session-Id`. Status, body and `ETag` come back unchanged. The gateway never
  invents a status code and never rewrites a payload: P3's `412`/`428`/`409`/`304` contract has to
  survive it byte for byte (AC-P4.3), and a gateway that "helps" is how contracts rot.
  **The whitelist is a security control, not tidiness** — once room-gameplay trusts `X-Player-Id`,
  a client-supplied one that reached it would be an authentication bypass, so any inbound
  `X-Player-Id`/`X-Session-Id` is overwritten, never merged.
  **`Authorization` is forwarded per target, not dropped outright** (corrected in F1): identity
  *owns* sessions and resolves the token itself, so `/auth/**` carries it through and the gateway's
  check is a fast rejection rather than a replacement. room-gameplay never sees a token at all —
  it has no key to check one with.
- **D3 — Stream key `room:{roomId}:events`, entry id `{sequenceNumber}-0`.** Redis accepts explicit
  entry ids, and room sequence numbers are already strictly increasing (the `(room_id,
  sequence_number)` PK is P3's concurrency mechanism), so the stream id **is** the sequence number.
  `Last-Event-ID` becomes a stream position with no lookup table and no second ordering to keep
  honest (Architecture §1.4). Fields: `type`, `seq`, `payload`, `correlationId`.
  `MAXLEN ~ 2000` — a P3 game closed at 359 events, so a full game plus slack — and a 6 h TTL
  **refreshed on every publish** (corrected in review: keying it to `RoomCompleted` reclaimed only
  the rooms that ended tidily, and an abandoned room never completes). Redis holds a transient copy;
  `room_events` is the archive.
  **`payload` is `publicPayload(event)` — the function the outbox row already uses** (`Outbox.kt`).
  `GameStarted` and `DeckRecycled` carry the RNG seed and are marked `PrivateEvent`; publishing the
  raw encoding would leak the deck order to every player. One filter for both transports, and the
  existing `leaksPrivateData` test extends to cover the stream rather than a second one being
  written beside it.
- **D4 — One tail per pod, across every subscribed room** (simplified in F4; the phases table said
  one per room). `XREAD BLOCK` takes many streams at once, so a single loop over the current room
  set feeds every local subscriber from one connection — a loop per room would mean a connection
  per room, and a blocking read holds its connection for the length of the block. The short block
  is what lets the room set change: it is re-issued with whatever is subscribed now, and a read
  still returns the instant any stream has data. Every pod tails independently, so replicas need no
  sticky routing and no shared state — the only reason the gateway can stay stateless while holding
  connections.
- **D5 — Publication is best-effort and strictly after commit.** The `XADD` happens outside the
  transaction, after it commits. A failure increments
  `roomgameplay_stream_publish_failures_total` and is logged; it never fails the command. The
  events are already durable — that is what log-before-broadcast bought — and the resync read is
  the repair path.
- **D6 — The heartbeat carries the latest sequence number.** Every 15 s the gateway sends
  `event: heartbeat, data: {"seq": N}` from the room's stream tail. It keeps the connection warm
  *and* closes R1: a client that missed the final frame of a burst notices within one heartbeat and
  resyncs, without reintroducing a poll loop. Three mechanisms guard the stream and each owns a
  different failure — the **gap check** catches a frame lost between two others, the **`resync`
  frame** catches a `Last-Event-ID` older than the retained window, and the **heartbeat** catches
  the one neither can see: a lost *last* frame, with nothing after it to reveal the hole. That is
  `GameCompleted` — a client that misses it waits forever.
- **D7 — The CLI's stream client is ~40 lines over `fetch`, not a dependency.** Node 20 has no
  `EventSource`, and the browser one cannot send `Authorization` — so a small reader over the
  response's `ReadableStream` (frame split on blank line, `id:`/`event:`/`data:`) is both the
  smaller and the only workable option.
- **D8 — One rule: the client narrates every frame, and re-reads when the player can act.**
  `GET /rooms/{id}/games/{n}` is called when the turn becomes theirs (so `playable` is the server's
  legality check, not the client's opinion), when a challenge window opens on someone else (the one
  thing playable out of turn), when their own cards changed, at game start and end, and whenever the
  picture cannot be trusted — a `resync` frame, a `seq` gap, or a heartbeat ahead of what the client
  has seen. Everything else is a feed line and nothing more.
  Two corollaries, both learned by playing it:
  **the board is only ever rendered from a state the server sent.** One command commits several
  events — a `+2` is `CardPlayed`, then `ForcedDraw`, then `TurnSkipped` — so a client drawing from
  its own running total shows a turn prompt in the middle of a batch that is still moving the turn
  on, and invites the player into a `409`. It did, 22 times in one game.
  And **frames whose sequence number the client already holds are narrated but not re-read**: the
  response to your own command carried the newer state, so its frames are feed lines, nothing more.
  **What this replaced** (review pass): the client used to apply every event to a local `GameView`.
  Once the board stopped being drawn from it, that projection's only remaining readers were the
  `state` command and the challenge window — one of which is worth a round trip because the player
  asked, and the other is covered by reading when the window opens. A second copy of a game's state
  is the thing most likely to lie; 130 lines of it went, and the two behaviours it existed for are
  now consequences of the one rule above.
- **D9 — The gateway verifies with identity's HS256 key.** `IDENTITY_JWT_SECRET` moves from
  `room-gameplay-secrets` to `gateway-secrets`; identity is unchanged. The coupling shrinks from
  "two services hold a signing key" to "the signer and the one verifier" — intrinsic to symmetric
  JWTs. RS256 + JWKS is the upgrade when there is a second verifier; recorded in
  `CHANGELOG-design.md`, not pretended away.
- **D10 — Membership is checked once, at subscribe,** through the gateway's own proxied
  `GET /rooms/{id}`. A player who leaves keeps the stream until the room completes. That is safe
  precisely because the frames are public-only (E2) — a stale subscriber learns nothing it could
  not read from `GET /rooms/{id}`. The rule is written down because "check it every frame" is the
  reflex, and it would buy nothing here.
- **D11 — Revoked sessions are a `Map<sessionId, expiresAt>`, swept on read.** Entries die with the
  token, so the map cannot grow. Per-pod and lost on restart (R4).
- **D12 — Metrics.** `gateway_requests_total{route,status}`,
  `gateway_http_request_duration_seconds{route,status}` (identity's naming, so the two read the
  same on a dashboard), `gateway_sse_connections_active`,
  `gateway_sse_events_delivered_total`, `gateway_sessions_revoked_total`, plus
  `roomgameplay_stream_publish_failures_total`. `sse_connections_active` is Architecture §05's
  name carrying the service prefix the other two services use. Assert the **exact exposed strings**
  in a test — Prometheus rewrites names it dislikes, and P3 lost a metric name that way.
- **D13 — The bot is a player, not a cheat.** It picks uniformly at random among the server-marked
  `playable` indices, declares a random colour for wilds, **challenges** whenever it sees an open
  window naming someone else, and calls Uno! when a play would leave it at one card — except with
  probability `--forget-uno` (default `0.25`), so the call-and-challenge mechanic is actually
  exercised instead of being permanently satisfied. `--seed` makes a run reproducible.
- **D14 — `feed()` is deleted, not left behind.** The inferred feed in `board.ts` goes with the
  poll loop. Dead code that looks like a feature is worse than no feature.
- **D15 — The client subscribes *with* the baseline it just read.** `play` does its
  `GET /rooms/{id}/games/{n}` first and then opens the stream with `Last-Event-ID: <that seq>`.
  Subscribing at the tail and reading the baseline afterwards leaves a window where the events in
  between are lost for good — a race that would surface as a frozen board in front of the faculty
  and nowhere else. Reusing the replay path means one mechanism covers first connect and reconnect,
  with no "drop frames older than my baseline" logic on the client at all.

## Phases (one commit each)

| Phase | Delivers | Validated by |
|-------|----------|--------------|
| F0 | This triad + `kickoff.md` | review |
| F1 | `gateway` becomes real: route table, JWT validation, header injection, `/health`, `/metrics`, structured logs; chart gets `imagePullSecrets`/`startupProbe`/ServiceMonitor; `deploy-staging:gateway` promoted to the real GitOps deploy (still `ClusterIP`) | unit tests; Argo shows `gateway-staging` Healthy, image pinned by digest, reachable via port-forward |
| F2 | REST proxying for `/auth/**` and `/rooms/**` (D2) + the Redis pub/sub subscriber and revoked-session map (D11) | identity's half live against the real service, second login → `401 session_superseded`; the rooms half against a real HTTP backend in tests, and **re-run against room-gameplay at F6** — see below |
| F3 | room-gameplay publishes committed events to `room:{id}:events` (D3/D5) + the failure counter | `XRANGE` after a played game shows one entry per event, ids equal to the sequence numbers |
| F4 | Gateway SSE: `GET /rooms/{id}/stream`, membership check, tail per room, `Last-Event-ID` replay, `resync`, heartbeat, `session-invalidated` frame | `curl -N` shows frames arriving as moves are posted; reconnect replays exactly the missed ones |
| F6 | **The collapse**: gateway `NodePort 30080`, identity + room-gameplay `ClusterIP`, room-gameplay drops JWT validation and trusts the headers, `IDENTITY_JWT_SECRET` leaves `room-gameplay-secrets`, CLI drops `UNOARENA_ROOMS_URL` | AC-P4.1, AC-P4.2 on a drill cluster |
| F5 | CLI: stream client (D7) replaces the poll loop, resync rules (D8), `feed()` deleted, `session_superseded` surfaced | a full game played by hand through the gateway |
| F7 | `bot --casual` (D13) + an N-bot drill script | two bots finish a game headless; §6 lines parse |
| F8 | Empty-cluster drill (AC-P4.9), session-kill drill (AC-P4.7), transcripts, README / `clients/cli/README.md` / `CHANGELOG-design.md` §9 deltas, `ESTADO-FINAL.md` | recorded in `validation.md` |

F1–F5 are additive: the two NodePorts keep working and the CLI keeps polling until F6, so the
branch is never in a state where a drill cannot play a game. F5 is validated **locally** (gateway
run with `npm start`, room-gameplay port-forwarded) because the gateway is not yet the cluster's
door — the same ordering P3 used when it hand-played before the cluster drill.

**F6 landed before F5** (reordered after F4). The flip is what makes the stack runnable end to end,
and writing the CLI's stream client against a stack that answers `401` would mean debugging both
halves at once, later, on a cluster. The precondition the ordering was protecting — "cross the
one-way door only when the proxy tests are green" — was met by F2 and F4, so the door was safe to
cross. Everything below about the flip still holds; only its position moved.

**The trust flip cannot be verified in halves** (found in F2). The gateway stops sending the token
the moment it starts injecting `X-Player-Id`, and room-gameplay only starts accepting that header at
F6 — so between F2 and F6 the rooms path through the gateway answers `401`, correctly, from
room-gameplay. AC-P4.3 is therefore an F6 gate, not an F2 one. The alternative — forwarding the
token *and* the headers for a few commits — would put a transitional auth mode in the code whose
only test is that it later gets removed, and the bite test for the header whitelist would have to
pass with it in place. Not worth it: `/auth/**` exercises the proxy against a real service today,
and the proxy suite covers `201`/`304`/`412`/`204`/`502` against a real HTTP backend.

**F6 ordering note.** From an empty cluster there is no NodePort conflict, and that is the drill
that counts. On a *running* cluster the gateway Service may fail to allocate `30080` until
identity's app syncs to `ClusterIP`; Argo retries on its own. If it is still stuck, sync identity
first, then the gateway — do not "fix" it by picking a third port, because kind's port mappings are
fixed at cluster creation and a third port means recreating the cluster.

## Changes by file

**Gateway**

- `services/gateway/src/` — `app.ts` (route table, D1), `proxy.ts` (D2), `sse.ts` (D4/D6),
  `auth.ts` (JWT verify + revoked map, D11), `sessions.ts` (Redis pub/sub subscriber),
  `metrics.ts` (D12), `server.ts` (wiring, config from env).
- `services/gateway/package.json` — `ioredis`, `jose`, `prom-client` (the three identity already
  uses, same versions).
- `services/gateway/tests/` — routing, header injection, proxy pass-through, revocation, SSE frame
  encoding, `Last-Event-ID` replay, exact metric names.
- `services/gateway/chart/` — `imagePullSecrets`, `startupProbe`, `ServiceMonitor`, `secretName`,
  `service.type`/`nodePort` support (identity's chart is the template to copy verbatim).
- `services/gateway/.gitlab-ci.yml` — `deploy-staging:gateway` extends `.deploy-gitops` like
  identity's and room-gameplay's; no new stage.

**room-gameplay**

- `src/main/kotlin/Streams.kt` — the post-commit publisher (D3/D5).
- `src/main/kotlin/Rooms.kt` / `EventStore.kt` — call the publisher after the transaction commits,
  never inside it.
- `src/main/kotlin/Auth.kt` — replaced: trust `X-Player-Id` / `X-Session-Id`, `401` without them.
- `src/main/kotlin/Config.kt` — `jwtSecret` out, `REDIS_URL` in.
- `src/main/kotlin/Metrics.kt` — `roomgameplay_stream_publish_failures_total`.
- `build.gradle.kts` — a Redis client in, the JWT library out.
- `src/test/kotlin/` — the HTTP suites mint JWTs today; they set headers after F6. That is a
  mechanical change across every route test, and it is the moment to check none of them was
  asserting `401` for a *reason* that no longer exists.

**Cluster**

- `gitops/secrets/seal.sh` + `gitops/secrets/staging/gateway-secrets.yaml` — `IDENTITY_JWT_SECRET`
  for the gateway; the same key leaves `room-gameplay-secrets.yaml`.
- `gitops/apps/gateway/overlays/staging/values.yaml` — `NodePort 30080`, `secretName`,
  ServiceMonitor, `REDIS_URL`, `IDENTITY_URL`, `ROOM_GAMEPLAY_URL`, digest pin.
- `gitops/apps/identity/overlays/staging/values.yaml` — `service.type: ClusterIP`, `nodePort` gone.
- `gitops/apps/room-gameplay/overlays/staging/values.yaml` — `ClusterIP`, `REDIS_URL` added.
- `gitops/bootstrap/kind-cluster.yaml` — comments only: `30080` is the gateway now, and `30081`
  stays mapped and unused so no existing cluster has to be recreated.

**Client / docs**

- `clients/cli/src/api.ts` — `ROOMS` deleted; one base URL.
- `clients/cli/src/stream.ts` — the SSE reader (D7).
- `clients/cli/src/rooms.ts` — `play` re-wired onto the stream, resync rules (D8),
  `session_superseded`.
- `clients/cli/src/board.ts` — `feed()` deleted (D14); frames render themselves.
- `clients/cli/tests/board.test.ts` — the `feed()` cases go with it, replaced by frame-rendering
  and stream-reader tests. Deleting a test with its subject is fine; deleting one to make a suite
  green is not, so the replacement lands in the same commit.
- `clients/cli/src/bot.ts` + `cli.ts` — `bot --casual` (D13).
- `clients/cli/scripts/` — the N-bot drill; `casual-drill.js` updated to the single URL.
- `clients/cli/README.md`, `README.md`, `CHANGELOG-design.md` §9 — one entry point, the stream
  contract, and the six deltas P4 introduces (Redis-sourced SSE instead of relay-fed, the control
  frame folded into the room stream, HS256 verification, no rate-limiting layers, membership checked
  at subscribe, no spectator routing).

## Risks

- **R1 — A gateway that quietly changes the REST contract.** The whole `412`/`428`/`409`/`304`
  mechanic P3 proved runs through it now. Mitigation: D2's whitelist, and AC-P4.3 re-runs P3's own
  concurrency checks against the gateway rather than trusting the pass-through by inspection.
- **R2 — SSE is easy to demo and hard to prove.** Ordering, resume and loss are the parts that
  break at an exam, not the happy path. Mitigation: the bite tests in `validation.md` trim the
  stream, kill Redis and reconnect with a stale `Last-Event-ID` on purpose.
- **R3 — The trust flip is a one-way door.** Once room-gameplay stops validating JWTs, a mistake in
  the gateway's header injection is an authentication bypass, not a `401`. Mitigation: F6 lands
  after F2's proxy tests are green, and AC-P4.2 verifies the refusal from a port-forward — the only
  place that can still reach the service directly.
- **R4 — Redis becomes load-bearing for the demo.** P1 deployed it as a cache; the live feed now
  depends on it. Mitigation: the degradation drill kills Redis mid-game and requires that REST play
  continues and the view repairs when it returns. If that is not true, the phase is not done.
- **R5 — Scope creep into P5.** The obvious "while we're here" is draining the outbox. It stays
  full; the stream is a second, transient path and the CHANGELOG says so.
- **R6 — CI minutes and pin ping-pong.** Same rules as P3: `-o ci.skip` on the first push,
  `git pull --rebase` before every local commit, batch `ci/templates/**` changes, one FF merge.

## Review pass (2026-08-10, before F7)

Read back as a reviewer would, questioning each decision rather than defending it. Seven changes;
the first two are behavioural, the rest are removals.

| # | Finding | Change |
|---|---------|--------|
| 1 | **The client kept a second copy of the game.** Once the board was only drawn from server state (F5), the local projection's remaining readers were the `state` command and the challenge window. | `apply()` deleted — 130 lines. One rule replaces it: narrate every frame, read when the player can act (D8). `state` now reads, which also makes it authoritative. |
| 2 | **Only tidy endings were reclaimed.** The stream TTL was set on `RoomCompleted`, so a room that was abandoned kept its stream in Redis forever. | The TTL is refreshed on every publish and keyed to the last write (D3). Simpler rule, no special case, no leak. |
| 3 | **A silent stall.** Node holds response headers until the first body byte, so a subscriber with nothing to replay left the client's `fetch` unresolved on a connection that was in fact open. | `res.flushHeaders()` on the SSE response. |
| 4 | **A leak on a fast disconnect.** The close handler was registered *after* the replay finished, so a client that vanished during it was never detached — the room kept tailing and the connection gauge drifted. | Handler registered first, with a re-check once `subscribe` returns. |
| 5 | **Redis down left connections hanging.** A rejected `subscribe` escaped as an unhandled rejection and the stream stayed open forever, carrying nothing. | Caught and the response ended, which puts the client back into its own reconnect loop. |
| 6 | **Speculative generality.** An injected `fetch` the proxy tests never used; `roomId`/`playerId` fields on `Subscriber` nobody read; a `queueDepth` constructor parameter nobody passed; `SERVICE` declared twice; four module-internal symbols exported; a `busy` flag in the CLI left over from the poll loop. | All removed. |
| 7 | **Two places knew the trust-boundary header names**, one of them the security-critical injection. | `PLAYER_HEADER`/`SESSION_HEADER`/`CORRELATION_HEADER` declared once and imported. |

Not changed, and why: the catch-up queue in `Subscriber` looks like machinery but is load-bearing —
the replay awaits Redis and the tail is free to run in that gap, so without it frame 10 overtakes
frames 5–9. The three stream guards stay for the same reason: each owns a failure the others cannot
see (D6).

## What this plan deliberately does *not* include

- Draining the outbox, publishing room events to Kafka, or the timer-worker (P5).
- A spectator stream, `/spectate/**` routing, or any privacy projection (P6).
- Rate limiting (L1–L4), TLS termination, or WAF-shaped policy at the gateway.
- RS256/JWKS, a separate session-scoped control channel, or gateway-side session introspection.
- Sticky routing, connection draining, or autoscaling for the SSE tier.
- Any change to P3's event names, sequence numbering, or the `GET …/games/{n}` contract.
