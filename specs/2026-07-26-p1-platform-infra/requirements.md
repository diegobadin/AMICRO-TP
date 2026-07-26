# Requirements — P1: Platform Infra from an Empty Cluster

> First phase of the final-delivery program
> ([`../2026-07-26-final-delivery-northstar/`](../2026-07-26-final-delivery-northstar/)). The exam
> opens with "empty k8s cluster → everything running"; P1 makes that a boring, repeatable drill
> before any real service exists.

## Objective

One command against any kubeconfig installs the whole platform via Argo CD: Kafka, Postgres,
Redis and the observability stack come up Healthy from nothing, on local kind (rehearsal) and on
an EKS cluster created in the AWS Academy Learner Lab (exam shape) — with zero AWS resources left
behind after a rehearsal.

## Locked decisions (session 2026-07-26)

| # | Decision | Chosen |
|---|----------|--------|
| E1 | AWS account | Learner Lab, account `811591236522`, profile `amicro`, region `us-east-1` (faculty mandated none) |
| E2 | Cost stance | Create → rehearse → destroy every time; no resource survives a lab session |
| E3 | Cluster IaC | eksctl config using the pre-created `LabRole` (Learner Lab blocks IAM creation) |

## In scope

- `gitops/platform/`: Argo `Application`s for the platform, under a new `unoarena-platform`
  AppProject and a `unoarena-platform-root` app-of-apps (operators before instances, sync waves).
- Kafka (Strimzi, KRaft, single broker), Postgres (CloudNativePG, one cluster), Redis
  (standalone), kube-prometheus-stack (Prometheus + Grafana; business dashboards stay in P8).
- Bootstrap split: `install.sh` keeps working for kind AND any existing kubeconfig; new
  `gitops/bootstrap/eks/` (eksctl config + create/destroy/sweep scripts, cost guardrails).
- One EKS rehearsal end to end, timed, ending with an empty sweep.

## Out of scope (→ later phases)

- Services actually using Kafka/Postgres/Redis (P2+), per-service DB credentials (P2).
- Business metrics, dashboards, ServiceMonitors for placeholders (P8).
- Ingress controller / TLS (decided when the CLI needs a stable URL, P4).
- Any automation of Learner Lab budget monitoring (manual banner check, E2).

## Acceptance criteria

- **AC-P1.1** On a fresh kind cluster, `gitops/bootstrap/install.sh` alone brings Argo CD + all
  platform apps to `Synced/Healthy`; Kafka accepts a produce/consume round-trip, Postgres accepts
  a connection, Redis answers PING, Grafana serves its login page (port-forward).
- **AC-P1.2** The same script with `USE_KIND=false` against any pre-existing kubeconfig performs
  identically — no kind-specific assumptions in the platform path.
- **AC-P1.3** `eks/create.sh` stands up an EKS cluster in the Learner Lab using `LabRole` for
  every role, public subnets only (no NAT), no LoadBalancer services; then AC-P1.1 passes on it.
- **AC-P1.4** `eks/destroy.sh` + `eks/sweep.sh` end the rehearsal with zero EKS/EC2/NAT/ELB/EBS
  resources (sweep output empty).
- **AC-P1.5** The existing pipeline stays green: `integration-staging:identity` is unaffected
  (platform apps live in their own project/namespaces).
- **AC-P1.6** A from-scratch sync converges with no manual intervention: instance apps retry
  until their operator's CRDs exist (waves + `SkipDryRunOnMissingResource` + retry), and a
  deleted platform namespace self-heals back to Healthy.

## Behaviour contract (edge cases)

- Learner Lab billing continues while the lab is OFF for EKS control plane/NAT/ELB/EBS — the
  destroy script is part of every rehearsal, never optional (E2).
- Lab credentials expire (~4h): scripts fail fast with a clear message when STS rejects the
  session token, instead of half-creating resources.
- kind is memory-constrained: platform components run with explicit small resource requests so
  the full stack + placeholders fit a laptop.
- A re-run of `install.sh` on an already-installed cluster is a no-op (idempotent), not an error.
