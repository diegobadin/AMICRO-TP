# Roadmap — Final Delivery Program

> Phases ordered by de-risk: the empty-cluster mechanic and the casual loop are the exam's
> hardest gates, so they come first. Each phase ships deployable and gets its own dated triad
> spec when it starts. Grounded in [`north-star.md`](./north-star.md).

## P0 — DevOps checkpoint closure — **SHIPPED (2026-07-26)**

Ships: drills evidence, ADRs, manual prod gates. Triad:
[`../2026-07-26-prod-promotion-closure/`](../2026-07-26-prod-promotion-closure/) — see its
`ESTADO-FINAL.md` and the evidence table in `devops-checkpoint/README.md` §9.

## P1 — Platform infra from an empty cluster — **SHIPPED 2026-07-27**

Triad: [`../2026-07-26-p1-platform-infra/`](../2026-07-26-p1-platform-infra/) — all six ACs
green (kind drills, EKS rehearsal with empty sweep, pipeline unaffected); evidence in its
`validation.md`, closure in its `ESTADO-FINAL.md`.

- Ships: Kafka (single-broker Strimzi or Redpanda), Postgres, Redis as GitOps apps under the
  app-of-apps; bootstrap split into *create-cluster* (kind for rehearsal, EKS on the educational
  AWS account for the exam — reproducible IaC, N2/R1) and *install-into-kubeconfig* (works
  against either); observability stack (Prometheus + Grafana) installed here, dashboards later.
- Why first: "empty cluster → everything Healthy" is the demo's opening move; it must become a
  boring, repeatable drill before any real service exists.
- Infra it debuts: everything. Depends on: P0 pipeline.

## P2 — Identity for real — **SHIPPED 2026-08-08**

Triad: [`../2026-08-08-p2-identity-auth/`](../2026-08-08-p2-identity-auth/) — all eight ACs green
(empty-cluster drill, both invalidation transports observed, degradation drill); evidence in its
`validation.md`, closure in its `ESTADO-FINAL.md`. The live SSE kill waits for P4's gateway, which
is where the connections will be.

- Ships: Postgres-backed accounts, JWT sessions, single-active-session kill via Redis pub/sub
  (the domain non-negotiable), `logout`, `seed --count N`.
- CLI: `login`/`logout`/`seed` join the existing `register`/`whoami`.
- Why now: every other flow authenticates through it; smallest real service, already fully wired.
- Depends on: P1 (Postgres, Redis).

## P3 — Uno engine + room-gameplay core — **SHIPPED 2026-08-10**

Triad + closure: [`../2026-08-08-p3-room-gameplay/`](../2026-08-08-p3-room-gameplay/) — decisions
E1–E8, all nine ACs green, evidence in
[`validation.md`](../2026-08-08-p3-room-gameplay/validation.md) and
[`ESTADO-FINAL.md`](../2026-08-08-p3-room-gameplay/ESTADO-FINAL.md).

**The casual gate is open**: two CLI processes played a complete casual game of Uno to a winner
against a cluster deployed from empty — wild colour declaration, draws, an Uno! call and a
successful challenge, closing a 245-event log with `GameCompleted` → `RoomCompleted`. P4 now
delivers the *quality* of that experience (a real live feed, one entry point, a bot) rather than
its existence.

- Ships (a): rooms — create/join/list/leave, membership REST per Architecture §2.3.1, Postgres
  event store scaffold. (b): the game — pure Uno rules engine (Kotlin module, property +
  log-replay tests), moves collection with sequence numbers / `If-Match` → `412`/`409`,
  log-before-broadcast into the event store + outbox table.
- CLI: `room create/join/list/leave`, `play --casual` entry.
- Why now: the biggest unknown (R2); everything downstream consumes its events.
- Depends on: P2 (auth), P1 (Postgres).

## P4 — Realtime fan-out (SSE) — **SHIPPED 2026-08-11**

Triad + closure: [`../2026-08-10-p4-gateway-sse/`](../2026-08-10-p4-gateway-sse/) — decisions E1–E6,
all ten ACs green, evidence in
[`validation.md`](../2026-08-10-p4-gateway-sse/validation.md) and
[`ESTADO-FINAL.md`](../2026-08-10-p4-gateway-sse/ESTADO-FINAL.md).

**The cluster has one door.** The gateway owns `30080`; identity and room-gameplay are `ClusterIP`
and room-gameplay holds no signing key — it trusts headers the gateway builds from scratch, and a
forged `X-Player-Id` through the gateway acts as the token's subject, not the forged one. The
polling loop is gone: 163 committed events produced 163 stream frames whose ids **are** the
sequence numbers, and two CLI processes played a full game to `GameCompleted` → `RoomCompleted`
against a cluster deployed from empty in 9 minutes. `bot --casual` plays the same game headless.

