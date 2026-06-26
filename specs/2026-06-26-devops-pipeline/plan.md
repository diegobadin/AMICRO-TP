# Plan — DevOps Pipeline (Phases 1 & 2)

> Implementation design for [`requirements.md`](./requirements.md). Describes *what gets built and
> where*, not final code. Grounded in [`../tech-stack.md`](../tech-stack.md).

## 1. Repository layout

```
.
├── .gitlab-ci.yml                      # root: stages + child-pipeline orchestration
├── ci/
│   ├── templates/
│   │   ├── service-spine.yml           # reusable test→build→deliver template (per language flavor)
│   │   ├── build-kaniko.yml            # image build+push, captures digest → dotenv artifact
│   │   ├── deploy-gitops.yml           # bump digest in overlay + argocd sync/wait
│   │   └── smoke-cli.yml               # integration-staging via Client CLI
│   └── contracts/
│       └── game-completed.schema.json  # async contract used by the seam check
├── services/
│   ├── gateway/         { src/, Dockerfile, chart/, .gitlab-ci.yml, tests/ }
│   ├── identity/        { src/, Dockerfile, chart/, .gitlab-ci.yml, tests/ }   ⭐ real slice
│   ├── room-gameplay/   { … Kotlin … }
│   ├── tournament/      { … Kotlin … }
│   ├── ranking/         { … Python … }
│   ├── analytics-workers/ { … Python … }
│   ├── analytics-api/   { … Python … }
│   ├── spectator/       { … Node … }
│   ├── outbox-relay/    { … Go … }
│   └── timer-worker/    { … Go … }
├── gitops/
│   ├── bootstrap/                      # kind + Argo CD install (vendor-neutral fallback)
│   ├── projects/unoarena.yaml          # Argo AppProject
│   └── apps/<svc>/
│       ├── base/                       # Argo Application(s) pointing at services/<svc>/chart
│       └── overlays/{staging,production}/values.yaml   # digest pin + env-specific values
├── clients/
│   └── cli/             { src/, Dockerfile, README }   # Client CLI (Node/TS): register, whoami, --json
├── integration/
│   └── identity-smoke/  { test spec + fixtures }       # drives clients/cli
└── devops-checkpoint/README.md          # the graded narrative + coverage matrix
```

A reviewer walks **architecture service → `services/<svc>/` → image `…/<svc>` → Argo app
`<svc>-staging` → wiring depth** in one hop (matrix in the README).

## 2. Placeholder service shape (the 9 canned ones)

Each is the **minimum runnable container** in its architecture language:
- `GET /health` → `200 {"status":"ok","service":"<svc>"}`.
- One canned endpoint (e.g. `gateway` `GET /rooms` → `[]`).
- ≥1 trivial unit test (asserts `/health` body) + static analysis.
- `Dockerfile`: multi-stage, non-root, minimal base (distroless/alpine).
- `chart/`: Deployment + Service + liveness/readiness probes on `/health`; `values.yaml` with
  `image.repository`, `image.digest`, `replicas`, `env`.

> They MAY share a chart *library*/template, but each is **separately versioned and delivered** —
> never one platform image (consigna §4).

## 3. The `identity` real slice ⭐

- `POST /register {user,pass}` → creates account in an **in-memory map**, returns `{userId,user}`.
- `POST /login {user,pass}` → returns a signed token (trivial JWT/HMAC).
- `GET /whoami` (token) → `{userId,user}` for the token holder.
- `GET /health`.
- Structured JSON log per request: `{ts,level,service,action,user,correlationId}`.
- Unit tests: register-then-whoami returns the same user; whoami without token → 401.
- (Optional migrate-job demo) a trivial Postgres + a `migrate` job that runs before deploy and
  aborts the deploy on failure. Default: in-memory (keep it trivial).

## 4. Pipeline architecture

### 4.1 Stages (root `.gitlab-ci.yml`)
```
stages: [test, build, deliver, deploy-staging, integration-staging,
         deliver-production, deploy-production]
```

### 4.2 Change detection — `rules: changes:` per-service includes (implemented)
- The root `.gitlab-ci.yml` `include:`s each `services/<svc>/.gitlab-ci.yml` fragment plus the
  shared `ci/templates/*` and the contract job.
- Every job in a fragment is guarded by `rules: changes: ["services/<svc>/**/*", "ci/templates/**/*"]`,
  so a change limited to one service only creates that service's jobs. A change to `ci/templates/**`
  intentionally rebuilds the affected set (justified: shared spine).
- Result: editing `services/ranking/**` runs only `ranking`'s `test→build→deliver`.
- **Alternative considered:** a `detect` job generating dynamic child pipelines. Rejected for the
  checkpoint — `rules: changes:` on per-service paths is explicitly acceptable (consigna §6.2),
  deterministic, and trivially reviewable. Documented as a future option if the matrix grows.

