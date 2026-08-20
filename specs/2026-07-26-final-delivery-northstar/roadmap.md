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

## P5 — Async spine: outbox relay + timers — **SHIPPED 2026-08-12**

Triad + closure: [`../2026-08-11-p5-async-spine/`](../2026-08-11-p5-async-spine/) — decisions E1–E8,
evidence in [`validation.md`](../2026-08-11-p5-async-spine/validation.md) and
[`ESTADO-FINAL.md`](../2026-08-11-p5-async-spine/ESTADO-FINAL.md); design deltas in
`CHANGELOG-design.md` §10.

**The outbox drains and time passes on its own.** `published_at IS NULL` reached 0 for the first
time since P3; a game where a player walks away ends by itself after three lapsed turns; a room
nobody joined closes on the clock. Two placeholders became real, so five of ten deployables are now
fully wired.

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

### Remotes — which one is the delivery

`gitlab` is the delivery: project `83816735`, the only remote CI and Argo read from, and the one
"push the branch" below means. It authenticates over HTTPS through a local credential helper
(`credential.https://gitlab.com.helper` in `.git/config`) that reads the PAT from
`~/.amicro_gitlab_token` at call time, so no token is ever written into a remote URL, the config or
the reflog. Rotating or revoking it is one file, and nothing in the repo changes.

`origin` is a **mirror** of the teammate's GitHub repo over SSH, kept in sync only when asked. It
runs no CI for this program, and its `main` is unprotected — never treat a green GitHub state as
evidence.

