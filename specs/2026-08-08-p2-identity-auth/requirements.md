# Requirements — P2: Identity for Real

> Second phase of the final-delivery program
> ([`../2026-07-26-final-delivery-northstar/`](../2026-07-26-final-delivery-northstar/)). P1 made
> "empty cluster → platform Healthy" a boring drill; P2 puts the first **real service** on top of
> it. Every later flow authenticates through identity, so it goes first — and it is the service
> already fully wired to the pipeline.

## Objective

`identity` stops being an in-memory placeholder: accounts live in Postgres, sessions are JWTs
backed by Redis, a second login kills the first one (the domain non-negotiable), and the Client
CLI drives `register` / `login` / `logout` / `whoami` / `seed` against a cluster deployed from
empty — images pulled from the registry, secrets decrypted, schema migrated, with no manual step.

## Locked decisions (session 2026-08-08)

| # | Decision | Chosen |
|---|----------|--------|
| E1 | Secrets on a fresh cluster | `install.sh` **restores the sealed-secrets private key** from an operator-held backup outside the repo; SealedSecrets stay committed in `gitops/` and decrypt on any new cluster |
| E2 | Images on a fresh cluster | GitLab **deploy token** (`read_registry`) as an `imagePullSecret`, and the digest pin **decoupled from `$ARGOCD_SERVER`** so `main` always commits the last built digest into the overlay |
| E3 | Single-active-session reach | identity publishes to Redis pub/sub **and** Kafka now; the SSE-owning subscriber (gateway) lands in P4. P2 proves old-token → 401 plus the message observed on both transports |
| E4 | HTTP surface | Architecture paths `POST /auth/register\|login\|logout`, `GET /auth/whoami` (+ `/health`, `/metrics`); the CLI still targets identity directly by NodePort — the gateway becomes the entry point in P4 |
| E5 | Token model | **HS256 JWT, access-only** (`sub`, `sid`, `exp`, ~1h) validated locally + revocation checked against `session:{sessionId}` in Redis. `/auth/refresh` is documented as degradable, not built |
| E6 | CI smoke dependencies | `integration-staging:identity` applies an **ephemeral `postgres` + `redis` pod** inside its kind cluster before the Argo app — job stays self-contained, pipeline shape unchanged |
| E7 | Event publish failure | **Best-effort**: the DB invalidation is the source of truth; a failed Redis/Kafka publish never fails the login — it increments a counter and logs WARN |
| E8 | Schema creation | **Idempotent migration at service startup** under a Postgres advisory lock, before the server listens |

## In scope

- **`services/identity`** rewritten as a real service: Postgres-backed `players` / `sessions`,
  scrypt-hashed credentials, JWT issuance, Redis session cache, atomic single-active-session
  replacement, `SessionInvalidated` on Redis pub/sub + Kafka, `/metrics`, startup migration.
- **Per-service DB credentials**: a CNPG-managed `identity` role owning the `identity` database,
  its password delivered as a SealedSecret (closes the P1 "owned by the bootstrap `app` role" gap).
- **`gitops/bootstrap/install.sh`**: sealing-key restore (E1) and the registry pull secret, so one
  command still stands up everything on a cluster that has never existed before.
