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
| AC-P1.6 | `kubectl delete` a platform namespace, let Argo resync from scratch. | Instances never apply before their operator (waves hold). |

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
kubectl -n redis exec deploy/redis-master -- redis-cli PING
# Grafana
kubectl -n monitoring port-forward svc/monitoring-grafana 3000:80 &
curl -s -o /dev/null -w "%{http_code}" http://localhost:3000/login   # 200
```

(Exact resource names finalize as F2–F5 land; this file updates with them.)

## Drill records

- [ ] F6 fresh-kind drill (AC-P1.1) — transcript pending.
- [ ] F6 `USE_KIND=false` drill (AC-P1.2) — transcript pending.
- [ ] F7 EKS rehearsal (AC-P1.3), timed — transcript pending.
- [ ] F7 sweep empty (AC-P1.4) — transcript pending.

## Definition of done

- [ ] AC-P1.1 … AC-P1.6 pass, drill records filled in.
- [ ] Learner Lab left with zero resources; budget banner checked and noted.
- [ ] North-star roadmap marks P1 shipped; ESTADO-FINAL.md written here.
