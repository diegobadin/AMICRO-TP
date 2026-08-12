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

---

## 7. Final delivery — P2 deltas (identity becomes a real service)

> The first phase that replaces a placeholder with real behaviour
> (`specs/2026-08-08-p2-identity-auth/`). Anything the running service does differently from
> `docs/architecture/` is recorded here rather than by editing the architecture.

| # | Delta | Rationale | Invariant impact |
|---|-------|-----------|------------------|
| 7.1 | REST surface is `POST /auth/register`, `/auth/login`, `/auth/logout` and `GET /auth/whoami`. The catalog (Architecture §5.3) lists login/refresh/logout plus gRPC `ValidateToken`. | Registration exists in the domain (`PlayerRegistered`) but had no REST endpoint, and the Client CLI needs one. `whoami` is the REST shape of `ValidateToken` for a client that has no gRPC channel; the gateway can still use gRPC when it arrives. | **None.** Same operations, one extra entry point each. |
| 7.2 | `/auth/refresh` is not implemented. Identity issues one short-lived JWT (~1h) and a client whose token expires logs in again. | No flow the exam exercises outlives the token, and refresh tokens are a second credential to store, rotate and revoke. Documented as a gap in `clients/cli/README.md`. | **None.** Single-active-session is enforced at login, which is exactly where refresh would have had to enforce it too. |
| 7.3 | `players` omits `email`; `sessions` omits `ip_address` (Architecture §4 lists both). | The CLI supplies neither, and the Identity context is the only one holding PII — storing a field nobody sets would widen the PII surface for nothing. Both are additive later. | **None.** |
| 7.4 | Single-active-session is enforced in the database and announced on both transports, but no consumer kills a live connection yet. | The connection-owning tier (gateway + SSE) arrives in P4; the event contract is published now so P3/P4 consume it without changing it. Today a superseded session dies at the next request (`401`), which is the belt-and-suspenders path Architecture §5.5 already describes. | **Partially deferred, deliberately.** The DB half of the invariant (atomic replacement, enforced by a partial unique index) is live; the live-kill half lands with the tier that owns the connections. |
| 7.5 | `identity.session-events` is created with 3 partitions instead of the catalog's 64. | 64 partitions on a single-broker kind cluster buys nothing. It is a values-level number, not a design choice. | **None.** |

**Affirmation.** No bounded-context boundary, aggregate, event, or invariant was weakened. The
single-active-session non-negotiable is now enforced by a database constraint rather than argued
in prose, and `SessionInvalidated` reaches both the low-latency and the durable transport the
architecture assigns it.

---

## 8. Final delivery — P3 deltas (the Uno engine and the room-gameplay core)

> The phase the program is de-risked around (`specs/2026-08-08-p3-room-gameplay/`): the rules
> engine, the event-sourced core and the casual game path. As in §7, anything the running service
> does differently from `docs/architecture/` and `docs/design/` is recorded here rather than by
> editing those documents.

