DevOps Checkpoint Assignment Instructions


0) Summary
The big idea
You already designed a microservices system (Architecture Checkpoint). Now you have to prove that each service can be built, tested, and deployed independently through a real CI/CD pipeline. If you can't ship them separately, you don't really have microservices — you have a monolith in disguise.

What you actually build
Don't write real game logic. Every service is a "hello world" placeholder — a tiny container with a /health endpoint and maybe one canned response.
One GitLab repo, all services inside it (monorepo).
Final target: containers running in Kubernetes, across two environments: staging and production.

The pipeline shape (per service)
test → build → deliver → deploy-staging → integration-tests-staging → (optional prod stages)
Fail-fast: if test breaks, nothing after it runs.
Independent: changing one service should not rebuild the others (path-based triggers, change detection).

Coverage rule
Every service must go through at least test → build → deliver.
Exactly one service goes the full way through deploy-staging + integration-staging. You pick which (typically the gateway or Identity/Session — something the CLI can talk to).

Key requirements
One service = one image = one Helm chart / GitOps app. No shared "platform" image.
Build once, promote. The same image that runs in staging must be the one promoted to prod (pin by digest/SHA). No rebuilding per environment.
Helm or GitOps — pick one, justify it.
Environments must legitimately differ (replicas, URLs, configs) — but secrets never go in the repo.
Integration smoke test in staging must use the CLI from the Client Checkpoint (e.g., whoami, room list), not raw curl.
Readiness gate before the smoke test runs (kubectl rollout status, Argo sync, etc. — not sleep 30).
Document a rollback path in one sentence.

Mandatory deliverables
.gitlab-ci.yml + per-service pipeline fragments
Placeholder source + Dockerfile + Helm chart / manifests per service
The CLI-driven smoke test for the fully-wired service
devops-checkpoint/README.md covering: layout, pipeline narrative, Helm-vs-GitOps choice, smoke test, and a coverage matrix (table showing which stages each service is wired through)
A link to at least one green pipeline run that reached integration-staging

What they grade
Pipeline shape vs. architecture decomposition — not pipeline craftsmanship, not service quality. A coherent placeholder pipeline passes; a fancy pipeline that rebuilds the whole repo on every push fails.

What NOT to do
Don't implement real domain logic, DBs, or game mechanics.
Don't build canaries/blue-green if your architecture doesn't need them.
Don't add stages "to look thorough."
Don't bake secrets into images.
Don't let staging and production builds diverge.

TL;DR: Make ~7 tiny placeholder services. Wire all of them through test/build/deliver. Wire ONE all the way to a CLI-driven smoke test in a real K8s staging cluster. Document why your choices are coherent with the architecture.



1) Context (must be included in your analysis)
UnoArena: Global Real-Time Uno Platform & Massive Tournaments
This checkpoint builds on the Design Checkpoint (domain model), the Architecture Checkpoint (microservices decomposition and integration contracts), and the Client Checkpoint (CLI as the canonical command surface against your backend). The authoritative problem statement is in presentation/high-level-definition.md; the architectural decisions about services, sync/async contracts, persistence per context, and durable domain timers already produced in architecture-checkpoint/ are the input of this checkpoint and must not be re-litigated here.

Why a DevOps checkpoint, framed for a microservices course. A microservices architecture that cannot be built, delivered, and deployed independently per service is not a microservices architecture in practice — it is a distributed monolith with extra failure modes. The purpose of this checkpoint is not to teach generic CI/CD; it is to make your team show that the service decomposition you proposed in the Architecture Checkpoint survives contact with a real delivery pipeline: that each context's services can be tested, packaged, and rolled out without dragging the rest of the system, with a stage spine that is appropriate for a microservices architecture.

Services are placeholders for this checkpoint. You are not asked to implement the real services. Each architecture-checkpoint service appears in the repository as a trivial placeholder — a "hello world" container that exposes a single health endpoint, returns a canned response, prints a configured greeting, or similar — that is enough to exercise the pipeline shape without producing real domain behavior. The grading lens is the pipeline and its fit to your microservices decomposition, not the services themselves.

Workloads will run in Kubernetes. The final deployable form of each placeholder service is a container running in a Kubernetes cluster, in two environments (staging and production). Deployments are driven from the pipeline either via Helm releases (pipeline-applied) or via GitOps (Argo CD / Flux watching a cluster-state repo). Both are acceptable; the trade-off must be argued.



