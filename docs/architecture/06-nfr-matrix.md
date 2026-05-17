# Non-Functional Requirements (NFR) Matrix

> Strongly recommended deliverable (Architecture Checkpoint §7). Latency budgets, throughput targets, availability goals, and how each major flow meets them.

---

## 1. Latency Budgets

Target latencies measured at the **client edge** (gateway response or SSE delivery). P95 unless noted.

| Flow | Budget (p95) | Budget (p99) | Breakdown | Where enforced |
|------|-------------|-------------|-----------|----------------|
| **PlayCard** (REST round-trip) | ≤ 100 ms | ≤ 200 ms | Gateway overhead ~5 ms + JWT check ~2 ms + Room Service command processing ~20 ms + DB transaction (event store + outbox) ~30 ms + HTTP response ~5 ms | Gateway timeout 5s; Room Service monitors `room_command_duration_seconds` |
| **DrawCard / PassTurn** | ≤ 80 ms | ≤ 150 ms | Lighter aggregate logic than PlayCard (no card-legality tree) | Same path as PlayCard |
| **JoinRoom** | ≤ 150 ms | ≤ 300 ms | Aggregate load + membership mutation + snapshot update | Same path |
| **SSE event delivery** (player) | ≤ 200 ms from commit | ≤ 500 ms | DB commit → outbox relay → gateway SSE push. Alternatively: HTTP 201 response already carries the event. | Outbox relay lag metric; gateway SSE buffer |
| **SSE event delivery** (spectator) | ≤ 1 s from commit | ≤ 3 s | DB commit → outbox relay → Kafka → Spectator View consumer → Redis write → SSE push | Kafka consumer lag metric |
| **Token validation** (gRPC) | ≤ 5 ms | ≤ 15 ms | Local JWT signature check; gRPC introspection only for revocation | Circuit breaker on Identity; local fallback |
| **Tournament registration** | ≤ 200 ms | ≤ 500 ms | Gateway + Tournament DB write (optimistic lock) | Gateway timeout 5s |
| **Bracket / leaderboard query** | ≤ 300 ms | ≤ 1 s | PostgreSQL read or ClickHouse aggregation | Gateway timeout 3s + CDN cache (30–60s TTL) |
| **Elo rating query** | ≤ 100 ms | ≤ 200 ms | Direct read from `player_ratings` table | Gateway timeout 3s + cache |
| **Session invalidation → SSE kill** | ≤ 500 ms | ≤ 2 s | Identity → Redis pub/sub → Gateway connection close | Redis pub/sub latency + fallback periodic check (30s) |

---

## 2. Throughput Targets

Peak values during the first round of a 1,000,000-player tournament (see capacity sketch §4).

| Metric | Target | Component |
|--------|--------|-----------|
| REST commands/s (aggregate) | ≥ 200,000 | API Gateway fleet (110–130 instances) |
| Commands/s per gateway instance | ≥ 1,500 | Single Nginx/Envoy pod |
| Events appended/s (event store) | ≥ 200,000 | Room Gameplay PostgreSQL (4–8 shards) |
| Kafka messages/s (produced) | ≥ 310,000 | Kafka cluster (5 brokers) |
| SSE event deliveries/s (players) | ≥ 3,000,000 | Gateway fleet (fan-out per room) |
| SSE event deliveries/s (spectators) | ≥ 300,000–1,500,000 | Spectator View Service (10–25 instances) |
| Redis operations/s | ≥ 520,000 | Redis cluster (6 nodes) |
| Room creations/s (first-round surge) | ≥ 1,600 | 16–32 Round Kickoff Workers |
| Tournament `RecordRoomResult`/s | ≥ 500 (burst) | Tournament Orchestration (2–3 instances) |

---

## 3. Availability and Recovery

