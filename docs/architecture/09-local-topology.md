# Local & Test Topology

> Optional enrichment deliverable (Architecture Checkpoint §7). Docker Compose sketch for local development and integration testing.

---

## 1. Overview

The local topology runs all UnoArena services, their data stores, and the event broker in Docker Compose. It is **not** a production deployment — it uses single-instance stores, reduced resource limits, and no TLS.

```
┌──────────────────────────────────────────────────────────────────┐
│  docker compose up                                               │
│                                                                  │
│  ┌─────────┐ ┌──────────┐ ┌────────────┐ ┌──────────────────┐   │
│  │ Gateway  │ │ Identity │ │   Room     │ │   Tournament     │   │
│  │  :8080   │ │  :8085   │ │ Gameplay   │ │  Orchestration   │   │
│  │          │ │          │ │  :8081     │ │     :8083        │   │
│  └────┬─────┘ └────┬─────┘ └─────┬──────┘ └───────┬──────────┘   │
│       │            │             │                │              │
│  ┌────┴────┐  ┌────┴────┐  ┌────┴────┐   ┌──────┴──────┐       │
│  │ Ranking │  │Spectator│  │Analytics│   │Timer Worker │       │
│  │  :8084  │  │  :8086  │  │  :8087  │   │   :8089     │       │
│  └─────────┘  └─────────┘  └─────────┘   └─────────────┘       │
│                                                                  │
│  ┌──────────┐ ┌──────┐ ┌───────┐ ┌────────────┐                 │
│  │PostgreSQL│ │Redis │ │ Kafka │ │ClickHouse  │                 │
│  │  :5432   │ │:6379 │ │ :9092 │ │   :8123    │                 │
│  └──────────┘ └──────┘ └───────┘ └────────────┘                 │
└──────────────────────────────────────────────────────────────────┘
```

---

## 2. Docker Compose Sketch

