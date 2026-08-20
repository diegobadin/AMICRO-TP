# Tech Stack & Key Decisions — DevOps Checkpoint

> Grounded in [`mission.md`](./mission.md). These are the standing decisions every feature spec
> assumes. Architecturally consequential choices are also written as ADRs (see roadmap §"Decision log").

## 1. Delivery platform

| Concern | Choice | Notes |
|---------|--------|-------|
| SCM + CI | **GitLab** (monorepo) + GitLab CI/CD | Single source of truth; the pipeline is the only deploy path. |
| Pipeline structure | **Parent + dynamic child pipelines**, one **fragment per service** | Root `.gitlab-ci.yml` orchestrates; `services/<svc>/.gitlab-ci.yml` defines that service's spine. |
| Container registry | **GitLab Container Registry** | `registry.gitlab.com/<group>/unoarena/<service>`. |
| Image build | **Kaniko** (rootless, in-cluster/runner) | No Docker-in-Docker privilege; reproducible. |
| Chart packaging | **Helm**, one chart per service | Charts under `services/<svc>/chart/`; rendered/synced by Argo CD. |
| Deploy model | **GitOps with Argo CD** | Pipeline never `kubectl apply`s; it updates `gitops/` and Argo reconciles. See §3. |

## 2. Languages per service (per-architecture, decision from interview)

Each placeholder uses the language its bounded context names in
`docs/architecture/01-service-architecture.md`, to demonstrate that build/test are genuinely
**independent and heterogeneous** (not one shared base image in a trench coat).

| Service | Language | Test tool | Lint/static |
|---------|----------|-----------|-------------|
| `gateway` | Node.js / TS | vitest | eslint + tsc |
| `identity` ⭐ | Node.js / TS | vitest | eslint + tsc |
| `room-gameplay` | Kotlin (JVM) | JUnit5 | ktlint |
| `tournament` | Kotlin (JVM) | JUnit5 | ktlint |
| `ranking` | Python | pytest | ruff + mypy |
| `analytics-workers` | Python | pytest | ruff + mypy |
| `analytics-api` | Python | pytest | ruff + mypy |
| `spectator` | Node.js / TS | vitest | eslint + tsc |
| `outbox-relay` | Go | `go test` | `go vet` + golangci-lint |
| `timer-worker` | Go | `go test` | `go vet` + golangci-lint |

⭐ `identity` carries the **real slice** (`register` + `whoami`); the other nine are
canned-response placeholders. The **Client CLI** (smoke-test harness) is **Node.js / TS** to
match `identity`.

> **The two Kotlin rows said "ktlint / detekt" until P9, and neither build applied either plugin** —
> `gradle check` was `test` alone. P9 wired ktlint, so `check` now genuinely lints; **detekt is
> struck rather than added**. One linter that is deterministic and auto-fixable is enough, and
> adopting a second ruleset on two mature services in the phase that exists to freeze and rehearse
> them buys nothing the exam can see.
>
> ktlint runs against a **baseline** (`config/ktlint/baseline.xml`, one per module, **1,377
> entries**): the code predates the linter, and formatting it to `ktlint_official` moved **5,870
> lines across 64 files** with no behavioural content. The baseline grandfathers exactly those and
> nothing else — a new violation fails `check`, which is bite-checked in P9's `validation.md`. The
> debt is listed rather than hidden, which is the point of a baseline over a suppression.

## 3. GitOps with Argo CD (deploy model)

**Why GitOps over pipeline-applied Helm:** the cluster state is auditable in git, the runner
needs **no cluster-admin credentials** (Argo pulls; the pipeline only commits a digest), and
staging/prod drift is visible as a diff. Trade-off accepted: an extra moving piece (Argo) and an
honest **sync-wait** gate so the pipeline never reports "deployed" while Argo is still syncing.

- **Cluster-state lives in-repo** under `gitops/` (acceptable per consigna §6.5; avoids a second
  repo for a course deliverable). Structure: `gitops/apps/<svc>/{base,overlays/staging,overlays/production}`.
- **One Argo `Application` per service per environment** (`<svc>-staging`, `<svc>-production`),
  pointing at the Helm chart with the env overlay's values.