| Component | Availability target | Recovery strategy | RPO | RTO |
|-----------|-------------------|-------------------|-----|-----|
| **Room Gameplay Service** | 99.95% | Stateless instances; rooms re-sharded on pod loss. Event store is source of truth — aggregate rebuilt from events. | 0 (event-sourced) | < 30s (Kubernetes pod restart + aggregate reload) |
| **Room Event Store (PostgreSQL)** | 99.99% | Synchronous replication (primary + 1 sync standby). Failover via Patroni / PgBouncer redirect. | 0 (sync replication) | < 60s (automatic failover) |
| **API Gateway** | 99.99% | Stateless; horizontal auto-scale. Load balancer health checks. SSE clients reconnect via `Last-Event-ID`. | N/A | < 10s (new instance + LB drain) |
| **Identity & Session** | 99.95% | Stateless service + PostgreSQL HA. Gateway falls back to local JWT validation. | 0 | < 60s |
| **Tournament Orchestration** | 99.9% | Optimistic-lock retry. Saga recovery from persisted state. | 0 | < 60s |
| **Spectator View** | 99.9% | Redis Cluster failover. Read model rebuilt from Kafka replay. | Minutes (eventual consistency) | < 2 min (Redis failover + consumer catch-up) |
| **Analytics & Brackets** | 99.5% | Acceptable degradation: brackets show stale data. Workers catch up from Kafka. | Minutes | < 5 min |
| **Kafka Cluster** | 99.99% | Replication factor 3. ISR-based leader election. | 0 (with ISR >= 2) | < 30s (leader election) |
| **Redis Cluster** | 99.95% | Automatic failover with Redis Cluster. Timer Worker uses leader election. | Seconds (AOF fsync) | < 30s |

---

## 4. Data Consistency SLOs

| Flow | Consistency model | Max staleness |
|------|-------------------|---------------|
| Player game state (own hand, turn) | **Strong** (linearizable per room) | 0 — served from ACID transaction result |
| Spectator view | Eventual | ≤ 1s typical, ≤ 30s under burst |
| Bracket / standings | Eventual | ≤ 5s normal, ≤ 30s round-end burst |
| Elo rating after casual game | Eventual | ≤ 10s typical |
| Leaderboard | Eventual | ≤ 60s (materialized view refresh) |
| Session invalidation propagation | Eventual (belt-and-suspenders) | ≤ 500ms (Redis pub/sub path), ≤ 30s (fallback poll) |

---

## 5. Scalability Limits and Caps

| Dimension | Limit | Rationale |
|-----------|-------|-----------|
| Max players per room | 10 | Product definition |
| Max tournament players | 1,000,000 | Product definition |
| Max concurrent tournaments | 10 | Operational limit; each tournament is an independent aggregate |
| Max spectators per room (default) | 50,000 | Beyond this: regional edge fan-out or CDN (see capacity sketch §7.2) |
| Max SSE connections per gateway pod | 20,000 | Kernel file descriptor and memory limits; auto-scale beyond |
| Max Kafka partitions per topic | 256 | Balances parallelism vs. metadata overhead |
| Game log retention | 1 year minimum | Dispute resolution; archive to cold storage after |

---

## 6. Flow-to-NFR Traceability

| Flow | Latency | Throughput | Availability | Consistency |
|------|---------|-----------|--------------|-------------|
| PlayCard (casual) | p95 ≤ 100ms | 200k/s aggregate | 99.95% | Strong per room |
| PlayCard (tournament final) | p95 ≤ 100ms | Same | 99.95% | Strong per room |
| First-round surge (100k rooms) | N/A (async) | 1,600 rooms/s creation | 99.9% | Eventual (rooms created over ~90s) |
| Round-end completion spike | N/A (async) | ~500 `MatchCompleted`/s burst | 99.9% | Eventual → strong at round gate |
| Spectator view update | p95 ≤ 1s | 300k events/s consumed | 99.9% | Eventual ≤ 1s |
| Elo update after game | N/A (async) | ~200/s (casual only) | 99.5% | Eventual ≤ 10s |
| Session invalidation | p95 ≤ 500ms | ~100/s | 99.95% | Eventual ≤ 500ms |
| Bracket query | p95 ≤ 300ms | ~1k/s queries | 99.5% | Eventual ≤ 30s |
