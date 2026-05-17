# 6.4 — Persistence Layer per Context

> For each bounded context, this document specifies the primary data store, consistency model, transactional boundaries, read models, and retention/audit strategy. It also details how Room Gameplay's persistence implements the log-before-broadcast guarantee and the game log's audit read path.

---

## Table of Contents

1. [Room Gameplay Context](#1-room-gameplay-context)
2. [Tournament Orchestration Context](#2-tournament-orchestration-context)
3. [Ranking & Elo Context](#3-ranking--elo-context)
4. [Identity & Session Context](#4-identity--session-context)
5. [Spectator View Context](#5-spectator-view-context)
6. [Analytics & Brackets Context](#6-analytics--brackets-context)
7. [Shared Infrastructure](#7-shared-infrastructure)
8. [Database Boundary Enforcement](#8-database-boundary-enforcement)
9. [Summary Matrix](#9-summary-matrix)

---

## 1. Room Gameplay Context

### 1.1 Primary Store: PostgreSQL (Event Store + Outbox)

The Room Gameplay context uses **PostgreSQL** as both the event store and the relational backbone. The database hosts three core tables per logical schema:

| Table | Purpose | Key Columns |
|-------|---------|-------------|
| `event_store` | Append-only game log. Source of truth for all room state. One row per domain event. | `room_id`, `sequence_number` (unique per room), `event_type`, `payload` (JSONB), `created_at`, `signature` (HMAC) |
| `outbox` | Transactional outbox for public (privacy-filtered) events pending Kafka relay. | `outbox_id`, `room_id`, `sequence_number`, `event_type`, `public_payload` (JSONB), `published` (boolean), `created_at` |
| `aggregate_snapshots` | Optional performance optimization. Cached aggregate state to avoid full event replay on every command. | `room_id`, `snapshot_version` (sequence_number), `state` (JSONB), `updated_at` |

### 1.2 Consistency Model

| Aspect | Guarantee |
|--------|-----------|
| **Intra-room** | **Strong consistency (linearizable).** All writes to a room's events are serialized by `sequence_number` with a unique constraint on `(room_id, sequence_number)`. Concurrent writes to the same room fail with a unique-constraint violation (optimistic concurrency). |
| **Cross-room** | **No transactional coupling.** Each room is an independent aggregate. Rooms in different partitions can be on different database shards without coordination. |
| **Transactional boundary** | A single ACID transaction encompasses: (1) appending events to `event_store`, (2) writing the public event(s) to `outbox`, and (3) optionally updating `aggregate_snapshots`. This transaction is the architectural implementation of **log-before-broadcast**. |

### 1.3 Log-Before-Broadcast: Transaction Design

**Isolation level:** **`READ COMMITTED`** (PostgreSQL default), with correctness anchored on the `UNIQUE (room_id, sequence_number)` constraint on `event_store` and `outbox` (optimistic concurrency). Rationale: the only contention between concurrent writers to the same room is the next sequence number; `READ COMMITTED` + the unique constraint produces a deterministic loser (the conflicting INSERT fails with `23505`, the command is rejected as stale, and the client reconciles via ETag — see Architecture §2.3.1 and CHANGELOG §1.2). Promoting to `SERIALIZABLE` would buy nothing on this path (no read-modify-write across rooms) while inflating serialization-failure retries under the first-round surge. The aggregate-snapshot `UPDATE` (step 3 below) uses the same isolation and is guarded by `WHERE snapshot_version = :prevSeq`, so a stale snapshot write fails the predicate rather than corrupting state.

```
BEGIN TRANSACTION (READ COMMITTED — correctness via UNIQUE(room_id, sequence_number))

  1. INSERT INTO event_store (room_id, sequence_number, event_type, payload, signature, created_at)
     VALUES (:roomId, :newSeq, :type, :fullPayload, :hmac, NOW())
     -- Full-fidelity event (includes hand mutations, deck state, RNG seed)

  2. INSERT INTO outbox (room_id, sequence_number, event_type, public_payload, published, created_at)
     VALUES (:roomId, :newSeq, :type, :filteredPayload, false, NOW())
     -- Privacy-filtered: no hand contents, no deck order, no RNG seed

  3. UPDATE aggregate_snapshots SET state = :newState, snapshot_version = :newSeq
     WHERE room_id = :roomId AND snapshot_version = :prevSeq
     -- Optional. Fails if snapshot is stale → snapshot rebuilt on next load.

COMMIT
```

**Crash scenarios:**

| Failure Point | Outcome |
|---------------|---------|
| Crash before COMMIT | Nothing persisted. Client may retry (idempotent by seq — same seq yields same result or rejection). |
| Crash after COMMIT, before Outbox Relay picks up | Events are durable in the game log. Outbox rows exist with `published = false`. Relay will pick them up on its next poll/CDC cycle. Clients may receive the HTTP response (if crash is after response send) or timeout and retry. |
| Outbox Relay crash mid-publish | Relay tracks its position (Kafka offset or outbox `outbox_id`). On restart, re-reads unpublished rows. At-least-once delivery to Kafka. Downstream consumers deduplicate. |

### 1.4 Read Models

| Read Model | Store | Built From | Staleness |
|------------|-------|-----------|-----------|
| Aggregate state (for command processing) | In-memory (rebuilt from event store on load; snapshot accelerates) | `event_store` rows for the room | Zero (loaded fresh per command) |
| Idempotency key → response mapping | PostgreSQL or Redis (TTL-based) | `CreateRoom` responses | Exact (within retention window, e.g., 24h) |

### 1.5 Game Log Audit Read Path

The `event_store` table is the immutable game log. Its read path supports dispute resolution and compliance:

| Accessor | Authorization | Access Path | Purpose |
|----------|--------------|-------------|---------|
| **Operators (admin role)** | JWT with `role: operator` or `role: admin` claim. Validated by the admin gateway (separate from public gateway). | `GET /v1/rooms/{roomId}/games/{gameId}/moves` — REST API exposing the event store filtered by `gameId`. Paginated. | Dispute resolution, post-game review. |
| **Automated replay jobs** | mTLS certificate within the internal network. No JWT required (service-to-service). | Direct read from `event_store` table or internal gRPC endpoint. | Integrity verification, statistical analysis, anti-cheat replay. |
| **Compliance / break-glass** | Multi-party approval logged in the platform audit log. Break-glass token issued by the Identity Service with time-limited, scoped access. | Dedicated audit API endpoint with break-glass authentication. | Regulatory compliance, legal disputes. |

Every access to the game log is itself logged in the platform audit log (separate from the game log) with the accessor's identity, timestamp, and scope of the query.

### 1.6 Retention and Immutability

| Policy | Detail |
|--------|--------|
| **Immutability** | The `event_store` table has no `UPDATE` or `DELETE` operations in any application code path. Database-level safeguards: a trigger rejects `UPDATE`/`DELETE` on the table; the application role has `INSERT` and `SELECT` only. |
| **Retention** | Game logs are retained for a minimum of **1 year** for dispute resolution and audit. After the retention period, logs may be archived to cold storage (S3-compatible object store) but never deleted while a tournament or dispute is pending. |
| **Integrity verification** | Each `event_store` row includes an HMAC `signature` computed with a server-managed key. Replay jobs can verify integrity by recomputing the HMAC chain. Key rotation is handled transparently (key version stored alongside the signature). |

---

## 2. Tournament Orchestration Context

### 2.1 Primary Store: PostgreSQL

| Table | Purpose | Key Columns |
|-------|---------|-------------|
| `tournaments` | Tournament aggregate state. | `tournament_id`, `status`, `current_round`, `config` (JSONB), `champion`, `final_placements` (JSONB) |
| `rounds` | Round state within a tournament. | `tournament_id`, `round_number`, `status`, `room_count`, `completed_room_count`, `advancing_players` (JSONB) |
| `tournament_rooms` | Room references within a round. | `tournament_id`, `round_number`, `room_id`, `assigned_players` (JSONB), `status`, `result` (JSONB) |
| `registered_players` | Player registrations. | `tournament_id`, `player_id`, `registered_at` |

### 2.2 Consistency Model

| Aspect | Guarantee |
|--------|-----------|
| **Intra-tournament** | **Strong consistency.** The Tournament aggregate is protected by optimistic locking (`version` column on `tournaments`). All `RecordRoomResult` commands are serialized per tournament via version check + retry. |
| **Cross-tournament** | **Independent.** No transactional coupling between tournaments. |
| **Transactional boundary** | A single transaction for `RecordRoomResult`: update `tournament_rooms.result`, increment `rounds.completed_room_count`, check `completedRooms == totalRooms`, and (if round complete) update `rounds.status`. |

### 2.3 Read Models

| Read Model | Store | Built From | Staleness |
|------------|-------|-----------|-----------|
| Tournament status / current round | Direct read from `tournaments` table | Authoritative write model | Zero (consistent read) |
| Round details / room statuses | Direct read from `rounds` + `tournament_rooms` | Authoritative write model | Zero |

Tournament does not need CQRS internally — the write model is queried directly for tournament status. Bracket visualization is handled by the Analytics & Brackets context (eventually consistent).

### 2.4 Retention

Tournament data is retained indefinitely for historical bracket views and placement verification. No PII beyond `player_id` references (actual player profiles are in the Identity context).

---

## 3. Ranking & Elo Context

### 3.1 Primary Store: PostgreSQL

| Table | Purpose | Key Columns |
|-------|---------|-------------|
| `player_ratings` | PlayerRating aggregate. | `player_id`, `elo_rating`, `tournament_placement_rating`, `version` (optimistic lock) |
| `rating_history` | Auditable log of every rating change. | `player_id`, `change_type` (elo / placement), `old_rating`, `new_rating`, `delta`, `source_id` (gameId or tournamentId), `created_at` |
| `processed_events` | Deduplication log. | `source_type` (game / tournament), `source_id`, `processed_at` |

### 3.2 Consistency Model

| Aspect | Guarantee |
|--------|-----------|
| **Per-player** | **Strong consistency** within the PlayerRating aggregate (optimistic locking on `version`). |
| **Cross-player** | **Eventually consistent.** Elo updates for different players in the same game are independent transactions. If the process crashes after updating some players, remaining updates are applied on event re-delivery. |
| **Deduplication** | `processed_events` table checked before applying any update. Prevents double-counting on at-least-once delivery. |

### 3.3 Read Models

| Read Model | Store | Built From | Staleness |
|------------|-------|-----------|-----------|
| Player rating (current) | Direct read from `player_ratings` | Write model | Zero |
| Leaderboard (top N) | Materialized view or cached query on `player_ratings` ordered by `elo_rating DESC` | Write model | Refreshed every 30s–60s (configurable) |
| Rating history | Direct read from `rating_history` | Write model (append-only) | Zero |

### 3.4 Retention

Rating history is retained indefinitely for auditing and dispute resolution. The `processed_events` table can be pruned after the Kafka retention window (7 days) since re-delivery beyond that is impossible.

---

## 4. Identity & Session Context

### 4.1 Primary Store: PostgreSQL

| Table | Purpose | Key Columns |
|-------|---------|-------------|
| `players` | Player identity. | `player_id`, `credentials_hash`, `email`, `created_at` |
| `sessions` | Active and historical sessions. | `session_id`, `player_id`, `is_active`, `created_at`, `expires_at`, `ip_address`, `invalidated_at`, `invalidation_reason` |
| `audit_log` | Platform-level audit trail. | `audit_id`, `actor_id`, `action`, `target`, `details` (JSONB), `created_at` |

### 4.2 Consistency Model

| Aspect | Guarantee |
|--------|-----------|
| **Per-player** | **Strong consistency.** Session invalidation is atomic: in a single transaction, the old session is marked `is_active = false` and the new session is inserted with `is_active = true`. |
| **Session propagation** | **Eventually consistent** to other contexts. The `SessionInvalidated` event reaches Room Gameplay via Kafka (seconds) and the gateway via Redis pub/sub (sub-second). The 60s reconnection window provides ample margin for propagation. |

### 4.3 Read Models

| Read Model | Store | Built From | Staleness |
|------------|-------|-----------|-----------|
| Rate-limit counters | **Redis** (sliding window counters per `playerId`) | Updated in-line with each request | Real-time (within Redis latency) |
| Session validity cache | **Redis** (key: `session:{sessionId}`, value: `{playerId, isActive}`, TTL = token expiry) | Written on login/invalidation | Near-real-time (written synchronously on session change) |

### 4.4 PII Boundaries

The Identity context is the **only** context that stores personally identifiable information (email, credentials). Other contexts reference players by `playerId` (opaque UUID) only. If GDPR deletion is required, only this database needs to be scrubbed; downstream contexts retain only pseudonymous IDs.

### 4.5 Retention

| Data | Retention |
|------|-----------|
| Player credentials | Until account deletion request. |
| Session history | 90 days (for security audit). |
| Audit log | 1 year minimum (compliance). |

---

## 5. Spectator View Context

### 5.1 Primary Store: Redis

The Spectator View is a **pure read model** with no authoritative state. Redis provides the low-latency, high-throughput store needed for spectator fan-out.

| Key Pattern | Value | TTL |
|-------------|-------|-----|
| `spectator:room:{roomId}` | Redis hash: full `SpectatorRoomView` (see §6.3 of 01-service-architecture.md) | 24h after last update (rooms expire after game completion) |
| `spectator:room:{roomId}:subs` | Redis set: connected spectator IDs | Same as room TTL |
| `spectator:room:{roomId}:seq` | Last processed sequence number | Same as room TTL |

### 5.2 Consistency Model

| Aspect | Guarantee |
|--------|-----------|
| **Per-room** | **Eventual consistency.** The projection lags behind the authoritative Room Gameplay state by the Kafka consumer lag (typically < 1s, up to 5–30s during tournament bursts). |
| **Ordering** | Events are consumed from `room.public.events` partitioned by `roomId`. Per-room ordering is guaranteed by Kafka partition ordering. The consumer checks `sequenceNumber` monotonicity and drops out-of-order events (or buffers for short reorder windows). |
| **Privacy** | **Structurally enforced.** The `SpectatorRoomView` hash has no fields for hand contents, deck order, or RNG seeds. Even if a bug in the producer leaked private data, the consumer's ACL validation rejects events containing `hand`, `cards`, `deckOrder`, or `rngSeed` fields. |

### 5.3 Read Models

The entire Spectator View context **is** a read model. There is no write model distinction — it projects from Room Gameplay events.

| Query | Source | Staleness |
|-------|--------|-----------|
| Room snapshot (initial hydration) | `GET spectator:room:{roomId}` from Redis | Eventual (< 1s typical) |
| Incremental updates (SSE stream) | Events pushed to connected spectators as they're projected | Eventual (same lag as snapshot) |

### 5.4 Retention

Spectator projections are ephemeral. Room data expires via Redis TTL after the room completes. No long-term retention needed — the authoritative game log is in the Room Gameplay event store.

---

## 6. Analytics & Brackets Context

### 6.1 Primary Store: ClickHouse (OLAP) + PostgreSQL (Bracket State)

The Analytics context uses a **dual-store** strategy:

| Store | Purpose | Data |
|-------|---------|------|
| **ClickHouse** | High-volume event analytics, player statistics, historical match data. Columnar storage optimized for aggregation queries. | `game_events` (per-card stats), `game_results`, `player_game_history`, `tournament_results` |
| **PostgreSQL** | Bracket state and tournament leaderboards. Relational model for tree-structured bracket queries. | `bracket_entries` (tournament_id, round, room, players, result), `tournament_leaderboards`, `player_stats_summary` |

### 6.2 Consistency Model

| Aspect | Guarantee |
|--------|-----------|
| **Overall** | **Eventual consistency.** Analytics projections lag behind authoritative events by seconds (normal) to minutes (tournament burst). This is explicitly acceptable — bracket views and stats are not used for game decisions. |
| **Write path** | Analytics Projection Workers consume from Kafka in micro-batches (1–5 second windows). ClickHouse ingests via batch inserts. PostgreSQL bracket updates are idempotent by `{ tournamentId, roundNumber, roomId }`. |
| **Burst absorption** | During the game-completed spike at round end (~100k `GameCompleted` events in minutes), Kafka consumer lag grows. Workers auto-scale to increase throughput. No backpressure reaches Room Gameplay (separate consumer groups, durable Kafka log). |

### 6.3 Read Models

| Read Model | Store | Staleness | Query Pattern |
|------------|-------|-----------|--------------|
| Tournament bracket | PostgreSQL | 5–30s during burst, < 5s otherwise | Tree traversal by tournament + round |
| Tournament leaderboard | PostgreSQL | Same as bracket | Top-N by advancement round + placement |
| Player stats (games played, win rate, avg card points) | PostgreSQL (summary) + ClickHouse (detail) | Minutes for summary (refreshed periodically), real-time for detail queries | Aggregation by `player_id` |
| Match history | ClickHouse | < 30s | Paginated by `player_id` + `created_at DESC` |
| Per-card analytics | ClickHouse | Minutes | Aggregation by `card_type`, `game_context` |

### 6.4 Retention

| Data | Retention |
|------|-----------|
| Game event details (ClickHouse) | 1 year (then aggregated into summary tables) |
| Bracket state (PostgreSQL) | Indefinite (historical tournament records) |
| Player stats summary | Indefinite (continuously updated) |

---

## 7. Shared Infrastructure

### 7.1 Redis (Shared Instance / Cluster)

Redis is used by multiple contexts but for **different, non-overlapping purposes**:

| Context | Redis Usage | Key Prefix / Channel |
|---------|-------------|---------------------|
| Identity & Session | Rate-limit counters, session validity cache, session-invalidation pub/sub | `ratelimit:*`, `session:*`, `session:invalidated:*` |
| Room Gameplay (Timer Worker) | Durable timer sorted set | `timer:*` |
| Spectator View | Per-room projection store, spectator subscriptions | `spectator:*` |
| API Gateway | Distributed IP rate-limit counters, adaptive throttling state | `gateway:ratelimit:*` |

Each context uses a distinct key prefix. No context reads another context's Redis keys. Redis is treated as infrastructure (like the network), not as a shared database.

#### Memory management (`maxmemory-policy`)

Redis is **not** the source of truth for game or tournament state — only cache, coordination, and ephemeral read models. On memory pressure, eviction must not corrupt authoritative data.

| Deployment | `maxmemory` | `maxmemory-policy` | Rationale |
|------------|-------------|-------------------|-----------|
| **Spectator View cluster** (primary memory consumer) | Set per node (e.g. 8–16 GB); monitor `used_memory` | `volatile-lru` | Keys use TTL (`spectator:room:*` expires after room completion). Evict least-recently-used keys **with TTL** first. Projections are rebuildable from Kafka. |
| **Rate-limit / session cache** (Identity + Gateway) | Smaller instance or logical DB index | `volatile-lru` | Keys have TTL (session cache, rate windows). Safe to evict expired/volatile keys. |
| **Timer sorted sets** (`timer:*`) | Co-located with timers or dedicated small instance | `noeviction` | Timer entries are short-lived but **must not** be silently evicted before expiry; worker would miss deadlines (aggregate backstop still correct, but delays penalties). Prefer dedicated Redis instance with headroom, or strict memory alerts. |

**Operational safeguards:**

- Alert when `used_memory` > 80% of `maxmemory` on any node.
- Spectator projection lag metric correlated with eviction rate (`evicted_keys` from Redis INFO).
- On eviction storm: scale Spectator View consumers to reduce write pressure; add Redis nodes — do not disable TTLs.

**Hot-key mitigation:** Per-room spectator hashes use `{roomId}` in the key name for cluster sharding (`spectator:room:{roomId}`). For finals with extreme fan-out, see capacity sketch §7.2 (regional edge).

### 7.2 Apache Kafka (Event Broker)

| Topic | Owner (Producer) | Partitions | Retention |
|-------|-----------------|-----------|-----------|
| `room.public.events` | Room Gameplay | 256 (by `roomId` hash) | 7 days |
| `room.lifecycle.events` | Room Gameplay | 256 (by `roomId` hash) | 7 days |
| `tournament.room-creation` | Tournament Orchestration | 256 (by `roomId` hash) | 7 days |
| `tournament.lifecycle.events` | Tournament Orchestration | 64 (by `tournamentId` hash) | 7 days |
| `identity.session-events` | Identity & Session | 64 (by `playerId` hash) | 3 days |
| `ranking.events` | Ranking & Elo | 64 (by `playerId` hash) | 3 days |

Each topic has a single owning producer context. Consumer groups are per-consuming context, ensuring independent offset tracking.

---

## 8. Database Boundary Enforcement

Each bounded context owns its own database (or schema). No context directly reads or writes another context's tables.

| Enforcement Mechanism | Detail |
|-----------------------|--------|
| **Separate database credentials** | Each service connects to its own database with a dedicated user. The Room Gameplay service cannot connect to the Tournament DB. |
| **Schema-level isolation** | If contexts share a PostgreSQL instance (cost optimization for smaller deployments), each context operates in a separate schema with distinct users and no cross-schema grants. |
| **No shared tables** | The `player_id` appears in multiple databases as a foreign key to the Identity context, but there are no JOIN-able cross-context tables. Cross-context data access is via events or APIs. |
| **Migration independence** | Each context manages its own database migrations. Schema changes in one context do not require coordinated deployment with others. |

---

## 9. Summary Matrix

| Context | Primary Store | Consistency Model | Read Models | Key Retention Policy |
|---------|--------------|-------------------|-------------|---------------------|
| **Room Gameplay** | PostgreSQL (event store + outbox) | Strong (linearizable per room) | Aggregate snapshots (in-memory + optional table) | Game log: 1 year minimum, immutable, HMAC-signed |
| **Tournament Orchestration** | PostgreSQL | Strong (optimistic locking per tournament) | Direct read from write model | Indefinite (historical brackets) |
| **Ranking & Elo** | PostgreSQL | Strong per player, eventual cross-player | Leaderboard (materialized view, 30–60s refresh) | Rating history: indefinite |
| **Identity & Session** | PostgreSQL + Redis (cache/counters) | Strong per player identity | Session cache (Redis), rate-limit counters (Redis) | Sessions: 90 days. Audit log: 1 year. PII: until deletion request. |
| **Spectator View** | Redis (read model only) | Eventual (< 1s typical, up to 30s in burst) | Entire context is a read model | Ephemeral (TTL-based, expires after room completes) |
| **Analytics & Brackets** | ClickHouse + PostgreSQL | Eventual (seconds to minutes) | Bracket, leaderboard, player stats, match history | Events: 1 year. Brackets/stats: indefinite. |
