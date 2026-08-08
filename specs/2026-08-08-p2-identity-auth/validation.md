# Validation — P2: Identity for Real

> One executable check per acceptance criterion. Phase gates and drill transcripts get recorded
> here as F1–F9 run.

## Verification matrix

| AC | Check | Pass condition |
|----|-------|----------------|
| AC-P2.1 | `register` a user, `kubectl -n unoarena-staging delete pod -l app=identity`, then `login` as the same user. | Login succeeds after the restart; `select password_hash from players` shows a `scrypt$…` string, never the password. |
| AC-P2.2 | `login` → decode the JWT header/claims → `whoami` → `logout` → `whoami` with the same token. | Claims carry `sub`/`sid`/`exp`; `whoami` 200 before logout, 401 after. |
| AC-P2.3 | Two `login`s for the same user from two session files, with `SUBSCRIBE` and a Kafka consumer attached. | First token → 401; one `is_active` row for that player; one message on `session:invalidated:{playerId}` and one on `identity.session-events`. |
| AC-P2.4 | Point identity at an unreachable broker (`kubectl -n unoarena-staging set env deploy/identity KAFKA_BROKERS=black.hole:9092`), run the full CLI flow, then repeat with `REDIS_URL` broken. | Every command still exits 0; `identity_session_event_publish_failures_total{transport=…}` increases; a WARN carries the `correlationId`. With Redis broken, a **revoked token still returns 401** (Postgres fallback, D6) — the drill fails if it returns 200. |
| AC-P2.5 | `seed --count 5 --prefix drill --json`, twice, then `whoami` with one seeded token. | 5 JSON lines per run with the §6 fields; second run reports `ok` for the same 5 names; the token authenticates. |
| AC-P2.6 | `kind delete cluster --name unoarena-staging`, then `install.sh` with the sealing key + repo token, then the probes below. | Platform **and** `identity-staging` `Synced/Healthy`; image pulled by digest; no manual step; re-run is a no-op. |
| AC-P2.7 | The `main` pipeline of the P2 merge. | Green; `integration-staging:identity` runs the extended flow against real PG+Redis; stage list identical to P1's. |
| AC-P2.8 | `curl /metrics` through a port-forward + Prometheus target list. | Identity counters present and moving; the `identity` target is `up` in Prometheus. |

## Probes (used by AC-P2.6)

```bash
# Argo state
argocd app list -o wide            # unoarena-platform-root + identity-staging Synced/Healthy
kubectl -n unoarena-staging get pods,secrets

# Secrets actually decrypted (never print the value)
kubectl -n unoarena-staging get secret identity-secrets -o jsonpath='{.data}' | tr ',' '\n' | cut -d'"' -f2

# Per-service DB credentials
kubectl -n postgres exec unoarena-pg-1 -- psql -U postgres -c "\du"    # identity role present
kubectl -n unoarena-staging exec deploy/identity -- node -e "…"        # or the app's own /health

# Image pulled by digest, not tag
kubectl -n unoarena-staging get pod -l app=identity \
  -o jsonpath='{.items[0].status.containerStatuses[0].imageID}'

# End-to-end through the CLI (the exam's own harness)
export UNOARENA_API_URL=http://localhost:30080 UNOARENA_SESSION=/tmp/p2.json
node clients/cli/dist/cli.js register --user drill-1 --pass pw --json
node clients/cli/dist/cli.js whoami --json
node clients/cli/dist/cli.js logout --json
```

## Single-active-session drill (AC-P2.3)

```bash
# terminal 1 — watch the live-kill channel identity publishes on
kubectl -n redis exec -it deploy/redis -- redis-cli PSUBSCRIBE 'session:invalidated:*'
# terminal 2 — watch the Kafka topic room-gameplay will consume in P3
kubectl -n kafka run kcat -it --rm --restart=Never --image=edenhill/kcat:1.7.1 -- \
  -b unoarena-kafka-bootstrap:9092 -t identity.session-events -C

# terminal 3 — same user, two sessions
UNOARENA_SESSION=/tmp/a.json node clients/cli/dist/cli.js login --user drill-1 --pass pw --json
UNOARENA_SESSION=/tmp/b.json node clients/cli/dist/cli.js login --user drill-1 --pass pw --json
UNOARENA_SESSION=/tmp/a.json node clients/cli/dist/cli.js whoami --json   # expect 401
kubectl -n postgres exec unoarena-pg-1 -- psql -U postgres -d identity \
  -c "select session_id,is_active,invalidation_reason from sessions order by created_at"
```

## Regression gate for F1 (app-of-apps refactor)

Collapsing 20 `Application` files into one template must not drop a service. Capture the list
before and after on the same cluster and diff it — reading the template is not evidence:

```bash
argocd app list -o name | sort > /tmp/apps-before.txt   # on main
# … F1 applied, same cluster …
argocd app list -o name | sort | diff /tmp/apps-before.txt -   # only production apps removed
```

## Definition of done

- [ ] AC-P2.1 … AC-P2.8 all green, with transcripts below.
- [ ] `npm test` in `services/identity` and `clients/cli` passes with no database required.
- [ ] No plaintext secret in the repo; `git log -p` on the branch shows no token or password.
- [ ] README documents every canonical CLI command, its endpoint, the seeding procedure, and the
      two deliberate gaps (`/auth/refresh`, live SSE kill in P4).
- [ ] `ESTADO-FINAL.md` written and the north-star roadmap marks P2 **SHIPPED**.

## Phase gates

_(F1–F9 record their evidence here as they land.)_
