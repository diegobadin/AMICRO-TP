# Observability & Health Endpoints

> Strongly recommended deliverable (Architecture Checkpoint §7). Describes what each major deployable emits for logs, metrics, and traces, how correlation IDs propagate across sync and async flows, and how liveness/readiness are exposed for orchestration.

---

## Table of Contents

1. [Correlation and Tracing](#1-correlation-and-tracing)
2. [Structured Logging](#2-structured-logging)
3. [Metrics by Service](#3-metrics-by-service)
4. [Health Endpoints](#4-health-endpoints)
5. [Tournament Round Health](#5-tournament-round-health)

---

## 1. Correlation and Tracing

Every request and domain event carries identifiers so operators can follow a single player action from gateway → Room Gameplay → Kafka → downstream consumers.

| Identifier | Created by | Propagated via |
|------------|------------|----------------|
| `traceId` | API Gateway (W3C `traceparent` or UUID) | HTTP headers (`traceparent`), gRPC metadata, Kafka `ce-correlationid`, log fields |
| `requestId` | API Gateway | `X-Request-Id` to upstream REST/gRPC |
| `correlationId` | Room Gameplay (on command accept) | Domain events, outbox rows, SSE events, Kafka headers |
| `eventId` | Room / Tournament / Identity (per event) | Kafka `ce-id`, consumer deduplication |
| `roomId` / `tournamentId` | Domain | Logs, metrics labels, trace baggage |

**Async flows:** When `GameCompleted` triggers Elo update and tournament advancement, all consumer logs include the same `correlationId` from the originating `PlayCard` command. Distributed trace spans (optional OpenTelemetry): `gateway.http` → `room.command` → `room.db.commit` → `kafka.publish` → `ranking.consume`.

---

## 2. Structured Logging

All services emit **JSON logs** to stdout (collected by the platform log aggregator). Minimum fields on every line:

```json
{
  "level": "info",
  "service": "room-gameplay",
  "environment": "prod",
  "traceId": "abc-123",
  "correlationId": "corr-456",
  "roomId": "R42",
  "event": "command_accepted",
  "command": "PlayCard",
  "sequenceNumber": 17,
  "duration_ms": 12
}
```

| Service | Notable log events |
|---------|-------------------|
| **API Gateway** | `request_received`, `rate_limit_exceeded`, `upstream_timeout`, `circuit_breaker_open`, `session_invalidated_push`, `sse_connection_closed` |
| **Room Gameplay** | `command_accepted`, `command_rejected` (stale seq, illegal play), `events_committed`, `timer_scheduled`, `timer_expired_noop`, `outbox_publish_lag` |
| **Tournament Orchestration** | `round_started`, `room_result_recorded`, `round_completed`, `room_creation_sweep`, `dlq_alert` |
| **Ranking** | `elo_skipped` (tournament/abandoned), `elo_applied`, `duplicate_event_skipped` |
| **Identity** | `session_invalidated`, `login_success`, `rate_limit_exceeded` |
| **Spectator View** | `projection_updated`, `acl_rejected_private_field`, `consumer_lag_high` |
| **Analytics** | `batch_written`, `projection_lag_seconds` |
| **Outbox Relay** | `rows_published`, `publish_failure`, `lag_seconds` |

**Sensitive data:** Never log hand contents, deck order, RNG seeds, passwords, or full JWTs. `playerId` and `roomId` only.

---

## 3. Metrics by Service

Metrics exported in Prometheus format (`/metrics` on each service, scraped every 15s).

### 3.1 API Gateway

| Metric | Type | Labels |
|--------|------|--------|
| `http_requests_total` | Counter | `method`, `route`, `status` |
| `http_request_duration_seconds` | Histogram | `method`, `route` |
| `sse_connections_active` | Gauge | `connection_type` (player, spectator, control) |
| `rate_limit_rejections_total` | Counter | `layer` (ip, user) |
| `circuit_breaker_state` | Gauge | `upstream` (room, tournament, identity) — 0=closed, 1=open, 2=half-open |
| `upstream_timeouts_total` | Counter | `upstream` |

### 3.2 Room Gameplay Service

| Metric | Type | Labels |
|--------|------|--------|
| `room_commands_total` | Counter | `command`, `result` (accepted, rejected_stale, rejected_illegal) |
| `room_command_duration_seconds` | Histogram | `command` |
| `room_events_appended_total` | Counter | `event_type` |
| `outbox_unpublished_rows` | Gauge | — |
| `outbox_publish_lag_seconds` | Gauge | — |
| `kafka_consumer_lag` | Gauge | `topic`, `partition` (for `tournament.room-creation`, `identity.session-events`) |

### 3.3 Tournament Orchestration

| Metric | Type | Labels |
|--------|------|--------|
| `tournament_rooms_expected` | Gauge | `tournament_id`, `round` |
| `tournament_rooms_completed` | Gauge | `tournament_id`, `round` |
| `tournament_round_duration_seconds` | Histogram | `round` |
| `room_creation_dlq_total` | Counter | `tournament_id` |

### 3.4 Kafka Consumers (Spectator, Analytics, Ranking)

| Metric | Type | Labels |
|--------|------|--------|
| `kafka_consumer_lag` | Gauge | `topic`, `consumer_group` |
| `kafka_messages_processed_total` | Counter | `topic`, `result` (success, retry, dlq) |
| `projection_batch_duration_seconds` | Histogram | `consumer` |

### 3.5 Redis / Timer Worker

| Metric | Type | Labels |
|--------|------|--------|
| `timer_expirations_total` | Counter | `timer_type` |
| `timer_duplicate_delivery_total` | Counter | — |
| `redis_command_duration_seconds` | Histogram | `command` |

---

## 4. Health Endpoints

Each deployable exposes **liveness** and **readiness** separately (Kubernetes-style). Readiness checks only **critical** dependencies — non-critical deps (e.g. Analytics) must not mark the pod unready.

| Service | Liveness | Readiness checks | Port |
|---------|----------|------------------|------|
| **API Gateway** | `GET /health/live` — process up | `GET /health/ready` — can reach Identity gRPC (or JWT local validator initialized) | 8080 |
| **Room Gameplay** | `GET /health/live` | PostgreSQL event store ping; optional: Kafka producer reachable (outbox relay may be separate deployment) | 8081 |
| **Outbox Relay** | `GET /health/live` | PostgreSQL (read outbox); Kafka broker metadata | 8082 |
| **Tournament Orchestration** | `GET /health/live` | PostgreSQL tournament DB | 8083 |
| **Ranking & Elo** | `GET /health/live` | PostgreSQL; Kafka consumer group joined | 8084 |
| **Identity & Session** | `GET /health/live` | PostgreSQL; Redis ping | 8085 |
| **Spectator View** | `GET /health/live` | Redis ping; Kafka consumer group joined | 8086 |
| **Analytics Query API** | `GET /health/live` | PostgreSQL + ClickHouse (degraded mode if ClickHouse down: serve cached brackets only) | 8087 |
| **Analytics Projection Workers** | `GET /health/live` | Kafka consumer joined | 8088 |
| **Timer Scheduler Worker** | `GET /health/live` | Redis ping; leader lock held (if leader-elected) | 8089 |

**Response shape (readiness):**

```json
{
  "status": "ready",
  "checks": {
    "postgresql": "ok",
    "kafka": "ok",
    "redis": "ok"
  }
}
```

HTTP **503** when any critical check fails. Liveness always returns **200** unless the process is dead (orchestrator restarts the pod).

**Compose / local dev:** `depends_on: condition: service_healthy` uses these readiness endpoints (see KB healthcheck pattern).

---

## 5. Tournament Round Health

During a million-player tournament, operators need a single view of round kickoff and completion progress.

**Dashboard panels (metrics-driven):**

| Panel | Source | Alert threshold |
|-------|--------|-----------------|
| Round kickoff progress | `tournament_rooms_completed` / `tournament_rooms_expected` | Stuck < 95% for 30 min after `RoundStarted` |
| Kafka lag — room creation | `kafka_consumer_lag{topic="tournament.room-creation"}` | Lag > 50k messages for 10 min |
| Kafka lag — lifecycle | `kafka_consumer_lag{topic="room.lifecycle.events"}` | Lag > 100k for 15 min at round end |
| Room command p99 | `room_command_duration_seconds` | p99 > 500ms for 5 min |
| Circuit breaker open | `circuit_breaker_state{upstream="room"}` | Any open state |
| DLQ depth | `room_creation_dlq_total` increase | > 0 in 5 min window |
| SSE connections | `sse_connections_active` | Capacity planning (no hard alert) |
| Outbox lag | `outbox_publish_lag_seconds` | > 30s |

**Synthetic probe (optional):** A canary room runs a scripted `PlayCard` loop every 60s outside tournament load; failure pages on-call before players mass-report issues.
