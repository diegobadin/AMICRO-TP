# CHANGELOG — Design Package Alignment for the Architecture Checkpoint

> **Purpose.** This document satisfies Architecture Checkpoint deliverable **§6.2**. It enumerates every change made to the Design Checkpoint package to align it with the architecture produced in [`docs/architecture/01-service-architecture.md`](docs/architecture/01-service-architecture.md) (deliverable §6.1), states the architecture/integration constraint that forced each change, and explicitly affirms that **no non-negotiable domain guarantee was weakened or dropped**.
>
> Design Checkpoint deliverable numbering follows `design-checkpoint` §5: **1 — Domain glossary**, **2 — Bounded contexts & context map**, **3 — Aggregates / entities / value objects**, **4 — Commands & domain events catalog**, **5 — Domain event flow narratives**, **6 — Edge cases & failure-path analysis**, **7 — Consistency & recovery strategy**, **8 — Open questions & assumptions**. These map 1:1 to `docs/design/01-…` … `docs/design/08-…`.

---

## 0. Summary

The architecture introduced **no change to bounded-context boundaries, aggregates, or domain events**. The command/event catalog (Deliverable 4) is preserved verbatim at the domain level; every synchronous endpoint and every Kafka topic in the architecture maps 1:1 to a named command or event already in the catalog. Traceability per Architecture Checkpoint §2 holds without a domain-model delta.

Four classes of edits were required, all **representation-level** or **catalog-completeness**, none altering an invariant:

| # | Class of change | Artifacts touched | Deliverable(s) |
|---|-----------------|-------------------|----------------|
| 1 | Synchronous interface representation (verb-RPC → resource-oriented REST; stale-command status mapping) | `docs/design/01-domain-glossary.md` §1.4; `docs/design/07-consistency-recovery.md` §7.2.1; `README.md` (EventStorming board) | 1, 7 |
| 2 | Event-catalog completeness (events referenced by failure-path analysis but missing from the catalog) | `docs/design/04-commands-events.md` §4.1 | 4 |
| 3 | Repository index repair + architecture wiring | `README.md` (deliverables index, added Architecture section) | — (packaging) |
| 4 | Context-map / EventStorming label alignment to the authoritative catalog | `README.md` (high-level Mermaid map) | 2, 4 |

No edits were made to Deliverables 3 (Aggregates), 5 (Event-flow narratives), 6 (Edge cases), or 8 (Open questions): the architecture conformed to them rather than the reverse.

---

## 1. Synchronous interface representation

### 1.1 Verb-RPC endpoints → resource-oriented REST

**Artifacts:** Architecture §2.3.1 (new); design package unchanged at the command level — see affirmation below.

**What changed (architecture side, recorded here for traceability):** The first-pass synchronous API was action-oriented (`POST /rooms/{id}/join`, `/start`, `/play`, `/draw`, `/pass`, `/call-uno`, `/challenge-uno`, `/reconnect`). The architecture replaced it with a resource model: membership (`PUT/DELETE/PATCH /rooms/{id}/players/{playerId}`), the game (`POST /rooms/{id}/games`), and an **append-only move collection** (`POST /rooms/{id}/games/{gameId}/moves`) that *is* the immutable game log.

**Why (architecture/integration constraint):** Architecture Checkpoint §2/§19 and §8 ("Interface quality") require synchronous APIs to be a coherent, usable contract and to map to the command catalog. The action endpoints were RPC-over-HTTP and ignored HTTP verb semantics. The move-collection model also makes the **log-before-broadcast** invariant (Deliverable 7 §7.2, non-negotiable per §2) structurally visible: a move is *created* in the log, not dispatched as an RPC.

**Traceability / non-negotiable affirmation:** **Deliverable 4 — Commands & domain events catalog is unchanged.** Every endpoint still carries a `Maps to Command` column binding it to an existing command (`JoinRoom`, `StartGame`, `PlayCard`, `DrawCard`, `PassTurn`, `CallUno`, `ChallengeUno`, `ReconnectPlayer`, `ForfeitPlayer`). The `PlayCard/DrawCard/PassTurn/CallUno/ChallengeUno` family is grouped under one `moves` resource with a `type` discriminator — a transport grouping, not a command rename/split/merge. Sequence-number enforcement, turn enforcement, and play-legality (Deliverable 3 invariants 1–3; Deliverable 7 §7.5) are **preserved exactly** and now additionally surfaced as standard HTTP conditional requests.

### 1.2 Stale-command status code: `409` → `412 Precondition Failed` (via `If-Match`)

