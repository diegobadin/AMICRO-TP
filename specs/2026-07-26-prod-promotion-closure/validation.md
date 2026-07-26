# Validation — Production Promotion & Checkpoint Closure

> One check per acceptance criterion in [`requirements.md`](./requirements.md). Live evidence gets
> linked here and in the README evidence section as F6 executes.

## Verification matrix

| AC | Check | Pass condition |
|----|-------|----------------|
| AC-C1 | Read `specs/roadmap.md`. | Phase 2 marked complete with the green-run link; Phase 3 status matches the repo at that commit. |
| AC-C2 | Open `devops-checkpoint/adrs.md`; grep README for it. | 5 ADRs (GitOps, change detection, promotion, secrets, contract placement); README links; old DoD box ticked. |
| AC-C3 | On a green main pipeline, play `deliver-production:identity`; then `grep sha256: gitops/apps/identity/overlays/production/values.yaml`. | Job green; overlay digest == that pipeline's `build:identity` digest; bot commit says `[skip ci]`. |
| AC-C4 | Inspect `deploy-production:identity` rules; check a pipeline without `ARGOCD_SERVER`. | Job absent when the variable is unset; when present it is manual + `allow_failure: true` and runs `argocd app wait --health`. |
| AC-C5 | Push `drill/contract-break` (schema-only edit). | `test:contract:game-completed` red; `build:ranking` and `build:analytics-workers` present and blocked (never start). |
| AC-C6 | Open the README evidence section. | Four live run links (independence, fail-fast, contract-break, promotion) + negative-smoke transcript reference. |
| AC-C7 | Open the last main pipeline after all closure pushes. | Green end to end, `integration-staging:identity` included; manual gates unclicked do not block it. |

## Negative smoke (AC-6 of the previous spec, local)

```bash
cd clients/cli && npm install && npm run build
UNOARENA_API_URL=http://127.0.0.1:9 node dist/cli.js whoami --json; echo "exit=$?"
```

Pass: `result` is an error payload and `exit` is non-zero. Transcript recorded below when run.

- [x] Run and recorded (2026-07-26, local):

```
{"ts":"2026-07-26T21:11:49.367Z","action":"whoami","result":"error","error_code":"unreachable","correlationId":"e186c222-5459-466a-b22b-7328c51cf52c","detail":"TypeError: fetch failed"}
exit=1
```

## Definition of done

- [x] AC-C1 … AC-C7 pass (2026-07-26; runs linked in README §9 — green state
  [2707017997](https://gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp/-/pipelines/2707017997),
  promotion [job 15541147687](https://gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp/-/jobs/15541147687);
  AC-C4 verified by absence of `deploy-production:identity` while `$ARGOCD_SERVER` is unset).
- [x] Old-spec DoD (2026-06-26 §4) fully ticked, including "All AC-1…AC-10 pass".
- [x] Drill branches deleted from the course repo; pipeline links still resolve.
- [x] ESTADO-FINAL.md written in this directory (what shipped, run links, leftovers).
- [x] User reminded to revoke the PAT and delete `~/.amicro_gitlab_token` (closure hand-off).