- Ships: room state → Redis Streams delta patches; gateway/broadcaster SSE tier; per-room
  ordering, reconnect + resync from last seq.
- CLI: interactive `play` view (live feed, turn board, play-by-index, wild colors, `draw`/`uno`/
  `challenge`/`pass`/`state`), 409 reconciliation surfaced; `bot --casual` random-valid-move.
- Gate: **met in P3** — a full casual game is playable through the CLI against a from-empty
  cluster. P4 replaces the polling stand-in with the real stream, collapses the two NodePorts into
  one gateway (removing the JWT secret room-gameplay and identity currently share), and adds the
  bot.
- Depends on: P3.

### Handoff from P3 (2026-08-10)

What P4 inherits, and must not break:

- **Two NodePorts and two CLI targets.** identity owns `30080`, room-gameplay `30081`
  (`UNOARENA_API_URL` + `UNOARENA_ROOMS_URL`). Both are published by `gitops/bootstrap/kind-cluster.yaml`
  — **kind port mappings are fixed at cluster creation**, so a gateway on a third port needs the
  cluster recreated. Collapsing them is P4's job; `CHANGELOG-design.md` §8.10 records the shape.
- **room-gameplay validates identity's JWT with a shared HS256 secret** (`CHANGELOG-design.md`
  §8.9, decision E1). The hand-off P4 completes: the gateway validates, room-gameplay trusts
  `X-Player-Id`/`X-Session-Id` inside the trust boundary and drops the secret from
  `room-gameplay-secrets`.
- **The polling contract SSE replaces.** `GET /rooms/{id}/games/{n}` with `If-None-Match` → `304`
  is live and is the documented *resync* read (E4). P4 should keep it exactly as it is and add the
  stream beside it — the CLI's poll loop already sits behind an interface, so `play` is re-wired,
  not rewritten (`clients/cli/src/rooms.ts`).
- **The live feed is currently inferred from state diffs** (`clients/cli/src/board.ts` `feed()`).
  It collapses two events inside one poll interval into one line. P4 deletes it in favour of the
  real event stream.
- **Nothing drains the outbox yet** — every row is `published_at IS NULL` by design. If P4's SSE
  tier reads from Redis (roadmap line above) rather than from the outbox, the outbox stays P5's.
- **`bot --casual` needs the §6 output contract**: one JSON line per action with the full field
  set, plus a final summary line and a non-zero exit on failure (`clients/cli/README.md`).
  `scripts/casual-drill.js` is the closest thing that exists and is a good starting shape.

Questions the P4 interview settled (E1–E6, all five answered — kept for the reasoning, not as open
work): the SSE tier reads **Redis Streams** written by room-gameplay after commit, so P4 never
depended on P5; the existing `gateway` placeholder was **made real** rather than an 11th deployable;
`UNOARENA_ROOMS_URL` was **removed outright** and bypass drills use `kubectl port-forward`; `bot` is
a **CLI subcommand** in the same image; and a superseded session is killed through the **Redis
pub/sub** half, which the gateway now consumes.

