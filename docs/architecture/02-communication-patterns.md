# 6.3 — Communication Patterns

> This document covers the client connection model, multi-layer rate limiting mapped to deployables, the full integration table for every significant inter-component communication, cross-cutting retry/backoff policy, and the CloudEvents-aligned domain event envelope. For logs, metrics, traces, and health endpoints, see [05-observability-and-health.md](./05-observability-and-health.md).

---

## Table of Contents

1. [Client Connection Model](#1-client-connection-model)
2. [Rate Limiting Architecture](#2-rate-limiting-architecture)
3. [Integration Table](#3-integration-table)
4. [Cross-Cutting Resilience: Retries and Backoff](#4-cross-cutting-resilience-retries-and-backoff)
5. [Domain Event Envelope (CloudEvents-Aligned)](#5-domain-event-envelope-cloudevents-aligned)
6. [Summary: Pattern Selection Rationale](#6-summary-pattern-selection-rationale)

---

## 1. Client Connection Model

### 1.1 Pattern: REST + SSE (Server-Sent Events) Hybrid

Clients communicate with UnoArena through a **hybrid REST + SSE** model:

| Direction | Protocol | Purpose |
|-----------|----------|---------|
| Client → Server | **HTTPS REST** | All commands (PlayCard, DrawCard, JoinRoom, etc.) and queries (room state, player rating, bracket). Request/response with synchronous validation. |
| Server → Client (players) | **SSE** (one persistent connection per room) | Real-time push of game events (CardPlayed, TurnPassed, ChallengeWindowOpened, etc.) to all players in a room. |
| Server → Client (spectators) | **SSE** (one persistent connection per room) | Privacy-filtered event stream from the Spectator View Service. Separate channel from the player stream. |
| Server → Client (control) | **SSE** (session-scoped control channel) | Platform-level notifications: session invalidation, tournament assignment, room invitations. |

### 1.2 Why REST + SSE Over WebSocket

| Criterion | REST + SSE | WebSocket |
|-----------|-----------|-----------|
| **Bidirectional need** | Commands are request/response (need HTTP status codes, ETags, idempotency headers). SSE handles the unidirectional push. No bidirectional frame-level protocol needed. | WebSocket is bidirectional but forces custom framing for request/response semantics (correlation IDs, error codes), duplicating HTTP. |
| **Firewall/proxy compatibility** | SSE runs over standard HTTP/2. Transparent to proxies, CDNs, and corporate firewalls. | WebSocket upgrade can be blocked by firewalls and HTTP proxies. Requires fallback logic. |
| **HTTP semantics** | Commands use standard HTTP verbs, status codes (201, 409, 412, 428, 429), conditional requests (`If-Match`), and caching (`ETag`, `Cache-Control`). | All semantics must be reinvented inside WebSocket frames. |
| **Scalability** | SSE connections are lightweight (unidirectional, no ping/pong framing). HTTP/2 multiplexes SSE streams on one TCP connection. | WebSocket connections are heavier (bidirectional state, ping/pong). |
| **Team familiarity** | REST APIs are standard. SSE is trivially implementable on any HTTP server. | WebSocket requires dedicated libraries, custom protocol design, and more complex testing. |

### 1.3 Connection Termination Point

| Component | Terminates |
|-----------|-----------|
| **API Gateway (Nginx/Envoy)** | TLS, HTTP connections, SSE upgrade. Maintains the long-lived SSE connection to the client. Routes REST commands to backend services. |
| **Room Gameplay Service** | Does not hold client connections. Produces events that the gateway relays to connected clients via their SSE streams. |
| **Spectator View Service** | Produces the spectator SSE stream payload. The gateway proxies the SSE connection between spectator clients and this service. |

The gateway is the single point that holds all long-lived client connections. Backend services are stateless and connection-unaware.

### 1.4 Per-Room Ordering on the SSE Stream

Each SSE event carries the room's `sequenceNumber`. The gateway maintains a per-room event buffer:

1. Events arrive at the gateway from the Room Gameplay Service (via internal event bus or direct push after command processing).
2. The gateway assigns each event to the room's SSE channel.
3. Clients receive events in sequence-number order within a room. If a gap is detected (missed event), the client fetches the missing state via `GET /rooms/{roomId}/games/{gameId}` (reconciliation).
4. SSE `id` field is set to `sequenceNumber`, enabling automatic reconnection with `Last-Event-ID` — the gateway replays events from that point.

Cross-room ordering is not guaranteed and not needed: each SSE subscription is scoped to a single room.

### 1.5 Composition with Session Invalidation

When the Identity Service invalidates a session (new login from another device):

1. Identity publishes to **Redis pub/sub** channel `session:invalidated:{playerId}`.
2. The API Gateway subscribes to `session:invalidated:*`. On receiving a message, it looks up all SSE connections for the affected `playerId` with the old `sessionId`.
3. The gateway sends a `session-invalidated` SSE event on the control channel and then closes the connection.
4. The old client is immediately disconnected. No further game events are delivered on the stale session.
5. The new client establishes a fresh SSE connection with the new session token.

This ensures that a superseded session cannot continue receiving gameplay or room streams, even though the REST token revocation is asynchronous.

### 1.6 Composition with Spectator Privacy

Spectator SSE streams are **structurally separate** from player SSE streams:

| Aspect | Player SSE | Spectator SSE |
|--------|-----------|--------------|
| **Source** | Room Gameplay Service (via gateway) | Spectator View Service (via gateway) |
| **Data origin** | Full game events (player sees their own hand in REST responses; SSE carries public state + their private hand delta) | SpectatorRoomView read model (privacy-filtered projection) |
| **Subscription scope** | Player must be a room member with a valid session | Any authenticated user can spectate (no room membership required) |
| **Token claims** | JWT contains `playerId`, `sessionId`, `role: player` | JWT contains `userId`, `sessionId`, `role: spectator` (or same user with spectator subscription) |
| **Endpoint** | `GET /rooms/{roomId}/stream` (gateway routes to Room Gameplay event relay) | `GET /spectate/{roomId}/stream` (gateway routes to Spectator View Service) |

A spectator cannot subscribe to the player stream — the gateway enforces that only authenticated room members with an active player session can connect to `/rooms/{roomId}/stream`. The spectator endpoint reads exclusively from the Spectator View Service, which has no access to hand data.

---

## 2. Rate Limiting Architecture

Rate limiting is mapped to concrete deployables across multiple layers, as required by the product definition.

### 2.1 Layer Map

```
Client Request
  │
  ▼
┌────────────────────────────────────────────────────────────────────┐
│  L1: API Gateway (Nginx / Envoy)                                   │
│  Scope: Per-IP                                                     │
│  Mechanism: Token bucket (gateway memory + Redis for distributed)  │
│  Default: 50 req/s per IP                                          │
│  Identity source: Source IP (or X-Forwarded-For behind CDN)        │
│  Rejection: HTTP 429 Too Many Requests                             │
└───────────────────────────┬────────────────────────────────────────┘
                            │ (passes if under IP limit)
                            ▼
┌────────────────────────────────────────────────────────────────────┐
│  L2: Identity & Session Service (gRPC call from gateway)           │
│  Scope: Per-user (playerId)                                        │
│  Mechanism: Redis-backed sliding window                            │
│  Defaults: 10 game actions/s, 5 tournament actions/s per user      │
│  Identity source: playerId from validated JWT claims               │
│  Rejection: HTTP 429 (gateway returns on behalf of Identity)       │
└───────────────────────────┬────────────────────────────────────────┘
                            │ (passes if under user limit)
                            ▼
┌────────────────────────────────────────────────────────────────────┐
│  L3: Room Gameplay Service (in-process middleware)                  │
│  Scope: Per-room (roomId + action type)                            │
│  Mechanism: In-memory rate limiter per room instance               │
│  Default: 30 commands/s per room (aggregate)                       │
│  Identity source: roomId from request path, playerId from headers  │
│  Rejection: HTTP 429                                               │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│  L4: Tournament Orchestration Service (in-process middleware)       │
│  Scope: Per-tournament (tournamentId + action type)                │
│  Mechanism: Redis-backed counter                                   │
│  Default: 100 registrations/s per tournament                       │
│  Identity source: tournamentId from request path, playerId headers │
│  Rejection: HTTP 429                                               │
└────────────────────────────────────────────────────────────────────┘
```

### 2.2 Adaptive Throttling

When a user or IP triggers multiple `429` responses within a sliding window (e.g., 10 rejections in 60 seconds), the system progressively lowers thresholds:

| Trigger | Action | Duration |
|---------|--------|----------|
| 10× 429 in 60s (per IP) | IP threshold reduced to 10 req/s | 5 minutes |
| 20× 429 in 60s (per IP) | IP temporarily blocked | 15 minutes |
| 5× 429 in 30s (per user) | User threshold reduced to 2 actions/s | 5 minutes |
| 10× 429 in 60s (per user) | User temporarily suspended | 30 minutes (requires admin review for lift) |

Adaptive counters are stored in Redis (shared across gateway instances) and managed by the Identity & Session Service.

### 2.3 How Rate Limiting Gets Principal Identity

| Layer | Identity Discovery | Trust Boundary |
|-------|-------------------|---------------|
| **L1 (Per-IP)** | TCP source IP or `X-Forwarded-For` header (CDN). No authentication needed. | Gateway edge — first thing checked before any JWT validation. |
| **L2 (Per-user)** | Gateway validates JWT signature and expiry, extracts `playerId` claim, sends it to Identity Service via gRPC `CheckRateLimit(playerId, actionType)`. | Gateway trusts the JWT it validated. Identity Service trusts the gateway's gRPC call (mTLS within the cluster). |
| **L3 (Per-room)** | `roomId` from the URL path. `playerId` from `X-Player-Id` header set by the gateway after JWT validation. | Room Gameplay Service trusts the gateway's forwarded identity (internal network boundary, not exposed to clients). |
| **L4 (Per-tournament)** | `tournamentId` from the URL path. `playerId` from gateway headers. | Same trust boundary as L3. |

---

## 3. Integration Table

Every significant inter-component communication is documented below with pattern, rationale, and failure semantics.

### 3.1 Synchronous Integrations

| # | From | To | Operation / Trigger | Pattern | Rationale | Failure Semantics |
|---|------|------|---------------------|---------|-----------|-------------------|
| S1 | API Gateway | Identity & Session Service | Token validation on every request | gRPC `ValidateToken()` (sync) | Sub-millisecond validation required on the hot path. Local JWT signature check first; gRPC fallback for revocation. | **1s timeout.** Circuit breaker (open after 5 consecutive failures / 50% error rate in 10s window; half-open after 30s). If Identity is down: gateway falls back to local JWT validation only (accepts non-revoked tokens). No automatic retry on the hot path — fail fast. |
| S2 | API Gateway | Room Gameplay Service | Player commands (`PlayCard`, `DrawCard`, `JoinRoom`, etc.) | HTTP REST (sync) | Commands need synchronous validation and immediate response (201, 409, 412, 422). Maps 1:1 to design command catalog. **Critical hot path.** | **5s timeout.** Circuit breaker per upstream pool (open after sustained 5xx/timeouts; half-open probes). Gateway returns **504** when open or timed out — **no server-side retry** (ambiguous outcome risk). Client retries with same `If-Match` ETag (idempotent by seq). Room state unaffected if command never committed. |
| S3 | API Gateway | Tournament Orchestration Service | Tournament commands (`RegisterPlayer`, `StartTournament`, etc.) | HTTP REST (sync) | Same as S2 — commands require immediate acknowledgment. | **5s timeout** + circuit breaker (same policy as S2). **504** to client; client retries. Idempotent by tournament status guards (`RegisterPlayer`, `StartTournament`). |
| S4 | API Gateway | Spectator View Service | Spectator SSE stream + state queries | HTTP REST (queries) + SSE proxy (stream) | Spectator joins need immediate hydration (GET snapshot), then continuous SSE push. | **3s timeout** on REST hydration; circuit breaker on query path. If Spectator View is down: **503** on join; game correctness unaffected. SSE reconnects via `Last-Event-ID` (client-driven, exponential backoff — see §4). |
| S5 | API Gateway | Analytics Query API | Bracket, leaderboard, stats queries | HTTP REST (sync) | Read-only queries; tolerate eventual consistency. | **3s timeout** + circuit breaker. **504** or stale cached response (CDN/gateway cache, TTL 30–60s). Optional single retry with jitter for idempotent GETs only. |
| S6 | API Gateway | Ranking Service | Player rating queries | HTTP REST (sync) | Read-only. Low latency for profile pages. | **3s timeout** + circuit breaker. **504** or cached rating snapshot (TTL 60s). |
| S7 | Room Gameplay Service | Timer Scheduler Worker | Schedule / cancel domain timers | Internal HTTP or gRPC (sync) | Timers must be persisted before the command response returns, so scheduling is synchronous within the command path. | **2s timeout**, up to **2 retries** with exponential backoff (100ms, 300ms) + jitter. If still unreachable: command succeeds (events committed); aggregate stores deadline as belt-and-suspenders (§2.6 of 01-service-architecture.md). |

### 3.2 Asynchronous Integrations (Pub/Sub via Kafka)

| # | From (Producer) | To (Consumer) | Topic | Event Types | Partition Key | Rationale | Failure Semantics |
|---|----------------|--------------|-------|-------------|---------------|-----------|-------------------|
| A1 | Room Gameplay (Outbox Relay) | Spectator View Service | `room.public.events` | `CardPlayed`, `CardDrawn`, `TurnPassed`, `ForcedDraw`, `DirectionReversed`, `TurnSkipped`, `TurnTimedOut`, `UnoCallMade`, `ChallengeWindowOpened/Closed`, `UnoChallengeIssued/Resolved`, `DeckRecycled`, `PlayerDisconnected/Reconnected`, `PlayerForfeited`, `GameStarted`, `PlayerJoined` | `roomId` | Privacy-filtered events for spectator projection. Per-room ordering guaranteed by partition key. Fan-out to spectators. | At-least-once; **exponential backoff + jitter** on handler failure (§4). Consumer lag → delayed spectator view; no game impact. **DLQ after 10 retries.** Idempotent by `sequenceNumber` per room. |
| A2 | Room Gameplay (Outbox Relay) | Analytics Projection Workers | `room.public.events` | Same as A1 | `roomId` | Per-card stats, gameplay analytics. Separate consumer group from Spectator View. | Same retry policy as A1. Lag grows during burst; micro-batched writes absorb. No backpressure to Room Gameplay. Idempotent by `eventId`. |
| A3 | Room Gameplay (Outbox Relay) | Tournament Orchestration | `room.lifecycle.events` | `MatchCompleted`, `RoomCompleted` | `roomId` | Tournament reacts to room outcomes for round advancement. Critical path for tournament progression. | At-least-once; backoff + jitter on `RecordRoomResult` failure. Tournament deduplicates by `roomId`. If Tournament is down: Kafka buffers (7-day retention); replay from checkpoint on recovery. |
| A4 | Room Gameplay (Outbox Relay) | Ranking & Elo Service | `room.lifecycle.events` | `GameCompleted` | `roomId` | Elo updates for casual, non-abandoned games. Ranking filters by `roomType` and `isAbandoned`. | At-least-once; backoff + jitter. Deduplication by `gameId`. Elo updates delayed but eventually applied if Ranking is down. |
| A5 | Room Gameplay (Outbox Relay) | Analytics Projection Workers | `room.lifecycle.events` | `GameCompleted`, `MatchCompleted`, `RoomCompleted`, `RoomCreated` | `roomId` | Match history, game stats, bracket data. Separate consumer group. | Same as A2. Burst absorption via micro-batching. |
| A6 | Tournament Orchestration | Room Gameplay Service | `tournament.room-creation` | `RoomCreationRequested` | `roomId` | First-round surge fan-out. 100k rooms created via sharded Kafka partitions consumed by Room Gameplay instances. | Idempotent by `idempotencyKey`. Message **not acked** on failure → Kafka redelivers with consumer backoff (§4). **DLQ after 5 attempts** → operator alert. Tournament sweep re-emits missing rooms. |
| A7 | Tournament Orchestration | Analytics Projection Workers | `tournament.lifecycle.events` | `TournamentCreated`, `TournamentStarted`, `RoundStarted`, `RoomResultRecorded`, `RoundCompleted`, `FinalRoomCreated`, `TournamentCompleted`, `PlayerRegistered`, `PlayerUnregistered` | `tournamentId` | Bracket visualization, tournament leaderboards, registration stats. | Lag acceptable (5–30s). Backoff + jitter on projection failure. Idempotent by `{ tournamentId, roundNumber, roomId }`. |
| A8 | Tournament Orchestration | Ranking & Elo Service | `tournament.lifecycle.events` | `TournamentCompleted` | `tournamentId` | Placement rating updates for tournament finalists. | At-least-once; backoff + jitter. Deduplication by `tournamentId`. |
| A9 | Identity & Session Service | Room Gameplay Service | `identity.session-events` | `SessionInvalidated` | `playerId` | Triggers `PlayerDisconnected` for the affected player in any active room. Part of the single-active-session enforcement path. | At-least-once; backoff + jitter. Room Gameplay deduplicates by `sessionId`. No-op if player already disconnected. |
| A10 | Ranking & Elo Service | Analytics Projection Workers | `ranking.events` | `EloUpdated`, `TournamentPlacementUpdated` | `playerId` | Player stats updates (rating history, leaderboard refresh). | Lag acceptable. Backoff + jitter. Idempotent by `eventId`. |

### 3.3 Push-Invalidation (Redis Pub/Sub)

| # | From (Publisher) | To (Subscriber) | Channel | Message | Rationale | Failure Semantics |
|---|-----------------|-----------------|---------|---------|-----------|-------------------|
| P1 | Identity & Session Service | API Gateway (all instances) | `session:invalidated:{playerId}` | `{ oldSessionId, newSessionId }` | **Sub-second live connection termination** for superseded sessions. Kafka latency (seconds) is too high for this path — stale SSE connections could leak game events to the old device. | Redis pub/sub is fire-and-forget. If the gateway misses the message (e.g., temporary disconnect from Redis): the gateway's periodic session-validity check (every 30s) catches stale sessions as a fallback. The old client also receives 401 on the next REST request. Belt-and-suspenders. |

### 3.4 Durable Timer Integrations

| # | From | To | Trigger | Mechanism | Failure Semantics |
|---|------|------|---------|-----------|-------------------|
| T1 | Room Gameplay Service | Timer Scheduler Worker | Start event: `ChallengeWindowOpened` (5s Uno challenge). Expiry event (emitted by aggregate on T4 callback): **`ChallengeWindowClosed`** (Deliverable 4 §4.1). | Room Service sends `ScheduleTimer { timerId, roomId, type: UNO_CHALLENGE, expiresAt }`. Timer Worker persists in Redis sorted set. | **Scheduling failure:** Room aggregate stores `expiresAt` in its own events. On the next command, the aggregate checks all pending deadlines and self-triggers expired ones. Timer Worker is the prompt path; aggregate is the correctness backstop. |
| T2 | Room Gameplay Service | Timer Scheduler Worker | Start event: `TurnStarted` (30s turn timer). Expiry event: **`TurnTimedOut`** (Deliverable 4 §4.1) → auto-`DrawCard` + `PassTurn`. | `ScheduleTimer { type: TURN_TIMER }`. Previous timer cancelled. | Same belt-and-suspenders as T1. If the worker dies, aggregate catches the expiry on the next command. |
| T3 | Room Gameplay Service | Timer Scheduler Worker | Start event: `PlayerDisconnected` (60s reconnection). Expiry event: **`ReconnectionTimerExpired`** (Deliverable 4 §4.1) → `PlayerForfeited`. | `ScheduleTimer { type: RECONNECTION, expiresAt: now+60s }`. Cancelled on `PlayerReconnected`. | Same pattern. If the worker fails over, the new leader picks up pending timers from the Redis sorted set. Aggregate also stores the deadline. |
| T4 | Timer Scheduler Worker | Room Gameplay Service | Timer expiry callback — drives emission of the named expiry event for the timer's type (T1 → `ChallengeWindowClosed`, T2 → `TurnTimedOut`, T3 → `ReconnectionTimerExpired`). | `POST /internal/rooms/{roomId}/timer-expired { timerId, timerType }`. Room Service loads aggregate, checks if the window/timer is still active (idempotent); if so, the aggregate appends the corresponding expiry event under log-before-broadcast (01 §2.5). | **Duplicate delivery:** If the same timer fires twice (failover overlap), the second callback is a no-op — the aggregate already transitioned past that state and no second expiry event is emitted. **Late delivery:** If the callback arrives after the window was already closed by player action, it's also a no-op. Idempotency is anchored on `timerId` + aggregate state. |

### 3.5 Transactional Outbox (Intra-Context)

| # | From | To | Mechanism | Rationale | Failure Semantics |
|---|------|------|-----------|-----------|-------------------|
| O1 | Room Gameplay Service | Outbox Relay (CDC / Polling) | Same ACID transaction writes events to the event store (game log) and the outbox table. Outbox Relay tails committed rows via Debezium CDC or periodic polling. | **Log-before-broadcast guarantee.** Events are durable in the game log before any client or downstream consumer sees them. The outbox is the bridge between the ACID write and the Kafka publish. | **Crash between commit and relay:** Events committed; relay resumes unpublished rows on restart — at-least-once to Kafka. **Publish failure:** exponential backoff + jitter (§4); alert if `outbox_publish_lag_seconds` > 30s. **Crash before commit:** no publish; client may retry (idempotent by seq). Players get HTTP 201 before SSE/Kafka fan-out. |

### 3.6 Saga: Tournament Round Advancement

| # | Step | Component | Action | Compensating Action |
|---|------|-----------|--------|---------------------|
| SG1 | Round starts | Tournament Orchestration | Emit `RoundStarted`. Partition players into rooms. Publish `RoomCreationRequested` × N to `tournament.room-creation`. | If crash mid-publish: on restart, re-emit missing `RoomCreationRequested` (idempotent by `idempotencyKey`). |
| SG2 | Rooms created | Room Gameplay Service | Consume `RoomCreationRequested`. Create room, auto-join players, auto-start game. | If room creation fails after retries: message goes to DLQ. `RoomCreationFailed` emitted. Tournament sweep detects the gap and re-emits or marks room as cancelled (0 advancers). |
| SG3 | Games play out | Room Gameplay Service | Normal gameplay (minutes to hours). | If room expires (`Waiting` timeout): `RoomExpired` emitted. Tournament treats as all-forfeit room (0 advancers). |
| SG4 | Rooms complete | Room Gameplay → Tournament | `MatchCompleted` / `RoomCompleted` consumed. Tournament issues `RecordRoomResult`. | If event is lost: Tournament's stale-room detector queries Room Gameplay for room status after a timeout threshold. If room is `Completed`, manually triggers `RecordRoomResult`. |
| SG5 | Round completes | Tournament Orchestration | `completedRooms == totalRooms` → `RoundCompleted`. Generate next round (back to SG1) or `FinalRoomCreated` / `TournamentCompleted`. | Idempotent by round number. Duplicate `RecordRoomResult` for the same room is a no-op. |

### 3.7 Session Invalidation → Live Connection Kill

| # | Step | Component | Action |
|---|------|-----------|--------|
| SK1 | Player logs in from new device | Identity & Session Service | Invalidate old session in DB. Issue new JWT. Publish `SessionInvalidated` to Kafka (`identity.session-events`) AND Redis pub/sub (`session:invalidated:{playerId}`). |
| SK2 | Gateway kills old connection | API Gateway | Subscribes to Redis `session:invalidated:*`. Finds matching SSE connections by `playerId` + `oldSessionId`. Sends `session-invalidated` control event. Closes the connection. |
| SK3 | Room Gameplay disconnects player | Room Gameplay Service | Consumes `SessionInvalidated` from Kafka. Issues `PlayerDisconnected` command for the affected player in any active room. Starts 60s reconnection timer. |
| SK4 | Player reconnects with new session | New client | Issues `ReconnectPlayer` with the new session token within 60s. Room restores the player to `Connected` status. |

---

## 4. Cross-Cutting Resilience: Retries and Backoff

Platform-wide policy (aligned with design §7.7 and distributed-systems best practice: no unbounded retries, always jitter).

| Path | Who retries | Policy | Max attempts |
|------|-------------|--------|--------------|
| **Player commands** (REST) | **Client only** | After `412`: reconcile via GET + SSE, retry with updated `If-Match`. After `504`/network error: exponential backoff (200ms → 2s cap) + jitter; same `If-Match` if seq unchanged. | Client-defined (recommend ≤ 5) |
| **Gateway → Room / Tournament** (sync) | **None** (server) | Fail fast; circuit breaker prevents thundering herd. | 0 server retries |
| **Gateway → Identity** (gRPC) | **None** on validate path | Circuit breaker + local JWT fallback. | 0 |
| **Room → Timer Worker** (sync) | **Room Service** | 2 retries, 100ms / 300ms + jitter. | 3 total |
| **Kafka consumers** (all A*) | **Consumer framework** | Exponential backoff starting at 500ms, cap 60s, **full jitter**. Pause partition on repeated failure. | 10 → DLQ |
| **Outbox Relay → Kafka** | **Relay process** | Exponential backoff on publish failure; relay is idempotent (unpublished rows retried). | Unlimited with alert on lag > 5min |
| **Tournament room creation** (A6) | **Kafka + consumer** | Consumer backoff (§4); Tournament sweep re-emits after DLQ. | 5 → DLQ, then sweep |
| **SSE client reconnect** | **Client** | Reconnect with `Last-Event-ID`; backoff 1s → 30s cap + jitter. | Until user leaves room |

**Why no server-side retry on S2/S3:** A timeout does not prove the command failed — the Room aggregate may have committed. Retrying server-side without idempotency keys on every mutation would risk duplicate side effects. Sequence numbers + `If-Match` make **client-driven retry** safe.

---

## 5. Domain Event Envelope (CloudEvents-Aligned)

We **do not** wrap every Kafka record in a full [CloudEvents](https://cloudevents.io/) HTTP binding, but every published domain event uses a **CloudEvents-aligned envelope** so payloads are interoperable, versionable, and traceable. This is an intentional simplification: Kafka record headers carry CloudEvents core attributes; the value body is the domain `data` object.

**Record headers (required):**

| Attribute | Kafka header | Example |
|-----------|--------------|---------|
| `specversion` | `ce-specversion` | `1.0` |
| `id` | `ce-id` | `evt_uuid` (= `eventId` in catalog) |
| `source` | `ce-source` | `/room-gameplay` |
| `type` | `ce-type` | `com.unoarena.room.CardPlayed.v1` |
| `subject` | `ce-subject` | `rooms/{roomId}/games/{gameId}` |
| `time` | `ce-time` | RFC3339 timestamp |
| `correlationid` | `ce-correlationid` | traces originating command / HTTP request |

**Record value (JSON):** domain payload per Deliverable 4 — e.g. `CardPlayed { roomId, playerId, card, ... }` plus internal fields `sequenceNumber`, `roomType` where applicable.

**Versioning:** `ce-type` suffix `.v1`, `.v2`. Consumers accept both during migration windows; schema registry (optional) holds JSON Schema per type.

**Why not full CloudEvents binary mode everywhere:** Room Gameplay already owns an event-sourced log with typed domain events; duplicating the entire envelope in the value would bloat the game log. Headers + domain body give traceability without forcing all persistence paths through CloudEvents SDKs.

**Alignment with catalog:** Every `ce-type` maps 1:1 to an event name in `docs/design/04-commands-events.md` (e.g. `GameCompleted` → `com.unoarena.room.GameCompleted.v1`).

---

## 6. Summary: Pattern Selection Rationale

| Pattern | Where Used | Why |
|---------|-----------|-----|
| **REST (sync)** | All client commands, queries | Commands need immediate validation (seq check, turn check, legality). HTTP status codes, ETags, conditional requests map naturally to the domain. |
| **SSE (server push)** | Player and spectator real-time streams | Unidirectional server→client push. Simpler than WebSocket, HTTP/2 compatible, automatic reconnection via `Last-Event-ID`. |
| **Circuit breaker** | Gateway → all sync upstreams | Prevents cascading failures when Room/Tournament/Identity degrade under tournament load. |
| **Kafka (async pub/sub)** | All cross-context event propagation | Durable, partitioned, at-least-once delivery. Decouples producers from consumers. Absorbs load spikes (tournament bursts) via consumer lag. |
| **Transactional outbox** | Room Gameplay → Kafka | Log-before-broadcast guarantee. Single ACID transaction for game log + outbox. Relay publishes only committed events. |
| **Redis pub/sub** | Session invalidation → gateway | Sub-second fan-out to all gateway instances. Fire-and-forget with fallback (periodic session check). |
| **Redis sorted set** | Durable domain timers | Persisted deadlines survive worker crashes. Leader-elected worker polls for expirations. Aggregate provides correctness backstop. |
| **gRPC (sync)** | Gateway → Identity (token validation) | Low-latency internal RPC (~1ms). Hot path on every request. |
| **Choreographed saga** | Tournament round advancement | No central orchestrator needed. Tournament publishes events; Room Gameplay reacts independently. Round gate (`completedRooms == totalRooms`) closes naturally as rooms report in. Compensation via sweep + re-emit. |
| **CQRS read models** | Spectator View, Analytics, Brackets, Leaderboard | Write path (Room Gameplay, Tournament) is decoupled from read path. Read models are eventually consistent, independently scalable, and optimized for their query patterns. |

> **Observability** (logs, metrics, traces, health endpoints): see [05-observability-and-health.md](./05-observability-and-health.md).
