# Requirements — P9: Demo rehearsal + presentation

## Context

Phase **P9** of `specs/2026-07-26-final-delivery-northstar/roadmap.md`, the last one. P0–P8 are
shipped and closed; `main` is at `01a7dfe` and the everyday kind cluster tracks it at **24/24
Synced/Healthy**. Ten deployables are real, no overlay carries `digest: ""`, and P8 left the
system observable from the same install that brings it up.

The authority for this phase is **`docs/final/consigna.md`**, not `specs/mission.md`: start from an
empty Kubernetes cluster, deploy everything including observability with at least three business
metrics, let the faculty drive a functional test through the CLI, present the final architecture
and the important decisions, and hand over the repo link ~48h ahead.

Nothing about the system needs to change for that to be true. What does not exist yet is the
**script** — the timed sequence somebody follows on the day, the deck the teammate presents from
(N4: teammate reviews and presents), and proof that the sequence survives contact with AWS. The
roadmap also parks five items on P9's checklist; the user has taken all five.

## Goal

The demo is a script that has been executed end to end on a freshly created AWS cluster, timed,
with its failure branches written down — and a Spanish deck someone who did not build the system
can present from.

## In scope

- **`docs/demo-runbook.md`** — the timed demo script: preconditions, the minute-by-minute
  sequence, what is said while the system converges, the CLI functional pass, the boards, and an
  explicit degrade path for every step that can go wrong.
- **A 48h-before checklist**, inside the same runbook (repo link handed over, lab budget checked,
  secrets present, CI token valid, digests pinned).
