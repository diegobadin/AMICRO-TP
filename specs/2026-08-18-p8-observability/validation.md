# P8 — Observability consolidation — validation

> Binary checks. Every item is either observable or it is not done.
> Evidence (transcripts, PromQL output, screenshots, timings) is appended to this file as it is
> produced, the same way P5–P7 did it.

## 1. Metrics surface

- [ ] Prometheus shows **every service target `up`**, including both relay jobs
      (`outbox-relay`, `outbox-relay-tournament`).
- [ ] `roomgameplay_command_duration_seconds_bucket` exists in Prometheus (F1).
- [ ] `tournament_http_request_duration_seconds_bucket` exists in Prometheus (F1).
- [ ] `histogram_quantile(0.95, …)` returns a number for gateway, identity, room-gameplay and
      tournament.
- [ ] No metric name was added to any service. `git diff` over `services/**` touches only the two
      `Metrics.kt` timer builders and their tests.

## 2. Dashboards

- [ ] All three boards appear in Grafana **from a git commit alone** — no manual import, no
      `kubectl apply` by hand.
- [ ] Business board names, in its own description, **which three panels are the consigna's ≥3
      business metrics**.
- [ ] On a cluster with no traffic yet, every panel reads `0` or an explicit "No data" — **no panel
      errors, none is blank**.
- [ ] After the tournament drill, every business panel has moved.
- [ ] Every `outboxrelay_*` panel is grouped `by (job)`. **No summed relay panel exists** — grep the
      dashboard JSON for `outboxrelay_` and confirm each query carries the grouping.
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

- [ ] **The histogram test bites.** Restore the pre-fix `Timer.builder` (via
      `git show <fixcommit>^:<path> > <path>`, **not** `git stash` — stash reverts to HEAD, which
      already has the fix) and confirm the `_bucket` assertion fails. Restore from a copy afterwards,
      using absolute paths.
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
