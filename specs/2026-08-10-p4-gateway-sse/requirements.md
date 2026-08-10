# Requirements — P4: Gateway + Realtime Fan-out (SSE)

> Fourth phase of the final-delivery program
> ([`../2026-07-26-final-delivery-northstar/`](../2026-07-26-final-delivery-northstar/)). P3 opened
> the casual gate — a full game of Uno is playable through the CLI against a cluster deployed from
> empty. P4 delivers the **quality** of that experience: one entry point instead of two, a real
> event stream instead of a polling loop, one trust boundary instead of a shared signing key, and a
> bot that can play without a human.

## Objective

Everything the CLI does goes through **one gateway on one port**, and a player's live view is fed by
a **real SSE stream** carrying one frame per committed event — ordered, resumable from
`Last-Event-ID`, and killed within a second when a second login supersedes the session. The shared
HS256 secret disappears from room-gameplay: the gateway validates, the service trusts
`X-Player-Id`/`X-Session-Id` inside the boundary.

## Locked decisions (session 2026-08-10)

| # | Decision | Chosen |
|---|----------|--------|
| E1 | Where the SSE tier gets its events | **room-gameplay `XADD`s each committed event to a per-room Redis Stream, right after the transaction commits; the gateway `XREAD`s and fans out.** Keeps P4 independent of P5, gives sub-second latency, and leaves the outbox untouched for the relay that owns it |
| E2 | What an SSE frame carries | **A public event envelope with `id` = `sequenceNumber`**, with `GET /rooms/{id}/games/{n}` read occasionally rather than per event. Hands never enter the fan-out tier — the boundary P6's spectator work depends on. *Mechanism refined in the review pass*: the option as chosen had the client apply each frame to a local board; it narrates them instead and re-reads when the player can act (plan D8). Same wire contract and the same read volume — one round trip per turn either way — without a second copy of the game living on the client |
| E3 | Direct room-gameplay access | **Removed.** The gateway takes the already-mapped `30080`; identity and room-gameplay become `ClusterIP`; `UNOARENA_ROOMS_URL` goes away. Bypass drills use `kubectl port-forward` |
| E4 | Gateway deployable | **The existing `gateway` placeholder becomes real** (Node/TS, its chart, overlay and CI fragment already exist). Third fully-wired service; no 11th deployable, no new name to explain |
| E5 | `bot --casual` | **A subcommand of the same CLI, in the same image.** N parallel bots are N processes or N containers with different args (§5.E); it reuses the session handling, the §6 line format and the stream client |
| E6 | How a superseded session kills a live stream | **Gateway subscribes to `session:invalidated:*`**, closes matching streams with a `session-invalidated` frame **and** remembers the dead `sessionId` so that token's REST calls get `401`. The revoked set is per-pod and lost on restart — documented, not hidden |

## In scope

- **`gateway` becomes real** — a Node/TS service that is the single entry point: JWT validation,
  `X-Player-Id`/`X-Session-Id` injection, transparent REST proxying to identity and room-gameplay,
  the SSE endpoint, `/health`, `/metrics`, structured logs carrying `correlationId`.
- **Realtime tier** — `GET /rooms/{roomId}/stream`: one frame per committed event, `id` =
  `sequenceNumber`, per-room ordering, `Last-Event-ID` replay, heartbeat, and the
  `session-invalidated` control frame on the same connection.
- **room-gameplay publishes** every committed event to `room:{roomId}:events` after commit —
  best-effort, counted when it fails, and carrying the **same privacy-filtered payload the outbox
  row carries**, so the RNG seed never reaches a client. It also **stops validating JWTs**, trusting
  the gateway's headers instead; `IDENTITY_JWT_SECRET` leaves `room-gameplay-secrets`.
- **The port collapse** — gateway on `NodePort 30080`; identity and room-gameplay `ClusterIP`; the
  CLI drops `UNOARENA_ROOMS_URL` and reaches everything through `UNOARENA_API_URL`. No kind cluster
  recreation: `30080` is already published.