1. **Where the SSE tier gets its events** — room-gameplay pushing delta patches to Redis Streams
   after commit (the roadmap's line), the gateway tailing the outbox, or the gateway subscribing to
   Kafka once P5 exists. This is the phase's real architectural choice.
2. **Whether the gateway is a new deployable or the existing `gateway` placeholder made real** —
   the placeholder, its chart and its CI fragment already exist.
3. **What happens to `UNOARENA_ROOMS_URL`** — removed outright, or kept working for one phase so a
   drill can bypass the gateway.
4. **Whether `bot` is a CLI subcommand or its own image** — §5.E says N parallel `bot` processes,
   and the Dockerfile already builds the whole CLI.
5. **How a superseded session kills a live stream** — identity publishes `SessionInvalidated` on
   Redis pub/sub *and* Kafka today; the gateway is the consumer the Redis half was written for
   (Architecture §5.5), and the CLI's `session_superseded` notice (§5.A) becomes reachable.

## P5 — Async spine: outbox relay + timers — **next**

- Ships: outbox-relay (Go) tailing the outbox to Kafka (`GameCompleted` for real, then the rest
  of the catalog); timer-worker (Go) driving the durable deadlines — Uno! 5s window, 60s
  reconnection, turn timeout, room expiry — as commands back into room-gameplay.
- Contract seam graduates: the CI schema check now validates the real producer.
- Depends on: P4 gate, P1 (Kafka).

### Handoff from P4 (2026-08-11)

What P5 inherits, and must not break:

- **One entry point.** The gateway owns `30080` and is the only `NodePort`; identity and
  room-gameplay are `ClusterIP`. Anything P5 exposes goes *through* the gateway's route table
  (`services/gateway/src/app.ts`) or stays inside the cluster. `30081` is still mapped by
  `kind-cluster.yaml` and deliberately unused, so no cluster has to be recreated.
- **room-gameplay trusts `X-Player-Id` / `X-Session-Id` and validates nothing.** It has no signing
  key. A timer-worker sending commands back into it is *inside* the trust boundary and must set both
  headers — and must never be reachable from outside.
- **The outbox is still full and still nobody's.** Every row is `published_at IS NULL`. The Redis
  stream P4 added is a **second, transient** path for the live feed only — the relay is not a
  replacement for it and must not be built on it. `room_events` is the archive; Redis holds 6 hours.
- **Deadlines are evaluated only when a command arrives.** This is the gap P5's timer worker exists
  to close, and it is load-bearing in the drills: a half-finished run leaves a `WAITING` room whose
  turn is parked on somebody who will never move, and nothing recovers. Three false alarms in P4
  came from exactly that.
- **`publicPayload(event)` is the privacy filter both transports share** (`Outbox.kt`). Whatever the
  relay publishes to Kafka must keep going through it: `GameStarted` and `DeckRecycled` carry the RNG
  seed, and a seed on a public topic is the deck order.
- **A phase that changes a service must get that service rebuilt.** P4 nearly drilled against a
  room-gameplay image built from `main`; the pin is per-service and change detection is path-based,
  so push once per service and check the digest actually moved.

### Closing a phase — the four steps, and the two that surprise people

1. **Push the branch before anything else.** First push with `git push -o ci.skip` (a new branch
   evaluates `rules:changes` as all-changed, so it would otherwise run all ten services), then one
   push per changed service so change detection runs that service's jobs alone. CI pins the digest
   back, so `git pull --rebase` before every local commit.
2. **Drill from empty** on the branch: `kind delete cluster` → `TARGET_REVISION=<branch>
   install.sh`. Leave the cluster tracking the branch only while the branch exists — **repoint both
   roots at `main` (`kubectl apply -f gitops/root-app.yaml -f gitops/platform-root.yaml`) before the
   branch is deleted**, or Argo loses the revision it is syncing from. The children pick the new
   revision up through the root's helm parameter within a poll.
3. **Write the closure**, FF-merge, push `main`.
4. **Ask for the closure pipeline.** The closure commit is docs and carries `[skip ci]` — which is
   right for the branch, but it means the push to `main` produces a **skipped** pipeline and the
   "green `main` pipeline" AC has no run behind it. Trigger it deliberately:
   `POST /api/v4/projects/83816735/pipeline?ref=main`. On `main` every job's
   `if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH` rule matches, so the full set runs and the
   production stages stay `manual`. Dropping `[skip ci]` instead would burn a branch pipeline *and*
   a main one for the same commit. **Read a red closure run before believing it** — the base-image
   pull is a public-registry call and can fail for reasons the repo did not cause (P4's first
   attempt; the detail is in that phase's `validation.md`).

## P6 — Ranking, spectator, analytics

- Ships: ranking Elo consumer (casual-only, non-abandoned filter); spectator projection with the
  privacy boundary (public info only — no hands, ever) behind `spectate <roomId>`;
  analytics-workers CQRS projections + analytics-api reads (stats, brackets store).
- CLI: `spectate`.
- Depends on: P5 (events flowing).

## P7 — Tournaments

- Ships: tournament orchestrator — registration with a **low configurable test threshold**,
  bracket state machine, round saga on `room.completed`, room provisioning through the same
  room-gameplay API, best-of-three series, top-3 advancement, deterministic tiebreaks.
- CLI: `tournament register/status`, `bot --tournament` registers and plays whatever it is
  assigned, rounds end to end.
- Degradable per N3: if the clock wins, what works/what doesn't is documented in the README
  without touching P1–P6.
- Depends on: P5 (saga events), P6 (bracket read model useful but not blocking).

## P8 — Observability consolidation (≥3 business metrics)

- Ships: ServiceMonitors for every real service, one Grafana dashboard with the business metrics
  (candidates: games completed/min, active rooms, moves/s, registered users, Elo updates/min —
  pick ≥3), correlationId traceable CLI → gateway → service logs.
- Small by design: the stack exists since P1 and services instrumented as they shipped.
- Depends on: whatever shipped; runs in parallel with P7 if needed.

## P9 — Demo rehearsal + presentation

- Ships: timed empty-cluster runbook (the exact demo script), CLI functional pass (the faculty's
  test, self-run), presentation deck (final architecture + key decisions, from the ADRs and
  `docs/architecture/`), README final pass, 48h-before checklist, date coordinated with faculty,
  and at least one full rehearsal on a freshly created AWS cluster (R1).
- Depends on: everything shipped by then; rehearse at least twice.

## Dependency sketch

```
P0 → P1 → P2 → P3 → P4 ═(casual gate)═> P5 → P6 → P7 → P9
                                          └────→ P8 ──↗
```
