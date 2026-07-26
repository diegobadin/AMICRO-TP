# Validation — DevOps Pipeline (Phases 1 & 2)

> How each acceptance criterion in [`requirements.md`](./requirements.md) is verified. These are the
> checks a reviewer (or we) run to call the feature done. Each maps to a consigna §8 criterion.

## 1. Verification matrix

| AC | Check | Pass condition |
|----|-------|----------------|
| AC-1 | Push to `main`; open the pipeline. | 10 services each show green `test`, `build`, `deliver`. |
| AC-2 | Branch editing only `services/ranking/src/**`; push. | Only `ranking`'s child pipeline runs; other 9 absent/not-triggered. |
| AC-3 | Branch with a deliberately failing `identity` unit test; push. | `test:identity` red; `build:identity` **not started**; other services green. |
| AC-4 | `grep -r 'sha256:' gitops/apps/identity/overlays/`. | Same digest string in staging (and prod, if promoted) overlay as the `deliver:identity` log. |
| AC-5 | Watch `deploy-staging:identity`. | Job succeeds **only after** `argocd app wait --health` returns Healthy+Synced; no `sleep`. |
| AC-6 | Inspect `integration-staging:identity` log; also run a negative test (point CLI at a dead URL). | Positive: CLI `register`+`whoami` `result=="ok"` and user matches → green. Negative: non-zero exit → red. |
| AC-7 | `diff` staging vs prod overlay; `grep -rE '(password|secret):' --include='*.yaml' gitops/`. | ≥1 value differs; no plaintext secret (only `SealedSecret`). |
| AC-8 | Branch with an incompatible change to `game-completed.schema.json`; push. | `test:contract:game-completed` red; `build:ranking` + `build:analytics-workers` blocked. |
| AC-9 | Open `devops-checkpoint/README.md`; cross-check each matrix cell against pipeline job names. | Every claimed job exists; exactly one row has `integration-staging ✅`; green run linked. |
| AC-10 | Run the documented rollback; run the documented `kubectl logs` command. | Rollback restores prior digest (Argo Healthy); log shows the structured JSON line with `correlationId`. |

## 2. Coverage matrix (target state — mirrors README §6.9)

Exactly one `integration-staging ✅` (identity). Every row `test→build→deliver ✅`.

| Service | test | build | deliver | deploy-staging | integration-staging | deliver-prod | deploy-prod | Notes |
|---|---|---|---|---|---|---|---|---|
| `identity` ⭐ | ✅ | ✅ | ✅ | ✅ | ✅ | ✋ manual | ✋ manual | fully wired; real `register`+`whoami` slice |
| `gateway` | ✅ | ✅ | ✅ | ⬜ stub | ⬜ stub | — | — | placeholder; deploy job `when: manual` |
| `room-gameplay` | ✅ | ✅ | ✅ | ⬜ stub | ⬜ stub | — | — | placeholder; contract **producer** |
| `tournament` | ✅ | ✅ | ✅ | ⬜ stub | ⬜ stub | — | — | placeholder (Round-Kickoff workers folded in) |
| `ranking` | ✅ | ✅ | ✅ | ⬜ stub | ⬜ stub | — | — | placeholder; contract **consumer** |
| `analytics-workers` | ✅ | ✅ | ✅ | ⬜ stub | ⬜ stub | — | — | placeholder; contract **consumer** |
| `analytics-api` | ✅ | ✅ | ✅ | ⬜ stub | ⬜ stub | — | — | placeholder |
| `spectator` | ✅ | ✅ | ✅ | ⬜ stub | ⬜ stub | — | — | placeholder |
| `outbox-relay` | ✅ | ✅ | ✅ | ⬜ stub | ⬜ stub | — | — | placeholder |
| `timer-worker` | ✅ | ✅ | ✅ | ⬜ stub | ⬜ stub | — | — | placeholder |

## 3. Manual drills (recorded in the README)

1. **Fail-fast drill** — AC-3 branch, screenshot red `test:identity` + skipped `build:identity`.
2. **Independence drill** — AC-2 branch, screenshot single-service child pipeline.
3. **Promotion drill** — show identical digest staging↔prod overlay (AC-4).
4. **Readiness honesty** — Argo `Healthy` timestamp precedes `deploy-staging` success (AC-5).
5. **Smoke positive + negative** — AC-6 both runs linked.
6. **Rollback drill** — AC-10 command + Argo history screenshot.

## 4. Definition of done (gate to close the feature)

- [ ] All AC-1…AC-10 pass (drill evidence tracked in
  [`../2026-07-26-prod-promotion-closure/validation.md`](../2026-07-26-prod-promotion-closure/validation.md)).
- [x] Green pipeline run reaching `integration-staging:identity` linked in `devops-checkpoint/README.md`
  ([run 2633085455](https://gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp/-/pipelines/2633085455)).
- [x] `CHANGELOG-design.md` records the Round-Kickoff-workers fold + any other drift (§6).
- [x] 5 ADRs written (roadmap §"Decision log") — [`devops-checkpoint/adrs.md`](../../devops-checkpoint/adrs.md).
- [x] README covers layout, pipeline narrative, GitOps justification, smoke test, coverage matrix.

## 5. Out-of-scope confirmations (restraint check — consigna §8)

- [ ] No canary/blue-green/multi-region manifests present.
- [ ] No build matrix producing identical artifacts.
- [ ] No real domain logic beyond the `identity` register/whoami slice.
- [ ] No observability stack (dashboards/alerting) — only the single structured-log seam.
