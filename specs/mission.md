# Mission — UnoArena DevOps Checkpoint

> Spec-Driven Development anchor document. Everything in `specs/` is grounded in this
> mission and in [`tech-stack.md`](./tech-stack.md). The authoritative assignment is
> [`docs/devops/consigna.md`](../docs/devops/consigna.md).

## 1. What we are building

A **CI/CD delivery pipeline**, in a single GitLab monorepo, that takes the microservices
from the Architecture Checkpoint **from source to a running Kubernetes deployment**, proving
that each service is **independently buildable, testable, and deployable**.

We are **not** building the real game. Every architecture service ships as a **trivial
placeholder** (a container with a `/health` endpoint and a canned response). **Exactly one
service** — `identity` — carries a **small real slice** (`register` + `whoami`) so the
staging smoke test validates real behaviour through the Client CLI, not just a canned string.

This is a deliberate reading of the faculty guidance:

> *"Es el pipeline. Hagan los placeholders solamente, no hagan todos los servicios.
> Solamente hagan 1 servicio (su implementación), pero una parte de este, no todo,
> que sirva para validar además de los tests."*

## 2. Why it matters (the grading lens)

A microservices architecture that cannot be built, delivered, and deployed **per service**
is a distributed monolith. The Design (10/10) and Architecture (10/10) checkpoints proved the
decomposition on paper. This checkpoint proves **the decomposition survives a real delivery
pipeline**: change one service, only that service rebuilds and redeploys; break one service's
test, only that service's pipeline goes red.

Grading is **pipeline shape vs. architecture decomposition** — not pipeline craftsmanship and
not service quality. A coherent placeholder pipeline passes; a fancy pipeline that rebuilds the
whole repo on every push fails.

## 3. Success criteria (from consigna §8)

| # | Criterion | How we meet it |
|---|-----------|----------------|
| 1 | **Architecture coverage** | All **10 deployables** from `09-local-topology.md` appear as their own placeholder, image, chart, and pipeline fragment. |
| 2 | **Independent deployability** | A change under `services/<svc>/**` triggers only that service's child pipeline (path-based change detection). |
| 3 | **Stage-spine discipline** | `test → build → deliver → deploy-staging → integration-staging → (opt) prod`, fail-fast, per service. |
| 4 | **End-to-end demo** | `identity` is reachable in staging and its smoke test passes **via the Client CLI** (`register` + `whoami`). |
| 5 | **Promotion model** | Build once, promote by **image digest** (`@sha256:…`) into the GitOps overlay. No rebuild per env. |
| 6 | **Deploy model coherence** | **GitOps (Argo CD)**, justified; readiness gate = Argo sync/health wait; rollback documented. |
| 7 | **Environment separation** | Staging vs prod overlays differ legitimately (replicas, URLs, log level); secrets never in the repo. |
| 8 | **Contract-check seam** | One illustrative async event-schema check between a producer and consumer placeholder. |
| 9 | **Repository navigability** | Coverage matrix (§6.9) maps each service → folder → image → Argo app → wiring depth in one hop. |
| 10 | **Traceability & honesty** | Any drift vs. the architecture is recorded in `CHANGELOG-design.md`; every stub is documented as a stub. |
| 11 | **Restraint** | No canaries, no build matrices "to look thorough", no observability stack before the smoke test works. |

## 4. Scope boundaries

**In scope:** the `.gitlab-ci.yml` + per-service fragments; placeholder source + Dockerfile +
Helm chart per service; the GitOps cluster-state (`gitops/`) with Argo CD Applications; staging
vs production overlays; secrets at the cluster boundary; the Client-CLI smoke test for `identity`;
a documented rollback path.

**Out of scope:** real domain logic, real persistence (an in-memory map is enough; `identity`
may use a trivial DB only to demo a migration job), SLOs/alerting/oncall, cluster provisioning,
multi-region / blue-green / canary, exhaustive security scanners, vendor-specific KMS.

## 5. The fully-wired service

`identity` (Identity & Session) goes the full distance. Rationale: it is the **first surface the
Client CLI touches** (`register`, `login`, `whoami`, `seed` → consigna client §5.A), it gives a
**meaningful, self-contained real slice** (register a user, assert `whoami` returns that user),
and it has no upstream service dependency, so the smoke test isolates the pipeline mechanic.

## 6. Definition of done

- Green GitLab pipeline run that reaches `integration-staging` for `identity` (linked in the README).
- All 10 services green through `test → build → deliver`.
- Coverage matrix populated and accurate; every named job exists in the pipeline.
- `devops-checkpoint/README.md` covers: layout, pipeline narrative, GitOps-vs-Helm justification,
  smoke test, and the coverage matrix.

See [`roadmap.md`](./roadmap.md) for phasing and the dated feature spec for the concrete plan.
