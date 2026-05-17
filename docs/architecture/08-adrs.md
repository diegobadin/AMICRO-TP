# Architecture Decision Records (ADRs)

> Strongly recommended deliverable (Architecture Checkpoint §7). Short records for the top architectural choices.

---

## ADR-01: Event Sourcing for Room Gameplay

**Status:** Accepted

**Context:** The product definition mandates an immutable game log for dispute resolution and replay, plus log-before-broadcast atomicity. The Room aggregate is the hottest write path (~200k commands/s at peak).

**Decision:** Use event sourcing for the Room Gameplay context. The event store (PostgreSQL append-only table) is the source of truth; aggregate state is rebuilt by replaying events (accelerated by optional snapshots).

**Consequences:**
- Game log immutability is structural, not just policy.
- Log-before-broadcast is a single ACID transaction (events + outbox).
- Aggregate size is bounded (~1,500 events per match max).
- Requires snapshot strategy to keep reload times < 20ms.
- Event schema versioning needs explicit planning (see 11-data-migration.md).

**Alternatives considered:** State-based persistence with a separate audit log — rejected because it would require maintaining two representations (state + log) with no atomicity guarantee between them.

---

## ADR-02: Transactional Outbox over Dual-Write

**Status:** Accepted

**Context:** Domain events must be published to Kafka after being committed to the event store, but a dual-write (DB commit + Kafka publish) is not atomic and can lose events on crash.

**Decision:** Use a transactional outbox. Events are written to both the event store and an `outbox` table in the same ACID transaction. A separate Outbox Relay (CDC via Debezium or polling) tails committed rows and publishes to Kafka.

**Consequences:**
- At-least-once delivery guaranteed (relay resumes from checkpoint).
- Adds operational complexity (relay process, CDC connector).
- Slight delay between commit and Kafka publish (< 1s typical).
- Downstream consumers must be idempotent.

**Alternatives considered:** (1) Kafka as the event store — rejected because PostgreSQL provides better transactional guarantees for the command path and game log queries. (2) Application-level publish-after-commit — rejected because crash between commit and publish silently drops events.

---

## ADR-03: REST + SSE over WebSocket

**Status:** Accepted

**Context:** Clients need synchronous command validation (status codes, ETags, conditional requests) and real-time push of game events.

**Decision:** Use HTTPS REST for commands/queries and Server-Sent Events (SSE) for server-to-client push. Do not use WebSocket.

**Consequences:**
- Commands use standard HTTP semantics (201, 409, 412, 428, 429, ETag, If-Match).
- SSE is unidirectional, lightweight, HTTP/2 compatible, and firewall-friendly.
- Automatic reconnection via `Last-Event-ID`.
- No bidirectional framing protocol to design/test.
- SSE does not support client→server frames — all actions go through REST.

**Alternatives considered:** WebSocket — rejected because it forces re-inventing request/response semantics (correlation IDs, error codes) inside frames, is blocked by some proxies/firewalls, and adds complexity without benefit since the push direction is server→client only.

---

## ADR-04: Kafka as Event Broker

**Status:** Accepted

**Context:** Cross-context event propagation must be durable, ordered per-room, and tolerate consumer lag during tournament bursts (~300k events/s).

**Decision:** Apache Kafka as the event broker. Topics partitioned by `roomId` or `tournamentId`. Consumer groups per consuming context.

**Consequences:**
- Durable log with 7-day retention.
- Per-partition ordering guarantees per-room event ordering.
- Consumer groups enable independent scaling and offset tracking.
- Absorbs load spikes via consumer lag (no backpressure to producers).
- Operational complexity: Zookeeper/KRaft, partition management, broker sizing.

**Alternatives considered:** (1) Redis Streams — viable for lower scale but lacks the partition/consumer-group maturity and durability guarantees of Kafka at 300k/s. (2) RabbitMQ — rejected because it is optimized for queue semantics, not log-based replay and partition-affine consumption.

---

## ADR-05: API Gateway (Not Direct Client-to-Service)

**Status:** Accepted

**Context:** Multiple backend services; clients must not be aware of internal topology. Cross-cutting concerns: TLS, JWT validation, rate limiting, SSE termination, session-kill.

**Decision:** A single API Gateway (Nginx/Envoy) is the public entry point. It terminates TLS, validates JWTs, enforces L1/L2 rate limits, routes to backend services, holds SSE connections, and subscribes to session-invalidation events.

**Consequences:**
- Single trust boundary for external traffic.
- Backend services trust gateway-injected headers (no independent JWT validation needed).
- SSE connection management centralized.
- Gateway must be horizontally scaled (110–130 instances at peak).

**Alternatives considered:** BFF-per-client (web BFF, mobile BFF) — considered but deferred; the current REST + SSE model serves all clients uniformly. A BFF layer can be introduced later if client-specific aggregation is needed.

---

## ADR-06: Choreographed Saga for Tournament Round Advancement

