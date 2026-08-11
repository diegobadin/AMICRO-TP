# ESTADO FINAL — P4: Gateway + Realtime Fan-out (SSE)

> Closed 2026-08-11. All ten acceptance criteria green. The cluster has **one door**: the gateway
> owns `30080`, identity and room-gameplay are `ClusterIP`, and room-gameplay holds no signing key
> at all — it trusts headers the gateway builds from scratch on every request. The polling loop is
> gone: a move appears on the other player's screen because the server pushed it, and the frame ids
> are the sequence numbers of the events in the log, so that claim can be checked rather than
> asserted. A bot plays the same game headless through the same door.

## What shipped

- **`services/gateway/`** — the third fully-wired service, and the only one reachable from outside.
  A route table (`app.ts`) rather than a framework, HS256 validation against identity's key, a
  header **whitelist** on the way down, `/health`, `/metrics`, structured logs, and the SSE tier.
  45 tests.
- **The trust flip** (`b39d6ae`, the one-way door). The gateway strips `Authorization` before
  room-gameplay and injects `X-Player-Id` / `X-Session-Id`; room-gameplay's `Auth.kt` became a
  provider that trusts those two headers and `401`s without them; `IDENTITY_JWT_SECRET` moved from
  `room-gameplay-secrets` to `gateway-secrets`. The whitelist is the security control the whole flip
  rests on — a client-supplied `X-Player-Id` that reached the backend would be an authentication
  bypass, so an inbound one is overwritten, never merged.
- **The publisher** (`Streams.kt`) — every committed event is `XADD`ed to `room:{roomId}:events`
  **after** the transaction commits, with the entry id equal to the sequence number, `MAXLEN ~ 2000`
  and a 6 h TTL refreshed on every write. It publishes `publicPayload(event)`, the same filter the
  outbox row uses, because `GameStarted` and `DeckRecycled` carry the RNG seed and a seed on the
  stream is the deck order handed to every player. A failed publish increments
  `roomgameplay_stream_publish_failures_total` and never fails the command.
- **The SSE endpoint** — `GET /rooms/{id}/stream`, membership checked once at subscribe, one
  blocking tail per pod across every subscribed room, `Last-Event-ID` replay, an explicit `resync`
  when the retained window no longer covers the client, a 15 s heartbeat carrying the room's latest
  sequence number, and a `session-invalidated` control frame.
- **The CLI on the stream** — a ~40-line reader over `fetch` (Node 20 has no `EventSource`, and the
  browser one cannot send `Authorization`). One rule: narrate every frame, re-read when the player
  can actually act. `UNOARENA_ROOMS_URL` is gone; there is one base URL.
- **`bot --casual`** — a player, not a cheat: it picks uniformly among the *server-marked* playable
  indices, declares a colour for wilds, challenges an open window on someone the board does not
  already show as safe, and forgets to call Uno! with probability `--forget-uno` (default `0.25`) so
  the call-and-challenge mechanic is exercised instead of permanently satisfied. `--seed` makes a run
  reproducible; the token lives in memory, so N bots need no shared file.

## Evidence

`validation.md` carries the transcripts and the probe output. The short version:

| AC | Verdict |
|---|---|
| AC-P4.1 one door | `gateway NodePort 30080`; identity and room-gameplay `ClusterIP`. Whole casual loop driven with only `UNOARENA_API_URL`. `UNOARENA_ROOMS_URL` survives nowhere but `CHANGELOG-design.md` history and past-phase specs. |
| AC-P4.2 the backend holds no key | Direct over a port-forward: no headers `401`, a bare JWT `401`, both trust headers `200`. Through the gateway, alice's token with a forged `X-Player-Id: bob` created a room owned by **alice**. `IDENTITY_JWT_SECRET` greps clean out of `services/room-gameplay/**`. |
| AC-P4.3 the contract survives the proxy | `428` / `412` (with the reconcilable body) / `409 not_your_turn` / `304` with 0 bytes / idempotent replay `201`→`200`. `ETag` identical through the gateway and direct: `"11"`. |
| AC-P4.4 one frame per event | 163 events in `room_events`, `XLEN` **163**, ids equal to the sequence numbers, delivered inside the same second as the move's response. |
| AC-P4.5 replay and resync | `Last-Event-ID` replays exactly the missed frames in order; a trimmed window produces `resync`, never a silent mid-game start; first connect uses the same path with the baseline read's seq (D15), so the connect race cannot exist. |
| AC-P4.6 the feed is observed | Frames render themselves in commit order; `feed()` is deleted, not orphaned. |
| AC-P4.7 a superseded session dies | `event: session-invalidated` **176 ms** after the second login, stream closed; next REST call `401 session_superseded`; the CLI says so and exits non-zero. |
| AC-P4.8 the bot | Two bots: both exit `0`, `errors: 0`, one won one lost, every line a §6 object. Two bots + one human at one table: zero `409`s. Bad URL: `unreachable`, exit `1`. |
| AC-P4.9 from empty | `kind delete cluster` → `install.sh` → all three `Synced/Healthy` in **9 min**, digest-pinned, secrets decrypted, targets scraped, and two CLI processes played a complete game to `GameCompleted` → `RoomCompleted`. |
| AC-P4.10 pipeline | Same stages, same job kinds, no 11th deployable. `deploy-staging:gateway` is a real GitOps deploy. `integration-staging:identity` untouched. |