| # | Delta | Rationale | Invariant impact |
|---|-------|-----------|------------------|
| 8.1 | **The game auto-starts** when a room reaches `ROOM_MIN_PLAYERS` (default 2). Architecture §2.3.1 has `POST /rooms/{id}/games` as host- or system-initiated. | Decision E3. `play --casual` is meant to be "put me into a game" (Client §5.B); a host-initiated start makes two players negotiate who presses go. `POST …/games` still exists and still works for an explicit start. | **None.** `StartGame` is the same command with the same preconditions; only who issues it changed. |
| 8.2 | **`GET /rooms` added** — a collection read the architecture's resource table does not define. | `room list` (Client §5.B) needs one, and answering it by replaying every room would be absurd. Read-only, served from the `rooms` projection, and it lists only rooms that are waiting, not full and **not empty**. | **None.** Additive read. |
| 8.3 | **`PlayerLeft` added** to the Room event catalog (§4.1). | Leaving a room that has not started is neither a forfeit nor a disconnection, and `DELETE /rooms/{id}/players/{pid}` had no event to emit. Leaving a game **in progress** is still `PlayerForfeited`. | **None.** Additive event; no existing consumer contract changes. |
| 8.4 | **`StaleCommandRejected` is not appended to the log.** The catalog lists it as a Room event. | Appending on rejection would consume the sequence number the client is about to retry with, and AC-P3.3 requires a failed command to leave the log untouched. The rejection travels as the HTTP response (`412`/`409`) instead — the catalog already notes it is not propagated to other contexts. | **None.** The client learns exactly what the event would have told it. |
| 8.5 | **One sequence number per event**, not per command. §3.2.6 splits `entryId` (per entry) from `sequenceNumber` (per command), implying several entries can share one. | The unique index on `(room_id, sequence_number)` is E6's whole concurrency mechanism; one row per number is what makes "exactly one writer commits" true. A command that emits five events consumes five numbers and the `ETag` jumps by five. | **Strengthened.** Serialisation is enforced by a database constraint rather than by convention. |
| 8.6 | **An initial Wild's colour is chosen from the recorded seed**, not by the first player. Invariant 14 says the first player chooses. | Letting them choose needs a sub-state where no colour is active and an extra round-trip before the game can start, for a case that arises in ~4% of deals. The colour is deterministic, recorded in `GameStarted`, and replay reproduces it. | **Narrowed, deliberately.** Invariant 12 (a Wild always resolves to a real colour, immediately) holds; who picks it in this one case does not. |
| 8.7 | **`consumed_events` table** beyond the four of the persistence plan. | Kafka is at-least-once and the `identity.session-events` consumer must be idempotent by `oldSessionId` (§4.7) — without a record of what has been seen there is nothing to be idempotent *by*, and a redelivery would re-open a reconnection window that had already expired. Generic, so P5's and P6's consumers reuse it. | **None.** It is what makes the documented idempotency real. |
| 8.8 | **The live feed is derived from polling**, not from an event stream. Client §5.C asks for a live feed. | Decision E4: P3's live view is `GET …/games/{gid}` with `If-None-Match` → `304`. The CLI diffs consecutive polls to print the feed, so two things inside one interval collapse into one line. **P4 replaces the loop with SSE over the same endpoint** and the feed becomes the real event stream. | **None.** Ordering still comes from the room's sequence number. |
| 8.9 | **room-gameplay validates identity's JWT with a shared symmetric secret.** | Decision E1, and a real (small, internal) coupling: two services hold one HS256 key until P4's gateway owns validation and room-gameplay trusts `X-Player-Id`/`X-Session-Id` inside the trust boundary. Written down rather than normalised. Each service gets its own sealed secret, so room-gameplay never sees identity's database password. | **None**, but it is the coupling P4 removes. |
| 8.10 | **A second NodePort (30081) and a second CLI target (`UNOARENA_ROOMS_URL`).** | There is no gateway yet, so identity and room-gameplay each own a port. Both collapse into one the moment P4's gateway becomes the single entry point. | **None.** Deployment shape, not design. |
| 8.11 | **`room.public.events` and `room.lifecycle.events` are declared with 3 partitions** (catalog: 64), and **nothing drains the outbox yet**. | Same reasoning as §7.5 for partitions. The outbox fills from P3 on; P5's relay is the only thing that has to be added, which is exactly the seam the architecture describes. | **None.** Log-before-broadcast is already whole: events and outbox rows share one transaction. |

**Affirmation.** No bounded-context boundary, aggregate or non-negotiable invariant was weakened.
All 14 Room invariants of §3.2.1 are implemented and property-tested; log-before-broadcast is a
single database transaction verified against a real Postgres by forcing the outbox insert to fail;
stale-command rejection is the `412` of §1.2 above, enforced by a unique index rather than argued.
The one narrowing is §8.6, recorded with its reason.

## 9. Final delivery — P4 deltas (the gateway and the realtime tier)

> The phase that gives the casual loop one entry point and a real event stream
> (`specs/2026-08-10-p4-gateway-sse/`). As in §8, anything the running system does differently from
> `docs/architecture/` is recorded here rather than by editing that document.
>
> **§8.9 and §8.10 close here**: room-gameplay holds no signing key and owns no NodePort.

