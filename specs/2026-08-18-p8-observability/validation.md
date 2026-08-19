# P8 — Observability consolidation — validation

> Binary checks. Every item is either observable or it is not done.
> Evidence (transcripts, PromQL output, screenshots, timings) is appended to this file as it is
> produced, the same way P5–P7 did it.

## 1. Metrics surface

- [x] Prometheus shows **every service target `up`**, including both relay jobs
      (`outbox-relay`, `outbox-relay-tournament`). — 11 targets for 10 services, all `up`.
- [x] `roomgameplay_command_duration_seconds_bucket` exists in Prometheus (F1). — 18 series
      (2 route/status pairs × 9 buckets).
- [x] `tournament_http_request_duration_seconds_bucket` exists in Prometheus (F1). — 18 series.
- [x] `histogram_quantile(0.95, …)` returns a number for gateway, identity, room-gameplay and
      tournament. — `0.00475 / 0.00475 / 0.00475 / 0.00525`, measured without the `or vector(0)`
      fallback so an empty result could not pass as a zero.
- [x] No metric name was added to any service. `git diff` over `services/**` touches only the two
      `Metrics.kt` timer builders and their tests.
- [x] Bucket cardinality is bounded: **9 bucket series per route/status pair** (the 8 gateway
      boundaries plus `+Inf`), not Micrometer's default percentile-histogram set. Measured on the
      real scrape before the change was pushed.

## 2. Dashboards

- [x] All three boards appear in Grafana **from a git commit alone** — no manual import, no
      `kubectl apply` by hand. — the `dashboards` Application syncs `gitops/platform/dashboards`,
      the three ConfigMaps carry `grafana_dashboard=1`, and the sidecar logged
      `Writing /tmp/dashboards/<board>.json` for each.
- [x] Business board names, in its own description, **which three panels are the consigna's ≥3
      business metrics**. — and in a text panel at the top of the board, which is what gets read.
- [x] **Every panel query resolves and every metric name it references is real.**
      `node scripts/check-dashboards.js` → 83 queries, 67 distinct metric names, 0 problems.
      The check distinguishes a typo (declared nowhere in `services/`, hard failure) from a
      tag-parameterised counter with no series yet (`roomgameplay_engine_rejections_total`,
      reported and allowed) — because `or vector(0)` renders both as a confident zero.
- [ ] On a cluster with no traffic yet, every panel reads `0` or an explicit "No data" — **no panel
      errors, none is blank**.
- [ ] After the tournament drill, every business panel has moved.
- [x] Every `outboxrelay_*` panel is grouped `by (job)`. **No summed relay panel exists** — no
      longer a grep somebody has to remember: `check-dashboards.js` fails any expression that
      names `outboxrelay_` without `by (job`, and the rule was bite-checked by summing one.
- [ ] Every gauge panel has its success counter beside it (`*_reads_total`, `*_sweeps_total`,
      `*_consumer_starts_total`).
- [ ] `analytics-api` and `spectator` latency: the panel states the metric does not exist rather
      than rendering empty (D2).

## 3. Grafana exposure

- [ ] `http://localhost:30081` serves Grafana on a **freshly created** kind cluster.
- [ ] The sealed admin credential logs in.
- [ ] The chart default (`admin` / `prom-operator`) **does not** log in.
- [ ] No plaintext credential anywhere in the repo: `grep -rn "prom-operator" gitops/` returns
      nothing outside a comment explaining that it is gone.

## 4. Alerting

- [ ] Alertmanager is Running and reachable, with the null receiver and **no notification config**.
- [ ] Every rule in the shipped set has been observed in `firing` at least once (§7 does this).
- [ ] The alert-state panel on the async-spine board shows a firing rule without leaving Grafana.
- [ ] No rule fires on a healthy idle cluster (checked after the from-empty install, before traffic).

## 5. Logs

- [ ] Loki and Alloy are Running in `monitoring`.
- [ ] One `correlationId` from a single CLI command returns log lines from **≥3 distinct services**
      in one LogQL query.
- [ ] The CLI prints that `correlationId` — the demo does not require reading it out of a log first.
- [ ] Kubelet health-probe lines are **not** in Loki.
- [ ] **Loki is non-load-bearing**: with Loki scaled to zero, all three dashboards render and all
      alerts still evaluate.

## 6. GitOps and platform

- [ ] `kubectl get application -A` is **Synced/Healthy for every app**, including the new ones.
- [ ] Loki and Alloy chart versions are **exact pins**, matching the platform's existing convention.
- [ ] No PVC is created by Prometheus, Loki or Alertmanager (`kubectl get pvc -n monitoring` is
      empty).
- [ ] From-empty install time is **recorded** and compared against the ~11 min P7 baseline.
- [ ] The two service overlays carry real digests that moved with F1 — checked in the overlay file,
      not from the job status. (This is the `needs:`/empty-variable trap that went green in P6.)

## 7. Bite tests — does the harness actually bite?

Presence is not proof. Each of these breaks something on purpose and requires the safety net to
notice.

