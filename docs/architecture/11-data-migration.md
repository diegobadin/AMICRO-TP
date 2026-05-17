# Data Migration & Schema Versioning

> Optional enrichment deliverable (Architecture Checkpoint §7). How UnoArena evolves event schemas and API versions without breaking consumers.

---

## 1. Event Schema Versioning

### 1.1 Strategy: Additive-Only Changes + Versioned Types

Domain events are the backbone of all cross-context integration. Breaking changes to event payloads would cascade across consumers. The versioning strategy prioritizes **backward compatibility**.

| Change Type | Allowed? | Mechanism |
|-------------|---------|-----------|
| **Add optional field** | Yes (non-breaking) | Consumers ignore unknown fields. No version bump needed. |
| **Add required field** | Requires new version | New `ce-type` suffix: `CardPlayed.v2`. Both `.v1` and `.v2` published during transition. |
| **Rename field** | Requires new version | Publish both old and new field names in `.v2` during transition; drop old after all consumers migrate. |
| **Remove field** | Requires new version | Stop publishing in `.v2`; consumers on `.v1` still receive it until `.v1` is retired. |
| **Change field type** | Requires new version | Same as rename — new version with the new type. |
| **Split event into two** | Requires new version | New event type(s) coexist with the old one during transition. Old event deprecated, then retired. |

### 1.2 Transition Protocol

When a breaking change is needed (e.g., `GameCompleted.v1` → `GameCompleted.v2`):

```
Phase 1 — Dual-publish (producer change only)
  Producer publishes BOTH v1 and v2 events.
  Consumers still read v1 only — no consumer changes needed yet.
  Duration: ≥ 1 sprint (or until all consumer teams confirm readiness).

Phase 2 — Consumer migration
  Each consumer team migrates to read v2 (or both v1 and v2).
  Validated in staging with dual-publish active.
  Duration: per-team, tracked in a migration checklist.

Phase 3 — Retire v1
  Producer stops publishing v1.
  Consumers remove v1 handling code.
  v1 schema marked as deprecated in the schema registry.
```

### 1.3 Schema Registry (Optional)

A schema registry (Confluent Schema Registry or equivalent) can enforce compatibility rules per topic:

| Topic | Compatibility mode |
|-------|--------------------|
| `room.public.events` | BACKWARD (new schema can read old data) |
| `room.lifecycle.events` | BACKWARD |
| `tournament.lifecycle.events` | BACKWARD |
| `identity.session-events` | BACKWARD |
| `ranking.events` | BACKWARD |

Producers register new schemas before publishing. Consumers validate against registered schemas. Incompatible schemas are rejected at registration time.

---

## 2. REST API Versioning

### 2.1 Strategy: URL-Path Versioning

```
/v1/rooms/{roomId}/games/{gameId}/moves
/v2/rooms/{roomId}/games/{gameId}/moves
```

The API Gateway routes `/v1/*` and `/v2/*` to the appropriate service version (or a single service that handles both).

### 2.2 Versioning Policy

| Rule | Detail |
|------|--------|
| **New version only for breaking changes** | Adding optional response fields, new query parameters, or new endpoints does not bump the version. |
| **Support N and N-1 simultaneously** | At most two live API versions at any time. Clients have ≥ 1 quarter to migrate. |
| **Deprecation notice** | The `Sunset` HTTP header and a `Deprecation` header on responses signal the retirement date. |
| **Breaking changes** | Removing a field, changing a field type, removing an endpoint, changing the meaning of a status code. |

### 2.3 Current State

All endpoints are at **v1**. No breaking changes have been made since the initial architecture.

---

## 3. Event Store Migration (Room Gameplay)

The Room Gameplay event store is append-only and immutable. Events are never updated in place. Schema evolution for stored events uses **upcasting**.

### 3.1 Upcasting

When the Room Gameplay Service loads an aggregate from the event store, it replays events through an **upcaster pipeline**:

```
event_store row (v1 payload)
  → Upcaster v1→v2 (adds default values for new fields)
    → Upcaster v2→v3 (renames fields)
      → Current aggregate handler (expects v3 schema)
```

Upcasters are pure functions that transform event payloads from version N to N+1. They run in-memory during aggregate reload — the stored data is never modified.

### 3.2 Snapshot Invalidation

When an event schema changes and upcasters are added:

1. Existing aggregate snapshots become stale (they were built from old-schema events without upcasting).
2. The snapshot version check fails on load → snapshot is discarded and the aggregate is rebuilt from the event store (with upcasting).
3. New snapshots are written in the current schema after the first command on the aggregate post-migration.
4. A background job can pre-warm snapshots for active rooms to avoid first-command latency spikes.

---

## 4. Database Schema Migrations (Non-Event-Sourced Contexts)

Contexts that use state-based persistence (Tournament, Ranking, Identity, Analytics) manage schema changes via standard database migration tools (Flyway, Liquibase, or framework-native).

### 4.1 Migration Principles

| Principle | Detail |
|-----------|--------|
| **Forward-only** | No rollback migrations. If a migration fails, fix forward with a new migration. |
| **Backward-compatible DDL** | Add columns as nullable or with defaults. Never rename or drop a column in the same release that changes the code — use two releases (add new → migrate data → drop old). |
| **Per-context independence** | Each context's migrations run independently. No cross-schema dependencies. |
| **Zero-downtime** | Migrations must be compatible with the previous code version (rolling deploy). Achieved by: (1) adding nullable column, (2) deploying code that writes both old and new, (3) backfilling, (4) deploying code that reads new only, (5) dropping old column in a future release. |

### 4.2 Example: Adding a Field to `player_ratings`

```sql
-- Migration 001: add column (backward-compatible)
ALTER TABLE player_ratings
  ADD COLUMN provisional BOOLEAN DEFAULT false;

-- Code v2 deployed: writes provisional flag, reads it if present.
-- Old code v1 still works (ignores the column).

-- Migration 002 (future): if needed, make it NOT NULL after backfill.
-- ALTER TABLE player_ratings ALTER COLUMN provisional SET NOT NULL;
```

---

## 5. Kafka Topic Configuration Changes

| Change | Procedure |
|--------|-----------|
| **Increase partitions** | Safe to add partitions but changes key→partition mapping for new messages. Existing consumers must handle potential rebalancing. For `room.public.events` (keyed by `roomId`), new rooms may land on new partitions; in-flight rooms stay on existing ones. |
| **Change retention** | Runtime configuration change via `kafka-configs.sh`. No consumer impact. |
| **Add new topic** | Create topic before deploying the producer. Consumer groups are added when the consuming service deploys. |
| **Rename topic** | Not supported natively. Use dual-publish to old and new topic during transition (same as event versioning Phase 1–3). |