- **CLI** — the poll loop is replaced by the stream client, `board.ts`'s inferred `feed()` is
  deleted, `409`/`412` reconciliation stays surfaced, and `session_superseded` (§5.A) becomes
  reachable for the first time.
- **`bot --casual`** — headless random-valid-move player emitting the §6 output contract (one JSON
  line per action, a final summary line, non-zero exit on failure), deterministic under `--seed`.
- **Platform seam** — `gateway-secrets` sealed secret, ServiceMonitor, digest pin, and
  `deploy-staging:gateway` promoted from manual stub to the real GitOps deploy.

## Out of scope (→ later phases)

- **Outbox relay, Kafka publication of room events, timer-worker** (P5). The outbox keeps filling
  and nothing drains it — unchanged by this phase, by design.
- **Spectator stream and its privacy projection** (P6). `/spectate/**` is not routed.
- **Rate limiting layers L1–L4** (Architecture §2.1), **TLS termination**, **per-IP token buckets**.
  The gateway is an entry point, not yet a policy enforcement point.
- **RS256 / JWKS.** One verifier does not need asymmetric keys; recorded as the upgrade path.
- **A separate session-scoped control channel** (Architecture §1.1). The control frame rides the
  room stream — the CLI's only stream in P4.
- **Multi-replica session affinity.** Every pod tails Redis independently, so replicas are already
  correct; scaling them is not a deliverable.
- **Tournaments, Elo, analytics** (P6/P7).

## Acceptance criteria

- **AC-P4.1 — One door.** Every CLI command works with only `UNOARENA_API_URL` set, pointing at the
  gateway. `UNOARENA_ROOMS_URL` no longer exists in the CLI, the README or the drill scripts.
  `kubectl get svc -n unoarena-staging` shows exactly one `NodePort` (gateway, 30080); identity and
  room-gameplay are `ClusterIP`.
- **AC-P4.2 — The trust boundary moved, and the gateway is the only way through it.**
  room-gameplay has no `IDENTITY_JWT_SECRET` in its config or its sealed secret. A port-forwarded
  request to room-gameplay **without** `X-Player-Id` is rejected `401`; one **with** it is served.
  The gateway rejects an invalid, expired or revoked token with `401` before it reaches any
  service, and a **client-supplied `X-Player-Id` sent through the gateway is overwritten, never
  forwarded** — otherwise trusting the header downstream would be an authentication bypass.
- **AC-P4.3 — REST is proxied, not reinvented.** `ETag`, `If-Match`, `If-None-Match`,
  `Idempotency-Key`, `X-Correlation-Id` and status codes (`201`/`204`/`304`/`409`/`412`/`428`)
  survive the gateway unchanged — verified by driving P3's own concurrency checks through it.
- **AC-P4.4 — The stream is real.** `GET /rooms/{id}/stream` delivers one frame per committed
  event with `id` = `sequenceNumber`, within **1 s** of the move's HTTP response (Architecture §06
  budget is 200 ms typical / 500 ms max; 1 s is the pass bar on kind). Unauthenticated → `401`,
  non-member → `403`.
- **AC-P4.5 — Ordering and resume are honest.** Frames for a room arrive in strictly increasing
  `sequenceNumber` order. Reconnecting with `Last-Event-ID: N` replays exactly the frames after `N`
  and nothing else. A `Last-Event-ID` older than the retained window produces an explicit `resync`
  frame — never a silent gap.
- **AC-P4.6 — The feed is observed, not inferred.** `feed()`'s state-diffing is deleted. Two events
  committed inside the same second appear as two feed lines in the right order (the exact case the
  P3 stand-in collapsed into one).
- **AC-P4.7 — A superseded session dies on the wire.** A second login for a player holding a live
  stream: the old stream receives `session-invalidated` and closes within **1 s**, the old token's
  next REST call gets `401`, and the CLI prints `session_superseded` and exits non-zero. **`logout`
  travels the same path** — identity publishes on the same channel for both (`sessions.ts`), so a
  logged-out client cannot keep a live stream either.