- **Two rehearsals**: R0 on kind (shakedown, free) and **R1 on a freshly created EKS cluster**
  (the roadmap's requirement). Both execute the runbook verbatim and correct it — the runbook is
  the artifact under test, not a record of what happened to work.
- **`presentation/` deck**: Marp markdown in Spanish with speaker notes, rendered to HTML and PDF.
- **README final pass** against the faculty's `Client-Checkpoint.md` §9 — every canonical command,
  its backend mapping, the seeding procedure, the tournament threshold, and any gap stated openly.
- **The five carried checklist items**: EKS NodePort access, the `gcr.io/distroless` mirror, the
  `gitops-push-bot` token renewal, resource requests/limits on the ten app containers, and
  ktlint/detekt.
- Coordinating the exam date with the faculty (user action; the runbook records it).

## Out of scope

- **Any change to domain behaviour, events, schemas, topics or tables.** P9 rehearses a frozen
  system. A defect found during a rehearsal is a defect; a feature thought of during one is not.
- **Alert receivers, SLOs, tracing backend, persistent observability storage** — declined in P8
  (deltas 13.3, 13.5, 13.9) and still declined.
- **The seven untested alert rules.** They stay listed as untested. Firing all nine on purpose is
  not a demo and not a P9 deliverable.
- **Production overlays / prod promotion in the demo.** Staging is the environment the exam sees.
- **New CLI commands.** Gaps are documented, per `Client-Checkpoint.md` §8's own instruction that
  an honestly documented gap beats a silent one.
- **A recording or asciinema** (`Client-Checkpoint.md` §9 calls supporting artifacts optional).

## Decisions

### User decisions (E-n), locked in the kickoff interview

| # | Decision | Rationale |
|---|----------|-----------|
| **E1** | **Open the node security group for 30080 and 30081 in `create.sh`, and keep `kubectl port-forward` documented as the fallback.** | Two real URLs beat two long-lived port-forwards in front of the cátedra. Nothing in `gitops/bootstrap/eks/` opens either port today and the P1 rehearsal predates the gateway, so this path has never run on AWS at all — which is exactly why the fallback stays written rather than assumed away. R1 is where it becomes proven. |
| **E2** | **The demo starts from a pre-created, empty EKS cluster; the clock starts at `install.sh`.** | The consigna says *"arrancar de un cluster de k8s vacío"* — an empty cluster, not a nonexistent one. Creating it live would spend the measured **16m58s** of `eksctl` before anything can be shown. Cluster creation is still demonstrated: `create.sh` and its R1 log are shown as evidence, so N2's "we create 100% of it" holds without burning half the slot on it. |
| **E3** | **All five carried checklist items are in scope**, including the four beyond E1. | The last phase is where debts get paid or written off, and every one of these is a plausible grader question. See the risk table — this decision is also P9's largest self-inflicted risk. |
| **E4** | **Marp markdown in `presentation/`, Spanish, with speaker notes.** | Committed and diffable like every other artifact, and it renders to real slides without a second tool. Spanish because it is spoken to the cátedra — the one deliberate exception to the repo's English rule. Speaker notes carry the numbers so the presenter does not have to hold them. |

### Implementer decisions (D-n) — confirm in review

| # | Decision | Rationale |
|---|----------|-----------|
| **D1** | **The demo shows a casual game *and* a tournament, with a written cut-to-casual degrade.** | The tournament is P7's headline and `Client-Checkpoint.md` §8 lists tournament play as evaluated, but §5.E marks it *mandatory but degradable*. A timed script needs one branch it can drop without the demo losing its spine. |
| **D2** | **The SG rule is scoped to the operator's public IP (`/32`), not `0.0.0.0/0`**, with a documented one-liner to re-authorize from a different network. | The lab account is shared-fate and a wide-open NodePort on a public subnet is an avoidable finding. The exam is likely presented from a different network than the rehearsal, so the re-authorize step is part of the 48h checklist, not an afterthought. |
| **D3** | **Wire ktlint on both Kotlin services; strike detekt from `tech-stack.md` §2 with the reason recorded.** | One linter that is deterministic and auto-fixable, rather than two, days before an exam. detekt's default ruleset on two mature services opens a style argument with no time to settle it — and §2 was written for placeholders. The correction is recorded, not silent (the convention for a promise that turns out wrong). |
| **D4** | **Requests on CPU and memory; limits on memory only.** | CPU limits throttle exactly when a demo bursts. A memory limit is what lets the golden-signals board express saturation as a percentage instead of absolute bytes, which is the reason the item is on the list at all. The ten containers become Burstable, not Guaranteed, and the boards say so. |
| **D5** | **Mirror both distroless bases into the project registry by digest, refreshed by a manual CI job.** | An immutable mirrored tag is the point; a mirror that re-resolves `latest` on every pipeline reintroduces the dependency it removes. Manual because it is a supply-chain refresh, not per-commit work. |
| **D6** | **`docs/demo-runbook.md` cross-references `docs/observability-runbook.md`; it does not restate it.** | P8 wrote the operational half to be read live. Two copies of the "looks like a fault, is not" list is the two-copies-of-one-rule bug in prose form. |

## Constraints from tech-stack

- **`tech-stack.md` §2** names ktlint/detekt for `room-gameplay` and `tournament`; neither
  `build.gradle.kts` applies either plugin, so `gradle check` is `test` alone. D3 resolves the
  contradiction in the direction of the code, and edits the table.
- **§9 is already marked superseded by P8.** P9 adds nothing to the observability stack, so the
  superseding note stands unchanged.
- **§5 promotion by digest** is what makes E2 safe: the demo installs pinned digests from the
  GitLab registry and pulls nothing from `gcr.io` on the day.
- **§6 sealed secrets** — Grafana's admin credential and everything else must survive on a cluster
  that has never existed. Verified on P8's from-empty drill; re-verified at R1 on EKS.

## Risks & mitigations

| # | Risk | Mitigation |
|---|------|------------|
| R1 | **E3's checklist threatens what P9 exists to prove.** A rehearsal phase is about to touch 5 Dockerfiles, 10 charts and 2 Kotlin builds. Every one of the ten services rebuilds. | All system-touching work lands and goes green **first**, then the system freezes. No rehearsal ever runs against a build changed since the previous rehearsal — if something changes after R1, R1 no longer counts. |
| R2 | **Resource requests can make the EKS demo unschedulable.** 2× `t3.large` is 4 vCPU / 16 GiB *total* for Kafka, Postgres, Redis, the whole kube-prometheus-stack, Loki, Alloy and eleven workloads. BestEffort pods fit anywhere; requested pods do not. P1's "~8 min convergence" was a **platform-only** install — there is no EKS timing for 24 apps at all. | Size requests from usage measured on kind, then validate on EKS at R1 and not only on kind. If they do not fit, raise `desiredCapacity` to 3 (≈+50% on a ~$0.35 cycle) or drop the requests. The demo is never what gets dropped. |
| R3 | **A supply-chain change to five services, days before an exam.** | Mirror by digest, immutable tags, and prove it in the same pipeline that rebuilds everything. Rollback is a one-line revert per Dockerfile. Note that this protects *last-minute fixes*, not the demo: the day installs pinned digests and never reaches `gcr.io`. Six rejections across P6–P8, the last on P8's own closure run on a service P8 never touched, three manual retries — kaniko reports "after 0 attempts", so it does not retry a base pull and every retry is a human. |
| R4 | **The NodePort path has never run on AWS.** | E1's fallback stays written. R1 proves it, not the day. D2's re-authorize step covers presenting from a different network. |
| R5 | **A reading taken too early looks like a defect — in front of the cátedra.** P8 hit this twice in one drill: a champion with `tournaments_completed` still at 0, and a five-service trace showing three. | The runbook orders narration *between* an action and the read that depends on it, and names the wait as a wait. Prometheus scrapes every 30 s; consumers poll. This is a script-design constraint, not a caveat. |
| R6 | **The post-closure review pass is 5 for 5 on finding the phase's worst defect, in the work the phase existed to do.** For P9 that is the runbook and the deck — not the checklist. | Budget for it and aim it there: read the runbook as somebody who has never run it, and the deck as somebody who did not build the system. |
| R7 | **Learner Lab credentials rotate ~4h; the budget is $50 and exhaustion is permanent.** | `destroy.sh` + an empty `sweep.sh` end every session, never optional (E2 of the AWS runbook). Check the banner at session start. R1 is budgeted at ≈65 min / ≈$0.35, plus whatever R2 costs if a third node is needed. |
| R8 | **The exam date is not coordinated yet**, and the `gitops-push-bot` token expires 2026-09-30. | Both are on the 48h checklist; the token renewal needs the user to create it. P9 cannot fully close until the date exists. |

## Mission alignment

`specs/mission.md` §2 frames the grading lens as *"the decomposition survives a real delivery
pipeline"* — proven on paper by the design and architecture checkpoints, and by CI ever since. P9
is where that stops being an argument and becomes something watched: an empty cluster, one script,
ten independently built and digest-pinned services converging, a game played through the faculty's
own CLI, and three business metrics on a board that came out of the same git commit. The mission's
own §4 excluded observability and cluster provisioning as out of scope for the checkpoint; the
final consigna put both back, P1 and P8 built them, and P9 is the phase that makes them legible to
someone watching for forty minutes.
