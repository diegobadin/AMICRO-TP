# North Star — Final Delivery (UnoArena, the real system)

> Program umbrella for the final exam ([`docs/final/consigna.md`](../../docs/final/consigna.md)).
> The DevOps-checkpoint pipeline is the delivery backbone; this program replaces the placeholders
> with the real system phase by phase **without changing the pipeline shape** (exactly the
> checkpoint-roadmap "Phase 4" handoff). Problem statement:
> [`presentation/high-level-definition.md`](../../presentation/high-level-definition.md).
> Exam harness: [`Client-Checkpoint.md`](../../Client-Checkpoint.md) — the canonical CLI surface.

## What the exam requires

1. Repo link 48h before + a **presentation** (final architecture, key decisions).
2. Live demo: start from an **empty k8s cluster** and deploy **all infrastructure and services**,
   including **observability with ≥3 business metrics**.
3. Functional requirements verified **through the CLI**: full casual loop (register → room →
   play/draw/uno → live feed → 409 reconciliation), spectator privacy, headless bot + seed,
   tournaments (mandatory but degradable, low configurable test threshold).

## Locked decisions (session 2026-07-26)

| # | Decision | Chosen |
|---|----------|--------|
| N1 | Timeline | 1.5–2 months to the exam → plan the **full program**, tournaments included |
| N2 | Demo cluster | **Provided by the faculty** — the bootstrap must install everything into *any* kubeconfig; local kind stays as the rehearsal harness |
| N3 | Tournament ambition | Planned end-to-end, but **casual core ships first**; degrades documented if time runs out |
| N4 | Execution | All implementation happens here (single executor); teammate reviews/presents |

## Architecture target (immutable input)

`docs/architecture/` — 10 deployables, event-sourced room-gameplay + transactional outbox,
REST + SSE (no WebSocket), Kafka async spine, per-context persistence, durable timers, Elo scope
casual-only. No re-litigation; any delta goes to `CHANGELOG-design.md`.

## Program rules

- **Casual-first gate.** Nothing beyond P4 starts until a human can play a full casual game
  through the CLI against a cluster deployed from empty.
- **Always deployable.** A phase is done only when its piece ships as a GitOps app that comes up
  from an empty cluster; the empty-cluster drill never breaks.
- **Instrument as you go.** Every real service exposes `/metrics` from its first real phase; P8
  only consolidates dashboards, it does not retrofit.
- **Additive growth.** No phase rewrites a previous phase's data model or events.
- **Pipeline shape is frozen.** Real services slot into the existing per-service spine
  (test → build → deliver → deploy) — replacing a placeholder must not add stages.

## Open risks

- **R1** Faculty-cluster unknowns (ingress class, storage class, RBAC, registry reachability) →
  cluster-profile values layer + an early install dry-run against a "foreign" kubeconfig; ask the
  faculty for cluster specs when coordinating the date.
- **R2** Uno rules engine correctness under concurrency (stacking, jump-in windows, Uno! races) →
  pure engine module + property/log-replay tests in P3, sequence numbers enforced at the API.
- **R3** SSE fan-out on demo hardware → demo targets correctness; the 1M-scale story is told in
  the presentation with the architecture numbers, not reproduced live.
- **R4** Single-executor bandwidth vs. scope → the casual-first gate is the cut line; P6+ phases
  are individually degradable-documented without breaking earlier ones.

## Roadmap

Phases P1–P9 with dependencies in [`roadmap.md`](./roadmap.md). Each phase gets its own dated
triad spec when it starts.