- [x] **The histogram test bites.** Removing `.serviceLevelObjectives(*LATENCY_SLOS)` fails
      `HttpTest > metrics exposes the business counters under the names P8 will chart`; restoring it
      is green again. (Run before the fix was committed, so a working copy was enough — once it is
      in history this needs `git show <fixcommit>^:<path>`, **not** `git stash`, which reverts to a
      HEAD that already contains the fix.)
- [x] **The dashboard checker bites.** A one-character typo in a metric name
      (`roomgameplay_games_completd_total`) is reported as `UNKNOWN METRIC … declared nowhere in
      services/` and the script **exits 1**. Verified the exit code directly rather than through a
      pipe — `$?` after `| tail` is `tail`'s status, and the first run of this check reported
      `EXIT=0` for exactly that reason.
- [x] **The relay-split rule bites.** Rewriting one panel as `sum(outboxrelay_backlog_rows)` is
      reported as `RELAY NOT SPLIT` and exits 1.
- [ ] **The relay panel bites.** Suspend the Argo root, scale one relay to zero, and confirm
      **exactly one** series flattens on the by-job panels while the other keeps moving. A summed
      panel would show a dip and hide which relay died — this is the P7 handoff's warning, tested.
- [ ] **The relay-idle alert bites.** The same outage fires the rule, and it clears on restore.
- [ ] **The consumer-never-started alert bites.** With a consumer pod up and its
      `*_consumer_starts_total` at 0, the rule fires — this is P6's 13-minute silent outage in alert
      form.
- [ ] **The Loki query bites.** Query a `correlationId` that does not exist and get **zero** lines.
      (A query that returns everything looks identical to a working one on a busy cluster.)
- [ ] **Dashboard provisioning bites.** Remove the `grafana_dashboard: "1"` label from one ConfigMap
      and confirm the board disappears from Grafana — proving the sidecar is what delivers it and
      not a leftover manual import.
- [ ] **The sealed credential bites.** Point `existingSecret` at a wrong key and confirm Grafana
      fails to start rather than silently falling back to the chart default.

## 8. Drills

- [ ] **Rolling upgrade** onto the running cluster: no Deployment is left holding a stale Secret.
      Grafana's admin credential is the exact shape of P7's timer-worker 401 — `envFrom` resolves at
      pod creation and a Deployment does not restart when a Secret changes.
- [ ] **From empty**: `kind delete cluster` → install → **all apps Synced/Healthy**, all three boards
      live, Loki collecting.
- [ ] The casual gate still plays a full game (wild, draw, uno, challenge observed) — the standing
      regression gate.
- [ ] The tournament drill still produces a champion from four bots.
- [ ] If any finding is startup-shaped, a **second** from-empty drill was run. (Standing rule since
      P6: a cold-start fix verified warm is not verified.)

## 9. Docs

- [ ] The runbook's Grafana URL, LogQL query and "how to fire an alert" steps were **executed from
      the runbook text**, not from memory.
- [ ] `CHANGELOG-design.md` §13 records the deltas, including Alertmanager reversing P1/D4.
- [ ] `specs/tech-stack.md` §9 points at P8 and keeps its original text.
- [ ] The roadmap marks P8 **SHIPPED** and carries a **"Handoff from P8"** block.
- [ ] P9's checklist carries the distroless-mirroring item.

## 10. Out-of-scope confirmation

These must **not** appear in the diff:

- [ ] No OpenTelemetry collector, Jaeger, or tracing SDK.
- [ ] No alert receiver, notification channel, email/Slack/webhook config.
- [ ] No PersistentVolumeClaim for any observability component.
- [ ] No new business counter in any service.
- [ ] No recording rule or log-derived metric standing in for a business counter.
- [ ] No SLO, error budget or burn-rate alert.
- [ ] No Ingress, TLS certificate or auth proxy.
- [ ] No new pipeline stage.
- [ ] No change to any event, schema or database table.

## 10b. Findings so far (not defects P8 introduced)

- **No app container declares resource requests or limits.**
  `kube_pod_container_resource_requests{namespace="unoarena-staging"}` and the matching `_limits`
  both return **nothing**, while every platform component sets them. Consequences: saturation
  cannot be expressed as a percentage of a limit (the golden-signals board shows absolute
  working-set bytes and says why), and every app pod is `BestEffort` QoS — first to be evicted
  under memory pressure. Academic on a single-node kind cluster, less so on the 2× t3.large EKS
  rehearsal. **Not fixed in P8**: it is a deployment concern, not observability, and it touches all
  ten charts. Candidate for P9.
- **`gradle check` runs no linter.** `tech-stack.md` §2 lists ktlint/detekt for both Kotlin
  services, and neither `build.gradle.kts` applies either plugin — so `check` is just `test`. Long
  predates P8; recorded here because P8 read the file while adding the histogram tests.

## 11. Mission check

Two questions. If both are yes, P8 moved the mission forward:

1. **Can a stranger who has never seen this repo open one URL and say how many games and tournaments
   the system has played, without being told which metric to look at?**
2. **When something in the demo stalls, does a board say which service stopped — within the time a
   grader will wait?**

And the exam's own bar, which is narrower than either: `docs/final/consigna.md` asks for
observability infrastructure with **at least three business metrics**, deployed from an empty
cluster as part of the same install. Item §6's from-empty check plus §2's named three panels are
that requirement, evidenced.
