# Plan — P1: Platform Infra from an Empty Cluster

> How [`requirements.md`](./requirements.md) gets built, in commit-sized phases ordered by
> de-risk (Kafka on kind is the biggest unknown). Design decisions are the implementer's (D-n) —
> flag objections early, they harden as they ship.

## Design decisions (confirm in review)

- **D1 — Kafka via Strimzi, KRaft, single broker.** The architecture says Kafka (not "a broker");
  Strimzi is the standard operator and its `Kafka` CR keeps the GitOps model declarative. KRaft
  (no ZooKeeper) halves the footprint; one combined broker/controller node, small PVC.
  Alternative (Redpanda) rejected: different implementation, and the course story is Kafka.
- **D2 — Postgres via CloudNativePG.** One `Cluster` (1 instance for kind, 2 for the exam
  values), per-context **databases** inside it — per-context persistence is preserved logically;
  separate instances would triple the RAM bill for placeholder-era value. Per-service credentials
  arrive with P2 (SealedSecrets).
- **D3 — Redis standalone** as plain manifests (Deployment + Service, official `redis:7-alpine`
  pinned), no replicas, AOF off. A chart adds nothing to a one-pod cache, and the Bitnami catalog
  moved to legacy/paid images in 2025 — the official image is the boring choice. It backs SSE
  fan-out + session kill later; durability is not its job here.
- **D4 — Observability = kube-prometheus-stack**, Alertmanager disabled (restraint: no alerting
  requirement), Grafana behind port-forward until an ingress exists (P4).
- **D5 — Topology in `gitops/platform/`**: new AppProject `unoarena-platform` (its own namespaces:
  `strimzi`, `kafka`, `cnpg-system`, `postgres`, `redis`, `monitoring`) + app-of-apps
  `unoarena-platform-root`. Ordering: sync waves stagger child-app creation (operators wave 0,
  instances wave 1), but across separate apps waves don't await health — so instance apps also
  carry `SkipDryRunOnMissingResource=true` + sync retry, and the guarantee is **convergence**
  (retries land the CRs once the operator's CRDs exist), not strict sequencing. The service
  root-app is untouched (AC-P1.5).
- **D6 — EKS via eksctl config file** (`gitops/bootstrap/eks/cluster.yaml`): `LabRole` as
  service role and nodegroup instance role, 2× `t3.large` managed nodes, public subnets only
  (no NAT — E2), gp3 volumes. eksctl chosen over Terraform: the Learner Lab forbids most IAM
  work, so IaC value shrinks to "one reproducible YAML", which eksctl does with less machinery.
- **D7 — Cost guardrails as scripts**: `create.sh` (STS preflight + create + kubeconfig),
  `destroy.sh` (delete cluster + wait), `sweep.sh` (lists EKS/EC2/NAT/ELB/EBS; exits non-zero if
  anything remains). Golden rules documented in `gitops/bootstrap/eks/README.md`.

## Phases (one commit each)

| Phase | Delivers | Validated by |
|-------|----------|--------------|
| F0 | This triad | review |
| F1 | `unoarena-platform` project + platform root app + `install.sh` wiring | apps tree visible in Argo on kind |
| F2 | Strimzi operator + Kafka CR | produce/consume round-trip on kind |
| F3 | CNPG operator + Postgres cluster + context databases | `psql` connect on kind |
| F4 | Redis | `PING`→`PONG` on kind |
| F5 | kube-prometheus-stack | Grafana login page via port-forward |
| F6 | Fresh-kind full drill (AC-P1.1) + `USE_KIND=false` drill (AC-P1.2) + resource-request tuning | both drills recorded in validation.md |
| F7 | `gitops/bootstrap/eks/` + one timed EKS rehearsal + sweep (AC-P1.3/4) | rehearsal + empty sweep recorded |

## Changes by file

- `gitops/projects/unoarena-platform.yaml` — new AppProject.
- `gitops/platform-root.yaml` — app-of-apps over `gitops/platform/`.
- `gitops/platform/{strimzi-operator,kafka,cnpg-operator,postgres,redis,monitoring}.yaml` — one
  Argo Application each (Helm charts pinned by version; `redis` points at raw manifests in
  `gitops/platform/redis/`), sync-wave annotated.
- `gitops/platform/values/` — pinned chart values (small requests, kind-friendly).
- `gitops/bootstrap/install.sh` — apply the platform project + root app too, and accept a
  `TARGET_REVISION` override (default `main`) so a rehearsal cluster can track a feature branch.
- `gitops/bootstrap/eks/{cluster.yaml,create.sh,destroy.sh,sweep.sh,README.md}` — new.

## Risks

- **R1** Full stack may not fit kind on a laptop → explicit requests/limits (F6 tunes), single
  replicas everywhere, Alertmanager off.
- **R2** Learner Lab EKS quirks (blocked instance types, quota of concurrent instances) → t3
  family, 2 nodes, verified live in F7 before anything depends on it.
- **R3** Chart/operator versions drift → every chart pinned to an exact version in the
  Application spec; upgrades are deliberate commits.
- **R4** CI minutes: on `main` every push runs the full pipeline, so P1 develops on
  `feat/p1-platform` (gitops-only changes create no service jobs on branches) with the rehearsal
  cluster tracking the branch via `TARGET_REVISION`; merge fast-forward into `main` once the kind
  drills pass — one full pipeline for the whole phase. Spec/docs commits use `[skip ci]`.