2) Assignment objective
Deliver, in a single GitLab repository — your group's designated repo — the full DevOps pipeline that would take the microservices you specified in the Architecture Checkpoint from source to a running Kubernetes deployment, with the following pipeline spine per service:

test → build → deliver → deploy (staging) → integration tests (staging) → (optional) deliver (production) → (optional) deploy (production)

The pipeline must implement a fail-fast strategy: as soon as any earlier stage fails for a given service, downstream stages for that service do not run and the pipeline reports the failure clearly.

Coverage rule. All services from your architecture decomposition must be present in the repository as placeholders and must be wired into the pipeline through at least test → build → deliver (so reviewers can verify the per-service stage spine and fail-fast strategy for every service). At least one of those services must be wired all the way through deploy (staging) → integration tests (staging) (and, optionally, to production) so that the complete process is verifiable end to end on a real cluster. You choose which service goes the full distance; pick one whose public interface lets you write a meaningful CLI-driven smoke test (e.g., the gateway/BFF or the Identity/Session service in most architectures).

Integration tests in staging, for the fully-wired service, must drive it through the CLI from the Client Checkpoint (even if only as a smoke call against a placeholder endpoint) so that the integration harness is consistent with what the faculty will run for grading and with your group's previous deliveries.

The goal of grading is microservices architecture quality through the lens of delivery, not pipeline craftsmanship and not service implementation. A pipeline that builds and deploys "the whole repo" on every push, or that fans every service through one shared image, does not demonstrate independent deployability and will be marked accordingly even if every job is green. Conversely, a pipeline whose services are placeholders but whose stage spine, fail-fast wiring, change detection, image versioning, deploy model, and promotion story are coherent and traceable to the architecture passes this checkpoint.



3) Relationship to the previous checkpoints
Your submission must include a placeholder for every service named in the Architecture Checkpoint — same names, same boundaries, same ownership boundaries (one placeholder per service, not one shared "monolith placeholder"). If you renamed, split, or merged a service since the Architecture Checkpoint, update the architecture document and note the delta (same CHANGELOG-design.md mechanic as in architecture-checkpoint/ §6.2, extended to architecture artifacts). Silent drift between the architecture document and the placeholder set is an automatic deduction — if the architecture says seven services and the pipeline knows about four, that is a finding about the architecture, not just the pipeline.

The fully-wired service's integration test in staging must use the CLI from the Client Checkpoint as the entry point (one or two CLI subcommands, asserting against the canned placeholder response). If you skip the CLI and use curl directly, you are removing the only piece of evidence that your team's integration surface matches the canonical contract from the previous checkpoint.

You are not asked to re-prove the architectural invariants (log-before-broadcast, durable timers, single-active-session push invalidation, spectator privacy, etc.) in this checkpoint — there are no real services to exercise them against. You are asked to show that the pipeline shape is capable of carrying invariant-level integration tests later, by demonstrating the mechanic on one service end to end.



4) Scope and constraints
In scope. GitLab CI/CD pipeline definition (.gitlab-ci.yml and any included templates/fragments); per-service test, build, deliver, and (for the chosen service) deploy jobs; Kubernetes manifests via Helm charts or GitOps-tracked manifests; environment separation (staging vs production); secrets and configuration management at the pipeline / cluster boundary; integration smoke test in staging driven by the Client CLI for the fully-wired service; documented rollback path.

Service placeholders (mandatory shape, minimal content). Each placeholder service is expected to:
Live in its own folder named after the architecture service.
Have its own Dockerfile (or equivalent) that builds a runnable container.
Expose at least a health endpoint (GET /health → 200 OK is enough) and, for the fully-wired service, one endpoint that returns a canned response the Client CLI can assert against (e.g., a stub whoami / room list / tournament status).
Carry at least one trivial unit test so the test stage has something to run and can demonstrate the fail-fast wiring (a deliberately failing test on a branch will be enough for reviewers to verify behavior — see §6.3).
Be independent: building one placeholder must not require building another. The placeholders may share a base image or template, but they are separately versioned and delivered. A single "platform" image that all services pull from at runtime is not a placeholder service — it is one service in a trench coat.

