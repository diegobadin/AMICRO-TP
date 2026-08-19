# P8 — Observability consolidation — requirements

> Phase **P8** of [`../2026-07-26-final-delivery-northstar/roadmap.md`](../2026-07-26-final-delivery-northstar/roadmap.md).
> Read that roadmap's **"Handoff from P7"** block before this file. Its first line — *"P8 should
> retrofit nothing"* — is the constraint the whole phase is shaped around, and the one place P8
> deviates from it is written down as **D1** below.

## Context

P7 closed with **ten of ten deployables real**. The exam's observability requirement
(`docs/final/consigna.md`: *"Debe incluir la infraestructura de observabilidad, con por lo menos
tres métricas de negocio"*) is the last technical requirement without an answer in the repo — not
because the metrics are missing, but because **nothing renders them**.

Verified live against the kind cluster tracking `main` (2026-08-18):

| Already true | Evidence |
|---|---|
| All 10 services expose `/metrics`, and Prometheus scrapes **11 targets, all `up`** | `/api/v1/targets`; the relay appears twice — jobs `outbox-relay` and `outbox-relay-tournament` |
| `serviceMonitor.enabled: true` in **all ten** staging overlays | `gitops/apps/*/overlays/staging/values.yaml` |
| Any ServiceMonitor is discovered, not only this release's | `serviceMonitorSelectorNilUsesHelmValues: false` (`gitops/platform/values/monitoring.yaml:26`) |
| ~100 business series are collecting right now | `tournament_*` ×18, `ranking_*` ×17, `analytics_*` ×15, `roomgameplay_*` ×12, `spectator_*` ×10, `gateway_*` ×7, `timerworker_*` ×7, `identity_*` ×6, `outboxrelay_*` ×5, `analyticsapi_*` ×2 |
| `correlationId` is propagated by **all ten** services and the CLI | `X-Correlation-Id` in gateway/identity/spectator (TS), room-gameplay/tournament (Kotlin), outbox-relay/timer-worker (Go), ranking/analytics-workers/analytics-api (Py) |
| Grafana's dashboard sidecar is running and cluster-wide | `grafana-sc-dashboard`, `LABEL=grafana_dashboard`, `LABEL_VALUE=1`, `NAMESPACE=ALL` |
| NodePort **30081** is published by the kind node and unused | `gitops/bootstrap/kind-cluster.yaml:14`; it was room-gameplay's until P4 collapsed the two NodePorts into the gateway |
| The platform AppProject already allows the `monitoring` namespace | `gitops/projects/unoarena-platform.yaml:22` |

| Not true yet | Evidence |
|---|---|
| **Any dashboard exists** | The only `grafana` reference in the repo is `gitops/platform/values/monitoring.yaml` |
| Alertmanager runs | `alertmanager.enabled: false` (same file, line 6 — P1/D4) |
| Grafana is reachable, or holds a credential we own | `ClusterIP`, chart-default admin; the file's own comment calls this a debt to pay "when the ingress lands (P4)" — P4 landed |
| Any `PrometheusRule` exists | `grep -rl PrometheusRule gitops/ services/` → nothing |
| A `correlationId` can be followed across services | Only per-pod `kubectl logs`, ten times, with no shared timeline |
| `room-gameplay` and `tournament` expose latency **quantiles** | Micrometer `Timer.builder(...)` with no `publishPercentileHistogram()` → `_sum`/`_count`/`_max`, **no `_bucket`** (`Metrics.kt:67` and `Metrics.kt:79`) |
| `analytics-api` and `spectator` expose latency at all | `analyticsapi_reads_total` + `read_failures_total` only; spectator has streams and consumer metrics only |

## Goal

From an empty cluster, the same install that brings up the system also brings up **metrics,
dashboards, alerts and logs**; a stranger opens one URL and reads how many games, tournaments and
rating updates the system has produced, and one `correlationId` from a CLI command can be followed
across every service that touched it in a single query.

## In scope

**Dashboards (E1 — three boards, as code)**
- **Business** — the consigna's answer. Named headline metrics: games completed/min
  (`roomgameplay_games_completed_total`), tournaments completed and registrations
  (`tournament_*`), registered players (`identity_registrations_total`), rating updates split Elo
  vs placement (`ranking_elo_updates_total`, `ranking_placement_updates_total`), moves/s, live rooms.
- **Golden signals** — traffic, errors, latency, saturation per service; latency as p95 where a
  histogram exists (see **D1**/**D2** for the two services where it does not).
- **Async spine** — consumer lag per group, `outboxrelay_*` **split by job** (the P7 handoff's
  requirement: a summed panel hides one relay going idle), `tournament_commands_contended_total`,
  dedupe/skip counters, restarts.
- Dashboards are committed JSON, delivered as sidecar-labelled ConfigMaps (**D6**).

**Grafana exposure (E2)**
- `Service.type: NodePort` on **30081**, the port kind already publishes.
- Admin credentials from a **SealedSecret** we own, replacing the chart default (**D5**).

**Alerting (E3)**
- Alertmanager enabled, **default null receiver, no notification channel** — reverses P1/D4, recorded
  as a delta.
- A small `PrometheusRule` set, every rule of which must be observed firing at least once in a drill.

**Logs (E4)**
- **Loki** (single binary, ephemeral) + **Grafana Alloy** as the collector, in the `monitoring`
  namespace (**D3**, **D4**).
- A Loki datasource in Grafana and a documented `correlationId` query that crosses services.
- The CLI surfaces the `correlationId` of a command so there is something to paste into that query.

**Instrumentation (the one deviation)**
- `publishPercentileHistogram()` on the two Micrometer timers, so the golden-signals board is not
  blank for two of the four HTTP services (**D1**).

**Docs and closure**
- Observability runbook, README pass, `CHANGELOG-design.md` §13, and the supersession of
  `tech-stack.md` §9 (see *Constraints*).
- Drills **both ways** — from empty and rolling upgrade — then the post-closure self-review pass.

## Out of scope

- **Distributed tracing.** No OpenTelemetry collector, no Jaeger, no spans. `correlationId` in logs
  is the correlation story this system has; a tracing backend is a third platform component for a
  seam that logs already answer.
- **Alert receivers.** No email, Slack or webhook. An alert nobody is on call for that pages a
  channel nobody reads is theatre; the requirement is that alerts *exist and fire visibly*.
- **Persistent storage for Prometheus, Loki or Alertmanager.** Everything stays ephemeral, matching
  the monitoring values' existing posture and avoiding the EKS lesson that PVC-backed EBS volumes
  outlive `eksctl delete` (`aws-learner-lab`, `gitops/bootstrap/eks/README.md`).
- **New business counters.** The handoff's rule holds: P8 renders what is already collecting. D1 is
  a rendering change to an existing meter, not a new metric; D2 says no to the genuine retrofits.
- **Log-derived metrics / recording rules for business KPIs.** The counters already exist.
- **SLOs, error budgets, burn-rate alerts.** No SLO has been agreed and inventing one for a demo
  makes the dashboard say something untrue.
- **Ingress, TLS, or an auth proxy in front of Grafana.** NodePort on a private cluster, same
  posture as the gateway.
- **Mirroring the `gcr.io/distroless` bases into the project registry.** A real pre-exam task (five
  rejections of the shared runners across P6/P7) but a CI-supply-chain change, not observability —
  it belongs in P9's 48h checklist and is named there so it cannot be lost.
- **P9's rehearsal, runbook timing and deck.**

## Decisions

### Locked by the user (E-decisions, 2026-08-18)

| # | Decision | Rationale |
|---|---|---|
| **E1** | **Three dashboards**: business, golden signals, async spine. | The business board is what the consigna asks for and what the faculty reads; the other two are what makes a stuck demo diagnosable in front of them. P7's handoff names exactly the two things worth watching — a relay gone idle and `tournament_commands_contended_total` — and neither belongs on a board shown to a grader. |
| **E2** | **Grafana on NodePort 30081, admin credentials from a sealed secret.** | 30081 is already published by the kind node and has been free since P4; one fixed URL works identically on kind and EKS, and no port-forward can fail live. Sealing the admin credential retires the debt the monitoring values file has carried since P1. |
| **E3** | **Alertmanager enabled, with no receivers.** | Reverses P1/D4, deliberately: "infraestructura de observabilidad" reads thin if nothing can alert. Receivers stay out (see *Out of scope*), so what ships is the alerting *plane* plus rules that visibly fire. |
| **E4** | **Loki + a collector.** | Makes `correlationId` traceability a single query instead of ten `kubectl logs`, and completes the observability story as metrics **and** logs in one pane. Accepted cost: more pods on the from-empty critical path, which the drills must measure (see *Risks*). |

### Implementer decisions (D-decisions — confirm in review)

| # | Decision | Why, and what would change it |
|---|---|---|
| **D1** | **Publish latency buckets on `roomgameplay.command.duration` and `tournament.http.request.duration`**, using `serviceLevelObjectives(...)` set to **the gateway's existing histogram boundaries** (5 ms → 2.5 s). Two services rebuild. | E1 chose a golden-signals board. Today `gateway` and `identity` publish real histograms (`prom-client` `Histogram`, explicit buckets) while both Kotlin timers publish Micrometer summaries — `_sum`/`_count`/`_max`, no `_bucket` — so a p95 panel would be populated for two of four HTTP services and silently blank for the other two. This is **not** the retrofit the handoff forbids: no new metric name, no new instrumentation point, the same meter rendered so it can be aggregated across instances. It does cost two image rebuilds, so it lands **first** and CI runs while the dashboards are written. **Mechanism refined during F1** (was: `publishPercentileHistogram()`): that call emits Micrometer's full percentile-histogram bucket set *per tag combination*, and both timers are tagged by route **and** status, so the cardinality risk the decision reserved the right to revert for would have been designed in from the start. Objectives at the gateway's own boundaries give **9 series per route/status** (8 + `+Inf`, measured) and make a p95 mean the same thing on both sides of one request. |
| **D2** | **`analytics-api` and `spectator` get no new latency metric.** Their golden-signals row shows traffic and errors from `analyticsapi_reads_total{surface,status}` / `spectator_*`, and the missing quantile is **labelled on the panel** rather than left as an empty graph. | This one *would* be the retrofit the handoff forbids — a new metric in a service P8 has no other reason to touch. A panel that says "no latency histogram in this service" is honest; an empty panel reads as an outage. |
| **D3** | **Loki chart `7.3.0` in `SingleBinary` mode with filesystem storage and no PVC; collector is Grafana Alloy (chart `1.11.1`), not promtail.** | Versions verified live against `https://grafana.github.io/helm-charts`. Promtail is still published but is EOL upstream in favour of Alloy — shipping a deprecated agent to an exam in 2026 is a question we would have to answer. Ephemeral storage matches the monitoring stack's existing choice and keeps the EKS teardown clean. Logs surviving a pod restart is not a demo requirement. |
| **D4** | **Loki and Alloy live in the `monitoring` namespace**, not a new `logging` one. | `unoarena-platform` whitelists destination namespaces explicitly, so a new namespace is a project edit as well as an app; and `kubectl get pods -n monitoring` staying the single "is observability up?" command is worth more during a live demo than the tidier separation. |
| **D5** | **The Grafana admin credential is sealed into `monitoring` as `gitops/platform/monitoring-secrets/grafana-admin.yaml`, synced by its own Application at sync wave −1.** | `gitops/secrets/<env>` is synced by the `unoarena` project into `unoarena-<env>`, and that project's destinations are `argocd` + `unoarena-*` — it cannot deliver into `monitoring`. `seal.sh` already takes `-n <namespace>`, so the sealing side is one more `seal` call and one more generated password. **Path refined during F5** (was `gitops/secrets/platform/`): `seal.sh` already writes cross-namespace blobs *next to what consumes them* — the CNPG role passwords live in `gitops/platform/postgres/` — so following that precedent beats inventing a second convention. It is its own Application rather than part of the dashboards one because the two need different sync waves: the credential must exist before Grafana's pod is created, the dashboards can arrive whenever. |
| **D6** | **Dashboards are committed JSON files rendered into labelled ConfigMaps by the platform chart**, not inline `grafana.dashboards` values or a `dashboardProviders` git-sync. | The sidecar is already running with `NAMESPACE=ALL` and `LABEL=grafana_dashboard`, so this needs no chart change. A dashboard stays a reviewable file whose diff is readable, and it cannot drift from what Grafana shows because there is one copy. |
| **D8** | **The three Kafka consumers read `ce-correlationid` into the log line they already write** (`ranking`, `analytics-workers`, `spectator`). Three services rebuild. | Found during F7 and **escalated to the user rather than decided**, because it is the one change in P8 that edits shipped P5/P6 code. `outbox-relay/envelope.go:64` has put the originating request's correlation id on every Kafka message since P5, and no consumer has ever read it — the "promise nobody verifies" shape, with the header travelling the whole spine unconsumed. Without this a trace stops at the service that produced the event (2 services for a register), which makes E4's whole purpose — one id, one query, across the system — untrue as sold. It is logging, not metrics, so it does not breach the handoff's "retrofit no counters". Side effect: `spectator`'s `log` helper moves from `server.ts` to `metrics.ts`, because the consumer needs it and `server.ts` imports the consumer — which is where the two Python siblings keep theirs anyway. |
| **D7** | **The alert rule set is small and every rule must be observed firing in a drill**, with its own `PrometheusRule` per board area. Candidates: relay idle/backlogged, consumer lag climbing, consumer never started, contended commands sustained, a service target down, a pod crash-looping. | Three phases of this program have paid for the same lesson — a gauge that was never `Set` reads `0`, and `0` is usually the healthy value. A rule that has never fired is exactly that failure in alert form: it looks like coverage and proves nothing. |

## Constraints from tech-stack

- **`tech-stack.md` §9 is superseded here, and must say so.** It reads: *"No dashboards/alerting/
  tracing collectors — just the seam left in place"* — the correct scope for the DevOps checkpoint,
  written before the final exam's consigna existed. P8 delivers dashboards and alerting (not
  tracing, which stays out). The file is amended with the reason and the delta is recorded in
  `CHANGELOG-design.md` §13; it is not silently contradicted.
- **Pipeline shape is frozen.** D1 touches two services and rides the existing
  `test → build → deliver → deploy-staging → integration-staging` spine. P8 adds no stage.
- **GitOps only.** Every platform change is a chart/values change Argo reconciles; nothing is
  `kubectl apply`ed into the cluster by hand, including the dashboards.
- **Chart versions are pinned**, like every other platform component (`kube-prometheus-stack`
  87.19.2, `strimzi` 1.1.0, `cnpg` 0.29.0). Loki and Alloy get exact pins, never `*`.
- **Always deployable**: P8 is done only when it comes up from an empty cluster.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| **P8 lengthens the from-empty critical path**, which is the exam's single most important minute count (P7 baseline: ~11 min to 10/10 Healthy on a warm host cache). | Loki single-binary is 1 pod, Alloy is a DaemonSet, Alertmanager is 1 pod. The drill **records the new time against the 11-minute baseline** and the number goes in the runbook. If it moves materially, the platform waits are re-tuned rather than discovered at the exam. |
| **A new platform component becomes load-bearing and fails live.** | Loki is required to be **non-load-bearing**: with Loki down, all three dashboards and every alert must still work. The drill asserts this explicitly by taking Loki out and re-reading the boards. |
| **A dashboard built on a warm cluster full of data is broken on a fresh one.** | Every board is opened twice in the from-empty drill: **before** any traffic (panels must read `0`/`No data` deliberately, not error) and after the tournament drill. This is the gauge-reads-0 lesson applied to panels. |
| **A summed `outboxrelay_*` panel hides one relay going idle** — the P7 handoff's explicit warning. | The two relays are already **separate scrape jobs**, so every relay panel is `by (job)` and the drill kills one relay and confirms exactly one series flattens. |
| **An alert rule that has never fired is not coverage.** | D7: each rule is fired on purpose in the drill. Suspending the Argo root is the documented way to scale a GitOps-managed service to zero — `selfHeal` undoes a plain `kubectl scale` well inside 30 s, which has already cost this project two false measurements. |
| **The rolling-upgrade class of defect** — P7's timer-worker 401 that the from-empty drill could not see. | P8 changes a Secret consumer (`grafana.admin.existingSecret`) and two Deployments' images: exactly the shape of that bug. Both drills run, and the rolling one runs **on the cluster that is already up**, before it is deleted. |
| **`gcr.io/distroless` rejects the shared runners.** | Five occurrences across P6/P7. D1 rebuilds two Kotlin images, so expect it. A red build is read before it is believed; the permanent fix is P9's checklist item. |
| **Alertmanager reverses a locked P1 decision.** | E3 makes that the user's call, and it is recorded as a delta in §13 rather than as a silent values edit. |
| **CI minutes.** | Only D1 touches `services/**`; everything else is `gitops/**` + docs, which change detection keeps out of the service pipelines. Feature-branch pushes stay incremental (P5's lesson). |

## Mission alignment

`mission.md` §3 criterion 11 is *restraint* — "no observability stack before the smoke test works".
That gate has been open since P2, and every phase since has instrumented itself on the way past, so
P8 is the phase that rule was deferring to rather than an exception to it. The exam asks for
observability infrastructure with at least three business metrics; the metrics have been collecting
for six phases and the honest remaining work is to make them legible, alertable and correlatable.
When P8 closes, the last requirement in `docs/final/consigna.md` without an artefact behind it has
one, and P9 is rehearsal and presentation of a system that is already complete.