```yaml
networks:
  public:
  backend:
    driver: bridge
    internal: true
  data:
    driver: bridge
    internal: true

volumes:
  pg_room_data:
  pg_tournament_data:
  pg_ranking_data:
  pg_identity_data:
  pg_analytics_data:
  redis_data:
  kafka_data:
  clickhouse_data:

x-logging: &default-logging
  driver: json-file
  options:
    max-size: "10m"
    max-file: "3"

x-resource-limits: &default-limits
  deploy:
    resources:
      limits:
        cpus: "1.0"
        memory: 512M

services:

  # ── Infrastructure ──────────────────────────────────────────────

  postgres-room:
    image: postgres:16-alpine
    env_file: ./config/postgres-room.env
    volumes:
      - pg_room_data:/var/lib/postgresql/data
    networks: [data]
    read_only: true
    tmpfs:
      - /tmp
      - /run/postgresql
    logging: *default-logging
    <<: *default-limits
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U room_svc"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 10s

  postgres-tournament:
    image: postgres:16-alpine
    env_file: ./config/postgres-tournament.env
    volumes:
      - pg_tournament_data:/var/lib/postgresql/data
    networks: [data]
    read_only: true
    tmpfs:
      - /tmp
      - /run/postgresql
    logging: *default-logging
    <<: *default-limits
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U tournament_svc"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 10s

  postgres-ranking:
    image: postgres:16-alpine
    env_file: ./config/postgres-ranking.env
    volumes:
      - pg_ranking_data:/var/lib/postgresql/data
    networks: [data]
    read_only: true
    tmpfs:
      - /tmp
      - /run/postgresql
    logging: *default-logging
    <<: *default-limits
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ranking_svc"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 10s

  postgres-identity:
    image: postgres:16-alpine
    env_file: ./config/postgres-identity.env
    volumes:
      - pg_identity_data:/var/lib/postgresql/data
    networks: [data]
    read_only: true
    tmpfs:
      - /tmp
      - /run/postgresql
    logging: *default-logging
    <<: *default-limits
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U identity_svc"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 10s

  postgres-analytics:
    image: postgres:16-alpine
    env_file: ./config/postgres-analytics.env
    volumes:
      - pg_analytics_data:/var/lib/postgresql/data
    networks: [data]
    read_only: true
    tmpfs:
      - /tmp
      - /run/postgresql
    logging: *default-logging
    <<: *default-limits
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U analytics_svc"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 10s

  redis:
    image: redis:7-alpine
    command: ["redis-server", "--appendonly", "yes", "--maxmemory", "256mb", "--maxmemory-policy", "volatile-lru"]
    volumes:
      - redis_data:/data
    networks: [backend, data]
    read_only: true
    logging: *default-logging
    <<: *default-limits
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
      CLUSTER_ID: "local-dev-cluster-001"
    volumes:
      - kafka_data:/var/lib/kafka/data
    networks: [data]
    logging: *default-logging
    deploy:
      resources:
        limits:
          cpus: "2.0"
          memory: 1G
    healthcheck:
      test: ["CMD-SHELL", "kafka-broker-api-versions --bootstrap-server localhost:9092 > /dev/null 2>&1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  clickhouse:
    image: clickhouse/clickhouse-server:24.3-alpine
    volumes:
      - clickhouse_data:/var/lib/clickhouse
    networks: [data]
    logging: *default-logging
    <<: *default-limits
    healthcheck:
      test: ["CMD-SHELL", "clickhouse-client --query 'SELECT 1'"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 10s

  # ── Application Services ────────────────────────────────────────

  gateway:
    build:
      context: ./services/gateway
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    env_file: ./config/gateway.env
    networks: [public, backend]
    read_only: true
    tmpfs: [/tmp]
    logging: *default-logging
    <<: *default-limits
    depends_on:
      identity: { condition: service_healthy }
      redis: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8080/health/alive || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 10s
    develop:
      watch:
        - action: sync+restart
          path: ./services/gateway/src
          target: /app/src

  identity:
    build:
      context: ./services/identity
      dockerfile: Dockerfile
    env_file: ./config/identity.env
    networks: [backend, data]
    read_only: true
    tmpfs: [/tmp]
    logging: *default-logging
    <<: *default-limits
    depends_on:
      postgres-identity: { condition: service_healthy }
      redis: { condition: service_healthy }
      kafka: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8085/health/ready || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 15s
    develop:
      watch:
        - action: sync+restart
          path: ./services/identity/src
          target: /app/src

  room-gameplay:
    build:
      context: ./services/room-gameplay
      dockerfile: Dockerfile
    env_file: ./config/room-gameplay.env
    networks: [backend, data]
    read_only: true
    tmpfs: [/tmp]
    logging: *default-logging
    <<: *default-limits
    depends_on:
      postgres-room: { condition: service_healthy }
      kafka: { condition: service_healthy }
      redis: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8081/health/ready || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 15s
    develop:
      watch:
        - action: sync+restart
          path: ./services/room-gameplay/src
          target: /app/src

  outbox-relay:
    build:
      context: ./services/outbox-relay
      dockerfile: Dockerfile
    env_file: ./config/outbox-relay.env
    networks: [data]
    read_only: true
    tmpfs: [/tmp]
    logging: *default-logging
    <<: *default-limits
    depends_on:
      postgres-room: { condition: service_healthy }
      kafka: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8088/health/alive || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 15s

  timer-worker:
    build:
      context: ./services/timer-worker
      dockerfile: Dockerfile
    env_file: ./config/timer-worker.env
    networks: [backend, data]
    read_only: true
    tmpfs: [/tmp]
    logging: *default-logging
    <<: *default-limits
    depends_on:
      redis: { condition: service_healthy }
      room-gameplay: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8089/health/alive || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 10s

  tournament:
    build:
      context: ./services/tournament
      dockerfile: Dockerfile
    env_file: ./config/tournament.env
    networks: [backend, data]
    read_only: true
    tmpfs: [/tmp]
    logging: *default-logging
    <<: *default-limits
    depends_on:
      postgres-tournament: { condition: service_healthy }
      kafka: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8083/health/ready || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 15s
    develop:
      watch:
        - action: sync+restart
          path: ./services/tournament/src
          target: /app/src

  ranking:
    build:
      context: ./services/ranking
      dockerfile: Dockerfile
    env_file: ./config/ranking.env
    networks: [backend, data]
    read_only: true
    tmpfs: [/tmp]
    logging: *default-logging
    <<: *default-limits
    depends_on:
      postgres-ranking: { condition: service_healthy }
      kafka: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8084/health/ready || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 15s

  spectator:
    build:
      context: ./services/spectator
      dockerfile: Dockerfile
    env_file: ./config/spectator.env
    networks: [backend, data]
    read_only: true
    tmpfs: [/tmp]
    logging: *default-logging
    <<: *default-limits
    depends_on:
      redis: { condition: service_healthy }
      kafka: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8086/health/ready || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 15s

  analytics-workers:
    build:
      context: ./services/analytics-workers
      dockerfile: Dockerfile
    env_file: ./config/analytics-workers.env
    networks: [data]
    read_only: true
    tmpfs: [/tmp]
    logging: *default-logging
    <<: *default-limits
    depends_on:
      kafka: { condition: service_healthy }
      clickhouse: { condition: service_healthy }
      postgres-analytics: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8090/health/alive || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 15s

  analytics-api:
    build:
      context: ./services/analytics-api
      dockerfile: Dockerfile
    env_file: ./config/analytics-api.env
    networks: [backend, data]
    read_only: true
    tmpfs: [/tmp]
    logging: *default-logging
    <<: *default-limits
    depends_on:
      clickhouse: { condition: service_healthy }
      postgres-analytics: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8087/health/ready || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 15s
```