| # | Delta | Rationale | Invariant impact |
|---|-------|-----------|------------------|
| 9.1 | **The player SSE stream is fed from Redis Streams, not from the outbox relay.** Architecture §2.5 sketches the gateway's push as piggybacked on the response or fed by the relay. | Decision E1. room-gameplay `XADD`s each committed event to `room:{roomId}:events` after the transaction commits; the gateway tails it. This keeps P4 independent of P5 (the relay), gives sub-second delivery, and leaves the outbox untouched for the consumer that owns it. The publish is best-effort and counted (`roomgameplay_stream_publish_failures_total`) — the events are already durable, so a lost frame costs a repaint, never a move. | **None.** Log-before-broadcast is unchanged: the commit still happens first, and Redis is a second, transient copy. |
| 9.2 | **The SSE entry id is the room's `sequenceNumber`** (`XADD` with an explicit `{seq}-0`). | It makes `Last-Event-ID` a stream position with no lookup table, so resume and per-room ordering are properties of Redis rather than of gateway code. Room sequence numbers are strictly increasing — that is P3's primary key — which is exactly what an explicit XADD id requires. | **Strengthened.** One ordering, enforced in two places by the same number. |
| 9.3 | **The control channel is folded into the room stream.** Architecture §1.1 lists a separate session-scoped control channel. | `session-invalidated` and the gateway's own `heartbeat`/`resync` frames ride the room stream the player is already holding. A second connection per player would buy nothing in P4, where the room stream is the client's only stream. Control frames are hyphenated and carry no `id`, so they are never mistaken for domain events. | **None.** The kill path of §5.5 is unchanged; only which socket carries it. |
| 9.4 | **The gateway verifies identity's tokens with the same HS256 key** rather than a public key. | One signer, one verifier — the smallest blast radius a symmetric key has. RS256 + JWKS is the upgrade the day a second verifier exists; it would mean changing identity, which P4 deliberately does not. | **None.** §8.9's two-service key sharing is what this replaces. |
| 9.5 | **Room membership for the stream is checked once, at subscribe.** | The gateway asks room-gameplay (`GET /rooms/{id}`) rather than keeping a second copy of who is seated. A player who leaves keeps the stream until the room ends, which is safe precisely because the frames are public-only — a stale subscriber learns nothing it could not read from the same endpoint. | **None.** The privacy boundary is the payload filter, not the subscription. |
| 9.6 | **No rate limiting, no TLS termination at the gateway.** Architecture §2.1 maps four limiter layers to deployables. | P4 makes the gateway an entry point, not yet a policy enforcement point. The layers are described in the architecture and none of the exam's functional requirements depend on them; adding them now would be scope with no demo behind it. | **None.** Nothing is weakened; a documented control is simply not implemented yet. |
| 9.7 | **The revoked-session set is per pod and in memory.** | Fed by the Redis pub/sub identity has published to since P2, so a killed session loses its stream and its next REST call within a second. A gateway restart forgets it, and a superseded token would be accepted again until it expires — accepted for P4 and written down: room-gameplay still disconnects the player through `identity.session-events`, and the authoritative alternative costs an introspection hop on every request. | **None**, and it is the first time §5.5's kill path is real end to end. |
| 9.8 | **The CLI narrates events and re-reads state when the player can act**, rather than projecting events onto a local board. | The client holds one authoritative snapshot plus a feed. A second copy of the game on the client is the thing most likely to disagree with the engine, and a board drawn from it showed a turn prompt in the middle of a multi-event batch — inviting the player into a `409`. Reads happen on turn arrival, on a challenge window, when the player's own cards change, and when a gap or heartbeat says the picture cannot be trusted. | **None.** `playable` is still the server's legality check, which is what the board promises. |

