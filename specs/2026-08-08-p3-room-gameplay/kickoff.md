# Kickoff — P3: Uno Engine + Room-Gameplay Core

> The triad here was written and review-passed on 2026-08-08, in the session that closed P2.
> Implementation starts in a fresh session. This file is the bridge: everything the next session
> needs that is not already in `requirements.md` / `plan.md` / `validation.md`.

## Where things stand

- `main` = the P2 closure (identity real, pipeline green). Nothing on P3 is implemented yet — the
  only P3 artefacts are the four files in this directory.
- `services/room-gameplay/` is still the P0 placeholder: `com.sun.net.httpserver`, one stub route,
  zero dependencies. Its chart, Dockerfile and CI fragment exist and work.
- The `room_gameplay` database exists in `unoarena-pg` but is still owned by the bootstrap `app`
  role — F1 gives it its own CNPG managed role, exactly as P2 did for identity.
- `identity.session-events` is live and declared as a `KafkaTopic`, so the consumer in F8 has a
  real contract to bind to.
- No kind cluster is guaranteed to be running. Recreate with
  `TARGET_REVISION=feat/p3-room-gameplay GITOPS_REPO_TOKEN="$(cat ~/.amicro_gitlab_token)" gitops/bootstrap/install.sh`.
  Expect ~30 min to full convergence (P2 measured 2027 s from empty).
  **F1 changes `kind-cluster.yaml`** (second NodePort), and port mappings are fixed at creation —
  so create the drill cluster *after* F1 lands, or recreate it then.

## Copy identity, do not reinvent

P2 established the patterns this phase should follow literally rather than re-derive:

| Concern | Where to copy from |
|---|---|
| Per-service DB role + sealed password | `gitops/platform/postgres/cluster.yaml` (`managed.roles`) + `gitops/secrets/seal.sh` |
| Sealed secret + pull secret + digest pin | `gitops/apps/identity/overlays/staging/values.yaml` |
| Chart: `imagePullSecrets`, `startupProbe`, `ServiceMonitor` | `services/identity/chart/` |
| Startup migration under an advisory lock | `services/identity/src/migrate.ts` |
| Ports-and-adapters so the test stage needs no database | `services/identity/src/{store,sessions}.ts` |
| Metrics naming and where counters are incremented | `services/identity/src/metrics.ts` + `server.ts` |

## Working rules that bit us in P2

- First push of the branch: `git push -o ci.skip` (a new branch evaluates `rules:changes` as
  all-changed → a full 30-job pipeline for nothing).
- CI pushes digest-pin commits **back to the feature branch**, so `git pull --rebase` before every
  local commit.
- Any change under `ci/templates/**` pulls all ten services into the pipeline — batch them.
- A pipeline that fails instantly with 0 jobs is a config error; the REST API hides the message,
  GraphQL `pipeline(iid){errorMessages{nodes{content}}}` shows it.
- Argo reverts fault injection within seconds (`selfHeal`), and the app-of-apps restores the child
  app's sync policy — suspend **both** `unoarena-root` and the child app for a degradation drill.
- Run the empty-cluster drill **before** declaring done. P2's caught two defects that no unit test
  could have: a CRD race that left a service permanently down, and a broken `install.sh` re-run.

## Suggested first moves

1. `git checkout -b feat/p3-room-gameplay` and read the triad end to end.
2. F1 (platform seam) before any Kotlin — it is pure copy-work and it unblocks every drill.
3. F3/F4 (the pure engine) are the phase's real content and the program's biggest risk. Resist
   wiring HTTP to them until the property and replay suites are green; that ordering is the whole
   point of D1.

## Open questions for the implementer (not blocking)

- Which property-testing library for Kotlin (kotest's `checkAll` is the obvious default) — pick one
  in F3 and record it as a D-n.
- Whether `rooms` (the projection) should carry membership for the F8 consumer's playerId → rooms
  lookup, or whether that deserves its own index. Decide when F8 makes the query concrete.