`git fetch gitlab` now gives real tracking refs (`gitlab/main`). Before the remote existed,
`refs/remotes/origin/*` held stale GitLab state while `origin` already pointed at GitHub, so
"is this branch pushed?" had to be answered from the API. Fetch first, then trust the refs.

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
   `POST /api/v4/projects/83816735/pipeline?ref=main`. That is also the *widest* run available:
   `rules:changes` is always true for a pipeline that did not come from a push, so the ten
   placeholders' `changes`-gated manual deploy gates appear too (43 jobs, against 36 for a push that
   touched one service). Production stages stay `manual` either way. Dropping `[skip ci]` instead
   would burn a branch pipeline *and* a main one for the same commit. **Read a red closure run before believing it** — the base-image
   pull is a public-registry call and can fail for reasons the repo did not cause (P4's first
   attempt; the detail is in that phase's `validation.md`).

## P6 — Ranking, spectator, analytics — **SHIPPED 2026-08-13**

Triad + closure: [`../2026-08-12-p6-read-models/`](../2026-08-12-p6-read-models/) — decisions E1–E8,
evidence in [`validation.md`](../2026-08-12-p6-read-models/validation.md) and
[`ESTADO-FINAL.md`](../2026-08-12-p6-read-models/ESTADO-FINAL.md); design deltas in
`CHANGELOG-design.md` §11.

**The events are read.** Four consumers across three contexts: a finished casual game moves two
ratings, a stranger with a session watches a live game and sees no hand, and three projections
answer "how many, by whom, how did it end". **Nine of ten deployables are real** — only `tournament`
is still canned. P5's two open acceptance criteria were closed live in the same drill.

- Ships: ranking Elo consumer (casual-only, non-abandoned filter) with rating, history and
  leaderboard; spectator projection with the privacy boundary (public info only — no hands, ever)
  behind `spectate <roomId>`; analytics-workers CQRS projections + analytics-api reads.
- CLI: `spectate`, `rating`, `leaderboard`, `stats`.
- Depends on: P5 (events flowing).

### Handoff from P6 (2026-08-13)

What P7 inherits, and must not break:

- **`ce-type` is a reverse-DNS URI, not the event name.** The relay writes
  `com.unoarena.room.GameCompleted.v1`; the catalog's bare name lives in the body's `type`, and that
  is what the contract schema pins. **Classify on the body.** Comparing the header against a bare
  name skips every event while the service looks perfectly healthy — it cost P6 a drill.
- **A room's log is split across two topics with no ordering between them.** Per-room ordering is a
  per-*topic* guarantee, so a lifecycle event can overtake an earlier public one from the same room.
  Any consumer reading both needs a dedup **set** (never a high-water mark) and a sticky terminal
  state. Deltas §11.2 and §11.3; there are property tests for both.
- **The three consumer groups are `ranking-elo`, `spectator-view` and `analytics-projections`**, each
  reading `earliest`, each committing by hand after its transaction. A tournament saga adds a fourth
  — it must not join an existing one, per architecture §7.2.
- **`consumed_events` is the pattern, keyed `(source, event_key)` with `event_key` = `ce-id`.** Live
  proof: a full replay of the topic history — **216 events redelivered, 216 deduped, every
  projection count byte-identical**.
- **The bracket store does NOT exist, deliberately** (D4). A table nobody writes is the dead-code
  shape the drill lessons name. P7 adds it with its writer, into a schema `analytics-workers`
  already owns and `analytics-api` already knows how to read.
- **A background consumer must retry its own startup for ever.** kafkajs and confluent-kafka both
  exhaust their internal retries during a cold start, and a `.catch` that only logs leaves a
  `Healthy` pod with no consumer. Pair every projection counter with a `*_consumer_starts_total`, and
  keep the lag read in its own `try` — a mislabelled gauge stopped P6's projections for 326
  consecutive loops from *inside* the poll loop. Delta §11.11.
- **Two database postures, on purpose.** A service that owns its schema exits when it cannot migrate
  (identity's posture; `ranking` and `analytics-workers` follow it, so 5–6 restarts on a cold start
  are expected and not a defect). A service that only reads someone else's connects lazily and
  answers 503. Delta §11.12.
- **Nine of ten deployables are real.** `tournament` alone carries `digest: ""` and sits
  `ImagePullBackOff` — that is its normal state, not a drill regression.
- **Ranking cannot be scaled past one replica, and P7 will be editing it.** Architecture §4 has it
  consuming `TournamentCompleted` for the placement rating, so this matters directly: applying a
  rating is a read-modify-write (read the current value, compute a delta, write it back) inside one
  transaction, which at READ COMMITTED does not stop a second consumer reading the same value first.
  Two replicas lose updates. `analytics-workers` is the contrast — every write there is an atomic
  upsert, so it *would* scale. Add placement rating the same way Elo is written, or make both safe;
  do not assume the replica count is a knob.
- **`deploy-staging` must `needs:` the BUILD, not just `deliver`.** `$IMAGE`/`$IMAGE_DIGEST` come
  from the build's dotenv and do not chain through `deliver`. Copy the stub's `needs` and the pin
  writes `repository: ""` into the overlay **and the job goes green**.

### Handoff from P5 (2026-08-12)

What P6 inherits, and must not break:

- **Both topics are flowing and the envelope is fixed.** `room.public.events` and
  `room.lifecycle.events` carry CloudEvents in **binary** mode: metadata in `ce-*` headers, the
  domain event in the body. `ce-id` is `{roomId}:{sequenceNumber}` — the log's primary key — so
  **that pair is the dedup key** every consumer should use. At-least-once is real, not theoretical:
  the relay publishes before it marks, so a crash redelivers.
- **The body is `publicPayload(event)` plus `roomId` and `sequenceNumber`.** No wrapper to unwrap.
  The privacy filter has been applied *before* the row was written, in the same transaction as the
  event — the spectator boundary P6 builds on is already true on the wire, and `grep -c seed` over
  both topics returning 0 is a drill check, not an aspiration.
- **Per-room ordering holds because the relay drains in `id` order with `roomId` as the partition
  key.** A consumer that parallelises across partitions keeps it; one that re-orders within a room
  breaks a guarantee nothing else will restore.
- **`roomType` on the wire is the Kotlin enum name** (`CASUAL`, `TOURNAMENT`), not the catalog's
  `Casual`. Ranking filters on it. The contract schema says so; `CHANGELOG-design.md` §10.5 records why.
- **The contract check now validates a producer-generated sample.** Adding a consumer means adding
  its required fields to `CONSUMER_REQUIRED` in `ci/contracts/validate.py`, and the schema has
  `additionalProperties: false` on purpose — a new field is a deliberate two-file change, which is
  what makes it a leak detector.
- **`consumed_events` already exists** in room-gameplay's schema, keyed `(source, event_key)`, and is
  the pattern P5 did *not* need but P6 does: it is how a Kafka consumer is idempotent.
- **Five of ten deployables are real.** `ranking`, `spectator`, `analytics-workers`, `analytics-api`
  and `tournament` are the canned placeholders left; they carry `digest: ""` and sit
  `ImagePullBackOff`, which is their normal state and not a drill regression.
- **The two Go workers are the shape to copy** for any new poller/consumer: health that reports only
  that the process is alive (delta 10.11), a success *counter* beside every gauge, and backoff on the
  loop rather than a crash. Do not add a shared Go module without solving the build context first —
  kaniko builds each service from its own directory.

## P7 — Tournaments — **SHIPPED 2026-08-18**

Triad + closure: [`../2026-08-16-p7-tournaments/`](../2026-08-16-p7-tournaments/) — decisions E1–E4,
evidence in [`validation.md`](../2026-08-16-p7-tournaments/validation.md) and
[`ESTADO-FINAL.md`](../2026-08-16-p7-tournaments/ESTADO-FINAL.md); design deltas in
`CHANGELOG-design.md` §12.

**Ten of ten deployables are real.** The last placeholder is gone. Four `bot --tournament`
processes register, are drawn into rooms, play best-of-three matches and produce a champion; the
bracket is readable from analytics; a placement rating moves without touching Elo.

- Ships: tournament orchestrator (registration with a low configurable threshold, round generation,
  the `tournament-saga` consumer, a reconciler), best-of-three matches decided by the **room**
  (`MatchCompleted` + `advancingPlayers`), synchronous room provisioning through an internal
  ClusterIP endpoint, a source-agnostic outbox-relay running twice, the placement rating, the
  bracket read model, and two more contract pairs.
- CLI: `tournament register/status/bracket`, `bot --tournament`.
- Depends on: P5 (the spine), P6 (the read models).

### Handoff from P7 (2026-08-18)

What P8 inherits, and must not break:

- **Every service exposes `/metrics` already, and P8 should retrofit nothing.** The business
  counters exist since each service's first real phase. P7 added `tournament_*` (registrations,
  rounds started/completed, rooms provisioned, room results, consumer starts, skips by reason,
  contended commands) and `ranking_placement_updates_total`. The ≥3 business metrics the exam asks
  for can be picked from what is already collecting.
