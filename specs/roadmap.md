# Roadmap — DevOps Checkpoint

> Phases ordered by dependency. Grounded in [`mission.md`](./mission.md) and
> [`tech-stack.md`](./tech-stack.md). Each phase becomes (or extends) a dated spec under `specs/`.

## Phase 1 — Repo skeleton & placeholders (all 10 → test/build/deliver) — **complete**

Spec: [`2026-06-26-devops-pipeline/`](./2026-06-26-devops-pipeline/)

- Monorepo layout: `services/<svc>/` (source + Dockerfile + `chart/` + `.gitlab-ci.yml`).
- Trivial placeholder per service (`/health` + canned response) in its architecture language.
- ≥1 unit test + static analysis per service.
- Root `.gitlab-ci.yml`: stages, child-pipeline orchestration, **change detection**, **fail-fast**.
- `deliver` to GitLab Container Registry; capture image **digest**.
- The one async **contract-check** seam (`GameCompleted`: room-gameplay → ranking/analytics).
- **Coverage matrix** row per service.

**Exit:** all 10 services green through `test → build → deliver`; a deliberately-failing test
on a branch turns exactly one pipeline red and blocks its `build`.

## Phase 2 — GitOps deploy + `identity` fully wired — **complete**

- `gitops/` cluster-state: Argo CD `Application` per service per env; staging/prod overlays.
- `identity` real slice: `register` + `whoami` (in-memory store; trivial DB only if demoing the
  migrate job).
- `deploy-staging` (identity): bot writes digest into staging overlay → `argocd app sync` +
  `argocd app wait --health` (**readiness gate**).
- **Client CLI** (Node/TS, Docker-packaged) subset: `register`, `whoami`, `--json`.
- `integration-staging` (identity): CLI-driven smoke test, asserts on `--json` output,
  `UNOARENA_API_URL` injected. Hermetic (namespaced/seeded account).
- **Rollback** documented; structured-log **observability hook** verified via `kubectl logs`.

**Exit:** green pipeline run reaching `integration-staging` for `identity`, linked in the README.
Met on 2026-06-26 — [run 2633085455](https://gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp/-/pipelines/2633085455),
self-contained kind + Argo CD inside the CI runner.

## Phase 3 — Production promotion (optional) — **complete (promotion run linked)**

Spec: [`2026-07-26-prod-promotion-closure/`](./2026-07-26-prod-promotion-closure/) (also carries
the checkpoint-closure work: ADRs, evidence drills, specs truth pass).

- `deliver-production` / `deploy-production` behind a **manual gate**, on protected branch / tag.
- Promote the **same digest** tested in staging into the production overlay (no rebuild).
- Optional non-destructive prod smoke (`whoami`).

**Exit (optional):** documented promotion run, or the promotion model documented "as if done"
(no points lost if staging is solid — consigna §6.7).

## Phase 4 — Real-service handoff — **superseded by the final-delivery program**

This handoff is now a program of its own (the exam requires the real system):
[`2026-07-26-final-delivery-northstar/`](./2026-07-26-final-delivery-northstar/) (north-star +
roadmap P1–P9). The notes below remain as the original sketch.

- Replace placeholders with real services one at a time **without changing the pipeline shape**.
- Stretch table (§6.9): architecture invariants → future pipeline job that carries each
  integration test (stub jobs that always pass, documented as seams). E.g. log-before-broadcast,
  single-active-session push invalidation, 60s reconnection window, spectator privacy,
  match-series coordination, async event-schema compatibility.

## Decision log (ADRs to write alongside Phase 1–2)

1. GitOps (Argo CD) vs. pipeline-applied Helm.
2. Monorepo change-detection: parent + dynamic child pipelines vs. `rules: changes:` only.
3. Promotion model: digest pin in GitOps overlay.
4. Secrets backend: Sealed Secrets (cluster-internal, no cloud).
5. Contract-test placement: async event-schema check in `test` stage of producer + consumers.

## Out of scope (restraint — consigna §4, §8)

No canaries/blue-green, no multi-region, no build matrices for thoroughness, no SLO/alerting/oncall,
no real game logic, no cluster provisioning IaC, no vendor KMS while the cluster is unconfirmed.
