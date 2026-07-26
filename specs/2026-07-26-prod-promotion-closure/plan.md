# Plan — Production Promotion & Checkpoint Closure

> How [`requirements.md`](./requirements.md) gets built, in small commit-sized phases so each one
> is validated before the next starts. Design decisions below are the implementer's (D-n), distinct
> from the user's locked E-n.

## Phases (one commit each)

| Phase | Delivers | Validated by |
|-------|----------|--------------|
| F0 | This spec triad | review |
| F1 | Specs truth pass (roadmap markers, old DoD ticks) | links resolve, claims match repo |
| F2 | `devops-checkpoint/adrs.md` (5 ADRs) + README link | AC-C2 |
| F3 | Production jobs + templates + README/matrix updates | AC-C3, AC-C4, lint below |
| F4 | Consumer change-detection on `ci/contracts/**` | AC-C5 (drill) |
| F5 | Drill branches (local) + negative smoke transcript | branch diffs reviewed |
| F6 | Live: push, drills, promotion run, evidence links, DoD close, ESTADO-FINAL | AC-C3, C5, C6, C7 |

F6 is blocked on the fresh PAT (E3). Everything else is local.

## Design decisions (confirm in review)

- **D1 — Promotion source of truth.** `deliver-production:identity` promotes `$IMAGE_DIGEST` from
  the *same pipeline's* `build:identity` dotenv, and `needs: ["integration-staging:identity"]` so
  the gate can only be clicked on a pipeline whose smoke passed. In the self-contained model that
  digest *is* the artifact staging tested (the kind job pulls `$IMAGE@$IMAGE_DIGEST`), so reading
  the staging overlay (which the gated `deploy-staging` path pins) would be less honest, not more.
- **D2 — Template split.** Two new hidden jobs in `ci/templates/deploy-gitops.yml`:
  `.promote-digest` (yq pin + bot push, no Argo) and `.argocd-sync` (login/sync/wait, no pin).
  `.deploy-gitops` stays untouched — it is green and `deploy-staging:identity` still describes the
  persistent-cluster flow that does both.
- **D3 — Gates.** Both prod jobs: `rules: if $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH, when: manual,
  allow_failure: true` (a rules-level `when: manual` defaults `allow_failure` to false and would
  hold the pipeline in *blocked*). `deploy-production` additionally requires `$ARGOCD_SERVER`.
- **D4 — Bot credentials.** `GITOPS_PUSH_TOKEN` = project access token (role Developer, scope
  `write_repository`), created via API in F6, stored as a masked CI variable. The user PAT is never
  stored in the project.
- **D5 — Consumer wiring.** Add `ci/contracts/**/*` to the `rules: changes:` of the six
  test/build/deliver jobs in `services/ranking/.gitlab-ci.yml` and
  `services/analytics-workers/.gitlab-ci.yml` only. The producer (`room-gameplay`) keeps its
  current wiring: the contract job already runs on its changes; blocking is a consumer concern
  (documented in ADR-5).

## Changes by file

- `ci/templates/deploy-gitops.yml` — append `.promote-digest` + `.argocd-sync`.
- `services/identity/.gitlab-ci.yml` — append `deliver-production:identity`,
  `deploy-production:identity`.
- `services/ranking/.gitlab-ci.yml`, `services/analytics-workers/.gitlab-ci.yml` — extend the
  `changes:` lists (D5).
- `devops-checkpoint/adrs.md` — new; 5 ADRs, same voice as `docs/architecture/08-adrs.md`.
- `devops-checkpoint/README.md` — §2 table rows for the prod stages, §5 matrix cells, ADR link,
  new evidence section (F6).
- `specs/roadmap.md`, `specs/2026-06-26-devops-pipeline/validation.md` — truth pass (F1) and
  final ticks (F6).

## Drill designs (F5/F6)

| Drill | Branch | Change | Expected pipeline |
|-------|--------|--------|-------------------|
| Independence (AC-2) | `drill/independence-ranking` | rename a local in `services/ranking/app.py` | only `ranking` jobs created, all green |
| Fail-fast (AC-3) | `drill/failfast-identity` | assertion flipped in an identity unit test | `test:identity` red, `build:identity` never starts |
| Contract-break (AC-8) | `drill/contract-break` | `game-completed.schema.json`: make `isAbandoned` type `string` | contract job red; both consumer builds blocked |
| Negative smoke (AC-6) | none (local) | CLI vs `http://127.0.0.1:9` | non-zero exit, transcript in validation.md |

Promotion drill (old AC-4): run `deliver-production:identity` once via the API on a green main
pipeline, then `grep sha256: gitops/apps/identity/overlays/production/values.yaml` matches the
`build:identity` digest of that pipeline.

## Risks

- **R1** Manual-job rules subtly wrong → pipeline `blocked` instead of green. Mitigated by D3 +
  `gitlab-ci` lint via API before pushing main.
- **R2** Project access tokens unavailable on the gitlab.com tier → fall back to a masked variable
  holding a fine-grained user PAT, documented in ESTADO-FINAL.
- **R3** Drill pushes race the main push → push main first, confirm green, then drills.