**Artifacts changed:** `docs/design/01-domain-glossary.md` §1.4 (*Sequence Number*); `docs/design/03-aggregates.md` §3.2.1 (Room invariant 1); `docs/design/07-consistency-recovery.md` §7.2.1 (mechanism diagram **+ a new package-wide normative HTTP-mapping note**); `docs/design/04-commands-events.md` §4.1 (`StaleCommandRejected` consumer note); `README.md` EventStorming board (stale-command exceptional flow).

**What changed:** The design package hard-coded `HTTP 409` as the stale-sequence rejection. The architecture models the sequence number as the resource **ETag** and rejects stale state-mutating requests with `412 Precondition Failed` (missing `If-Match` → `428 Precondition Required`); `409 Conflict` is now reserved for true state conflicts (act-out-of-turn, join a started/full room, start a game already in progress). The definitional spots (glossary, aggregate invariant, mechanism diagram, catalog note, README) now describe the domain rule ("stale command is rejected; client reconciles and retries") and **cite Architecture §2.3.1** for the wire mapping. A single **normative HTTP-mapping note** added to §7.2.1 governs every remaining incidental "409" phrasing in the edge-case prose (Deliverable 6 §6.1.1/§6.3/§6.5.2) and the §7.5/§7.7 summaries, so the package speaks at the domain abstraction and defers the wire code to the architecture in one authoritative place rather than scattering edits.

**Why (architecture/integration constraint):** Architecture §6.1 / §8 ("Interface quality", "respect HTTP verb properties"). The domain invariant is *"reject stale or replayed state-mutating commands and force reconciliation"* (Deliverable 7 §7.2.1) — the wire status code is an architecture mapping, not a domain rule. Pinning `409` in the glossary made the design and architecture tell two stories.

**Non-negotiable affirmation:** The **sequence-number enforcement** invariant (Architecture Checkpoint §2 mandatory architectural home; Deliverable 7 §7.5) is **strengthened, not weakened**: optimistic concurrency is now mandatory at the protocol level (`428` if `If-Match` is omitted), so an unconditional write to game state is impossible. Exactly-once-per-room semantics are unchanged.

---

## 2. Event-catalog completeness (Deliverable 4 — Commands & domain events catalog)

**Artifact changed:** `docs/design/04-commands-events.md` §4.1 (Room Gameplay domain events).

**What changed:** Added two events that were already *referenced* by the design package's failure-path and consistency analysis but absent from the catalog table:

- **`RoomCreationFailed`** — referenced in Deliverable 6 §6.4.3 and Deliverable 7 §7.4.2/§7.7 (tournament room provisioning failure, dead-letter, operator alert).
- **`RoomExpired`** — referenced in Deliverable 6 §6.8.5 and Deliverable 7 §7.4.2 (`Waiting`-state inactivity timeout; tournament round compensation treats it as an all-forfeit room).

**Why (architecture/integration constraint):** Architecture Checkpoint §2/§6.1: every integration row must trace to a *named* event in the catalog. The architecture's first-round-surge handling (Architecture §3.5 partial-failure sweep, dead-letter → operator alert) and the round-advancement saga depend on `RoomCreationFailed`; the `Waiting`-room inactivity path depends on `RoomExpired`. Leaving them out of Deliverable 4 would make those integration rows untraceable.

**Non-negotiable affirmation:** No invariant changed. These events were already part of the documented behaviour (Deliverables 6 & 7); the catalog now reflects them. The **round-advancement gate** (a round never advances until all rooms report; Deliverable 7 §7.4.3, non-negotiable) is unaffected — `RoomExpired`/`RoomCreationFailed` are exactly how a non-completing room still produces a terminal signal so the gate can close.

---

## 3. Repository index repair + architecture wiring (packaging)

**Artifact changed:** `README.md` — deliverables index and a new Architecture section.

**What changed:** The design documents were relocated to `docs/design/` but the README index still linked `./docs/01-…` (broken links). Repaired all eight links to `./docs/design/0X-…`, added an **Architecture** section linking `docs/architecture/01-service-architecture.md` and this changelog, and adjusted the scope note (the repo is no longer "domain design only" — it now also carries the architecture).

**Why:** Architecture Checkpoint §9 ("a root README.md or index.md that links all documents") and §6.2 ("a reader can verify" the package). Broken links fail the navigability bar.

**Non-negotiable affirmation:** Packaging only; no domain content changed.

---

## 4. Context-map / EventStorming label alignment (Deliverables 2 & 4)

**Artifact changed:** `README.md` — the high-level Mermaid domain map.

**What changed:** The Mermaid map used edge labels that never existed in the authoritative catalog (Deliverable 4): `RoundAdvanced`, `BracketUpdated`, `TournamentPlacementChanged`. Relabelled to the catalog's real events — `RoundStarted`/`RoundCompleted`, `RoomResultRecorded`, `TournamentPlacementUpdated` — so the context map, the catalog, and the architecture's Kafka topic contracts (Architecture §3.3.2) tell one story.

