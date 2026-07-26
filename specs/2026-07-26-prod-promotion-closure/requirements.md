# Requirements — Production Promotion & Checkpoint Closure

> Closes the DevOps checkpoint. Phase 2 shipped green (`integration-staging:identity` linked in
> [`devops-checkpoint/README.md`](../../devops-checkpoint/README.md)); what remains is the evidence
> pack (drills, ADRs), the optional Phase 3 production promotion, and bringing the specs back in
> sync with reality. Grounded in [`../mission.md`](../mission.md) and
> [`../roadmap.md`](../roadmap.md) Phase 3; extends
> [`2026-06-26-devops-pipeline/`](../2026-06-26-devops-pipeline/).

## Objective

A reviewer opening the repo finds: specs that tell the truth about what shipped, the five decision
ADRs, manual production-promotion jobs that move the staging-tested digest (never rebuild), and
linked pipeline evidence for the fail-fast / independence / contract-break drills.

## Locked decisions (session 2026-07-26)

| # | Decision | Chosen |
|---|----------|--------|
| E1 | Scope of this iteration | Close the checkpoint now; new faculty material folds into a later spec when provided |
| E2 | Phase 3 production stages | Implement as `when: manual` + `allow_failure: true` jobs (not "documented as if done") |
| E3 | GitLab access for live validation | Old PAT is dead (401); user supplies a fresh PAT in `~/.amicro_gitlab_token` |
| E4 | Evidence drills | Run live on the course repo (red pipelines on drill branches), link the runs, delete the branches |

## In scope

- `deliver-production:identity`: manual job that pins the digest tested by
  `integration-staging:identity` into `gitops/apps/identity/overlays/production/values.yaml`
  (promotion = digest copy, no rebuild).
- `deploy-production:identity`: manual job that syncs `identity-production` and waits for
  Healthy+Synced; gated on `$ARGOCD_SERVER` (persistent-cluster seam, same as `deploy-staging`).
- The five decision-log ADRs (roadmap §"Decision log") in `devops-checkpoint/adrs.md`.
- Change-detection fix: consumers (`ranking`, `analytics-workers`) also react to
  `ci/contracts/**/*` so a schema-only change creates — and blocks — their builds (makes AC-8
  verifiable with a one-file drill branch).
- Live drills (independence, fail-fast, contract-break) + local negative smoke; evidence linked.
- Specs truth pass: roadmap phase markers, old DoD checkboxes, coverage matrix `deliver-prod` cell.

## Out of scope (→ future spec)

- Anything from the new faculty material (E1) — lands as its own dated spec.
- Persistent cluster / running `deploy-production` for real (needs `ARGOCD_SERVER`; job + docs are
  the deliverable, consigna §6.7 makes prod optional).
- Phase 4 real-service handoff; invariant stub jobs (roadmap §"stretch").

## Acceptance criteria

- **AC-C1** `specs/roadmap.md` marks Phase 2 complete (green run linked) and Phase 3 in progress →
  complete; no phase claims a state the repo can't show.
- **AC-C2** `devops-checkpoint/adrs.md` holds the 5 ADRs; README links it; old-spec DoD ticks it.
- **AC-C3** `deliver-production:identity` exists, runs only manually on the default branch, never
  blocks the pipeline, and after one run the production overlay carries the exact digest from the
  same pipeline's `build:identity` (old AC-4 grep passes).
- **AC-C4** `deploy-production:identity` exists behind the same manual gate, appears only when
  `$ARGOCD_SERVER` is set, and reuses the honest readiness gate (`argocd app wait --health`).
- **AC-C5** A schema-only incompatible edit to `game-completed.schema.json` on a branch turns
  `test:contract:game-completed` red and shows `build:ranking` + `build:analytics-workers` created
  but blocked.
- **AC-C6** README gains a drills/evidence section linking each live run (independence, fail-fast,
  contract-break, promotion) plus the local negative-smoke transcript.
- **AC-C7** After all pushes, the default-branch pipeline is still green end to end.

## Behaviour contract (edge cases)

- Manual production jobs with `allow_failure: true` — a never-clicked gate must leave the pipeline
  green, not `blocked`.
- Promotion pushes a bot commit with `[skip ci]`; it must not trigger a recursive pipeline.
- `GITOPS_PUSH_TOKEN` lives only as a masked CI variable (project access token), never in the repo.
- Drill branches are deleted after evidence capture; the linked pipeline pages survive deletion.