- **The service app-of-apps gains what P1 gave the platform**: a `targetRevision` that cascades to
  the child Applications, so a drill cluster can track `feat/p2-identity-auth` instead of `main`
  (without it, no P2 drill can ever test P2's own code), plus a knob for which environments get
  registered at all.
- **CI**: digest pin independent of a live cluster (E2); `integration-staging:identity` extended to
  the real auth flow against ephemeral Postgres + Redis (E6). No new stage, no new job kind.
- **Client CLI**: `/auth/*` mapping, real `logout`, `seed --count N [--prefix p]`, all conforming to
  `Client-Checkpoint.md` §5.A and the §6 JSON-lines output contract.
- **Docs**: README command→endpoint mapping and seeding procedure; `CHANGELOG-design.md` deltas for
  the route change, the two REST endpoints the catalog does not list (`/auth/register`,
  `/auth/whoami` — the architecture assumes registration and gRPC `ValidateToken`), and the omitted
  `email` / `ip_address` columns. `docs/architecture` itself stays immutable.

## Out of scope (→ later phases)

- **Gateway as entry point, SSE, live connection kill** (P4) — P2 emits the invalidation signal and
  proves it on the wire; nobody owns a long-lived connection yet.
- **`/auth/refresh` and refresh tokens** (E5) — documented as a deliberate gap in the README.
- **Per-user rate limiting** (architecture §5.4 L2, `ratelimit:*`) — it is a gateway-side concern
  and has no consumer until P4.
- **The other nine placeholders' ImagePullBackOff.** The pull-secret + digest-pin mechanism P2
  builds is generic, but only identity gets pinned (change detection). They are replaced by their
  own phases; if a fully-green app tree is needed earlier, a one-off digest pin covers them.
- **`unoarena-production` as a live environment.** The production overlays keep their pinned
  digests as the promotion-target shape (P0 already proved promotion end to end), but P2 seals no
  production secrets and stands up no second database — a registered `identity-production` would
  crash-loop for want of them. The new environments knob leaves it unregistered by default;
  whether the demo cluster registers it at all is P9's call.
- **EKS rehearsal.** Drills run on local kind; AWS hours are spent on rehearsals only (P9, and the
  P1 runbook already covers the cycle).
- **Grafana dashboards / business-metric panels** (P8) — P2 exposes and scrapes the metrics.

## Acceptance criteria

- **AC-P2.1 — Accounts are real.** `register` persists to the `identity` database with a hashed
  password (no plaintext, no in-memory map anywhere); after `kubectl delete pod` the same account
  still logs in.
- **AC-P2.2 — Sessions are real.** `login` returns an HS256 JWT carrying `sub`/`sid`/`exp`;
  `whoami` accepts it, `logout` invalidates it and the same token then returns 401.
- **AC-P2.3 — Single-active-session.** A second `login` for the same user makes the first token
  return 401; the first `sessions` row flips to `is_active=false` in the same transaction that
  inserts the new one; a message is observed on Redis `session:invalidated:{playerId}` **and** on
  the Kafka topic `identity.session-events`.
- **AC-P2.4 — Degradation is visible, not fatal.** With Kafka scaled to zero,
  `register`/`login`/`whoami`/`logout` all still succeed; the failure shows up as
  `identity_session_event_publish_failures_total` and a WARN log line carrying the `correlationId`.
- **AC-P2.5 — CLI conformance.** `register`, `login`, `logout`, `whoami` and
  `seed --count N --prefix p` run against the deployed service and exit non-zero on failure. Every
  `--json` line carries the **full §6 field set** — `ts`, `action`, `room`, `player`, `latency_ms`,
  `result`, `error_code`, `seq`, `correlationId` — with `null` where a field does not apply (today
  the CLI omits four of them). `seed` is re-runnable: existing accounts are ensured, not errors.
- **AC-P2.6 — Empty cluster, one command.** From no cluster at all, `install.sh` (with the sealing
  key and repo token) brings the platform **and** `identity-staging` to `Synced/Healthy`: image
  pulled from the private registry by digest, SealedSecret decrypted, schema migrated, `/health`
  green — zero manual steps. Re-running is a no-op.
- **AC-P2.7 — Pipeline stays green and unchanged in shape.** The `main` pipeline after the merge is
  green; `integration-staging:identity` now drives register → login → whoami → second login → 401 →
  logout against real Postgres and Redis, with no new stage and no new job.
- **AC-P2.8 — Instrumented from day one.** `/metrics` serves the identity counters
  (registrations, logins by result, supersessions, publish failures, request duration) and a
  `ServiceMonitor` makes Prometheus scrape them in-cluster.

## Behaviour contract (edge cases)

- **Lost sealing key.** If the key backup is gone, every committed SealedSecret is undecryptable.
  The recovery procedure (re-seal from operator-held plaintext, one commit) is documented next to
  the restore step, and the key file is git-ignored.
- **Dependencies not ready.** If Postgres is unreachable at startup, the migration fails and the pod
  stays not-ready — it must never serve traffic against a missing schema. Redis unreachable does
  **not** degrade to signature-only validation (that would accept revoked tokens): revocation falls
  back to reading `sessions` in Postgres, which is the architecture's documented introspection
  fallback. Slower, still correct, counted as a cache miss.
- **Concurrent logins for the same user.** The login transaction locks the `players` row before
  touching `sessions`, so concurrent logins serialize instead of colliding; the last commit is the
  active session. The partial unique index is the backstop that makes two `is_active` rows
  impossible even if that ordering is ever broken.
- **Duplicate `register`.** Returns 409, never a 500 and never a second account (unique constraint
  is the authority, not a read-then-write check).
- **Token from a previous cluster.** A JWT signed with another cluster's secret fails signature
  validation → 401, not a 500.
- **`seed --count N`** is idempotent by name: an existing user is re-logged-in and reported `ok`,
  so N parallel bots always get N usable credentials.