**Why (architecture/integration constraint):** Architecture Checkpoint §2 ("Traceability is not a vibe check") and §8 ("Alignment"). The async contracts in the architecture are partitioned by the Deliverable-4 event names; a context map advertising non-existent events breaks reviewer traceability.

**Non-negotiable affirmation:** Pure label correction toward the existing catalog; no event added, removed, or re-scoped. **Tournament advancement** semantics (top-3 per room, best-of-3 series, deterministic tiebreak — non-negotiable per §2/§33) are untouched and remain defined in Deliverable 1 §1.5, Deliverable 3 §3.3.1, and enforced per Architecture §2.7 / §3.

---

## 5. Package-wide non-negotiable affirmation

For the design package **as a whole**, every Design Checkpoint non-negotiable guarantee is preserved and now has an explicit architectural owner (Architecture §10 invariant→component map). No guarantee was weakened or dropped:

| Non-negotiable (Architecture §2 / Design §33) | Still defined in (design) | Owned at runtime by (architecture) |
|---|---|---|
| Sequence-number enforcement (reject stale/replayed) | D7 §7.2.1, D7 §7.5 | Room Gameplay Service + Event Store (§2.3.1, §9) — *strengthened* (§1.2 above) |
| Log-before-broadcast atomicity | D7 §7.2, D1 §1.9 | Transactional outbox + event sourcing (§2.5, §8.1) |
| 5-second Uno! challenge window (durable, idempotent) | D1 §1.3, D3 §3.2.9, D6 §6.1.4 | Room aggregate deadline + Timer Worker (§2.6) |
| 60-second reconnection window (durable, idempotent) | D1 §1.7, D5 §5.4, D6 §6.2 | Room aggregate deadline + Timer Worker (§2.6) |
| Single-active-session (live connection kill) | D1 §1.7, D6 §6.2.4, D3 §3.5.1 | Identity → Redis pub/sub → Gateway (§5.5, §8.3) |
| Spectator privacy (no hand data) | D2 §2.1.5/§2.5, D6 §6.5.3 | Public Event Publisher + ACL + read model (§6.3) |
| Match-series coordination (best-of-3 cross-game state) | D1 §1.4, D3 §3.2.1 inv. 6 | Room aggregate `matchScores`/`gamesPlayed` (§2.7) |
| Abandoned vs. completed (Elo/tournament) | D1 §1.6, D6 §6.8.1 | `GameCompleted{roomType,isAbandoned}`; Ranking filter (§2.8, §4.5) |
| Elo scope: casual-only, non-abandoned, per-game | D3 §3.4.1, D7 §7.5 | Ranking consumer entry filter (§4.5) |
| Tournament advancement: top-3, series, deterministic tiebreak | D1 §1.5, D3 §3.3.1 | Room aggregate + Tournament aggregate (§2.7, §3) |
| Consistent match vs. game terminology | D1 §1.4 | Preserved across all interface/event names |

**Conclusion.** The design package and the architecture now tell one story. All deltas are intentional, representation- or completeness-level, and recorded above with rationale; no domain invariant was relaxed.

---

## 6. DevOps Checkpoint deltas (placeholder set vs. architecture)

> Per DevOps Checkpoint §3, any drift between the architecture document and the set of service
> placeholders must be recorded here. The placeholder set mirrors the **10 deployable containers**
> of `docs/architecture/09-local-topology.md` one-to-one (`services/<name>/`).

| # | Delta | Rationale | Invariant impact |
|---|-------|-----------|------------------|
| 6.1 | C4 "Round Kickoff Workers" (Architecture §1, §3.2) is **not** a separate placeholder; it is **folded into the `tournament` placeholder**. | The local-topology compose (the authoritative *deployable* decomposition) does not ship it as its own container; it is a stateless worker pool of the Tournament context. For a placeholder pipeline whose grading lens is independent deployability, one `tournament` image is the honest unit. | **None.** No domain guarantee lives in the worker pool; the first-round-surge mechanism (Architecture §3.5) is unchanged and would be exercised by a real `tournament` service later. |
| 6.2 | The placeholder set is the **10 deployables**, not the 6 bounded contexts. | Room Gameplay ships as `room-gameplay` + `outbox-relay` + `timer-worker`; Analytics as `analytics-workers` + `analytics-api`. Splitting them as separate images/charts reflects their separate release lifecycles (Architecture §2.2, §7.2). | **None.** Boundaries and ownership are preserved; this is finer-grained delivery, not a boundary change. |

**Affirmation.** No bounded-context boundary, aggregate, event, or non-negotiable invariant was
changed by the DevOps checkpoint. The placeholders carry no domain behaviour except the small
`identity` `register`/`whoami` slice (the fully-wired demonstrator), which does not encode any
domain invariant.
