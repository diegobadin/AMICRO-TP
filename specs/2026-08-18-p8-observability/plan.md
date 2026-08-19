# P8 — Observability consolidation — plan

> Ordered task groups, one focused commit each, validated before the next starts.
> Rationale lives in [`requirements.md`](./requirements.md); this file is imperative.
> Evidence goes in [`validation.md`](./validation.md).

**Ordering rule for this phase:** F1 touches two service images, so it lands and pushes **first** —
CI rebuilds `room-gameplay` and `tournament` while the dashboards are being written. Everything
after F1 is `gitops/**` or docs, which change detection keeps out of the service pipelines.

---

## F1 — Latency histograms on the two Kotlin timers (D1)

1.1 `services/room-gameplay/src/main/kotlin/Metrics.kt` — `.serviceLevelObjectives(*LATENCY_SLOS)`
on `Timer.builder("roomgameplay.command.duration")`, where `LATENCY_SLOS` is the gateway's own
boundary list (5, 10, 50, 100, 250, 500 ms, 1 s, 2.5 s).

1.2 `services/tournament/src/main/kotlin/Metrics.kt` — same for
`Timer.builder("tournament.http.request.duration")`. The list is duplicated per service rather than
shared, per P7/D8: kaniko builds each service from its own directory.

1.3 A test per service asserting the **exact exposed strings**: scrape the registry and require
`roomgameplay_command_duration_seconds_bucket` / `tournament_http_request_duration_seconds_bucket`
to be present. Assert the name as a string — Prometheus rewrites names it dislikes, which is how
`roomgameplay_rooms_created_total` became `roomgameplay_rooms_total`.

1.4 One commit per service, **pushed together in one pipeline**, then confirm **both** digests moved
in `gitops/apps/<svc>/overlays/staging/values.yaml`. `git pull --rebase` first: CI pins back to the
branch. (Two services pinning in the same stage race on the ref and the loser retries by re-reading
the winner's head — known since P2 and handled; splitting this into two pipelines would spend scarce
CI minutes to avoid a race that is already covered.)

1.5 After Argo syncs, confirm the `_bucket` series is live in Prometheus, not just in a test.

**Done when** `histogram_quantile(0.95, sum by (le) (rate(roomgameplay_command_duration_seconds_bucket[5m])))`
returns a number on the cluster, and the same for `tournament_*`.

---

## F2 — Dashboard delivery + the business board (E1, D6)

2.1 New `gitops/platform/dashboards/` holding committed dashboard JSON, and a template in
`gitops/platform/templates/` rendering each file into a ConfigMap in `monitoring` labelled
`grafana_dashboard: "1"`. No Grafana chart change — the sidecar already runs with `NAMESPACE=ALL`.

2.2 `unoarena-business.json`. Panels, all from metrics that are already collecting:
- Games completed (total + per-minute) — `roomgameplay_games_completed_total`
- Tournaments completed / started / opened, and registrations — `tournament_*`
- Registered players and logins — `identity_registrations_total`, `identity_logins_total`
- Rating updates, **Elo and placement as separate series** — `ranking_elo_updates_total`,
  `ranking_placement_updates_total`
- Moves played, by result — `roomgameplay_moves_total`
- Live rooms — opened minus completed minus expired
- Live spectator streams — `spectator_active_streams`, paired with `spectator_streams_opened_total`
  so a `0` gauge can be told from a gauge nobody ever set

2.3 Mark, in the dashboard description text, **which three panels are the consigna's ≥3 business
metrics**. The faculty will ask; the answer should be on the board, not in someone's memory.

2.4 Every panel gets an explicit "no data" posture: a fresh cluster must render `0`, never an error
and never a blank.

**Done when** the board renders in Grafana from a git commit alone, with no manual import.

---

## F3 — Golden-signals board (E1, D2)

3.1 `unoarena-golden-signals.json` — one row per service: traffic, error rate, latency, saturation.

3.2 Latency p95 for `gateway`, `identity` (existing `prom-client` histograms) and — after F1 —
`room-gameplay`, `tournament`.