Out of scope (do not over-build — this is not a DevOps course, and the services are placeholders).
No real domain code. Do not implement Room Gameplay, the RNG service, the tournament state machine, the Elo calculator, etc. Trivial canned responses are the expected shape.
No real persistence. Placeholder services do not need a real database; an in-memory map is enough. If you wire a database for the fully-wired service to demonstrate the migration job (see §6.5), keep it trivial.
No production-grade SLO definitions, alert routing, or oncall runbooks.
No cluster provisioning (the cluster is assumed to exist; you may sketch a minimal kind/k3d/minikube local equivalent if helpful for reviewers).
No multi-region, multi-cluster, blue-green / canary infrastructure beyond what your architecture genuinely needs. If you do not need canaries, do not build canaries.
No exhaustive security scanner / compliance tooling configuration; mention what you would add and where, but do not invent fictional compliance pipelines.
No vendor-specific platform features (managed Kafka SKUs, cloud KMS specifics, IaC for cloud accounts). Keep cluster-internal where possible.

Single repository constraint. All services live in one repository on GitLab. The pipeline is the single source of truth for what gets deployed; manual kubectl apply / helm upgrade from a developer laptop is not part of the deliverable and must not be documented as the operational path.

Fail-fast constraint. Failure of a test, build, contract check, or staging integration test for a given service must prevent its own subsequent stages from running. Cross-service contract failures (e.g., an event schema change without its consumer update) must block all affected services. A pipeline that "lets the deploy job run anyway because the test was flaky" violates the constraint.

Independent deployability constraint. A change limited to one service's source must not rebuild and redeploy every other service. Use path-based triggers, change detection, per-service pipeline fragments, or equivalent. If you intentionally rebuild the world on every push (e.g., because your services share a base image that warrants it), justify it; otherwise it is a finding.



5) Mandatory methodology
Treat the pipeline as a product of the architecture, not the other way around. Concretely:

Per-service pipeline fragments. Each service from the Architecture Checkpoint has its own pipeline definition (an included *.gitlab-ci.yml fragment, a template instantiated per service, or an equivalent mechanism). The root .gitlab-ci.yml orchestrates them. One giant inline pipeline that conditionally branches on $CHANGED_FILES with shell is discouraged — it tends to hide independent deployability behind shell logic.

Stage spine, not stage proliferation. The canonical stage spine for each service is the seven stages of §2. Add stages only when an architectural reason demands them (e.g., a separate migrate stage if you have schema migrations that must run before deploy); do not add stages to look thorough.

One service = one image = one chart/release. Each service produces its own container image with its own version tag and is deployed by its own Helm release (or its own set of GitOps-tracked manifests). Bundling multiple services into one chart is acceptable only when they truly share a release lifecycle (e.g., a sidecar); the bundle must be justified in the README.

Promotion model. State, in one short paragraph, how an artifact built once is promoted from staging to production (image digest pinning, GitOps environment overlays, tag promotion, etc.). Rebuilding from source between staging and production is not a promotion model — it is two unrelated builds, and it defeats the purpose of testing in staging.

Trace each architectural invariant to a job. For each invariant in §6.6, point to the pipeline job (by name) that exercises it. Reviewers should be able to open .gitlab-ci.yml and find the job.



6) Required deliverables
Your submission must include all of the following.