- **Pair every gauge with a success counter.** Third phase running with this rule; a gauge never
  `Set` reads 0, and 0 is usually the healthy value.
- **`tournament_commands_contended_total` is the one to watch.** It moving a little is a
  registration rush; a lot means the retry budget is too small for the contention.
- **Two relay Deployments, one image.** Dashboards must split `outboxrelay_*` **by pod or by
  topic** — the two instances publish to different topics and a summed panel hides one of them
  going idle.
- **A fresh cluster cannot show every class of defect.** P7's timer-worker outage came from a
  rolling upgrade: `envFrom` resolves at pod creation, so a Deployment does not pick up a key added
  to an existing Secret. The from-empty drill did not reproduce it, because secrets sync at wave −1.
  Drill both ways.
- **`consumed_events` is keyed `(source, event_key)` and there are now two sources per consumer in
  ranking.** A dedup table keyed on the event id alone would have collided the day P7 shipped.
- **Ten of ten deployables are real**, so `digest: ""` no longer appears anywhere and an
  `ImagePullBackOff` is now always a real failure — the "that placeholder is meant to look like
  that" exception is gone.
- **`gcr.io/distroless` intermittently rejects the shared runners.** Five occurrences across P6 and
  P7, including two consecutive retries of one job before the third passed. It is not the repo. For
  the exam, mirror the distroless bases into the project registry rather than trusting a retry.

## P8 — Observability consolidation (≥3 business metrics) — **SHIPPED 2026-08-20**

Triad + closure: [`../2026-08-18-p8-observability/`](../2026-08-18-p8-observability/) — decisions
E1–E4, evidence in [`validation.md`](../2026-08-18-p8-observability/validation.md); design deltas in
`CHANGELOG-design.md` §13. Operational half: [`docs/observability-runbook.md`](../../docs/observability-runbook.md).

