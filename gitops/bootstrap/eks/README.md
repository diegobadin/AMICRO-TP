# EKS rehearsal bootstrap (AWS Academy Learner Lab)

The exam opens with "empty cluster → everything running". This directory makes the cloud half
of that drill boring: one config + three scripts against the Learner Lab account
(`811591236522`, profile `amicro`, `us-east-1` — E1).

## Golden rules (E2 — cost)

1. **Billing survives the lab being OFF** for the EKS control plane, EC2, EBS and any
   NAT/ELB. Turning the lab off is NOT cleanup.
2. **Every rehearsal ends with `./destroy.sh && ./sweep.sh`** — sweep must print nothing and
   exit 0. Never end a session without it.
3. After sweeping, check the **budget banner** in the Learner Lab UI and note the reading.
4. Lab CLI credentials **rotate on every lab start and expire ~4h** — refresh the `amicro`
   profile each session. The scripts fail fast (STS preflight) instead of half-creating.

## Shape (D6)

- `eksctl` config, cluster `unoarena`, Kubernetes 1.33, 2× `t3.large` managed nodes, gp3.
- The lab forbids IAM creation → the pre-created **`LabRole`** is both the cluster service role
  and the node instance role; no OIDC/IRSA; addons run with the node role (that is how the EBS
  CSI driver — required for the Kafka/Postgres PVCs — gets its permissions).
- **Public subnets only, no NAT gateway, no LoadBalancer services** — nothing that bills idly.
- Rough cost while up: control plane ~$0.10/h + 2× t3.large ~$0.17/h ≈ **$0.27/h + EBS cents**.

## Rehearsal runbook

```bash
./create.sh                                   # ~15-20 min, STS preflight included; opens the NodePorts
export KUBECONFIG=~/.kube/unoarena-eks        # dedicated kubeconfig, kind context untouched
GITOPS_REPO_TOKEN=<token> USE_KIND=false ../install.sh
# wait for the platform apps, run the probes (see specs/2026-07-26-p1-platform-infra/validation.md)
./destroy.sh                                  # drops stateful namespaces first to free EBS
./sweep.sh                                    # MUST print nothing and exit 0
```

## Reaching the demo's two URLs (P9)

The demo answers on **two NodePorts** — 30080 (gateway) and 30081 (Grafana) — and until P9 nothing
here opened either one. On kind both are published by the node at cluster creation, so the question
never came up; the P1 rehearsal predates P4's gateway, so this path had **never run on AWS**.

`create.sh` now calls `./authorize-nodeports.sh`, which adds ingress for both ports to the cluster
security group **scoped to one source address** — the machine that ran it. The lab account is
shared-fate and these are public subnets, so `0.0.0.0/0` is not the default.

**Presenting from a different network than the rehearsal is the normal case**, and the rule follows
the address, not the cluster. Re-run it from the presenting machine:

```bash
./authorize-nodeports.sh                      # this machine's public IP, prints both URLs
SOURCE_CIDR=203.0.113.7/32 ./authorize-nodeports.sh
./authorize-nodeports.sh --revoke             # drop the rules for that CIDR again
```

It is idempotent — a second run reports "already open" rather than failing on
`InvalidPermission.Duplicate`. The rule lives on the security group EKS owns, so it goes away with
the cluster; `sweep.sh` catches a failed teardown through the CloudFormation check either way.

**If this path misbehaves on the day, the demo does not stop** — `docs/demo-runbook.md` carries the
`kubectl port-forward` fallback for both surfaces.
