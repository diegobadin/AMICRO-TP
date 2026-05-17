# 6.5 — Capacity Sketch

> Order-of-magnitude reasoning for UnoArena at peak tournament scale (1,000,000 players, 100,000+ simultaneous first-round matches). This is not a benchmark — it is a credibility check that the decomposition and integration choices hold under the stated load.

---

## Table of Contents

1. [Baseline Assumptions](#1-baseline-assumptions)
2. [Peak Concurrent Entities](#2-peak-concurrent-entities)
3. [Event and Command Rates](#3-event-and-command-rates)
4. [Per-Component Scaling Analysis](#4-per-component-scaling-analysis)
5. [First-Round Surge Timeline](#5-first-round-surge-timeline)
6. [Round-End Completion Spike](#6-round-end-completion-spike)
7. [Spectator Multiplier](#7-spectator-multiplier)
8. [Kafka Broker Sizing](#8-kafka-broker-sizing)
9. [Database Load](#9-database-load)
10. [Network Bandwidth](#10-network-bandwidth)
11. [Summary: What Scales Horizontally vs. What is Partitioned](#11-summary-what-scales-horizontally-vs-what-is-partitioned)

---

## 1. Baseline Assumptions

| Parameter | Value | Source / Rationale |
|-----------|-------|-------------------|
| Max tournament players | 1,000,000 | Product definition |
| Players per room | 10 | Product definition (2–10; 10 for tournaments) |
| First-round rooms | 100,000 | 1M ÷ 10 |
| Games per match (tournament) | Up to 3 (best-of-3) | Design Checkpoint §1.4 |
| Turns per game | ~50–100 (estimated for a 10-player Uno game) | Empirical: 108-card deck, 7 dealt per player = 38 dealt + first discard = 70 in deck; game ends when one player empties hand |
| Events per turn | ~1.5 average (CardPlayed + occasional ForcedDraw, TurnSkipped, ChallengeWindow, etc.) | Design Checkpoint §4.6 causality map |
| Turn duration (avg) | ~5 seconds (mix of fast plays and timeouts) | Tournament pace assumption |
| Game duration (avg) | ~5 minutes (50 turns × 5s + overhead) | Derived |
| Match duration (avg) | ~12 minutes (2.5 games avg × 5 min) | Derived; some matches end in 2 games |
| Spectator ratio (regular rooms) | 1:1 (1 spectator per player, typical) | Conservative estimate for most rooms |
| Spectator ratio (popular/final rooms) | 10:1 to 100:1 | Tournament finals, featured matches |
| Concurrent casual rooms (non-tournament) | ~10,000 | Background load during tournament |

---

## 2. Peak Concurrent Entities

### 2.1 During First Round of Maximum Tournament

| Entity | Count | Derivation |
|--------|-------|-----------|
| **Active tournament rooms** | 100,000 | 1M players ÷ 10 per room |
| **Active casual rooms** | ~10,000 | Background platform load |
| **Total active rooms** | ~110,000 | Tournament + casual |
| **Connected players** | ~1,050,000 | 1M tournament + ~50k casual (5/room avg) |
| **Connected spectators (tournament)** | ~100,000–500,000 | 1–5 spectators per tournament room on average (most rooms are unremarkable); a few popular rooms may have thousands |
| **Connected spectators (casual)** | ~10,000 | 1:1 ratio for casual rooms |
| **Total concurrent SSE connections** | ~1,200,000–1,600,000 | Players + spectators |
| **Active games** | ~110,000 | One game per room at a time |

### 2.2 Connection Distribution

| Connection Type | Count | Held By |
|----------------|-------|---------|
| Player SSE (room-scoped) | ~1,050,000 | API Gateway instances |
| Spectator SSE (room-scoped) | ~110,000–510,000 | API Gateway → Spectator View Service |
| Control SSE (session-scoped) | ~1,050,000 | API Gateway instances |
| Total persistent connections | ~2,200,000–2,600,000 | Distributed across gateway instances |

With ~20,000 connections per gateway instance (conservative for Nginx/Envoy with HTTP/2 multiplexing), this requires **110–130 gateway instances**.

---

## 3. Event and Command Rates

### 3.1 Steady-State (All 100k Rooms Playing Simultaneously)

| Metric | Rate | Derivation |
|--------|------|-----------|
| **Commands (player actions)** | ~200,000/s | 100k rooms × 1 turn every 5s × ~1 command/turn = ~20k/s from tournament. But during peak action windows (all rooms mid-game), bursts reach ~200k/s across all rooms. |
| **Events produced (internal)** | ~300,000/s | ~1.5 events per command (action + side effects), spread across 100k rooms |
| **Public events (Kafka)** | ~300,000/s | Same rate, privacy-filtered payloads |
| **SSE events pushed to players** | ~300,000/s | Each public event pushed to ~10 players per room = 3M player-event deliveries/s. But each player only receives events for their own room, so the per-player rate is ~3 events per 5 seconds = <1 event/s per connection. |
| **SSE events pushed to spectators** | ~150,000/s | Similar rate for spectator connections, lower on average (fewer spectators per room than players) |

### 3.2 Per-Room Rates

| Metric | Rate per Room |
|--------|--------------|
| Commands | ~0.2/s (one turn every ~5 seconds) |
| Events | ~0.3/s (1.5 events per turn) |
| SSE deliveries | ~3/s (10 players × 0.3 events/s) |

These per-room rates are very low. The challenge is not per-room throughput but the aggregate volume across 100k rooms.

---

## 4. Per-Component Scaling Analysis

### 4.1 API Gateway

| Metric | Value |
|--------|-------|
| Total persistent connections | ~2.2M–2.6M |
| Connections per instance | ~20,000 (conservative) |
| **Instances needed** | **110–130** |
| Request throughput (REST commands) | ~200k/s total → ~1,500–2,000 req/s per instance |
| SSE event delivery | ~450k events/s total → ~3,500–4,000 deliveries/s per instance |
| Scaling | **Horizontal.** Stateless (except connection affinity). Add instances to handle more connections. |

### 4.2 Room Gameplay Service

| Metric | Value |
|--------|-------|
| Total rooms | ~110,000 |
| Commands/s | ~200,000 (peak) |
| Partition affinity | Each room is assigned to one instance (by `roomId` hash). Single-writer per room. |
| Rooms per instance | ~3,000–5,000 (depending on instance size) |
| **Instances needed** | **25–40** |
| Commands per instance | ~5,000–8,000/s |
| DB writes per instance | ~5,000–8,000 event inserts/s + ~5,000–8,000 outbox inserts/s = ~10,000–16,000 row inserts/s |
| Scaling | **Horizontal** by partition. Adding instances redistributes rooms. |

### 4.3 Timer Scheduler Worker

| Metric | Value |
|--------|-------|
| Active timers (peak) | ~300,000 (100k rooms × 3 timers: turn, challenge window, and some reconnection timers) |
| Expirations/s | ~20,000 (turn timers fire every 30s for inactive players; most turns complete before timeout) |
| **Instances needed** | **1–3** (leader-elected; Redis sorted set polling is O(log N) per expiry) |
| Scaling | **Partitioned** by Redis key range if needed, but a single leader can handle 20k expirations/s. |

### 4.4 Tournament Orchestration Service

| Metric | Value |
|--------|-------|
| Active tournaments | 1 (at peak scale) to ~10 |
| Commands/s (steady state) | ~50–300 (RecordRoomResult as rooms complete; bursty at round end) |
| **Instances needed** | **2–3** (for HA; one active per tournament) |
| Scaling | **Partitioned by tournamentId.** Each tournament is a single aggregate. Low write volume. The service is not a bottleneck. |

### 4.5 Round Kickoff Workers

| Metric | Value |
|--------|-------|
| Room creation messages to process | 100,000 (first-round burst) |
| Target completion time | 60–120 seconds |
| Throughput per worker | ~50–100 room creations/s (bounded by DB write) |
| **Workers needed** | **16–32** (temporary, scaled up for round start, scaled down after) |
| Scaling | **Horizontal.** Stateless Kafka consumers. Auto-scaled based on consumer lag. |

### 4.6 Ranking & Elo Service

| Metric | Value |
|--------|-------|
| Events consumed/s (steady state) | ~200/s (GameCompleted events from casual rooms; tournament games are filtered out) |
| Events consumed/s (round end burst) | ~3,000–5,000/s (burst of GameCompleted from tournament rooms, but all filtered/skipped since roomType=Tournament) |
| Elo updates/s | ~200/s (only casual games) |
| **Instances needed** | **2–3** |
| Scaling | **Horizontal** by Kafka partition. Low CPU, low DB write. |

### 4.7 Spectator View Service

| Metric | Value |
|--------|-------|
| Events consumed/s from Kafka | ~300,000/s (all public events) |
| Redis writes/s | ~300,000/s (one hash update per event) |
| SSE connections | ~110k–510k spectators |
| **Instances needed** | **10–25** (room-affine routing; each instance serves spectators for a subset of rooms) |
| Scaling | **Horizontal** by room affinity. Popular rooms (tournament finals) may need dedicated fan-out proxies. |

### 4.8 Analytics & Brackets Service

| Metric | Value |
|--------|-------|
| Events consumed/s | ~300,000/s (all public + lifecycle events) |
| ClickHouse writes | Micro-batched: ~60–300 batch inserts/s (each containing 1,000–5,000 rows) |
| PostgreSQL writes (bracket updates) | ~50–300/s (one per RoomResultRecorded) |
| **Instances needed** | **5–10** (Analytics Projection Workers) + **2–3** (Query API) |
| Scaling | **Horizontal.** Workers scale by Kafka partitions. ClickHouse scales by adding nodes. |

---

## 5. First-Round Surge Timeline

The critical engineering challenge: creating 100,000 rooms within a reasonable window after `StartTournament`.

```
T+0s    Tournament Service: generate 100k room assignments (in-memory, ~2–5s CPU)
T+5s    Publish 100k RoomCreationRequested messages to Kafka (batched, ~10–20s)
T+25s   Kafka has all messages distributed across 256 partitions
        (~390 messages per partition)

T+25s   Round Kickoff Workers (×32 instances) begin consuming
        Each worker processes ~50 room creations/s
        Total throughput: 32 × 50 = ~1,600 rooms/s

T+25s   Room Gameplay Service instances receive room creation commands
        Each instance creates room, inserts players, starts game
        DB writes per room creation: ~15 rows (room events + player joins + game start)

T+90s   ~100,000 rooms created (elapsed: ~65s of creation)
        All rooms now InProgress, games underway

T+5min  First games start completing (fastest rooms)
T+15min Most rooms have completed at least Game 1
T+30min Most matches complete (2–3 games)
T+45min Slowest rooms complete (edge cases: disconnections, close games)
T+50min Round 1 gate closes. RoundCompleted. Next round begins with 300k players.
```

**Bottleneck analysis:**

| Component | Bottleneck? | Mitigation |
|-----------|-------------|-----------|
| Kafka broker | No. 100k messages across 256 partitions = trivial for Kafka (millions/s capable). | Standard configuration. |
| Room Gameplay DB (PostgreSQL) | Potential. 1,600 rooms/s × 15 rows = ~24,000 inserts/s across all instances. | Connection pooling (PgBouncer), batched inserts, partitioned tables by `roomId` range. Multiple DB replicas for read-heavy aggregate loading. |
| Room Gameplay Service CPU | No. Room creation is lightweight (no game logic yet, just initial state). | Horizontal scaling, partition affinity. |
| Network | No. Messages are small (< 1KB each). | Standard networking. |

---

## 6. Round-End Completion Spike

When ~100,000 rooms complete their matches within a ~15–30 minute window:

| Metric | Rate |
|--------|------|
| `GameCompleted` events | ~250,000 total (100k rooms × 2.5 games avg) over ~30 minutes ≈ ~140/s average, bursts up to ~1,000/s |
| `MatchCompleted` events | ~100,000 total over ~30 minutes ≈ ~55/s average, bursts up to ~500/s |
| Tournament `RecordRoomResult` commands | ~100,000 over ~30 minutes ≈ ~55/s average |

**Impact per component:**

| Component | Impact | Mitigation |
|-----------|--------|-----------|
| **Tournament Orchestration** | ~55 `RecordRoomResult`/s at peak. Each is a lightweight counter increment + optimistic lock retry. | Easily handled by 2–3 instances. Contention on the Tournament aggregate is manageable at this rate (< 500 writes/s on a single aggregate with optimistic locking). |
| **Ranking Service** | Burst of `GameCompleted` events to filter (all tournament → skip Elo). Negligible CPU. | No concern. |
| **Analytics** | Burst of completion events for bracket updates. ~500 bracket writes/s at peak. | Micro-batching + auto-scaling workers. ClickHouse handles the event volume trivially. |
| **Spectator View** | Rooms completing = spectator connections closing. Load decreases during this phase. | Self-resolving. |

---

## 7. Spectator Multiplier

Spectators are a multiplier on real-time connection load but not on command processing:

### 7.1 Normal Rooms

| Metric | Value |
|--------|-------|
| Spectators per room | 0–5 (most rooms have few or no spectators) |
| Additional SSE connections | ~100k–500k (across 100k tournament rooms) |
| Per-event fan-out | 1 event → push to N spectators per room (handled by Spectator View Service's local pub/sub) |

### 7.2 High-Profile Rooms (Tournament Finals, Featured Matches)

| Metric | Value |
|--------|-------|
| Spectators per room | 1,000–100,000+ for the final |
| SSE connections per room | 1,000–100,000+ |
| Per-event fan-out | 1 event → 100k pushes (per room) |

**Mitigation for extreme fan-out:**

| Strategy | Detail |
|----------|--------|
| **Room-affine routing** | All spectators for a given room are routed to the same Spectator View Service instance (or small cluster). The instance broadcasts once to all local connections. |
| **Regional edge / CDN** | For > 10,000 spectators per room, a CDN or regional SSE edge layer sits between the Spectator View Service and clients. The service pushes one event to the edge; the edge fans out to regional spectators. |
| **Spectator cap** | If needed, a configurable spectator cap per room (e.g., 50,000) prevents unbounded connection growth. Excess spectators are redirected to a delayed stream or a polling endpoint. |
| **Connection cost** | Each SSE connection is lightweight (~2KB memory on the server). 100k connections ≈ 200MB. Manageable on a single large instance or 2–3 medium instances. |

---

## 8. Kafka Broker Sizing

| Topic | Partition Count | Peak Message Rate | Avg Message Size | Peak Throughput |
|-------|----------------|------------------|-----------------|----------------|
| `room.public.events` | 256 | ~300k/s | ~500B | ~150 MB/s |
| `room.lifecycle.events` | 256 | ~1k/s (bursty to ~5k/s) | ~1KB | ~5 MB/s |
| `tournament.room-creation` | 256 | ~5k/s (burst during round start only) | ~500B | ~2.5 MB/s |
| `tournament.lifecycle.events` | 64 | ~100/s | ~500B | ~0.05 MB/s |
| `identity.session-events` | 64 | ~100/s | ~200B | ~0.02 MB/s |
| `ranking.events` | 64 | ~200/s | ~300B | ~0.06 MB/s |
| **Total** | | ~310k/s | | **~160 MB/s** |

A 5-node Kafka cluster with modern hardware (SSD, 10GbE) comfortably handles > 1M messages/s and > 500 MB/s. The peak of ~310k/s at ~160 MB/s is well within capacity. Replication factor of 3 for durability.

---

## 9. Database Load

### 9.1 Room Gameplay PostgreSQL

| Metric | Peak Value | Derivation |
|--------|-----------|-----------|
| Write rate (events) | ~200k inserts/s | 200k commands/s × ~1 event insert per command (avg) |
| Write rate (outbox) | ~200k inserts/s | 1:1 with event inserts |
| Read rate (aggregate load) | ~200k reads/s | One aggregate load per command (snapshot + replay) |
| Connection pool | ~1,000–2,000 connections | 25–40 Room Service instances × 30–50 connections each |
| Storage growth | ~200GB/day at peak | ~400k rows/s × 500B avg × 8 hours tournament duration. Mostly during tournament; much lower at steady state. |

**Sharding strategy:** Partition `event_store` and `outbox` tables by `room_id` hash. Each Room Gameplay Service instance operates on a subset of rooms, hitting a subset of partitions. This distributes the write load across PostgreSQL instances or Citus shards.

For the peak of 200k writes/s, a sharded PostgreSQL setup (4–8 shards, each handling 25k–50k writes/s) is feasible with NVMe storage and connection pooling.

### 9.2 Tournament PostgreSQL

| Metric | Peak Value |
|--------|-----------|
| Write rate | ~500/s (RecordRoomResult bursts) |
| Read rate | ~100/s (tournament status queries) |

Trivial for a single PostgreSQL instance.

### 9.3 Redis

| Metric | Peak Value | Cluster? |
|--------|-----------|---------|
| Spectator View writes | ~300k/s (HSET per event) | Yes (6-node Redis Cluster, sharded by `roomId`) |
| Timer sorted set operations | ~20k/s (ZADD + ZRANGEBYSCORE) | No (single instance sufficient; leader-elected worker) |
| Rate-limit counters | ~200k/s (INCR + EXPIRE) | Yes (same cluster or dedicated instance) |
| Session cache | ~1k/s (SET + GET) | No (single instance sufficient) |

Total Redis operations: ~520k/s. A 6-node Redis Cluster handles this comfortably (each node ~100k ops/s).

---

## 10. Network Bandwidth

### 10.1 Ingress (Client → Platform)

| Traffic | Rate | Size | Bandwidth |
|---------|------|------|-----------|
| Player commands (REST) | ~200k/s | ~500B avg | ~100 MB/s |
| Spectator subscriptions | ~1k/s (new connections) | Negligible | < 1 MB/s |
| **Total ingress** | | | **~100 MB/s** |

### 10.2 Egress (Platform → Clients)

| Traffic | Rate | Size | Bandwidth |
|---------|------|------|-----------|
| REST responses | ~200k/s | ~1KB avg | ~200 MB/s |
| SSE events to players | ~3M deliveries/s (300k events × 10 players/room) | ~500B | ~1.5 GB/s |
| SSE events to spectators | ~300k–1.5M deliveries/s | ~500B | ~150–750 MB/s |
| **Total egress** | | | **~1.8–2.4 GB/s** |

Distributed across 110–130 gateway instances: ~15–20 MB/s per instance. Well within a single server's network capacity.

### 10.3 Internal (Service-to-Service)

| Traffic | Rate | Bandwidth |
|---------|------|-----------|
| Kafka production + consumption | ~600k messages/s (produce + consume) | ~300 MB/s |
| gRPC (Gateway → Identity) | ~200k/s | ~40 MB/s |
| Internal HTTP (commands, timer callbacks) | ~200k/s | ~100 MB/s |
| **Total internal** | | **~440 MB/s** |

Standard 10GbE network handles this with ample headroom.

---

## 11. Summary: What Scales Horizontally vs. What is Partitioned

| Component | Scaling Strategy | Min Instances (Peak) | Bottleneck Axis |
|-----------|-----------------|---------------------|-----------------|
| **API Gateway** | Horizontal (stateless, connection-affine) | 110–130 | Connection count (SSE) |
| **Room Gameplay Service** | Horizontal (partition-affine by `roomId`) | 25–40 | DB write throughput, CPU |
| **Room Gameplay DB (PostgreSQL)** | Sharded by `roomId` hash | 4–8 shards | Write IOPS (200k inserts/s) |
| **Outbox Relay** | Horizontal (one per DB shard) | 4–8 | Kafka produce rate |
| **Timer Scheduler Worker** | Leader-elected (1 active) | 1–3 (HA) | Redis sorted set poll rate |
| **Tournament Orchestration** | Partitioned by `tournamentId` | 2–3 | Low (not a bottleneck) |
| **Round Kickoff Workers** | Horizontal (stateless Kafka consumers) | 16–32 (burst, then scale down) | Room creation DB write |
| **Ranking & Elo Service** | Horizontal (Kafka consumer group) | 2–3 | Low (casual-only processing) |
| **Spectator View Service** | Horizontal (room-affine) | 10–25 | Redis writes, SSE fan-out |
| **Analytics Projection Workers** | Horizontal (Kafka consumer group) | 5–10 | ClickHouse batch insert rate |
| **Analytics Query API** | Horizontal (stateless) | 2–3 | Read query load |
| **Kafka Cluster** | Fixed cluster, horizontal by broker | 5 brokers | Message throughput (~310k/s) |
| **Redis Cluster** | Sharded by key prefix | 6 nodes | Operations/s (~520k/s) |

### Total Infrastructure at Peak Tournament

| Resource | Count |
|----------|-------|
| Gateway instances | ~130 |
| Service instances (all backend) | ~80–120 |
| PostgreSQL instances (all contexts) | ~12–15 |
| Redis nodes | ~6 |
| Kafka brokers | ~5 |
| **Total compute instances** | **~230–280** |

This is a significant but realistic infrastructure footprint for a platform serving 1M+ concurrent players with real-time gameplay. Outside of tournament peaks, the infrastructure scales down to ~20–30% of peak capacity (auto-scaling on connection count and consumer lag).
