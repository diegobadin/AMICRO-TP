# UnoArena — DevOps Checkpoint

> Proves the Architecture Checkpoint decomposition survives a real delivery pipeline: every
> service is **independently** testable, buildable, and deployable. Services are **placeholders**;
> exactly one (`identity`) carries a **small real slice** so the staging smoke test validates real
> behaviour through the Client CLI. Assignment: [`../docs/devops/consigna.md`](../docs/devops/consigna.md).
> Full reasoning lives in the Spec-Driven docs under [`../specs/`](../specs/).

**Status:** Phase 1 complete (all 10 services wired `test → build → deliver`, fail-fast +
change detection, the contract-check seam). Phase 2 complete (GitOps under `gitops/`, the
Client CLI under `clients/cli/`, and `identity`'s `deploy-staging` + `integration-staging` jobs).
The fully-wired jobs run **automatically** on the default branch as soon as the cluster CI
variables (`ARGOCD_SERVER`, `IDENTITY_STAGING_URL`) are set — no code change needed.

🔗 **Green pipeline run reaching `integration-staging`:** _added once the staging cluster
(AWS EKS / Azure AKS educational, or the `gitops/bootstrap/` kind+Argo fallback) is connected and
its CI variables are set._ The CLI smoke mechanic is verified end-to-end locally against the real
`identity` service (`register` → `whoami` → assert; see `gitops/README.md` and the smoke template).

---

## 1. Repository layout (§6.1)

```
.gitlab-ci.yml                 # root orchestrator: stages + per-service fragment includes
ci/
  templates/                   # reusable hidden jobs (extends:)
    build-kaniko.yml           #   .build-image  — build+push, capture digest
    deploy-gitops.yml          #   .deploy-gitops — pin digest in overlay + argocd sync/wait
    smoke-cli.yml              #   .smoke-cli    — CLI-driven staging smoke test
  contracts/
    game-completed.schema.json #   published async contract (GameCompleted)
    validate.py                #   producer-conformance + consumer back-compat check
    contract.gitlab-ci.yml     #   test:contract:game-completed job
services/<svc>/                # one folder per deployable (source + Dockerfile + chart + fragment)
  src|main.go|app.py|...       #   placeholder source in the service's architecture language
  Dockerfile                   #   multi-stage, non-root, minimal base
  chart/                       #   Helm chart (one image = one chart)
  .gitlab-ci.yml               #   the service's stage-spine fragment
gitops/                        # Argo CD AppProject, app-of-apps, per-svc Applications + overlays, bootstrap
clients/cli/                   # Client CLI (register/whoami/--json) used by the smoke test
specs/                         # Spec-Driven Development: mission, tech-stack, roadmap, feature spec
```

**Path convention (uniform for all 10).** For service `<svc>`:
- Source: `services/<svc>/` · Pipeline fragment: `services/<svc>/.gitlab-ci.yml` · Helm chart:
  `services/<svc>/chart/` · GitOps overlay: `gitops/apps/<svc>/overlays/<env>/values.yaml` ·
  Argo app: `<svc>-staging` / `<svc>-production` (manifests in `gitops/applications/`).

**Image name & versioning scheme.** Image: `registry.gitlab.com/<group>/unoarena/<svc>`. Versioning
is **hybrid**: a human-readable tag `<ref-slug>-<short-sha>` (provenance) **plus** the
content-addressable **digest** `@sha256:…` captured at build. Deploys pin by **digest**, so staging
and production point at the same built artifact (build once, promote — consigna §5.4 / §6.4).

### One-hop navigability map (architecture service → folder → image → Argo app → wiring)

| Service | Lang / Port | Folder (= source) | Image `…/unoarena/` | Argo app(s) | Wiring depth |
|---|---|---|---|---|---|
| `identity` ⭐ | Node / 8085 | `services/identity/` | `identity` | `identity-staging`, `identity-production` | **full** → integration-staging |
| `gateway` | Node / 8080 | `services/gateway/` | `gateway` | `gateway-staging`, `gateway-production` | test→build→deliver (+stub deploy) |
| `spectator` | Node / 8086 | `services/spectator/` | `spectator` | `spectator-staging`, `spectator-production` | test→build→deliver (+stub deploy) |
| `room-gameplay` | Kotlin / 8081 | `services/room-gameplay/` | `room-gameplay` | `room-gameplay-staging`, `…-production` | test→build→deliver (+stub deploy) |
| `tournament` | Kotlin / 8083 | `services/tournament/` | `tournament` | `tournament-staging`, `…-production` | test→build→deliver (+stub deploy) |
| `ranking` | Python / 8084 | `services/ranking/` | `ranking` | `ranking-staging`, `ranking-production` | test→build→deliver (+stub deploy) |
| `analytics-workers` | Python / 8090 | `services/analytics-workers/` | `analytics-workers` | `analytics-workers-staging`, `…-production` | test→build→deliver (+stub deploy) |
| `analytics-api` | Python / 8087 | `services/analytics-api/` | `analytics-api` | `analytics-api-staging`, `…-production` | test→build→deliver (+stub deploy) |
| `outbox-relay` | Go / 8088 | `services/outbox-relay/` | `outbox-relay` | `outbox-relay-staging`, `…-production` | test→build→deliver (+stub deploy) |
| `timer-worker` | Go / 8089 | `services/timer-worker/` | `timer-worker` | `timer-worker-staging`, `…-production` | test→build→deliver (+stub deploy) |

### Canned surface per service

| Service | Canned surface |
|---|---|
| `identity` ⭐ | **real**: `register`, `login`, `whoami` + `/health` |
| `gateway` | `GET /rooms → []` |
| `spectator` | `GET /spectate → {"spectators":0}` |
| `room-gameplay` | `GET /rooms/sample/state` |
| `tournament` | `GET /tournaments/sample` |
| `ranking` | `GET /players/sample/rating` |
| `analytics-workers` | worker (`/health` only) |
| `analytics-api` | `GET /tournaments/sample/bracket` |
| `outbox-relay` | worker (`/health` only) |
| `timer-worker` | worker (`/health` only) |

⭐ **Fully-wired service: `identity`.** It is the first surface the Client CLI touches
(`register`/`login`/`whoami`/`seed`, consigna client §5.A), gives a meaningful self-contained real
slice (register a user → assert `whoami` returns that user), and has no upstream dependency, so the
smoke test isolates the pipeline mechanic. The other nine are canned placeholders.

> **Architecture delta:** the C4 "Round Kickoff Workers" are folded into the `tournament`
> placeholder (not a separate compose container). Recorded in
> [`../CHANGELOG-design.md`](../CHANGELOG-design.md).

---

## 2. Pipeline narrative & failure semantics (§6.2)

Stages: `test → build → deliver → deploy-staging → integration-staging → deliver-production → deploy-production`.

> **`build` is merged with the image build** (consigna §6.2 — "may merge these and must say so"):
> there is no separate compile-artifact stage. The `build` job *is* the Kaniko image build. The
> only services with a distinct compile step (Kotlin `room-gameplay`, `tournament`) compile during
> their `test` job (`gradle test`) and again inside the multi-stage Dockerfile; the others
> (Node/Python/Go) build entirely within the image.

| Stage | What runs | Failure semantics |
|---|---|---|
| **test** | Per service in its own toolchain: ≥1 unit test + static analysis (`tsc --noEmit`, `go vet`, `ruff`+`mypy`, `gradle test`). Plus the async **contract check**. | Any failure aborts that service's pipeline — `build` has `needs: [test:<svc>]` so it never starts. |
| **build** | **Kaniko** builds `services/<svc>/Dockerfile`, pushes `…/<svc>:<ref-slug>-<short-sha>`, captures the **digest** to a dotenv artifact. | No image, no digest → `deliver`/`deploy` can't run (build once, promote by digest). |
| **deliver** | `helm lint` + `helm package` the chart; (chart push when the chart registry is wired). | A broken chart fails before anything is deployed. |
| **deploy-staging** | **(identity, Phase 2)** bot pins the digest into the staging overlay → `argocd app sync` → `argocd app wait --health`. Idempotent. Stub (`when: manual`) for the other nine. | Job is green **only after Argo reports Synced+Healthy** — honest readiness gate, never `sleep`. |
| **integration-staging** | **(identity only)** the Client CLI smoke test (§4). | Non-zero exit on unreachable / wrong canned response fails the pipeline. Flake budget: `retry: 1`. |
| **deliver/deploy-production** | _(optional, Phase 3)_ manual gate; promote the **same digest** — no rebuild. | — |

### Fail-fast wiring
- `build:<svc> needs [test:<svc>]`; `deliver needs build`; `deploy needs deliver` — downstream
  never starts if an upstream job fails.
- **Cross-service block:** `build:ranking` and `build:analytics-workers` also
  `needs: [test:contract:game-completed]`, so an incompatible `GameCompleted` schema change blocks
  **both consumers** (not just the producer).
- **Flakes:** only the smoke test retries, exactly once, declared explicitly. No silent retries.

### Change detection (independent deployability)
Every job is guarded by `rules: changes: ["services/<svc>/**/*", "ci/templates/**/*"]`. A change
limited to `services/ranking/**` runs only `ranking`'s `test→build→deliver` — the other nine
services' jobs are not created. A change to a shared template (`ci/templates/**`) intentionally
rebuilds the affected set (justified: it is the shared spine). No "rebuild the world".

---

## 3. Deploy model — GitOps with Argo CD (§6.5)

**Why GitOps over pipeline-applied Helm:** the cluster state is auditable in git, the runner needs
**no cluster-admin credentials** (Argo pulls; the pipeline only commits a digest), and staging↔prod
drift is a visible diff. Trade-off accepted: one extra moving piece (Argo) and an honest
**sync-wait** gate so the pipeline never claims "deployed" while Argo is still syncing.

- Cluster-state under `gitops/`: one Argo `Application` per service per env (`<svc>-staging`,
  `<svc>-production`) = the service's Helm chart + that env's overlay values.
- **Promotion = digest pin.** `deliver` captures `@sha256:…`; the deploy job writes it into the
  staging overlay; prod promotion copies the **same digest** into the production overlay — never a
  rebuild (consigna §5.4).
- **Environments differ** (overlays): staging `replicas:1 / logLevel:debug / automated sync`;
  production `replicas:3 / logLevel:info / manual sync`. **Secrets** via **Sealed Secrets**
  (cluster-internal; encrypted blobs are safe to commit) — **no plaintext secret in the repo**.
- **Readiness gate:** chart liveness/readiness probes on `/health` + `argocd app wait --health`.
- **Rollback (one sentence):** run `argocd app rollback identity-staging <previous-revision>`
  (equivalently `git revert` the digest-bump commit, which Argo auto-reconciles) to restore the
  last healthy image.
- **Cluster TBD:** Argo + Helm run identically on EKS, AKS, or local **kind/k3d**; a
  `gitops/bootstrap/` kind+Argo recipe guarantees a reproducible green run before the cloud cluster
  is confirmed. Only the Argo target + secret backend (Sealed Secrets → External Secrets Operator)
  change later; chart, overlays, and pipeline stay identical.

---

## 4. Smoke test — Client-CLI driven (§6.6)

`integration-staging:identity` drives the deployed `identity` through the **Client CLI** (not raw
curl), matching the harness the faculty uses to exercise the cluster:

1. `UNOARENA_API_URL` = the staging ingress of `identity` (injected; never hardcoded localhost).
2. `cli register --user smoke-$CI_PIPELINE_ID --pass … --json` → assert `result == "ok"`.
3. `cli whoami --json` → assert the returned `user` equals the registered user.
4. Hermetic: the account is namespaced per pipeline, so reruns don't collide. `retry: 1`.
5. Fails the pipeline if `identity` is unreachable or returns the wrong canned response.

**Observability seam:** `identity` emits one structured JSON log line per request (with
`correlationId`); retrieve it with `kubectl logs deploy/identity -n unoarena-staging`.

---

## 5. Service × pipeline-coverage matrix (§6.9)

Exactly one row has `integration-staging ✅` (`identity`). Every row has `test → build → deliver ✅`.

| Service | test | build | deliver | deploy-staging | integration-staging | deliver-prod | deploy-prod | Notes |
|---|---|---|---|---|---|---|---|---|
| `identity` ⭐ | ✅ | ✅ | ✅ | ✅ | ✅ | ⬜ opt | ⬜ opt | fully wired; real `register`+`whoami` slice |
| `gateway` | ✅ | ✅ | ✅ | ⬜ stub | — | — | — | placeholder; deploy job `when: manual` |
| `spectator` | ✅ | ✅ | ✅ | ⬜ stub | — | — | — | placeholder |
| `room-gameplay` | ✅ | ✅ | ✅ | ⬜ stub | — | — | — | placeholder; contract **producer** |
| `tournament` | ✅ | ✅ | ✅ | ⬜ stub | — | — | — | placeholder (Round-Kickoff workers folded in) |
| `ranking` | ✅ | ✅ | ✅ | ⬜ stub | — | — | — | placeholder; contract **consumer** |
| `analytics-workers` | ✅ | ✅ | ✅ | ⬜ stub | — | — | — | placeholder; contract **consumer** |
| `analytics-api` | ✅ | ✅ | ✅ | ⬜ stub | — | — | — | placeholder |
| `outbox-relay` | ✅ | ✅ | ✅ | ⬜ stub | — | — | — | placeholder (worker) |
| `timer-worker` | ✅ | ✅ | ✅ | ⬜ stub | — | — | — | placeholder (worker) |

Each named cell maps to a real job (`<stage>:<svc>`) in that service's
`services/<svc>/.gitlab-ci.yml`. `deploy-staging` for the nine placeholders exists as a
`when: manual` stub (job present, not auto-run) — the seam is visible without rebuilding the world.

---

## 6. Configuration, secrets & observability (§6.8)

- **Configuration source of truth.** Per-service, per-environment config lives in the GitOps
  overlays `gitops/apps/<svc>/overlays/<env>/values.yaml` (replicas, log level, image digest),
  layered over the chart defaults in `services/<svc>/chart/values.yaml`. **Never in the image** —
  the same image runs in any environment; only the overlay changes.
- **Secrets.** Bitnami **Sealed Secrets** (see §3 and `gitops/apps/identity/overlays/staging/`).
  Only the in-cluster controller decrypts; the sealed blob is safe to commit. **No plaintext secret
  in the repo.**
- **Observability hook.** `identity` emits one structured JSON log line per request with a
  `correlationId` (propagated from the CLI's `x-correlation-id`). Retrieve it with
  `kubectl logs deploy/identity -n unoarena-staging`. This is the seam a real service later uses to
  carry `correlationId` end to end — no dashboards/alerting are built (restraint, §8).

---

## 7. Running it locally

```bash
# Per-service test stage (examples)
cd services/identity && npm install && npm run lint && npm test     # Node
cd services/ranking  && pip install ruff mypy pytest && ruff check . && mypy . && pytest   # Python
cd services/timer-worker && go vet ./... && go test ./...           # Go
cd services/tournament && ./gradlew test                            # Kotlin

# Contract check
python ci/contracts/validate.py
```

See [`../specs/2026-06-26-devops-pipeline/validation.md`](../specs/2026-06-26-devops-pipeline/validation.md)
for the full verification matrix (fail-fast drill, independence drill, promotion-by-digest, etc.).