**Status:** Accepted

**Context:** Tournament round advancement spans Room Gameplay (100k rooms complete independently) and Tournament Orchestration (waits for all rooms, generates next round). The flow can last 30–60 minutes.

**Decision:** Choreographed saga. Room Gameplay publishes `MatchCompleted` / `RoomCompleted` events. Tournament Orchestration reacts with `RecordRoomResult`. Round gate (`completedRooms == totalRooms`) triggers next round generation. Compensation: stale-room detector, re-emit sweep, DLQ monitoring.

**Consequences:**
- No central orchestrator — reduces single-point-of-failure risk.
- Each room completes independently; no distributed locking.
- Debugging requires correlation IDs across events.
- Compensation is event-driven (sweep + re-emit), not immediate.

**Alternatives considered:** Orchestrated saga with a dedicated Round Orchestrator — rejected because 100k rooms reporting to a single orchestrator creates a bottleneck and the flow is naturally fan-in (many rooms → one round gate), which choreography handles well.

---

## ADR-07: Redis for Durable Timers (with Aggregate Backstop)

**Status:** Accepted

**Context:** Domain timers (5s Uno challenge, 30s turn, 60s reconnection) must survive process crashes and deliver idempotent expiry callbacks.

**Decision:** Timer Scheduler Worker persists deadlines in a Redis sorted set (score = expiry timestamp). A leader-elected worker polls for expirations and delivers callbacks to the Room Gameplay Service. The Room aggregate itself stores `expiresAt` in its events as a belt-and-suspenders backstop.

**Consequences:**
- Timers persist across worker restarts (Redis AOF/RDB).
- Aggregate checks pending deadlines on every command — catches missed expirations.
- Duplicate callbacks are no-ops (aggregate state idempotency).
- Redis is not the sole correctness mechanism — aggregate is.

**Alternatives considered:** (1) Kafka delayed delivery — Kafka does not natively support delayed messages; workarounds (delay topics, external schedulers) are more complex. (2) Database-only timers — viable but polling the event store for 300k active timers at sub-second intervals is expensive. Redis sorted set is O(log N) per operation.

---

## ADR-08: Per-Context Database Isolation

**Status:** Accepted

**Context:** Microservices autonomy requires data ownership per bounded context. Shared databases create coupling, coordinated migrations, and implicit contracts.

**Decision:** Each bounded context owns its own database (or schema with isolated credentials). No cross-context table access. Data flows between contexts via events or APIs.

**Consequences:**
- Schema changes in one context do not break others.
- No cross-context JOINs; read models (CQRS) replace them.
- `playerId` appears in multiple databases as an opaque reference, not a foreign key to a shared table.
- Operational overhead: multiple PostgreSQL instances or schemas.

**Alternatives considered:** Shared database with schema-level isolation — acceptable as a cost optimization for small deployments but enforced at the credential level (no cross-schema grants), documented in 03-persistence-layer.md §8.

---

## ADR-09: Separate Spectator View Bounded Context

**Status:** Accepted

**Context:** Spectators must never see player hands. The spectator read model has different scaling characteristics (potentially 100x player count for popular rooms) and different consistency requirements (eventual is acceptable).

**Decision:** Spectator View is a separate bounded context with its own Redis-backed read model, its own SSE endpoint, and its own Kafka consumer group. It receives privacy-filtered events from Room Gameplay's Public Event Publisher.

**Consequences:**
- Structural isolation: no code path connects spectator queries to private game state.
- Independent scaling: spectator load does not affect game command processing.
- Failure isolation: spectator projection lag does not impact gameplay.
- Three-layer privacy defense: strip at source, ACL at boundary, no private fields in data model.

**Alternatives considered:** Filtered API on Room Gameplay — rejected because a single bug in the filter could leak hand data, and spectator scaling concerns would directly compete with command-processing resources.

---

## ADR-10: CloudEvents-Aligned Event Envelope

**Status:** Accepted

**Context:** Domain events are published to Kafka and consumed by multiple bounded contexts. Interoperability, traceability, and schema versioning require a standard envelope.

**Decision:** Use a CloudEvents-aligned envelope: core attributes (`specversion`, `id`, `source`, `type`, `time`, `subject`, `correlationid`) in Kafka record headers; domain payload in the value body. Full CloudEvents binary/structured mode is not mandated.

**Consequences:**
- Events are interoperable and traceable without custom tooling.
- `ce-type` carries version suffix (`.v1`) for schema evolution.
- Consumers can deserialize headers independently of the body schema.
- Game log (event store) stores the domain payload directly; CloudEvents headers are added only at the Kafka boundary.

**Alternatives considered:** (1) No standard envelope — rejected because downstream teams would need ad-hoc tracing/deserialization. (2) Full CloudEvents binary mode everywhere — rejected because wrapping the event store's internal format in CloudEvents adds bloat to the game log with no audit benefit.