| 9.9 | **When Redis is unreachable the live feed degrades to REST *and says so*.** The architecture describes the push channel but not what it does when its transport dies. | Found by the F8 drill, which is the only place it could be: with Redis at zero replicas the SSE connection stays open and healthy-looking, because `ioredis` parks a blocking `XREAD` in its offline queue rather than rejecting it, and the heartbeat reports a cursor the gateway holds in memory. The feed froze while the room moved on, and nothing told the player. The tail connection no longer queues, and the first failed read of an outage broadcasts one `resync` — the frame the client already re-reads on. Play itself is unaffected throughout: moves are REST, and publication was always best-effort and counted. | **None.** It makes an existing guarantee reachable rather than adding one: the client's picture is either current or it has been told to re-read. The stream stays a transient convenience over an authoritative log. |

**Affirmation.** No bounded-context boundary, aggregate or non-negotiable invariant was weakened.
The trust boundary moved outward as the architecture always described it (§1.3): the gateway is the
only holder of client connections and the only validator of sessions, and room-gameplay — now
`ClusterIP` — trusts headers it can only receive from inside the cluster. The spectator privacy
boundary is stronger than in P3, not weaker: the room stream carries `publicPayload(event)`, the
same filter the outbox row goes through, so the RNG seed never reaches a player.

## 10. Final delivery — P5 deltas (the async spine)

> The phase that drains the outbox and gives the deadlines a clock
> (`specs/2026-08-11-p5-async-spine/`). As in §8 and §9, anything the running system does differently
> from `docs/architecture/` is recorded here rather than by editing that document.
>
> **§9's "nothing drains the outbox" closes here**, and with it the last piece of §2.5 that existed
> only on paper.

