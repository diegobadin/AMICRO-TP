# ESTADO FINAL — Production Promotion & Checkpoint Closure (2026-07-26)

The DevOps checkpoint is closed: specs tell the truth, the five ADRs exist, the production
promotion ran for real behind its manual gate, and every verification drill has a linked live run.

## What shipped

| Piece | Where | Evidence |
|-------|-------|----------|
| Closure green run (all 10 services + smoke + new gates) | `main` | [pipeline 2707017997](https://gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp/-/pipelines/2707017997) |
| 5 DevOps ADRs | `devops-checkpoint/adrs.md` | linked from README header |
| Production gates (`.promote-digest` / `.argocd-sync`) | `ci/templates/deploy-gitops.yml`, `services/identity/.gitlab-ci.yml` | promotion [job 15541147687](https://gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp/-/jobs/15541147687) |
| Digest promotion (build once) | `gitops/apps/identity/overlays/production/values.yaml` | bot commit `ed8a3834`, digest `sha256:4731de…` = `build:identity` digest, `[skip ci]` honored |
| Independence drill (AC-2) | ranking-only change | [pipeline 2707021400](https://gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp/-/pipelines/2707021400) |
| Fail-fast drill (AC-3) | broken identity assertion | [pipeline 2707019434](https://gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp/-/pipelines/2707019434) |
| Contract-break drill (AC-8) | incompatible schema type | [pipeline 2707019496](https://gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp/-/pipelines/2707019496) |
| Observability hook (AC-10) | `kubectl logs` inside the integration job | [job 15541020672](https://gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp/-/jobs/15541020672) |
| Negative smoke (AC-6) | local CLI vs dead URL | transcript in [`validation.md`](./validation.md) |

## Corrections made during the live run

- **New-branch change detection:** GitLab evaluates `rules: changes:` as "everything changed" on a
  branch's first push, so the independence drill needed a second, ranking-only push to have a
  baseline; the first (all-services) pipeline was canceled.
- **Protected `main` vs bot push:** the promotion push was rejected because `main` allowed only
  Maintainers and the `gitops-push-bot` project access token has the Developer role. Resolved by
  re-protecting `main` as *push: Developers + Maintainers, merge: Maintainers* (user-approved);
  the job retry then succeeded.

## Deliberately left open (→ final-delivery program)

- `deploy-production:identity` stays a documented seam: it appears only when `$ARGOCD_SERVER`
  points at a persistent cluster — which P1 of
  [`../2026-07-26-final-delivery-northstar/`](../2026-07-26-final-delivery-northstar/) provides.
- Placeholders remain placeholders; replacement is the final program (P2+), pipeline shape frozen.

## Useful coordinates

- GitLab project id `83816735`; default branch `main` (push: Developers+Maintainers since today).
- CI variable `GITOPS_PUSH_TOKEN` = project access token `gitops-push-bot` (Developer,
  `write_repository`, expires 2026-09-30) — used only by `.promote-digest`/`.deploy-gitops`.
- Promoted image: `…/unoarena/identity@sha256:4731de947f870b91db69f50d05ed50e40b14ff0ed1d989a12c87cccc0465800c`.
- Drill branches are deleted; their pipeline URLs above remain browsable.
