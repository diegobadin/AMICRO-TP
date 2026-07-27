# Validation — P1: Platform Infra from an Empty Cluster

> One executable check per acceptance criterion. Drill transcripts get recorded here as F6/F7
> run.

## Verification matrix

| AC | Check | Pass condition |
|----|-------|----------------|
| AC-P1.1 | `kind delete cluster --name unoarena-staging; gitops/bootstrap/install.sh` then the probes below. | All platform apps `Synced/Healthy`; all four probes pass. |
| AC-P1.2 | Same install with `USE_KIND=false` against another cluster's kubeconfig. | Identical result, no kind assumptions hit. |
| AC-P1.3 | `gitops/bootstrap/eks/create.sh` then `install.sh` (`USE_KIND=false`) on the Learner Lab. | Cluster up with `LabRole` only, no NAT/ELB; AC-P1.1 probes pass on EKS. |
| AC-P1.4 | `gitops/bootstrap/eks/destroy.sh && gitops/bootstrap/eks/sweep.sh` | Sweep prints nothing and exits 0. |
| AC-P1.5 | Next `main` pipeline after P1 merges. | `integration-staging:identity` still green, untouched by platform files. |
| AC-P1.6 | `kubectl delete` a platform namespace, let Argo resync from scratch. | Argo converges back to Synced/Healthy with no manual steps (retries absorb the CRD race). |

## Probes (used by AC-P1.1/2/3)

```bash
# Kafka round-trip
kubectl -n kafka run kcat --rm -i --restart=Never --image=edenhill/kcat:1.7.1 -- \
  -b unoarena-kafka-bootstrap:9092 -t p1-probe -P <<< "hello"
kubectl -n kafka run kcat-c --rm -i --restart=Never --image=edenhill/kcat:1.7.1 -- \
  -b unoarena-kafka-bootstrap:9092 -t p1-probe -C -c1 -e
# Postgres
kubectl -n postgres exec unoarena-pg-1 -- psql -U postgres -c "\l"
# Redis
kubectl -n redis exec deploy/redis -- redis-cli PING
# Grafana
kubectl -n monitoring port-forward svc/monitoring-grafana 3000:80 &
curl -s -o /dev/null -w "%{http_code}" http://localhost:3000/login   # 200
```

(Exact resource names finalize as F2–F5 land; this file updates with them.)

## Phase gates

- **F1 (passed 2026-07-26).** Fresh kind + `TARGET_REVISION=feat/p1-platform`:
  `unoarena-platform-root` reaches `Synced/Healthy` tracking the branch (empty chart, zero
  children yet). Re-run of `install.sh` was a no-op. Fixed en route: the repo is private, so
  `install.sh` now creates Argo's declarative repo secret from `GITOPS_REPO_TOKEN` (same pattern
  as the CI job); the `stable` Argo CD manifest had drifted past the client-side apply annotation
  limit, so bootstrap now pins argocd `v2.12.3` (matching the CI CLI) and sealed-secrets
  `v0.38.4`.
- **F2 (passed 2026-07-26).** Strimzi 1.1.0 (chart pinned) + single-node KRaft Kafka 4.3.0:
  `strimzi-operator` and `kafka` apps `Synced/Healthy`, Kafka CR `Ready=True` ~2.5 min after the
  push with zero manual steps — the wave-0/wave-1 + `SkipDryRunOnMissingResource` + retry
  convergence design absorbed the CRD race as intended. kcat round-trip on
  `unoarena-kafka-bootstrap:9092` produced and consumed `hello`. Found en route: Argo CD 2.x
  cannot SSA-diff against Kubernetes 1.33+ (`status.terminatingReplicas` missing from its
  bundled schemas) — bootstrap now pins argocd `v3.4.5` applied server-side (Strimzi's Kafka CRD
  is ~330KB, so `ServerSideApply=true` is mandatory on the operator app; kube-prometheus-stack
  will need the same in F5). The CI job's v2.12.3 CLI is unaffected (self-contained install).
- **F3 (passed 2026-07-26).** CNPG chart 0.29.0 (operator 1.30.0) + `unoarena-pg` (1 instance,
  2Gi): converged Synced/Healthy in ~100s, no manual steps. The five context `Database` CRs
  (identity, room_gameplay, tournament, ranking, analytics — from
  `docs/architecture/03-persistence-layer.md`; Spectator is Redis-only) all report
  `applied=true`, and `psql -U postgres` inside `unoarena-pg-1` lists them.
- **F4 (passed 2026-07-26).** `redis:7.4.10-alpine` pinned, AOF+RDB off, `maxmemory 96mb` +
  `noeviction` (per persistence doc §7.1: `timer:*` must never be silently evicted; the shared
  instance errors loudly instead). `PING`→`PONG` ~30s after push.
- **F5 (passed 2026-07-26).** kube-prometheus-stack 87.19.2, Alertmanager off, control-plane
  scrape targets off (their Services land in kube-system — outside the AppProject — and are
  unreachable on EKS/kind anyway). Converged Synced/Healthy; Grafana `/login` → HTTP 200 via
  port-forward. Note: a values fix during a running sync leaves the old operation retrying with
  the old desired state — removing `.operation` (terminate) lets auto-sync restart cleanly;
  from-scratch installs never hit this.
- **Probe names confirmed** as written above: `unoarena-kafka-bootstrap:9092`, `unoarena-pg-1`,
  `deploy/redis`, `svc/monitoring-grafana`. One flake: `kubectl run -i --rm` sometimes swallows
  kcat's consumed output — for evidence, run the consumer without `--rm` and read `kubectl logs`.
- **Pre-existing, deferred to F6** (service side, invisible to CI because `integration-staging`
  applies its own ad-hoc Application under project `default`): (a) `unoarena-root` shows
  `InvalidSpecError` on a fresh cluster — the `unoarena` AppProject only allows `unoarena-*`
  destinations but the root app targets `argocd`; (b) all 10 staging Applications mis-indent
  `syncPolicy` (`automated`/`syncOptions` sit at spec level), so automated sync was never active.
  Both must be fixed for AC-P1.1's "everything Healthy from nothing" to include the service tree.

## Drill records

- [ ] F6 fresh-kind drill (AC-P1.1) — transcript pending.
- [ ] F6 `USE_KIND=false` drill (AC-P1.2) — transcript pending.
- [ ] F7 EKS rehearsal (AC-P1.3), timed — transcript pending.
- [ ] F7 sweep empty (AC-P1.4) — transcript pending.

## Definition of done

- [ ] AC-P1.1 … AC-P1.6 pass, drill records filled in.
- [ ] Learner Lab left with zero resources; budget banner checked and noted.
- [ ] North-star roadmap marks P1 shipped; ESTADO-FINAL.md written here.
