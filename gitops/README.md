# GitOps cluster-state (Argo CD)

The pipeline never `kubectl apply`s. It pins the delivered image **digest** into an env overlay and
commits; **Argo CD** reconciles the cluster to match. This is the single deploy path (consigna §6.5).

## Layout

```
gitops/
  projects/unoarena.yaml          # AppProject for services (allowed repos/destinations)
  projects/unoarena-platform.yaml # AppProject for platform infra (kafka/postgres/redis/monitoring)
  root-app.yaml                   # app-of-apps → registers everything in applications/
  platform-root.yaml              # app-of-apps → renders the platform chart below
  platform/                       # Helm chart: one child Application per template
    templates/{strimzi-operator,kafka,cnpg-operator,postgres,redis,monitoring}.yaml
    values/                       # pinned chart values per component (kind-sized requests)
    kafka/ postgres/ redis/       # raw CRs / manifests the instance apps point at
  applications/<svc>-<env>.yaml   # one Argo Application per service per environment (20)
  apps/<svc>/overlays/<env>/values.yaml   # env overlay; image.digest pinned here by CI
  apps/identity/overlays/staging/sealed-secret.example.yaml   # secret pattern (no plaintext)
  bootstrap/                      # kind + Argo CD + Sealed Secrets installer (vendor-neutral)
```

The platform root is **Helm-typed** so its `targetRevision` helm parameter cascades into every
child Application — a rehearsal cluster can track a feature branch end to end. Operators sync at
wave 0 and instance CRs at wave 1 with `SkipDryRunOnMissingResource` + retry: the guarantee is
**convergence** (a from-scratch install or a deleted namespace self-heals with no manual steps).
Operator charts apply **server-side** — their CRDs exceed the client-side annotation limit.

Each Application uses Argo **multi-source**: the Helm chart at `services/<svc>/chart` plus the env
values from `$values/gitops/apps/<svc>/overlays/<env>/values.yaml` (same repo, `ref: values`).

## How a deploy happens (identity, staging)

1. `deliver:identity` captures the image digest (`@sha256:…`).
2. `deploy-staging:identity` writes that digest into `apps/identity/overlays/staging/values.yaml`,
   commits, and pushes.
3. `argocd app sync identity-staging` + `argocd app wait --health` — the **readiness gate**.
4. `integration-staging:identity` runs the Client-CLI smoke test against the staging URL.

## Promotion (build once)

`deliver-production` copies the **same digest** from the staging overlay into the production overlay
— no rebuild. Production Applications use **manual** sync (no `automated` block); staging is
automated + self-heal. Overlays differ legitimately: staging `replicas:1/logLevel:debug`,
production `replicas:3/logLevel:info`.

## Secrets

Bitnami **Sealed Secrets** — only the in-cluster controller can decrypt; the sealed blob is safe to
commit. No plaintext secret is ever in the repo. Cluster-internal by design — no external/cloud
secret backend.

## Rollback

`argocd app rollback identity-staging <previous-revision>` — or `git revert` the digest-bump commit,
which Argo auto-reconciles — restores the last healthy image.

## Bootstrap (vendor-neutral fallback)

```bash
GITOPS_REPO_TOKEN=<token> gitops/bootstrap/install.sh   # kind + Argo CD + Sealed Secrets + both app-of-apps
```

The repoURL (in `root-app.yaml`, `platform-root.yaml` and `applications/*.yaml`) and the registry
group (in the overlays) are already set to the course repo
`itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp`. Knobs:

- `GITOPS_REPO_TOKEN` — the repo is private; the script creates Argo's declarative repository
  secret from it (any token with `read_repository`; CI uses its job token, locally use a PAT).
- `USE_KIND=false` — reuse whatever cluster kubectl points at instead of creating kind; the rest
  is identical.
- `TARGET_REVISION=<branch>` — make the **platform** apps track a feature branch (rehearsals);
  the service root always tracks `main`.

Argo CD (`v3.4.5`) and Sealed Secrets (`v0.38.4`) are version-pinned and applied server-side —
`stable`/`latest` drifted into CRDs too large for client-side apply, and Argo ≥3 is required for
server-side-apply diffing against Kubernetes 1.33+.