- **AC-P4.8 — The bot plays.** `bot --casual` completes a full game against another bot with no
  human input: §6 JSON lines throughout, a final summary line, exit `0` on a completed game and
  non-zero on failure. Two bots plus one human in the same room also finish.
- **AC-P4.9 — It comes up from an empty cluster.** `kind delete cluster` → `install.sh` → gateway,
  identity and room-gameplay `Synced/Healthy`, gateway image pinned by digest, `gateway-secrets`
  decrypted, `/metrics` scraped by Prometheus, and a full casual game played through the gateway by
  two CLI processes.
- **AC-P4.10 — Pipeline unchanged in shape and green.** No new stages. `deploy-staging:gateway` is
  a real GitOps deploy. `integration-staging:identity` still passes **untouched** — it deploys
  identity directly with its own inline NodePort values and must not notice the overlay change.

## Constraints from tech-stack

- `gateway` is **Node.js / TS + vitest + tsc** (`tech-stack.md` §2) — the language its bounded
  context names. No new framework: identity's `handle(method, path)` shape is the model to copy.
- **Pipeline shape is frozen** (north-star program rules): a real service slots into the existing
  per-service spine, it does not add stages.
- **GitOps only** — the pipeline pins a digest into `gitops/apps/gateway/overlays/staging/`; Argo
  reconciles. Secrets are Sealed Secrets; no plaintext in the repo.
- **Instrument as you go** — the gateway exposes `/metrics` from its first real commit; P8
  consolidates dashboards, it does not retrofit instrumentation.
- **Additive growth** — no phase rewrites a previous phase's data model or events. P3's event
  names, sequence numbers and the `GET …/games/{n}` resync read are reused verbatim.

## Risks & mitigations

- **R1 — A best-effort publish can lose the last frame of a burst.** The event is durable (it
  committed); only the notification is lost, and in a turn-based game the next event repairs the
  view. The gap that cannot self-repair is the last event of a game, so the stream heartbeat
  carries the room's latest `sequenceNumber` and the client resyncs when it is behind (plan D6).
  Failed publishes are counted, not swallowed silently.
- **R2 — SSE through kind's NodePort could buffer.** No compression on the stream, explicit flush
  per frame, `X-Accel-Buffering: no`, and a 15 s heartbeat that would expose a stalled pipe in the
  drill rather than at the exam.
- **R3 — Two services claiming NodePort 30080 during the collapse.** From an empty cluster there is
  no conflict at all (AC-P4.9 is the real proof). On a *running* drill cluster the gateway's Service
  may fail to allocate the port until identity's sync releases it; Argo retries and it self-heals —
  documented in the plan rather than discovered live.
- **R4 — The revoked-session set is per-pod, in memory.** A gateway restart would accept a
  superseded token again until it expires. Accepted for P4 and written down: room-gameplay still
  disconnects the player through `identity.session-events`, and the authoritative fix (introspection
  on every request) costs a hop per request for a hole no exam demo can reach.
- **R5 — Scope creep into P5/P6.** The outbox stays undrained and `/spectate/**` stays unrouted;
  both are named in "out of scope" so they cannot arrive as "while we're here".
- **R6 — CI minutes and pin ping-pong.** Work on `feat/p4-gateway-sse`, first push with
  `git push -o ci.skip`, `git pull --rebase` before every local commit, batch any `ci/templates/**`
  change (it pulls all ten services in), and one fast-forward merge at the end.

## Mission alignment

The exam's functional pass is driven **through the CLI**: register → room → play/draw/uno → **live
feed** → 409 reconciliation, plus a headless bot (`docs/final/consigna.md`, north-star §"What the
exam requires"). P3 proved the loop exists; P4 is what makes it demonstrable — a single URL the
faculty can point at, a feed that shows events as they happen instead of a diff of two polls, and a
bot that plays the game while the presenter talks. It also completes an architectural promise the
first three phases could only write down: the gateway is the single point holding client connections
(Architecture §1.3) and the only holder of a session-validation key, which is what lets
`CHANGELOG-design.md` §8.9 and §8.10 close instead of accumulating.