3.3 `analytics-api` and `spectator`: traffic and errors only, with a **panel-level note naming the
absence** (D2). No empty latency panel.

3.4 Saturation from `kube-state-metrics` / kubelet: restarts, memory vs limit, CPU throttling.
Restarts panel carries the standing caveat — schema-owning services restarting 5–7× on a cold start
is the documented posture (identity, room-gameplay, ranking, analytics-workers, tournament), and
`analytics-api` at 0 is the contrast.

**Done when** every service on the board has traffic and errors, and no panel is silently blank.

---

## F4 — Async-spine board (E1, P7 handoff)

4.1 `unoarena-async-spine.json`.

4.2 `outboxrelay_*` **`by (job)`** — never summed. Rows published, backlog rows, lag seconds, publish
failures, and `outboxrelay_backlog_reads_total` beside the two gauges so "no backlog" can be told
from "never queried the database".

4.3 Consumer lag and throughput per group — `ranking_consumer_lag`, `analytics_consumer_lag`,
`spectator_consumer_lag`, plus `*_consumer_starts_total` for every consumer, which is the counter
that distinguishes "nobody has played a game" from "the consumer never started".

4.4 `tournament_commands_contended_total` gets its own panel — the P7 handoff names it as the one to
watch, with the reading rule ("a little is a registration rush; a lot means the retry budget is too
small") in the panel description.

4.5 Dedupe and skip counters: `*_events_deduped_total`, `ranking_events_skipped_total{reason}`,
`tournament_events_skipped_total`, `spectator_events_malformed_total`,
`spectator_private_field_rejections_total`.

4.6 Reconciler health — `tournament_reconcile_sweeps_total` vs `tournament_reconcile_failures_total`,
and `timerworker_sweeps_total` vs `timerworker_due_rooms`.

**Done when** killing one relay flattens exactly one series on the relay panels and leaves the other
moving.

---

## F5 — Grafana exposure and its credential (E2, D5)

5.1 `gitops/secrets/seal.sh` — `generate GRAFANA_ADMIN_PASSWORD 24` and a `seal` call for
`grafana-admin -n monitoring`, written to `gitops/secrets/platform/`.

5.2 A platform-chart Application syncing `gitops/secrets/platform/` into `monitoring` under the
`unoarena-platform` project (which already whitelists that namespace), at a sync wave **before**
monitoring.

5.3 `gitops/platform/values/monitoring.yaml` — `grafana.admin.existingSecret` pointing at it, and
`grafana.service.type: NodePort` with `nodePort: 30081`. Replace the "chart-default admin
credentials" comment with what is now true.

5.4 Verify the chart default (`prom-operator`) no longer authenticates.

**Done when** `http://localhost:30081` serves Grafana on a freshly installed kind cluster and the
password is the sealed one.

---

## F6 — Alertmanager and the rules (E3, D7)

6.1 `alertmanager.enabled: true` in the monitoring values, default null receiver, no notification
config. Update the comment that currently explains why it is off, and cite the delta.

6.2 `PrometheusRule` objects next to the dashboards, one per board area. Starting set:
- a scrape target down
- a pod crash-looping
- a relay idle while its backlog is non-zero (**`by (job)`**)
- consumer lag climbing without recovery
- a consumer that has never started (`*_consumer_starts_total == 0` while the pod is up)
- `tournament_commands_contended_total` sustained above a threshold
- the reconciler failing repeatedly

6.3 An alert-state panel on the async-spine board so a firing rule is visible without leaving
Grafana.

6.4 Tune every threshold against the **real** drill numbers, not guessed ones.

**Done when** each rule in the set has been observed in `firing` at least once (F9 does the firing).

---

## F7 — Loki, Alloy, and the correlationId path (E4, D3, D4)

7.1 `gitops/platform/templates/loki.yaml` + `values/loki.yaml` — chart `loki` **7.3.0**,
`deploymentMode: SingleBinary`, filesystem storage, **no PVC**, namespace `monitoring`, wave
alongside the rest of the platform.

7.2 `gitops/platform/templates/alloy.yaml` + `values/alloy.yaml` — chart `alloy` **1.11.1**, a
DaemonSet tailing container logs and shipping to Loki, with the Kubernetes namespace/pod/container
labels needed to filter by service.

7.3 A Loki datasource for Grafana — via the monitoring values' `additionalDataSources`, so the
datasource and the Prometheus one are configured in the same place.

7.4 Confirm the services' structured JSON log lines survive the trip with `correlationId` queryable,
and write the exact LogQL query into the runbook.

7.5 The CLI prints the `correlationId` of a command (behind a flag if it clutters normal output),
so the demo has something to paste.

7.6 **Do not log the kubelet's probes into Loki** — health-probe lines drowned the one line that
explained P6's failure. Drop them at the collector.

**Done when** one `correlationId` from a single CLI command returns lines from **three or more
distinct services** in one Loki query.

---

## F8 — Docs, deltas and the supersession

8.1 `docs/` observability runbook: the three boards and what each answers, the Grafana URL and where
the credential lives, the LogQL correlationId query, how to fire an alert on purpose, and the
from-empty timing measured in F9.

8.2 `CHANGELOG-design.md` **§13** — the P8 deltas. At minimum: Alertmanager enabled (reverses
P1/D4), Grafana exposed on 30081 with a sealed credential, Loki+Alloy added to the platform,
`tech-stack.md` §9 superseded, and D1's histogram change.

8.3 `specs/tech-stack.md` §9 — amend to point at P8, with the reason. Do not delete the old text;
it was correct for the checkpoint.

8.4 README pass: the observability section, the new URL, and the ≥3 business metrics named.

8.5 Roadmap: mark P8 **SHIPPED**, write the **"Handoff from P8"** block for P9, and add the
distroless-mirroring item to P9's 48h checklist so it cannot be lost.

**Done when** every claim in the docs has a live artefact behind it.

---

## F9 — Drills, both ways, then the review pass

9.1 **Rolling upgrade first**, on the cluster that is already up: sync the whole of P8 onto it and
watch for the P7 class of defect — a Deployment that does not restart when a Secret changes.
Grafana's admin credential is exactly that shape.

9.2 **Then from empty**: `kind delete cluster`, full install, time it against the **~11 min**
baseline, and record the number.

9.3 Open all three boards **before** any traffic — panels must read `0`, not error.

9.4 Run the casual gate and the tournament drill, then re-read the boards.

9.5 Fire every alert rule on purpose. To take a GitOps-managed service down, **suspend the Argo
root** — `kubectl scale --replicas=0` is undone by `selfHeal` inside 30 s and has already produced
two false measurements in this project.

9.6 Take Loki down and confirm the three dashboards and the alerts still work (the non-load-bearing
requirement).

9.7 If any finding is startup-shaped, **re-drill from empty**. Standing rule since P6.

9.8 The post-closure self-review pass: question every decision as a PR reviewer for production,
remove overengineering, and write the table into this file — including a "not changed, and why"
list. This pass is 4 for 4 at finding the phase's worst defect.

**Done when** both drills are green, every rule has fired once, and the review pass has landed.

---

## What this plan deliberately does *not* include

- **No tracing backend.** No OpenTelemetry collector, no Jaeger, no spans.
- **No alert receivers.** No email, Slack or webhook — the alerting plane and the rules, nothing to
  notify.
- **No persistent volumes** for Prometheus, Loki or Alertmanager.
- **No new business counters.** F1 changes how an existing meter is *published*; it adds no
  instrumentation point. F3 explicitly declines to add latency metrics to `analytics-api` and
  `spectator`.
- **No recording rules or log-derived metrics** for business KPIs.
- **No SLOs, error budgets or burn-rate alerts.**
- **No ingress, TLS or auth proxy** in front of Grafana.
- **No mirroring of the distroless base images** — real, and P9's, recorded in 8.5.
- **No new pipeline stage.** F1 rides the frozen spine.