| # | Delta | Rationale | Invariant impact |
|---|-------|-----------|------------------|
| 10.1 | **The timer worker finds due rooms by polling a deadline cached on the `rooms` projection**, rather than room-gameplay pushing `ScheduleTimer` into a Redis sorted set (architecture T1–T3). | Decision E1. `next_deadline` is written in the same transaction as the events, so it can never describe a room that does not exist, and the worker compares it against the database's own clock. The pushed-schedule design would keep a second copy of every deadline outside the aggregate and need cancel-and-reschedule semantics for each of them; this needs neither, because the aggregate is asked rather than told. It also costs the command path nothing: no synchronous hop to a worker before a move can be answered. | **None.** Architecture §1 already made the aggregate the correctness backstop ("the Timer Worker provides prompt expiry; the aggregate provides correctness"). This makes the worker purely the prompt path, which is exactly the role that document gives it. |
| 10.2 | **The callback is `POST /internal/rooms/{roomId}/tick` with no body**, where T4 specifies `/internal/rooms/{roomId}/timer-expired { timerId, timerType }`. | A worker that names which timer expired is holding a second opinion about game state, and a stale one — by the time the call lands the window may have closed, the player may have acted, or a different deadline may be the overdue one. Saying only "this room's clock has run out" leaves every judgement in the aggregate, which is what makes a late, duplicated or unnecessary tick a no-op instead of a decision. | **None.** T4's idempotency requirement is satisfied more simply: there is nothing to be idempotent *about*, because the tick asserts nothing. |
| 10.3 | **A player whose turn lapses three times in a row gives up the seat** (`PlayerForfeited`, reason `idle`; `IDLE_TIMEOUTS_BEFORE_FORFEIT`). Not in the architecture at all. | Decision E2, and a direct consequence of 10.1: a room everyone walked away from used to be merely stuck, because deadlines were only evaluated when a command arrived. Give it a clock and it produces a `TurnTimedOut` every turn *forever*, growing the log and the outbox without end. The forfeit routes into invariant 7 — below two active players the game ends abandoned — so an existing rule ends the room and no new event type was needed. The streak resets only on events a player command can produce; a reset on the draw and pass the timeout performs *for* the player would clear the counter the timeout just set. | **None.** Invariant 7 is unchanged and is what actually ends the game; this only supplies a forfeit it can act on. |
| 10.4 | **`RoomExpired` is a room-gameplay event for any `WAITING` room that never filled**, not only a tournament concern. Architecture SG3 names it inside the tournament saga. | Decision E2. The same gap seen from the other end: a room whose members are gone is invisible to every deadline the engine knows about, because they all live inside a game that never started. Left alone it stays in `GET /rooms` forever, and the next casual player joins a table nobody is sitting at — the failure that cost P4's drills three false alarms. The window runs from the last arrival rather than from creation, so a late joiner gets a full one. | **Additive.** A new event type, terminal like `RoomCompleted`; no existing event's meaning changed. |
| 10.5 | **Kafka messages are CloudEvents in binary mode with a flat body**: `ce-*` headers, `ce-id` = `{roomId}:{sequenceNumber}`, key = `roomId`, body = `publicPayload(event)` with `roomId` and `sequenceNumber` merged in. | Decision E3, and this is catalog §10 as written rather than a departure from it. Worth recording because the *identity* of an event is now pinned: the room and its sequence number are the log's primary key, so `ce-id` is derived rather than generated and a redelivery after a crash is recognisably the same event. Consumers dedupe on it, which is what makes the relay's at-least-once safe. One genuine drift: `roomType` travels as the Kotlin enum name (`CASUAL`), where the catalog wrote `Casual`. The producer is the truth and the schema now says so. | **None.** The privacy filter is the same `publicPayload` the outbox row already went through — the relay adds two identity fields and invents nothing. |
| 10.6 | **The contract check validates a sample the producer generates**, not one written by hand in the check. | The committed sample had drifted from the real event in two directions at once: it required a `gameId` and an `eventId` that exist nowhere in this system, while the engine emits `gameNumber`. A check whose sample no producer emits is a seam pretending to be a contract — it could never have gone red for a real change. The sample is now written by a room-gameplay test from the event class and the privacy filter, so a rename turns both suites red before it reaches a consumer. | **Strengthened.** The cross-service fail-fast of consigna §6.3 now has a real producer behind it. |
| 10.7 | **One replica each for the relay and the worker**, where §4's capacity sketch has 4–8 relays and §6 gives the timer worker leader election. | Draining in `id` order is what preserves per-room ordering; a second relay would have to reconstruct that with row locks to buy throughput this system does not need. For the worker, a missed tick is a latency problem and never a correctness one, so failover buys promptness during a restart and nothing else. Both scale-out paths are described in the phase's `ESTADO-FINAL.md` as deliberate gaps rather than built. | **None.** At-least-once and per-room ordering both hold; what is missing is headroom, not a guarantee. |
| 10.8 | **The relay publishes every outbox row to the topic the row names**, rather than a curated list of event types. | Decision E5. `topicFor` already decides the destination when the row is written, inside the transaction, so the relay needs no knowledge of the catalog and P6's spectator and P7's tournament find their topics already flowing. A relay that filtered would be a second place to update every time an event is added. | **None.** |
| 10.9 | **`PATCH /rooms/{id}/players/{playerId}` reaches room-gameplay again.** P4's gateway whitelist allowed only `POST` and `DELETE` on that path. | Decision E8, and a correction to §9 rather than a new choice: the whitelist erring closed made `PlayerReconnected` an event nothing outside the cluster could cause, so the 60-second reconnection window of T3 could only ever *expire*. One element of one array; the route's auth and header injection are untouched. Found while specifying the timer worker, which is the first thing that had a reason to care that the window has two ends. | **None**, and it makes an architecture path (T3's cancellation) reachable for the first time. |
| 10.10 | **The CLI prints the seconds left on a turn when it draws the board, rather than ticking a counter down in place.** | Decision E4, mechanism refined. The feed writes lines to the same terminal, so a self-rewriting counter would fight the narration for the cursor. Printing it at render delivers what the decision asked for — the deadline is visible, and `bot --idle` makes a timeout demonstrable on purpose — without a second thing owning the screen. The client still renders a number and never decides that a deadline has passed. | **None.** §9.8's rule holds: the client narrates and re-reads, and the server is the only judge. |

**Affirmation.** No bounded-context boundary, aggregate or non-negotiable invariant was weakened.
Log-before-broadcast is now true in both directions it was always described in: the events are
committed before the relay can see them, and the relay marks a row published only after the broker
has taken it, so the failure mode is a duplicate rather than a loss. The trust boundary is unchanged
in shape and has one more inhabitant: the timer worker sits inside it, sets both headers like any
other caller room-gameplay trusts, and reaches a route the gateway has no pattern for.
