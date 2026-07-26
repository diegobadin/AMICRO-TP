# DevOps Decision Records (ADRs)

> Strongly recommended deliverable (DevOps Checkpoint §7). Short records for the five delivery
> choices that are architecturally consequential (roadmap §"Decision log"). Same format as
> [`../docs/architecture/08-adrs.md`](../docs/architecture/08-adrs.md).

---

## ADR-D1: GitOps (Argo CD) over pipeline-applied Helm

**Status:** Accepted

**Context:** Consigna §6.5 demands one deploy model, justified. Deploys must be auditable, the
readiness gate honest, and the runner should not hold cluster-admin credentials it only needs for
one job.

**Decision:** GitOps with Argo CD. Cluster state lives in-repo under `gitops/` (one `Application`
per service per environment); the pipeline only commits an image digest and then waits on
`argocd app wait --health --sync` before reporting success.

**Consequences:**
- Every deploy is a git commit — staging↔production drift is a reviewable diff.
- The runner needs a repo write token, not a kubeconfig; Argo pulls.
- The pipeline must explicitly wait for reconciliation or it would lie to the next stage.
- One extra moving piece (Argo CD) that the bootstrap recipe has to install.

**Alternatives considered:** `helm upgrade --install` from the deploy job — rejected because it
puts cluster credentials in every runner, leaves no durable record of what was deployed when, and
makes the two environments diverge silently unless extra tooling is added.

---

## ADR-D2: Change detection via `rules: changes:` on per-service fragments

**Status:** Accepted

**Context:** Independent deployability (consigna §4) requires that a change limited to one service
runs only that service's jobs, with the mechanism visible to a reviewer reading the CI files.

**Decision:** Each service owns `services/<svc>/.gitlab-ci.yml`; the root file only `include:`s
fragments. Every job carries `rules: changes:` on its own path plus `ci/templates/**` (and, for
contract consumers, `ci/contracts/**`). On the default branch all jobs run (integration branch).

**Consequences:**
- Reviewer verifies the mechanism by reading one fragment — no shell to trace.
- A template change intentionally rebuilds the affected set (shared spine, justified).
- Job count grows linearly with services; acceptable at 10.

**Alternatives considered:** A `detect` job generating dynamic child pipelines — rejected for this
checkpoint as harder to review and unnecessary at this scale; recorded as the escape hatch if the
matrix grows.

---

## ADR-D3: Promotion = digest pin in the GitOps overlay

**Status:** Accepted

**Context:** Consigna §5.4/§6.4: the artifact tested in staging must be the one that reaches
production — rebuilding per environment is two unrelated builds.

**Decision:** `build` captures the image digest (`@sha256:…`) to a dotenv artifact. Deploy jobs pin
that digest (plus the repository) into `gitops/apps/<svc>/overlays/<env>/values.yaml`.
`deliver-production` copies the staging-tested digest into the production overlay behind a manual
gate — never a rebuild.

**Consequences:**
- Staging and production provably run the same bytes (grep the two overlays).
- Human-readable provenance stays on the tag (`<ref-slug>-<short-sha>`); reproducibility on the digest.
- The promotion action is a one-line bot commit, trivially revertable (rollback path).

**Alternatives considered:** Tag promotion (retagging `:prod`) — rejected because tags are mutable
and break the audit chain; rebuild-per-environment — rejected outright per consigna.

---

## ADR-D4: Secrets via Sealed Secrets, cluster-internal

**Status:** Accepted

**Context:** Secrets must reach the cluster without plaintext in the repo (blocking finding), and
the design must stay vendor-neutral — no cloud KMS while the cluster is a local kind/k3d.

**Decision:** Bitnami Sealed Secrets. Plaintext is sealed with `kubeseal` against the in-cluster
controller's key; the encrypted `SealedSecret` is committed in the overlay and only the controller
can decrypt it.

**Consequences:**
- The GitOps model stays complete: even secrets are declarative, in-repo, and diffable.
- No dependency on a cloud provider or external vault.
- Re-sealing is required if the controller key rotates or the cluster is rebuilt.

**Alternatives considered:** GitLab masked CI variables injected at deploy — rejected as the
primary path because it re-couples deploys to the pipeline (Argo would not know the secret);
External Secrets Operator / Vault — rejected as over-build for a placeholder checkpoint.

---

## ADR-D5: Contract check in `test`, blocking consumer builds

**Status:** Accepted

**Context:** One illustrative contract seam is required (consigna §6.3). The most consequential
async pair is `GameCompleted`: producer `room-gameplay`, consumers `ranking` and
`analytics-workers`. A schema change must block every affected service, not just the service that
changed.

**Decision:** `test:contract:game-completed` validates the producer sample against
`ci/contracts/game-completed.schema.json` and runs a backward-compatibility check for the
consumers. It runs in the `test` stage and is a `needs:` dependency of `build:ranking` and
`build:analytics-workers`; consumer fragments also list `ci/contracts/**` in their change rules.

**Consequences:**
- An incompatible schema edit turns the contract job red and both consumer builds never start —
  cross-service fail-fast is visible in one pipeline.
- The seam shows where real contract testing (more pairs, registry-backed) would live.
- The producer's build is not blocked: it publishes the schema, consumers own compatibility.

**Alternatives considered:** Contract check in a dedicated stage between `test` and `build` —
rejected as stage proliferation; per-consumer duplicate checks — rejected because one job blocking
both builds states the coupling exactly once.
