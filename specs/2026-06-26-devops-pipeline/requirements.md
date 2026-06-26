# Requirements — DevOps Pipeline (Phases 1 & 2)

> Feature: the full CI/CD pipeline (10 placeholders through `test→build→deliver`; `identity`
> fully wired to `integration-staging`). Grounded in [`../mission.md`](../mission.md),
> [`../tech-stack.md`](../tech-stack.md), and [`docs/devops/consigna.md`](../../docs/devops/consigna.md).

## 1. Actors

- **Faculty / reviewer** — drives the deployed cluster through the **Client CLI**; reads the
  coverage matrix and walks architecture → folder → image → Argo app in one hop.
- **Team developer** — pushes a change to one service and expects only that service to rebuild.
- **Operator** — performs the documented rollback.

## 2. Functional requirements

### FR-1 — One placeholder per deployable (architecture coverage)
The repo MUST contain **10** services under `services/`, named exactly as the deployables in
`docs/architecture/09-local-topology.md`: `gateway`, `identity`, `room-gameplay`, `outbox-relay`,
`timer-worker`, `tournament`, `ranking`, `spectator`, `analytics-workers`, `analytics-api`.
Each MUST have: source, `Dockerfile`, Helm `chart/`, a `.gitlab-ci.yml` fragment, and ≥1 unit test.
- Delta vs. architecture: the C4 "Round Kickoff Workers" is folded into `tournament` for the
  placeholder set; this MUST be recorded in `CHANGELOG-design.md` (consigna §3).

### FR-2 — Per-service stage spine + fail-fast
Each service MUST be wired `test → build → deliver` at minimum. `build` MUST NOT start if `test`
failed for the same service (`needs:`). A failing `test` MUST turn that service's pipeline red and
leave the other nine unaffected.

### FR-3 — Change detection (independent deployability)
A change limited to `services/<svc>/**` MUST trigger only `<svc>`'s child pipeline. A change to a
shared template MAY trigger a justified bounded set (documented). No "rebuild the world".

### FR-4 — Build once, deliver by digest
Each service produces its **own image** with a hybrid tag (`<ref-slug>-<short-sha>`) pushed to the
GitLab registry by its own `deliver` job, which MUST capture the image **digest** (`@sha256:…`).
No shared "platform" image. No secrets baked into images.

### FR-5 — `identity` real slice
`identity` MUST implement `register --user <u> --pass <p>` (creates an account) and `whoami`
(returns the authenticated identity), plus `GET /health`. Storage MAY be in-memory. Response MUST
let the CLI assert the registered user is returned by `whoami`. All other services return a canned
response + `/health` only.

### FR-6 — GitOps deploy to staging (identity)
`deploy-staging` for `identity` MUST: write the delivered **digest** into
`gitops/apps/identity/overlays/staging`, trigger Argo sync, and **wait for Argo health**
(`argocd app wait --health --sync`) before succeeding. It MUST be idempotent. No `kubectl apply`
from the job; no `sleep`-based readiness.

### FR-7 — Environments differ, secrets external
Staging and production overlays MUST differ in ≥1 legitimate value (e.g. replicas, log level,
external URL). Secrets MUST come from **Sealed Secrets** in-cluster; **no plaintext secret in the
repo**.

### FR-8 — CLI-driven smoke test in staging (identity)
`integration-staging` MUST run a smoke test that drives `identity` through the **Client CLI**
(`register` then `whoami`), consuming the CLI `--json` output and asserting `result == "ok"` plus
the returned user matches. The staging URL MUST be injected via `UNOARENA_API_URL` (no hardcoded
localhost). The test MUST fail the pipeline if the service is unreachable or returns the wrong
response. ≤1 retry, explicit. The test MUST be hermetic (namespaced/unique account per run).

### FR-9 — Contract-check seam
One async **event-schema compatibility** check MUST exist in the `test` stage on the
`GameCompleted` pair (producer `room-gameplay`; consumers `ranking`, `analytics-workers`). Its
failure MUST block the affected consumers (cross-service fail-fast).

### FR-10 — Coverage matrix + navigability
`devops-checkpoint/README.md` MUST contain the §6.9 matrix: exactly one row with
`integration-staging ✅` (`identity`); every row `test→build→deliver ✅`. Every named stage MUST
map to a real job in the pipeline.

### FR-11 — Rollback + observability hook
A one-sentence rollback path for `identity` MUST be documented. `identity` MUST emit ≥1 structured
JSON log line on `register`/`whoami` (with `correlationId`), retrievable via `kubectl logs`
(documented invocation).

### FR-12 — Documentation (README)
`devops-checkpoint/README.md` MUST cover: layout (§6.1, which service is fully wired and why),
pipeline narrative per stage + failure semantics (§6.2), GitOps-vs-Helm justification (§6.5),
smoke-test description (§6.6), and the coverage matrix (§6.9). It MUST link a green pipeline run
reaching `integration-staging`.

### FR-13 (optional) — Production promotion
If implemented: `deliver-production` / `deploy-production` behind a manual gate on a protected
branch/tag, promoting the **same digest** (no rebuild), prod overlay differing from staging.

## 3. Non-functional / constraints

- **Restraint:** no canaries, blue-green, multi-region, build matrices for thoroughness, SLO/
  alerting, or real domain logic (consigna §4, §8).
- **Honesty:** every stub/skip documented as such; architecture drift in `CHANGELOG-design.md`.
- **Vendor-neutral:** runs on kind/k3d, EKS, or AKS unchanged; no cloud KMS dependency.

## 4. Acceptance criteria (traceable to consigna §8)

| AC | Statement | Verifies |
|----|-----------|----------|
| AC-1 | All 10 services green through `test→build→deliver` in one pipeline run. | FR-1, FR-2 |
| AC-2 | Edit `services/ranking/**` → only `ranking` child pipeline runs. | FR-3 |
| AC-3 | Deliberately failing `identity` test → red pipeline, `build` skipped, others green. | FR-2 |
| AC-4 | Same image digest appears in staging overlay and (if done) prod overlay. | FR-4, FR-13 |
| AC-5 | `deploy-staging` succeeds only after Argo reports healthy. | FR-6 |
| AC-6 | Smoke test: CLI `register`+`whoami` against staging URL passes; wrong response fails it. | FR-8 |
| AC-7 | Staging vs prod overlays diff in ≥1 value; `grep -r` finds no plaintext secret. | FR-7 |
| AC-8 | Contract-check failure blocks `ranking` + `analytics-workers`. | FR-9 |
| AC-9 | README matrix matches actual jobs; green run linked. | FR-10, FR-12 |
| AC-10 | Rollback command documented; `kubectl logs` shows the structured line. | FR-11 |