6.1 Repository layout, per-service ownership, and the fully-wired service
Document the monorepo layout: top-level folders per placeholder service (matching the Architecture Checkpoint names), a place for shared pipeline templates / Helm chart library / GitOps manifests, and a place for the integration test harness (Client CLI invocation specs + fixtures used by the fully-wired service's staging smoke test). For each service, state:

Source path.
Pipeline fragment path.
Helm chart path (or GitOps manifest path) — even for services that do not deploy in this checkpoint, the chart/manifest should exist as a placeholder so reviewers see what would ship.
Container image name (registry path, naming convention).
Image versioning scheme (semantic version, commit SHA, build number, hybrid — pick one and justify; the scheme must let you reproducibly point staging and production at the same built artifact, see §5.4).
Wiring depth: which stages of the spine the service is wired through — test/build/deliver for placeholder services, test/build/deliver/deploy-staging/integration-staging (and optionally production) for the one fully-wired service.

Include a short diagram (Mermaid or ASCII) or a table mapping architecture service → repository folder → image → Helm release / GitOps app name → wiring depth. Reviewers must be able to walk from the architecture diagram to a concrete path in the repo in one hop, and tell at a glance which service is the fully-wired one.

Choosing the fully-wired service. Pick a service from your architecture whose public interface gives the Client CLI a non-trivial smoke target — typically the gateway/BFF, the Identity/Session service, or whichever service whoami / room list / tournament status lands on in your decomposition. Document the choice and the rationale in one short paragraph.

6.2 GitLab CI pipeline structure (mandatory)
Provide the actual .gitlab-ci.yml (and any included files), plus a narrative section in the README that explains, per stage, what runs and what the failure semantics are:

test — unit tests, static analysis, contract tests (see §6.3). Must complete before build for the same service. Fail-fast: any failure aborts that service's pipeline.
build — compile / assemble the service artifact (binary, JAR, etc.) if your stack has a separate compile step distinct from image build. Teams whose build collapses into image build may merge these and must say so.
deliver — publish the container image to the GitLab Container Registry (or equivalent) and the Helm chart (if applicable) to a chart registry. The image must be tagged according to §6.1 and pulled by digest in later stages where reproducibility matters.
deploy-staging — apply the Helm release or GitOps app for the staging environment. Required for the fully-wired service; optional (placeholder job that is skipped via rules: or marked when: manual is acceptable) for the others. Must be idempotent. Must wait for readiness (rolling out, healthy pods, database migrations applied if any) before declaring success.
integration-staging — run the smoke test of §6.6 against the staging environment, driven by the Client CLI. Required for the fully-wired service only.
deliver-production (optional) — promote the artifact (not rebuild). Behind a manual gate (GitLab when: manual) is acceptable and recommended.
deploy-production (optional) — apply to the production environment. Manual gate is again recommended.

Fail-fast wiring (mandatory subsection). Document how fail-fast is enforced:
needs: / dependencies: configuration that ensures build does not start if test failed.
Cross-service blocking: if a shared contract test (event schema check, OpenAPI lint against producer + consumer) fails, which services are blocked and how the pipeline surfaces it.
Behavior on flakes: retries (if any) must be bounded and explicit; a job that retries three times silently is hiding instability and is not fail-fast.

Change detection (mandatory subsection). Document how the pipeline avoids rebuilding / redeploying unchanged services. Acceptable mechanisms: GitLab rules: changes: on per-service paths, dynamic child pipelines, an explicit "affected services" computation step that drives downstream jobs. If your team intentionally rebuilds everything, see §4.

6.3 Test stage — what counts (mandatory, calibrated for placeholders)
The test stage must cover at minimum, per service:

At least one trivial unit test that runs against the placeholder. Its job is to prove the test stage exists and is wired correctly — not to test real domain logic. Reviewers may push a deliberately-failing test on a branch to verify that the pipeline reports red and prevents build from running; the test stage must support that.
Static analysis appropriate to the stack (linter / type check / go vet / equivalent). Cheap to add even on placeholders and demonstrates that the pipeline enforces basic hygiene.
(Strongly recommended, illustrative on one pair) A contract check on one meaningful interface — pick the most interesting pair from your architecture and wire the check even if the producer/consumer are placeholders:
For a synchronous interface: an OpenAPI / .proto / GraphQL schema lint, plus a stub consumer test that calls a canned response from the producer placeholder.
For an asynchronous interface: an AsyncAPI / JSON Schema / Avro / Protobuf compatibility check between a producer placeholder and a consumer placeholder. The point is to show where the contract check lives in the pipeline, not to validate a real production contract. A single illustrative check is sufficient; full coverage across every pair is not required.
(Optional) A lightweight security check (dependency scan, container base-image scan). Not a blocking finding if missing.

What fails this bar: a test stage that does not exist per service, that does not block build on failure, or that is wired identically across every service in a way that ignores their individual sources.

6.4 Build and deliver — artifacts and versioning (mandatory)
The container image is the unit of delivery. Each service has its own Dockerfile (or equivalent), produced by its own build job, pushed to the registry by its own deliver job.
The image tag must encode both human-readable provenance (semver or branch name) and a content-addressable form (commit SHA or image digest). Deploys to staging and production must pin to the same content-addressable identifier of the artifact built once (see §5.4).
Helm charts (if used) are versioned independently from the image, published to a chart registry (GitLab packages or equivalent), and referenced by version from the deploy stage. GitOps users: the manifest repo (or path) must be updated by the deliver stage with the new image reference; document the bot/PR mechanism.
Image build must produce an artifact that runs in both staging and production unchanged; environment differences (URLs, secrets, scale, feature flags) come from configuration, never from the image itself.

What fails this bar: separate builds for staging and production from the same source; hand-edited image tags in the production deploy job that diverge from the staging deploy; secrets baked into the image.

6.5 Deploy to staging and production — Helm or GitOps (mandatory choice, applied to the fully-wired service)
Pick one primary deployment model for the team and justify the choice in one short section:

Helm releases applied from the pipeline. The deploy-staging / deploy-production job runs helm upgrade --install (or equivalent) against the cluster, using a chart versioned in §6.4. Document where kubeconfig / cluster credentials live and how the runner is authorized.
GitOps (Argo CD / Flux / Rancher Fleet / equivalent). The pipeline does not apply manifests to the cluster directly; it updates a cluster-state repo (or a path in this repo) with the new image reference, and the cluster controller reconciles. Document where the state repo lives, who can write to it, and how the pipeline observes that reconciliation succeeded before declaring deploy-staging green (a pipeline that says "deployed" while Argo is still syncing is lying to the next stage).

For both models, you must specify (concretely for the fully-wired service; the same shape may be assumed for the others without re-stating them):

Helm chart values (or kustomize overlays / manifest patches) for staging vs production — replicas, resource requests/limits, environment-specific config (URLs, broker addresses, feature flags). At minimum, one value must legitimately differ between environments — if your staging and prod values are byte-identical, you have not modeled the environments.
Secrets management. Where secrets live (sealed-secrets, External Secrets Operator, Vault, GitLab CI variables masked, etc.) and how the pipeline gets them into the cluster without committing plaintext. Plaintext secrets in the repo are a blocking finding.
Database migrations (only if your fully-wired placeholder uses a real DB to demonstrate this — optional, but if you do it, document which job runs migrations, at what point in the staging deploy, and how a failed migration aborts the deploy rather than leaving the cluster in a half-state).
Readiness gate. How deploy-staging knows the rollout is healthy before letting integration-staging start. Liveness/readiness probes in the chart, plus a pipeline-side wait (kubectl rollout status, Argo sync status, etc.). A sleep 30 is not a readiness gate.
Rollback path. Document the operator action that rolls a bad release back for the fully-wired service. One sentence is enough; not having one is a finding.

6.6 Integration tests in staging — Client CLI smoke test on the fully-wired service (mandatory)
The integration-staging stage must run at least one smoke test that drives the fully-wired placeholder service through the Client CLI from client-checkpoint/ (the same canonical command surface §5 / output contract §6 the faculty will run). The test's purpose is to verify the complete pipeline mechanic: that an image built and delivered by this pipeline was actually deployed to the staging cluster, is reachable from a CLI invocation, and returns the canned response the placeholder is configured to emit.

Minimum content of the smoke test:

Invoke one or two CLI subcommands appropriate to the fully-wired service. Examples (pick what fits the service you wired):
For an Identity/Session placeholder: register --user X --pass Y followed by whoami — assert that the canned response identifies the user.
For a gateway/BFF placeholder: room list — assert the JSON-line response shape (e.g., an empty array [] or the canned room list the placeholder returns).
For a Tournament Orchestration placeholder: tournament status <id> — assert the canned status payload.
Consume the CLI's --json output (client-checkpoint/ §6) and assert against it. The assertion can be as simple as result == "ok" and a single field match.
Use the staging URL of the placeholder service, injected as an environment variable in the job (UNOARENA_API_URL=...). Hard-coding localhost is a finding.

Failure semantics. The smoke test must fail the pipeline when the deployed placeholder is unreachable, returns the wrong canned response, or never started. A flake budget (e.g., one retry max) is acceptable but must be explicit. The test must be hermetic against staging: if it creates state (a seeded account, a placeholder room), it must clean up or namespace itself so repeated runs do not contaminate each other.

Not required (do not over-build). You are not asked to test architectural invariants (log-before-broadcast, single-active-session push invalidation, 60-second reconnection window, spectator privacy, tournament series coordination, etc.) in this checkpoint — there is no real service behavior to test them against. You are asked to demonstrate that the pipeline mechanic is in place such that those invariant tests could be added later, one row at a time, against real services without changing the pipeline shape. If you want to stub an invariant test that always passes (so the job slot exists in .gitlab-ci.yml), that is welcome — make clear in the README that the stub is a placeholder for a real test.

6.7 (Optional) Deliver and deploy to production
Production stages are optional for this checkpoint. If you implement them:

They run only on a tagged commit / protected branch and behind a manual gate.
They promote the artifact tested in staging (image digest pinning or equivalent); no rebuild. See §5.4.
The production environment uses the same chart / manifests as staging with environment-scoped value overrides (§6.5).
A smoke test in production (one or two CLI assertions against a non-destructive endpoint — whoami, a no-op room list, etc.) is recommended after the deploy. Full integration tests against production are explicitly not required and should be approached with care.

Not implementing production stages does not lose points if the staging side is solid and the promotion model is documented as if it would be done.

6.8 Cross-cutting: configuration, secrets, observability hooks
Configuration. Per-service, per-environment configuration values live in the chart values / overlay / GitOps repo, not in the image. Document where the source of truth is.
Secrets. As in §6.5.
Observability hooks (lightweight). You are not required to set up dashboards, alerting, log aggregation, or tracing collectors. You are required to show that the fully-wired placeholder emits at least one structured log line when the CLI smoke test hits it, and that an operator could retrieve that log via kubectl logs from staging. One documented invocation pattern in the README is sufficient. If you later swap the placeholder for a real service, that log path is what carries correlationId end to end — the point here is to leave the seam in place.

6.9 Service × pipeline-coverage matrix (mandatory)
Provide a small table (one row per service from the architecture) showing, for each service, which pipeline stages it is wired through and which it intentionally skips:

| Service (Architecture Checkpoint name) | test | build | deliver | deploy-staging | integration-staging | deliver-prod | deploy-prod | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e.g. Identity/Session (fully wired) | ✅ | ✅ | ✅ | ✅ | ✅ | ⬜ optional | ⬜ optional | chosen as the end-to-end demonstrator |
| Room Gameplay | ✅ | ✅ | ✅ | ⬜ stubbed | ⬜ stubbed | — | — | placeholder; deploy job present but when: manual |
| RNG / Deck | ✅ | ✅ | ✅ | ⬜ stubbed | ⬜ stubbed | — | — | placeholder |
| Tournament Orchestration | ✅ | ✅ | ✅ | ⬜ stubbed | ⬜ stubbed | — | — | placeholder |
| Spectator View | ✅ | ✅ | ✅ | ⬜ stubbed | ⬜ stubbed | — | — | placeholder |
| Ranking / Elo | ✅ | ✅ | ✅ | ⬜ stubbed | ⬜ stubbed | — | — | placeholder |
| … one row per architecture service | | | | | | | | |

Exactly one row must have integration-staging ✅, and that row identifies the fully-wired service. Every row must have test → build → deliver ✅ (the minimum required wiring per §2).

A reviewer must be able to read this table, open .gitlab-ci.yml, and find the named jobs for the listed stages — both the active ones and the placeholders. A missing service row, or a row whose claimed jobs do not exist, is a finding.

Stretch goal (optional). Once the pipeline shape is in place, you may add a second small table that lists the Architecture Checkpoint invariants (log-before-broadcast, single-active-session push invalidation, 60-second reconnection window, spectator privacy, match series coordination, async event schema compatibility, etc.) with the future pipeline job that would carry the integration test for each. Stub-job placeholders in .gitlab-ci.yml that always pass and that are documented as "to be implemented when the real service replaces the placeholder" are welcome — they show the seam where the checkpoint hands off to a real implementation phase. This is optional.



7) Suggested additional deliverables (to demonstrate understanding)
Items below are not required unless your instructor says otherwise. Use the tiers to prioritize.