### 4.3 Fail-fast wiring
- `build:<svc>` `needs: [test:<svc>]`; `deliver:<svc>` `needs: [build:<svc>]`; etc. A failed
  upstream job means downstream **does not start**.
- **Cross-service block:** the contract-check job (`test:contract:game-completed`) is a `needs:`
  dependency of `build:ranking` and `build:analytics-workers`; its failure blocks both consumers.
- **Flakes:** at most `retry: 1` on the smoke test only, declared explicitly; no silent 3× retries.

### 4.4 `build` + `deliver`
- `build` (Kaniko) builds `services/<svc>/Dockerfile`, pushes
  `…/<svc>:${CI_COMMIT_REF_SLUG}-${CI_COMMIT_SHORT_SHA}`, and writes the resulting
  **digest** to a dotenv artifact (`<svc>.digest`).
- `deliver` packages/pushes the Helm chart (GitLab Packages) — chart version independent of image.
- For most services `build` and image-build collapse (no separate compile artifact); documented in
  the README. Kotlin services compile in `build`; Go/Node/Python build inside the image.

## 5. GitOps deploy (Phase 2)

### 5.1 Argo topology
- `AppProject` `unoarena`; per service two `Application`s: `<svc>-staging`, `<svc>-production`,
  each = `services/<svc>/chart` + `gitops/apps/<svc>/overlays/<env>/values.yaml`.
- Argo `syncPolicy`: staging **automated** (self-heal) ; production **manual**.

### 5.2 deploy-staging (identity) — the readiness gate
1. Bot writes the captured `identity.digest` into `…/overlays/staging/values.yaml` and commits.
2. `argocd app sync identity-staging`.
3. `argocd app wait identity-staging --health --sync --timeout 180` → **honest readiness gate**.
   Chart probes (`/health`) gate pod readiness; Argo waits for `Healthy`.
4. Job green only after Argo reports Healthy+Synced.

### 5.3 Promotion (build once)
- `deliver-production` (manual): copies the **same digest** from the staging overlay into the
  production overlay — no rebuild. `deploy-production` syncs `identity-production` (manual policy).

### 5.4 Environment differences (overlays)
| Value | staging | production |
|-------|---------|-----------|
| `replicas` | 1 | 3 |
| `logLevel` | `debug` | `info` |
| `env.PUBLIC_URL` | `…staging…` | `…prod…` |
| Argo sync | automated+selfHeal | manual |

### 5.5 Secrets
- Sealed Secrets controller in-cluster. Plaintext → `kubeseal` → `SealedSecret` committed under
  `gitops/apps/identity/overlays/<env>/sealed-secret.yaml`. Only the controller decrypts.
- `grep -rE '(password|secret|token)\s*[:=]' --include=*.yaml` finds no plaintext.

### 5.6 Rollback (one sentence)
> Run `argocd app rollback identity-staging <previous-revision>` (equivalently `git revert` the
> digest-bump commit, which Argo auto-reconciles) to restore the last healthy image.

## 6. Client CLI + smoke test (Phase 2)

- `clients/cli` (Node/TS): subcommands `register --user --pass`, `whoami`, global `--json`,
  target via `UNOARENA_API_URL`. Docker-packaged (`docker run <cli> register …`). Token held
  in-process / session file.
- `integration-staging` (`ci/templates/smoke-cli.yml`):
  1. `UNOARENA_API_URL` = staging ingress of `identity`.
  2. `register --user smoke-$CI_PIPELINE_ID --pass … --json` → assert `result=="ok"`.
  3. `whoami --json` → assert returned `user == smoke-$CI_PIPELINE_ID`.
  4. Unique user per pipeline → hermetic; `retry: 1` max.
  5. Non-zero exit on unreachable/wrong response → fails the pipeline.

## 7. Contract-check seam

- `ci/contracts/game-completed.schema.json` is the published `GameCompleted` shape (from
  `docs/architecture/10-api-event-catalog.md`).
- `test:contract:game-completed` validates the producer's emitted sample against the schema **and**
  runs a backward-compat check vs. the consumers' expected shape. Wired as a `needs:` for
  `build:ranking` + `build:analytics-workers`.

## 8. Documentation deliverable

`devops-checkpoint/README.md`: layout (§6.1) + why `identity` is fully wired; per-stage narrative
+ failure semantics (§6.2); GitOps-vs-Helm justification (§6.5); smoke-test description (§6.6);
coverage matrix (§6.9); link to a green `integration-staging` run. Plus 5 short ADRs (roadmap §"Decision log").

## 9. Open risks / decisions deferred

- **Cluster confirmation (AWS/Azure):** until confirmed, staging runs on kind+Argo (bootstrap).
  When confirmed: swap Argo target cluster + Sealed Secrets→ESO. No chart/pipeline change. (ADR-4)
- **Migrate-job demo:** default off (in-memory identity). Enable only if we want to show the
  migrate stage; keep DB trivial.
- **Chart library vs per-chart copy:** start per-chart (clarity); factor a library only if drift hurts.
