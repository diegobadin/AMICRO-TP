# GitOps cluster-state (Argo CD)

The pipeline never `kubectl apply`s. It pins the delivered image **digest** into an env overlay and
commits; **Argo CD** reconciles the cluster to match. This is the single deploy path (consigna §6.5).

## Layout

```
gitops/
  projects/unoarena.yaml          # AppProject (allowed repos/destinations)
  root-app.yaml                   # app-of-apps → registers everything in applications/
  applications/<svc>-<env>.yaml   # one Argo Application per service per environment (20)
  apps/<svc>/overlays/<env>/values.yaml   # env overlay; image.digest pinned here by CI
  apps/identity/overlays/staging/sealed-secret.example.yaml   # secret pattern (no plaintext)
  bootstrap/                      # kind + Argo CD + Sealed Secrets installer (vendor-neutral)
```

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
gitops/bootstrap/install.sh   # kind + Argo CD + Sealed Secrets, then the project + app-of-apps
```

Edit `REPLACE_REPO_URL` (in `root-app.yaml` and `applications/*.yaml`) and `REPLACE_GROUP` (in the
overlays) to your GitLab repo/group first. To reuse an existing cluster instead of kind, set
`USE_KIND=false` and point kubectl at it; the rest is identical.