Strongly recommended
Decision log (ADRs) — Short ADRs for the top 3–5 DevOps choices that are architecturally consequential: Helm vs GitOps, monorepo change-detection strategy, promotion model, secrets backend, contract-test placement. Same format as the architecture-checkpoint/ ADR suggestion.
Local topology — A docker-compose or kind/k3d recipe that reviewers can run on a laptop to bring up a representative subset of the services. Strongly helps grading in case the staging cluster is unavailable during evaluation.
Per-service README snippets documenting how to run that service's pipeline fragment locally (image build, chart lint, etc.).

Optional enrichment
Renovate / Dependabot config for image and chart-version updates.
Container image hardening notes — non-root user, minimal base, multi-stage builds.
Cluster-side policy (NetworkPolicies, PodSecurityStandards) sketches per service.



8) Evaluation criteria
Submissions will be evaluated on:

Architecture coverage — Every service from the Architecture Checkpoint appears in the repository as its own placeholder, with its own pipeline fragment, image, and chart/manifest. Missing services are findings against the architecture, not just the pipeline.
Independent deployability — A change in one placeholder's source path triggers that placeholder's pipeline only (or a justified bounded set). The pipeline does not couple services that the architecture said were independent.
Stage-spine discipline — test → build → deliver → deploy-staging → integration-staging → (optional) deliver-prod → (optional) deploy-prod, fail-fast, per-service. Deviation from the spine is justified. Every service reaches at least test → build → deliver; one service goes the full distance.
End-to-end demonstration on the chosen service — The fully-wired service exists, is reachable in staging via the Client CLI, and the smoke test of §6.6 passes in a real pipeline run.
Promotion model — Built once, promoted to environments; not rebuilt per environment. Reproducible by digest or equivalent.
Deploy model coherence — Helm or GitOps chosen with reasons; readiness gate exists and is honest; rollback path documented for the fully-wired service.
Environment separation realism — Staging values and production values legitimately differ where they should; secrets are not in the repo.
Contract-check seam (illustrative) — At least one contract check (sync or async) exists somewhere in the pipeline, even if it operates over placeholders. It demonstrates where real contract testing would live.
Repository navigability — A reviewer can walk from an architecture service name to its folder, pipeline fragment, chart/manifests, and (for the fully-wired one) its smoke test in one or two clicks. The §6.9 coverage matrix is populated and accurate.
Traceability and honesty — Differences between the architecture document and the set of placeholders are explicitly called out in the changelog. Stages or services that you intentionally stubbed are documented as such, not silently skipped.
Restraint — The pipeline does what the architecture decomposition implies and stops there. Build matrices that exist "because they look thorough", parallel jobs that produce identical artifacts, stages that do nothing, and observability tooling layered in before the smoke test works are evidence of misallocated effort. Implementing real services to "make the integration test richer" is out of scope and will not gain points — it will, however, cost time you should have spent on the pipeline shape.



