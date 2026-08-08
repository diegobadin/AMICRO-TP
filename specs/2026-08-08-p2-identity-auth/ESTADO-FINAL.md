# ESTADO FINAL — P2: Identity for Real

> Closed 2026-08-08. All eight acceptance criteria green. `identity` is no longer a placeholder:
> accounts live in Postgres, sessions are JWTs, a second login kills the first one, and the whole
> slice is driven through the Client CLI against a cluster deployed from an empty kind.

## What shipped

- **`services/identity`** — Postgres-backed `players`/`sessions` with scrypt credentials, schema
  migrated by the service itself under an advisory lock, HS256 JWT sessions validated locally with
  a Redis revocation cache that falls back to Postgres, `POST /auth/register|login|logout` +
  `GET /auth/whoami`, `/metrics`, and `SessionInvalidated` published to Redis pub/sub (awaited) and
  Kafka (fire-and-forget). Ports and adapters throughout, so the test stage still needs no
  database.
- **Single-active-session as a constraint, not a convention** — a partial unique index on
  `sessions (player_id) where is_active`, with the login transaction locking the player row first
  so concurrent logins queue instead of colliding.
- **`gitops/secrets/`** — the sealed-secrets key now travels with the operator: `install.sh`
  restores it before the controller starts, so the SealedSecrets committed in git decrypt on any
  cluster that has never existed before. `seal.sh` regenerates every blob offline from the
  operator-held plaintexts.
- **Per-service database credentials** — CNPG manages an `identity` login role that owns the
  `identity` database (closes P1's "everything on the bootstrap `app` role" gap).
- **Images reach a fresh cluster** — deploy token `registry-pull-bot` (`read_registry` only) as a
  sealed pull secret, and the CI digest pin no longer depends on a live cluster, so the overlay
  always points at the last image built. P1's ImagePullBackOff on identity is closed.
- **`gitops/apps-root/`** — the 20 hand-written service Applications became one chart, which is
  what lets `targetRevision` cascade to the children: a drill cluster can finally test the branch's
  own code. It also decides which environments exist on a cluster (`staging` only, by default).
- **Client CLI** — `/auth/*`, real server-side `logout`, `seed --count N`, and the full §6 field set
  on every JSON line. `integration-staging:identity` drives register → login → whoami → stale
  `401` → logout → `401` → seed against an ephemeral Postgres and Redis, with `KAFKA_BROKERS`
  deliberately pointing nowhere so every pipeline re-proves the degradation contract.

## Evidence (validation.md has the full transcripts)

| AC | Result |
|----|--------|
| AC-P2.1 | Accounts survive pod restarts; stored credential is `scrypt$16384…`, never the password |
| AC-P2.2 | JWT carries `sub`/`sid`/`exp`; logout makes the same token `401` |
| AC-P2.3 | Old token `401`, one `superseded` row + one active, message observed on **both** Redis pub/sub and Kafka |
| AC-P2.4 | Kafka down → auth unaffected, failure counted and logged. Redis down → auth served from Postgres, and a revoked token still `401` (no fail-open) |
| AC-P2.5 | `SMOKE OK`: full CLI flow + double `seed --count 3`, §6 fields on every line |
| AC-P2.6 | Empty kind → 2027 s to platform + identity `Synced/Healthy`, image by digest, secrets decrypted, schema migrated, zero manual steps; re-run creates nothing |
| AC-P2.7 | Branch pipelines green through `deploy-staging`; no new stage, no new job |
| AC-P2.8 | Counters move as expected; Prometheus reports the `identity` target `up` |

## Deltas vs the plan (all recorded inline as they happened)

1. **F1 grew a phase.** The service app-of-apps had to become Helm-typed *before* anything else:
   its children were pinned to `main`, so no drill could ever have exercised P2's own code.
2. **The Topic Operator was enabled** so `identity.session-events` could be declared in git
   instead of auto-created by the broker (D9 turned out to require it).
3. **Logout also publishes `SessionInvalidated`.** The architecture describes the supersession
   path; a logged-out client whose stream stays open is the same bug, so it goes out on the same
   channel with `newSessionId: null`.
4. **Three defects the drills caught**, all fixed and all invisible to unit tests: service apps
   could not converge through the ServiceMonitor CRD race; `install.sh` stopped being idempotent
   once it restored the sealing key; and the JVM image builds raced Kaniko over
   `/tmp/hsperfdata_root` (pre-existing, and it took a wrong fix before the right one). Details
   in `validation.md`.

## Known gaps, owned by later phases

- **The live SSE kill has no consumer yet.** Identity emits the invalidation on both transports;
  the tier that owns long-lived connections lands in P4, room-gameplay's consumer in P3. Until
  then a superseded session dies at the next request — the belt-and-suspenders path the
  architecture already documents. Recorded in `CHANGELOG-design.md` §7.4.
- **`/auth/refresh` is not built** (§7.2 there): one short-lived JWT, re-login on expiry.
- **The other nine placeholders stay in ImagePullBackOff.** The pull-secret and digest-pin
  mechanism is generic, but only identity is pinned; each is replaced by its own phase.
- **`unoarena-production` is not a live environment** — pinned digests as the promotion shape, no
  sealed secrets, unregistered by default. P9 decides whether the demo cluster registers it.
- **Per-user rate limiting** (architecture §5.4 L2) belongs to the gateway phase.
- **Grafana still uses chart-default credentials** (inherited from P1, due in P4 with the ingress).

## Next

P3 (Uno engine + room-gameplay core) — its kickoff should start from the north-star roadmap. Two
things P2 hands it: `identity.session-events` is live and declared, so the `PlayerDisconnected`
consumer has a contract to bind to; and every service app now converges through CRD races, so
room-gameplay can ship its own ServiceMonitor from day one.