---

## 3. Environment File Layout

Each service reads its configuration from `./config/<service>.env`. Sensitive values (passwords, keys) live in these files, which are `.gitignore`'d. A `config/example/` folder ships with placeholder values.

```
config/
├── example/                         # Committed — placeholder values
│   ├── postgres-room.env
│   ├── postgres-tournament.env
│   ├── postgres-ranking.env
│   ├── postgres-identity.env
│   ├── postgres-analytics.env
│   ├── gateway.env
│   ├── identity.env
│   ├── room-gameplay.env
│   ├── outbox-relay.env
│   ├── timer-worker.env
│   ├── tournament.env
│   ├── ranking.env
│   ├── spectator.env
│   ├── analytics-workers.env
│   └── analytics-api.env
├── postgres-room.env                # .gitignore'd — actual dev values
├── ...
└── .gitignore
```

Example `config/example/gateway.env`:

```dotenv
IDENTITY_GRPC_URL=identity:50051
ROOM_URL=http://room-gameplay:8081
TOURNAMENT_URL=http://tournament:8083
SPECTATOR_URL=http://spectator:8086
ANALYTICS_URL=http://analytics-api:8087
RANKING_URL=http://ranking:8084
REDIS_URL=redis://redis:6379
```

Example `config/example/postgres-room.env`:

```dotenv
POSTGRES_DB=room_gameplay
POSTGRES_USER=room_svc
POSTGRES_PASSWORD=room_dev_password
```

---

## 4. Network Isolation

| Network | Visibility | `internal` | Services |
|---------|-----------|-----------|----------|
| `public` | Host-accessible (port 8080) | No | Gateway |
| `backend` | Service-to-service + Redis access | Yes | Gateway, Identity, Room Gameplay, Tournament, Ranking, Spectator, Analytics API, Timer Worker, **Redis** |
| `data` | Services-to-stores | Yes | All application services, PostgreSQL ×5, Redis, Kafka, ClickHouse |

Redis is on both `backend` and `data` because the Gateway (which lives on `backend`, not `data`) needs direct Redis access for session-invalidation pub/sub and L1 rate-limit counters.

Data stores are **not** exposed to the host by default. For debugging, temporarily add `ports:` to the specific store.

---

## 5. Running Integration Tests

```bash
# Start all services, wait until all healthchecks pass
docker compose up -d --build --wait

# Run integration tests against the gateway
./scripts/run-integration-tests.sh http://localhost:8080

# Tear down and remove volumes
docker compose down -v --remove-orphans
```

For development with hot-reload:

```bash
docker compose watch
```

Tests target the gateway as the single entry point (same as production). No test bypasses the gateway to hit internal services directly.

---

## 6. Differences from Production

| Aspect | Local | Production |
|--------|-------|-----------|
| PostgreSQL | Single instance per context (`-alpine`) | HA (primary + sync standby, Patroni) |
| Redis | Single instance | 6-node Redis Cluster |
| Kafka | Single broker (KRaft) | 5-broker cluster, replication factor 3 |
| TLS | Disabled | TLS 1.3 everywhere; mTLS for internal |
| Gateway instances | 1 | 110–130 (auto-scaled) |
| Room Gameplay instances | 1 | 25–40 (partition-affine) |
| Secrets | `env_file` (`.gitignore`'d) | Vault / cloud KMS |
| Observability | Stdout logs (`json-file` driver) | Prometheus + Grafana + distributed tracing |
| Resource limits | CPU 1.0 / 512M per service | Per-service tuned limits (Kubernetes) |
| Filesystem | `read_only: true` + `tmpfs` | Immutable container images, ephemeral storage |
