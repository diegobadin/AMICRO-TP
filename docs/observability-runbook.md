# Observability runbook

> What P8 shipped, and how to drive it during a demo. The design rationale is in
> [`architecture/05-observability-and-health.md`](./architecture/05-observability-and-health.md);
> the deltas from that design are `CHANGELOG-design.md` §13. This file is operational: URLs,
> queries, and the two or three things that look like faults and are not.

## Getting in

| | |
|---|---|
| **Grafana** | `http://localhost:30081` — NodePort, published by the kind node since P3 and free since P4 collapsed the two NodePorts into the gateway. **On kind only.** On EKS nothing opens the node security group for 30081 (or for the gateway's 30080), and that path has never been exercised there — use `kubectl -n monitoring port-forward svc/monitoring-grafana 3000:80` on a cloud cluster until P9 settles it. |
| **Login** | `admin` / the value of `GRAFANA_ADMIN_PASSWORD` in `~/.amicro_secrets.env`. Sealed into `monitoring` as `grafana-admin` by `gitops/secrets/seal.sh`; the chart's own generated password no longer works. |
| **Prometheus / Alertmanager** | No node port. `kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9090:9090`. Both are reachable from Grafana as datasources (`prometheus`, `alertmanager`, `loki` — fixed uids, so dashboards survive a reinstall). |

The same install brings all of this up from an empty cluster; there is no separate observability
step.

## The three boards

Stable UIDs, so these URLs do not change between clusters.

| Board | URL | Answers |
|---|---|---|
| **Business** | `/d/unoarena-business` | What the system has produced: games, tournaments, players, ratings. This is the board to show. |
| **Golden signals** | `/d/unoarena-golden-signals` | Latency, traffic, errors, saturation per service. |
| **Async spine** | `/d/unoarena-async-spine` | Both relays, six consumer groups, the reconciler, the timer worker, and which alerts are firing. This is the board to open when the demo stalls. |

### The consigna's three business metrics

`docs/final/consigna.md` asks for at least three. Named on the business board itself, in a text
panel, so the answer is on screen rather than in someone's memory:

- **`roomgameplay_games_completed_total`** — games played to a winner
- **`tournament_tournaments_completed_total`** — tournaments run to a champion
- **`identity_registrations_total`** — players registered

Each is incremented from a **committed domain event**, not from the request that appeared to cause
it — so a game that ends by forfeit, or because the last player left, is still counted.

## Following one request across the system

Every CLI command prints its `correlationId`. Paste it into Grafana → Explore → Loki:

```logql
{namespace="unoarena-staging"} |= "<correlationId>"
```

One `POST /rooms` returns lines from **five services** — the gateway that routed it, the aggregate
that owned it, and all three read models that projected the event it produced. The relay carries
the id onto every Kafka message as a `ce-correlationid` header, and since P8 the three consumers
read it.

Useful variants:

```logql
{namespace="unoarena-staging", service="room-gameplay"}          # one service
{namespace="unoarena-staging"} |= "correlationId" | json         # parse the JSON fields
{namespace="unoarena-staging"} |json| level="error"              # only failures
```

**Health-probe lines are dropped at the collector**, not by you. The kubelet probes every few
seconds forever — on the gateway that is ~98% of its log volume — and in P6 those lines drowned the
single line that explained a whole failure.

## Alerts

Nine rules, in `gitops/platform/alert-rules/unoarena.yaml`. Every one is a failure this project has
actually had; a rule that has never fired is the alerting form of a gauge that was never `Set`.

Alertmanager runs with **no receivers**: the alerting plane exists and rules fire visibly, but
nothing pages a channel nobody reads. Firing alerts appear on the async-spine board and in
Prometheus at `/alerts`.

### Firing one on purpose

To take a GitOps-managed workload down you must **suspend the root first** — `kubectl scale
--replicas=0` is undone by Argo's `selfHeal` well inside 30 seconds, and two attempts at measuring
"what happens without this component" in earlier phases actually measured a component Argo had
already restored.

```bash
kubectl -n argocd patch application unoarena-platform-root --type merge \
  -p '{"spec":{"syncPolicy":{"automated":null}}}'
kubectl -n monitoring scale deploy/... --replicas=0      # or the workload under test
# ... watch the rule go pending, then firing ...
kubectl -n argocd patch application unoarena-platform-root --type merge \
  -p '{"spec":{"syncPolicy":{"automated":{"prune":true,"selfHeal":true}}}}'
```

## Things that look like faults and are not

- **`Watchdog` is always firing.** It is the stack's own alert, and its entire job is to prove the
  alerting path works — silence then means healthy rather than broken. It is excluded from the
  board's "Alerts firing" count for that reason.
- **Schema-owning services restart 5–7× on a cold start.** `identity`, `room-gameplay`, `ranking`,
  `analytics-workers` and `tournament` exit rather than serve against a database that is not
  migrated yet. `analytics-api` restarting **0** times is the contrast: it reads a schema it does
  not own, so it connects lazily.
- **4xx on the golden-signals board is the rules working.** A `409` is an illegal move refused, a
  stale command, or a registration that lost its race and will retry. 5xx is the panel to watch.
- **`ranking` logging `outcome: ignored`** for a room event is correct — it scores
  `GameCompleted`, and the skip is counted with its reason rather than being silent.
- **Empty tournaments sitting in `REGISTRATION`** are clients that lost the create race and
  converged elsewhere. They are joinable and harmless.
- **A `0` on a business panel is a real zero.** Every query ends in `or vector(0)` so a fresh
  cluster reads zero instead of "No data" — and `clients/cli/scripts/check-dashboards.js` is what
  stops that from hiding a mistyped metric name, by checking every name against Prometheus and,
  when a tagged counter has no series yet, against the source that declares it.

## Checking the boards after a change

```bash
kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9091:9090 &
cd clients/cli && PROM_URL=http://localhost:9091 node scripts/check-dashboards.js
```

Fails on a metric name no service declares, on a query that does not resolve, and on any
`outboxrelay_` panel that is not split `by (job)` — the two relays publish to different topics from
one image, and a summed panel hides one of them going idle.
