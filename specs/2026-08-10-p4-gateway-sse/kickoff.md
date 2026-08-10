# Kickoff — P4: Gateway + Realtime Fan-out (SSE)

> The triad here was written and review-passed on 2026-08-10, in the session that closed P3.
> Implementation starts in a fresh session. This file is the bridge: everything the next session
> needs that is not already in `requirements.md` / `plan.md` / `validation.md`.

## Where things stand

- `main` = `ea1ab15` (P3 closure + the roadmap handoff). Branch `feat/p4-gateway-sse` exists and
  holds only these four files.
- `services/gateway/` is still the P0 placeholder: `handle(method, path)` with a canned
  `/health` and an empty `GET /rooms`. Its chart, Dockerfile, overlay and CI fragment exist —
  but the chart's `service.yaml` has **no** `type`/`nodePort` support and no ServiceMonitor, and
  `deploy-staging:gateway` is still the manual stub. F1 fixes all four by copying identity's.
- identity already publishes `SessionInvalidated` on **both** transports
  (`services/identity/src/events.ts`): Redis `session:invalidated:{playerId}` — written for this
  gateway and with **no subscriber until F2** — and Kafka `identity.session-events`, which
  room-gameplay already consumes. Nothing in identity has to change this phase.
- room-gameplay has **no Redis client and no Redis config** today (`Config.kt` carries
  `IDENTITY_JWT_SECRET`, DB and Kafka only). F3 adds both.
- The CLI's poll loop is a closure inside `interactive()` (`clients/cli/src/rooms.ts`) and every
  update funnels through one `adopt(next: GameView)`. F5 replaces the closure and **reshapes
  `adopt`** — frames are events, not whole views — so "re-wired, not rewritten" is true of `play`'s
  command handling and the board, not of `adopt` itself. Budget for that honestly.
- No kind cluster is guaranteed to be running. Recreate with
  `TARGET_REVISION=feat/p4-gateway-sse GITOPS_REPO_TOKEN="$(cat ~/.amicro_gitlab_token)" gitops/bootstrap/install.sh`.
  Expect ~35 min from empty on a cold image cache. **No `kind-cluster.yaml` change is needed this
  phase** — `30080` is already published, which is why the gateway takes that port and not a third.

## Copy identity, do not reinvent

| Concern | Where to copy from |
|---|---|
| Pure `handle`/route shape, testable without sockets | `services/identity/src/app.ts` |
| Redis client that fails fast instead of queueing | `services/identity/src/cache-redis.ts` (`enableOfflineQueue: false`, an `error` listener, every op swallows-and-counts) |
| JWT verify with `jose` | `services/identity/src/sessions.ts` `resolve()` |
| Metrics naming + where counters are incremented | `services/identity/src/metrics.ts` + `server.ts` |
| Chart: `imagePullSecrets`, `startupProbe`, ServiceMonitor, `service.type`/`nodePort` | `services/identity/chart/` |
| Sealed secret + digest pin + overlay shape | `gitops/apps/identity/overlays/staging/values.yaml`, `gitops/secrets/seal.sh` |
| A real `deploy-staging` job | `services/room-gameplay/.gitlab-ci.yml` |

## Traps this phase can walk into

- **`integration-staging:identity` must not be touched.** It builds its own Application with
  **inline** helm values (`service: {type: NodePort, nodePort: 30080}`), so flipping the *staging
  overlay* to `ClusterIP` does not affect it — as long as identity's routes and the CLI's
  `UNOARENA_API_URL` semantics stay put. If that job needs an edit, something went wrong in F6.
- **Redis stream ids are explicit.** `XADD room:{id}:events {seq}-0 …` only works while the room's
  sequence numbers are strictly increasing (they are — it is P3's PK). A retry that re-adds an
  existing id fails with `ERR The ID specified in XADD is equal or smaller`; that must be counted,
  not retried into a loop.
- **`XADD` goes *after* the commit, never inside the transaction.** Putting it inside would make a
  Redis outage roll back a legal move — the exact inversion of log-before-broadcast.
- **Publish `publicPayload(event)`, not `encodeEvent(event)`** (`Outbox.kt`). `GameStarted` and
  `DeckRecycled` carry the RNG seed; the raw encoding on a player-visible stream hands out the deck
  order. The `leaksPrivateData` test already exists — point it at the stream too.
- **Node 20 has no `EventSource`.** Do not add a polyfill dependency; D7's reader over
  `fetch`'s `ReadableStream` is smaller and can send `Authorization`, which `EventSource` cannot.
- **Read the baseline, then subscribe *from that seq*** (D15). Subscribing at the tail and reading
  the state afterwards silently drops whatever committed in between — invisible in a hand-played
  test, and a frozen board in a two-process drill.
- **Do not let the client do the player's job** (P3's drill lesson). The bot must *sometimes forget*
  to call Uno! (D13's `--forget-uno`), or the challenge mechanic is never exercised again.
- **Start the two drill processes simultaneously.** P3's convergence rule (lowest room id wins)
  depends on both sides applying it at the same time; starting one after the other hides races.
- **First push of the branch:** `git push -o ci.skip` (a new branch evaluates `rules:changes` as
  all-changed → a full 30-job pipeline for nothing). CI pushes digest-pin commits back to the
  branch, so `git pull --rebase` before every local commit.
- **Argo self-heals fault injection within seconds.** For the Redis-outage bite test, suspend both
  `unoarena-root` and the child app, or Argo scales Redis straight back up.

## Suggested first moves

1. `git checkout feat/p4-gateway-sse` and read the triad end to end.
2. F1 before anything clever: the gateway is a placeholder today, and getting it deployed, pinned,
   scraped and Healthy while it still does nothing removes every plumbing question from the phases
   that matter.
3. F2 and F3 are independent — the proxy and the publisher touch different repos' worth of code.
   Do F3 second anyway: F4 has nothing to read until events are in Redis.
4. Resist landing F6 early. Everything before it is additive and reversible; F6 is the one-way door
   (R3), and it should cross only when the proxy tests are green.

## Open questions for the implementer (not blocking)

- Whether the gateway's per-room tail should use `XREAD` on the raw stream (D4) or a consumer group
  per pod. Groups buy per-pod acknowledgement nobody needs here; pick `XREAD` unless F4 shows
  otherwise, and record it as a D-n either way.
- Where the membership check's result is cached, if at all (D10 re-reads `GET /rooms/{id}` on every
  subscribe — fine at demo scale, and a cache is a second source of truth about who is in a room).
- Whether `bot --casual` should share `play`'s interactive loop with a scripted input source or run
  its own loop. Decide in F7 when both shapes are concrete.
