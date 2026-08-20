# ESTADO FINAL — P8: Observability consolidation

> Drilled from an empty cluster on 2026-08-20: **24 of 24 Argo apps Synced/Healthy in 12 m 25 s**,
> three dashboards live from a git commit alone, nine alert rules loaded, and one `correlationId`
> returning log lines from **five services** in a single query. The exam's observability
> requirement — *"la infraestructura de observabilidad, con por lo menos tres métricas de negocio"* —
> is answered by the same install that brings the system up.

## What shipped

- **Three dashboards, as code.** Business (the board to show), golden signals, async spine (the
  board to open when a demo stalls). Committed JSON rendered into ConfigMaps that Grafana's sidecar
  already knew how to find — no change to the monitoring release, and no import step that can drift
  from git.
- **Grafana on NodePort 30081, with a credential we own.** Sealed into `monitoring` at wave −1. It
  replaces the password the chart generates for itself, which nobody held: the values file's comment
  had said "chart-default admin credentials" since P1, and there was no default to use.
- **Alertmanager, and nine rules.** No receivers — the alerting plane and rules that fire visibly,
  not a channel nobody reads. Every rule is a failure this project has actually had.
- **Loki + Grafana Alloy.** Single binary, ephemeral, no PVC. Alloy rather than promtail, which is
  end of life upstream.
- **`check-dashboards.js`.** Fails a panel whose metric name no service declares, a query that does
  not resolve, and any `outboxrelay_` query not split `by (job)`.

## What P8 did *not* do

The handoff's rule was to retrofit nothing, and almost nothing was retrofitted. Two exceptions, both
escalated rather than assumed:

- **The two Kotlin timers publish histogram buckets** at the gateway's own boundaries, so a p95
  means the same thing on both sides of one request. Same meter, no new metric name.
- **The three Kafka consumers read `ce-correlationid`.** This added no claim — architecture §1
  already said "all consumer logs include the same `correlationId`", and the relay has written that
  header since P5. Nobody read it.

`analytics-api` and `spectator` still publish no request latency, and the golden-signals board says
so on the panel rather than showing an empty graph.

## Evidence

`validation.md` carries it. The short version: **24/24 from empty in 12 m 25 s**, 11 of 11 scrape
targets up, **no PVC** in `monitoring`, p95 resolving for all four HTTP services
(`0.00475/0.00475/0.00475/0.00525`, measured without the `or vector(0)` fallback), 86 dashboard
queries with 0 problems both before and after traffic, the casual gate playing a full game (28
actions, 0 errors), four bots producing **exactly one champion**, and a five-service correlationId
trace.

## What the drills caught that nothing else did

**Ephemeral still has to exist somewhere.** Loki crash-looped on `mkdir /var/loki: read-only file
system`. Turning off persistence removes the PVC and mounts *nothing* in its place, while the
container keeps `readOnlyRootFilesystem: true`. An emptyDir is the missing half.

**Two label conventions live in this cluster.** Alloy labelled log streams from
`app.kubernetes.io/name`, which the upstream platform charts set and the ten UnoArena charts do
not — they use `app:`. The result was a Loki that knew every platform component and **none of the
actual services**, which looks like a working log pipeline until you go looking for a service.

**A trace that stopped one hop short of the point.** The relay had carried `ce-correlationid` since
P5 and no consumer read it, so a trace ended at the service that produced the event. The promise
nobody verifies, in a header.

**Two alert rules that could not fire.** Found by the review pass, not the drill: the consumer-lag
threshold was `500` against a system whose *entire demo* publishes 179 events. D7's own rule —
a rule that has never fired is not coverage — broken by two of the nine rules written to satisfy it.

**Reading an async system too early looks exactly like a bug.** Twice: a declared champion with
`tournaments_completed` still at 0, and a five-service trace that showed three. Both resolved
themselves within a scrape interval. The inverse of P4's "a probe sent late measures the deadline".

## Decisions worth carrying forward

- **A panel that names an absence beats an empty panel.** An empty graph is a diagnosis somebody has
  to perform; "this service publishes no latency histogram" is an answer.
- **`or vector(0)` needs a lint behind it.** It stops a fresh cluster reading "No data", and it
  makes a typo render as a confident zero. The checker closes that hole — and on a fresh install it
  reported **nine** metrics as "declared but not yet emitted", every one of which would otherwise
  have looked like a typo.
- **The Watchdog is the alerting form of pairing a gauge with a success counter.** An always-firing
  alert is what makes silence mean healthy rather than broken.
- **A threshold is a measurement, not an opinion.**

## Known gaps, deliberate

- **Seven of nine rules have never been observed firing.** Listed as untested rather than described
  as covered.
- **No tracing backend.** Architecture §1's optional OpenTelemetry spans stay unbuilt; logs carrying
  a correlation id answer the same question here.
- **No alert receivers**, no SLOs, no persistent storage for any observability component.
- **A workload scaled to zero fires nothing** — its target disappears rather than failing. The case
  GitOps `selfHeal` prevents.
- **No resource requests or limits on the ten app containers**, and **`gradle check` runs no
  linter** despite tech-stack §2. Both found here, neither is observability; both are on P9's
  checklist.

## Next

**P9 — demo rehearsal + presentation.** The system is complete; what remains is rehearsing it,
timing it, and telling the story. The roadmap carries a "Handoff from P8" block and a P9 checklist
that now includes mirroring the distroless bases, renewing the CI token, and the two findings above.