**The exam's observability requirement is answered.** The same install that brings the system up
brings up its observability, and the consigna's three business metrics are named on the board
itself.

- Ships: three dashboards as committed JSON (business, golden signals, async spine), Grafana on
  NodePort **30081** with a sealed admin credential, Alertmanager plus **nine alert rules**, and
  **Loki + Alloy** so one `correlationId` is one query. Plus `check-dashboards.js`, which fails a
  panel whose metric no service declares.
- **Not what the line above predicted.** ServiceMonitors were already enabled on all ten services
  and `correlationId` was already propagated end to end — that half was done. What was missing was
  anything that *rendered* it: zero dashboards, zero alert rules, Grafana on ClusterIP behind a
  password the chart generated and nobody held.
- Only two services' instrumentation changed (`serviceLevelObjectives` on the two Kotlin timers) and
  three gained one log field. No new business counter.

### Handoff from P8 (2026-08-20)

What P9 inherits, and must not break:

- **The demo has a second URL now.** Grafana is `http://localhost:30081`, admin, password in
  `~/.amicro_secrets.env`. It is sealed, so it works on a cluster that has never existed — verified
  on the from-empty drill. Board UIDs are stable: `/d/unoarena-business` and friends.
- **From empty is ~12½ minutes to 24/24 Synced/Healthy** (`install.sh` returns at ~3 min), against
  P7's ~11 min for 19 apps. P8's five extra components cost about **90 seconds**. Both figures are
  on a warm host image cache; budget more on a cold one.
- **Read an async system one scrape interval late.** Twice in one drill a check run immediately
  after the action looked like a defect and was not: a champion with `tournaments_completed` still
  at 0, and a correlationId trace showing 3 services that was 5 a minute later. The inverse of P4's
  "a probe sent late measures the deadline".
- **Two of nine alert rules have been observed firing**, both by recreating a real outage. Seven are
  untested and listed as such. Do not describe the set as "covered".
- **Set a threshold from a measurement, not from a plausible production number.** Two rules shipped
  that could not fire at all: the lag threshold was higher than the total event volume of an entire
  demo (179 events). Found by the review pass, not by the drill.
- **A workload scaled to zero fires nothing.** Its scrape target disappears rather than reporting
  `up 0`. Do not "test" the alerts that way and conclude they are broken.
- **Loki is non-load-bearing and must stay that way** — with it down, all three boards and all nine
  rules still work. Verified by taking it away.

## P9 — Demo rehearsal + presentation

- Ships: timed empty-cluster runbook (the exact demo script), CLI functional pass (the faculty's
  test, self-run), presentation deck (final architecture + key decisions, from the ADRs and
  `docs/architecture/`), README final pass, 48h-before checklist, date coordinated with faculty,
  and at least one full rehearsal on a freshly created AWS cluster (R1).
- Depends on: everything shipped by then; rehearse at least twice.

### Carried into P9's checklist

- **Mirror the `gcr.io/distroless` bases into the project registry.** Five rejections of the GitLab
  shared runners across P6 and P7, including two consecutive retries of one job before the third
  passed. It is not the repo, and a retry is not a fix — the exam should not depend on a public
  registry's mood. A CI supply-chain change, which is why P8 left it alone.
- **Renew the `gitops-push-bot` CI token**, which expires **2026-09-30**.
- **Decide whether the ten app containers get resource requests/limits.** None of them declare
  either today, while every platform component does — so they are `BestEffort` QoS, first to be
  evicted under memory pressure, and saturation cannot be expressed as a percentage of a limit
  (P8's golden-signals board shows absolute bytes and says why). Academic on a single-node kind
  cluster; less so on the 2× t3.large EKS rehearsal, and a plausible thing for a grader to ask
  about.
- **`gradle check` runs no linter.** `tech-stack.md` §2 lists ktlint/detekt for `room-gameplay` and
  `tournament`, and neither `build.gradle.kts` applies either plugin, so `check` is just `test`.
  Either wire them or correct the table.

## Dependency sketch

```
P0 → P1 → P2 → P3 → P4 ═(casual gate)═> P5 → P6 → P7 → P9
                                          └────→ P8 ──↗
```