Pipelines: `2751392837` (gateway), `2751407819` and `2751424567` (room-gameplay), `2751543258`
(the gateway rebuild after the drill finding) — all green, each running only the jobs its own
service's paths triggered.

## What the drill caught that nothing else did

**The push was the blocker, and it was bigger than it looked.** `gateway` carried `digest: ""`, but
`room-gameplay` was pinned to an image built from `main` — P3's binary, with no publisher and the
old JWT-validating `Auth.kt`. Nothing in the repo was wrong; the *cluster* would have been, and it
would have presented as a gateway that 401s everything and never emits a frame. A phase that changes
two services has to rebuild both, and only the drill asks that question.

**A Redis outage was invisible to the client.** Killing Redis under a live stream did not drop it.
`ioredis` parks a blocking `XREAD` in its offline queue rather than rejecting it, so the tail never
errored — `stream-tail-failed` was logged **0 times** — and the heartbeat, which reads this
process's in-memory cursor rather than Redis, kept ticking every 15 s with a sequence number that
had stopped moving. Three of the bite test's four claims held (REST play continued, publication
stayed best-effort and counted, the view repaired on return via the gap check); the fourth, "the CLI
says so", was simply false. Fixed in `cb5754b`: the tail stops queueing, and the first failed read of
an outage broadcasts `resync` once, re-armed on recovery. Verified against the rebuilt image — the
frame arrives one second after Redis dies, once per outage, and the tail resumes on its own.

**Two probes that looked like defects and were not**, both worth writing down because the next
session will hit them:

- `X-Player-Id` alone is not enough. `Auth.kt` requires `X-Session-Id` too, so the "trusted header"
  probe returns `401` until both are set. `validation.md`'s AC-P4.2 row named only one; corrected.
- The out-of-turn check took three attempts. `TURN_TIMEOUT_SECONDS=30` and deadlines are evaluated
  only when a command arrives, so a probe sent late makes the arriving command resolve the lapsed
  deadline first — the mover is force-drawn and the "out of turn" move is legitimately in turn by the
  time it lands. Both requests must sit inside one turn window. That is P5's timer worker, seen from
  outside.

## Decisions worth carrying forward

- **The stream id *is* the sequence number.** `Last-Event-ID` is therefore a stream position with no
  lookup table and no second ordering to keep honest — the single decision the whole resume story
  rests on.
- **Publication is best-effort and strictly after commit.** The events are already durable; that is
  what log-before-broadcast bought. Redis holds a transient copy, `room_events` is the archive, and
  the repair path is a read.
- **Never render a board from a locally-applied state.** One command commits several events, so
  mid-batch a client that applies frames itself genuinely believes it is its turn — and walks into a
  `409`. It did, 22 times in one game, before the projection was deleted.
- **Four guards, each owning a different failure**: the gap check (a frame lost between two others),
  the `resync` frame (a `Last-Event-ID` older than the retained window), the heartbeat (a lost *last*
  frame, with nothing after it to reveal the hole — that is `GameCompleted`), and now the tail's own
  failure (the transport gone, which none of the other three can see).
- **HS256 with one verifier** is the coupling shrunk, not removed: identity signs, the gateway
  verifies. RS256 + JWKS is the upgrade when a second verifier appears (`CHANGELOG-design.md` §9).

## Known gaps, deliberate

- **The revoked-session map is per-pod and lost on restart** (R4). With one replica it is exact; a
  second replica means a killed session keeps its stream until the token expires. Documented, not
  pretended away.
- **Nothing drains the outbox** — still, by design. P5's relay is the only thing to add.
- **Deadlines only fire when a command arrives.** P5's timer worker closes it; until then a
  half-finished drill leaves a `WAITING` room that nothing recovers.
- **No rate limiting, no TLS termination, no WAF-shaped policy** at the gateway (L1–L4 are P7's).
- **No spectator routing and no privacy projection** — P6. The stream already carries only
  `publicPayload`, which is the boundary P6 will build on.
- **The seven canned placeholders still carry `digest: ""`** and sit in `ImagePullBackOff`. That is
  their state on `main` too; they have never been built.

## Next

**P5 — the async spine**: the outbox relay and the timer worker. Both close gaps this phase
documented rather than fixed, and neither needs anything in P1–P4 to move. The casual gate stayed
open throughout: the same two-process game that opened it in P3 now plays through the gateway, on
pushed frames, with a bot able to take either seat.