9) Submission format and deadline
Deliver in your group's designated GitLab repository (the same monorepo whose pipeline this checkpoint defines). The repository must contain:
The root .gitlab-ci.yml and any included pipeline fragments.
Per-service placeholder source, Dockerfile, and Helm chart / GitOps manifests, organized as in §6.1. One service is fully wired through staging; the rest are placeholders wired through test → build → deliver.
The smoke test for the fully-wired service (Client CLI invocation + assertion + any fixtures it needs).
A devops-checkpoint/README.md that links: the §6.1 layout (including which service is fully wired and why), the §6.2 pipeline narrative, the §6.5 deploy-model justification, the §6.6 smoke-test description, and the §6.9 coverage matrix.
The updated architecture document (or its delta CHANGELOG-design.md) if service boundaries moved since the Architecture Checkpoint (see §3).
Provide a link to at least one green pipeline run that reached integration-staging for the fully-wired service (a link to a passing GitLab pipeline page is enough). If the staging cluster is shared and intermittently unavailable, include the run history showing the pipeline did pass at least once on the submission branch and document the recovery procedure.
Deadline: TBD by the teaching staff.

Note for the teaching staff (remove before publishing to students): this checkpoint intentionally tests the pipeline shape against the architecture decomposition, not service implementations. Services are placeholders so students can focus on per-service independent deployability, the seven-stage spine, fail-fast wiring, change detection, image versioning, the Helm-vs-GitOps trade-off, the promotion model, and the Client-CLI integration seam — without burning the budget on real game logic. One service is wired end-to-end (the "complete process" demonstration); the rest exist to prove the per-service shape holds across the architecture. The Architecture Checkpoint invariants remain the long-term target — the §6.9 stretch table is where teams that want to push further can stub the future invariant tests so the seam is visible.
