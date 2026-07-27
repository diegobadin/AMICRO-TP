# ESTADO FINAL — P1: Platform Infra from an Empty Cluster

> Closed 2026-07-27. All six acceptance criteria green. "Empty cluster → whole platform
> Healthy" is now a boring, repeatable, one-command drill on kind AND on Learner Lab EKS.

## What shipped

- `gitops/platform/` — Helm-typed app-of-apps (`unoarena-platform-root`, project
  `unoarena-platform`): Strimzi 1.1.0 → single-node KRaft Kafka 4.3.0; CNPG 0.29.0 →
  `unoarena-pg` + the five context databases; pinned `redis:7.4.10-alpine` (noeviction);
  kube-prometheus-stack 87.19.2 (no Alertmanager). `targetRevision` cascades root→children via
  helm parameter, so rehearsal clusters can track a branch (R4).
- `gitops/bootstrap/install.sh` — `TARGET_REVISION` + `GITOPS_REPO_TOKEN` knobs, argocd v3.4.5
  + sealed-secrets v0.38.4 pinned and applied server-side.
- `gitops/bootstrap/eks/` — Learner Lab cluster config (LabRole everywhere, 2× t3.large, public
  subnets, no NAT, default gp3 StorageClass) + `create.sh` / `destroy.sh` / `sweep.sh` with STS
  preflight and cost guardrails (E2).
- Service-side repairs: `unoarena` project now allows the `argocd` destination (root app was
  InvalidSpecError on every fresh cluster) and the 10 staging apps' `syncPolicy` is valid YAML
  again (automated sync had never actually been on).

## Evidence (validation.md has the full transcripts)

| AC | Result |
|----|--------|
| AC-P1.1 | Fresh kind: 7/7 platform apps Synced/Healthy in ~9 min, 4/4 probes, re-run no-op |
| AC-P1.2 | `USE_KIND=false` on a bare cluster: identical, ~8.5 min |
| AC-P1.3 | EKS (Learner Lab): create 16m58s, install 2m38s, 7/7 Healthy, 4/4 probes, no LB/NAT |
| AC-P1.4 | `destroy.sh` 10m22s + sweep prints nothing, exit 0 (after catching 2 EBS leftovers once) |
| AC-P1.5 | Merge pipeline `2707575117`: 32 success + 1 manual gate; `integration-staging:identity` green |
| AC-P1.6 | `kubectl delete ns kafka` → self-healed to Ready in ~3 min, zero manual steps |

## Deltas vs the plan (all recorded inline as they happened)

1. Platform root became **Helm-typed** (children in `templates/`) — a directory source cannot
   cascade `targetRevision`, which R4's branch rehearsals require.
2. Argo CD bumped to **v3.4.5**: 2.x cannot SSA-diff on k8s ≥1.33, and Strimzi/CNPG/prometheus
   CRDs force `ServerSideApply=true`.
3. kube-prometheus-stack drops control-plane scrape targets (kube-system Services — dead
   weight on EKS and kind alike).
4. EKS needs an explicit default StorageClass (`gp3`) — none ships since 1.30.

## Known gaps, owned by later phases

- Staging placeholders sit in ImagePullBackOff on fresh clusters: private registry pull secret,
  CI-pinned digests and per-cluster sealed secrets are P2 scope.
- Postgres runs 1 instance (kind-sized); the exam shape may raise it (values-only change).
- Budget banner reading after this rehearsal: to be noted here when the user reports it
  (~$0.35 estimated for the 65-minute rehearsal).

## Next

P2 (identity + real auth slice) — its kickoff should start from the north-star roadmap.