- **Promotion = digest pin.** `deliver` captures the pushed image digest (`@sha256:…`) and a
  bot commit writes it into `gitops/apps/<svc>/overlays/staging/values.yaml`. Prod promotion
  copies the **same digest** into the production overlay — never a rebuild.
- **Readiness gate.** `deploy-staging` runs `argocd app sync <svc>-staging` then
  `argocd app wait <svc>-staging --health --sync` (honest gate; not `sleep 30`). Pods carry
  liveness/readiness probes in the chart.
- **Rollback.** `argocd app rollback <svc>-staging <prev>` (or `git revert` of the digest
  commit, which Argo reconciles) — one operator action.

## 4. Cluster target (local Kubernetes — no cloud)

Per consigna §4 the **cluster is assumed to exist** and "you may sketch a minimal kind/k3d/minikube
local equivalent". **No public cloud is required.** The staging cluster is a local Kubernetes
(kind/k3d/minikube) — a real cluster for grading purposes; `kind/k3d` counts.

- `gitops/bootstrap/` stands up the assumed cluster locally: **kind + Argo CD + Sealed Secrets**,
  then registers the AppProject and the app-of-apps. Run once per environment.
- The pipeline targets that cluster through its CI variables (`ARGOCD_SERVER`, `IDENTITY_STAGING_URL`).
  `deploy-staging` + `integration-staging` reach a green run once those point at the bootstrapped
  cluster — entirely within GitLab, no external infrastructure.
- Provider-agnostic by construction: the same chart/overlays/pipeline would run unchanged on any
  Kubernetes, but that is a property, not a requirement of this checkpoint.

## 5. Image versioning

**Hybrid tag + digest pin.** Build tags carry human provenance **and** content addressing:

```
registry.gitlab.com/<grp>/unoarena/<svc>:${CI_COMMIT_REF_SLUG}-${CI_COMMIT_SHORT_SHA}
```

Deploys **pin by digest** (`@sha256:…`) captured at `deliver`, so staging and production point at
the **same built artifact** (consigna §5.4 / §6.4). Helm chart versions are independent (chart
`Chart.yaml` `version` bumped on chart changes only).

## 6. Secrets

**Bitnami Sealed Secrets** (cluster-internal controller). Plaintext secrets are encrypted to a
`SealedSecret` that is safe to commit under `gitops/`; only the in-cluster controller can decrypt.
Cluster-internal by design — no external/cloud secret backend (consigna §4 discourages
vendor-specific KMS). **No plaintext secret ever enters the repo.**

## 7. Contract-check seam (one illustrative)

An **async event-schema compatibility check** on the most interesting pair: the
`room.lifecycle.events` `GameCompleted` event (producer: `room-gameplay`) consumed by `ranking`
and `analytics-workers`. The check lints a JSON Schema / AsyncAPI fragment (already sketched in
`docs/architecture/10-api-event-catalog.md`) and runs a **backward-compatibility** check
(producer schema vs. consumer-expected schema). It blocks **all affected consumers** on failure
(cross-service fail-fast). One illustrative check is sufficient per consigna §6.3.

## 8. Test-stage bar (per service)

- ≥1 trivial unit test (proves the stage exists + fail-fast wiring; a deliberately-failing test
  on a branch must turn the pipeline red and block `build`).
- Static analysis appropriate to the stack (table §2).
- The one async contract check (§7) wired on the producer/consumer pair.
- Optional: container base-image scan (Trivy) — non-blocking.

## 9. Observability hook (lightweight)

> **Superseded by P8 for the final delivery** (`specs/2026-08-18-p8-observability/`, delta 13.10).
> The paragraph below was the right scope for the DevOps checkpoint, whose §3 criterion 11 is
> *restraint* — "no observability stack before the smoke test works". It was written before
> `docs/final/consigna.md` existed, and that asks for observability infrastructure with at least
> three business metrics, deployed from an empty cluster. P8 ships dashboards, alerting and log
> aggregation. **Tracing collectors are still out**, so the last third of the sentence holds.
> Kept rather than deleted: it was true when written, and the gate it describes is what every
> phase since P2 instrumented its way past.

`identity` emits **one structured JSON log line** per `register`/`whoami` (carrying
`correlationId`). The README documents retrieval via `kubectl logs deploy/identity -n unoarena-staging`.
No dashboards/alerting/tracing collectors — just the seam left in place.
