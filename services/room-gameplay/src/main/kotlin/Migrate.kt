// Schema creation runs at startup, under an advisory lock so several replicas cannot race each
// other, and before the server listens — serving requests against a missing schema is worse than a
// restart. Every statement is idempotent, so later phases append rather than rewrite.

import java.sql.Connection
import javax.sql.DataSource

// Arbitrary, room-gameplay specific: identity holds a different number.
private const val LOCK_ID = 81420263

private val STATEMENTS = listOf(
    // THE immutable game log. The primary key is E6's concurrency mechanism, not bookkeeping: two
    // writers that both computed event N from the same state race here, and exactly one wins.
    """create table if not exists room_events (
         room_id         uuid not null,
         sequence_number int  not null,
         type            text not null,
         payload         jsonb not null,
         correlation_id  text,
         created_at      timestamptz not null default now(),
         primary key (room_id, sequence_number)
       )""",
    // Written in the same transaction as the events above (Architecture §2.5). P5's relay drains
    // it; until then it fills up and nothing reads it, which is exactly the seam P5 plugs into.
    """create table if not exists outbox (
         id              bigserial primary key,
         room_id         uuid not null,
         sequence_number int  not null,
         topic           text not null,
         event_type      text not null,
         payload         jsonb not null,
         correlation_id  text,
         created_at      timestamptz not null default now(),
         published_at    timestamptz
       )""",
    "create index if not exists outbox_unpublished_idx on outbox (id) where published_at is null",
    // A small projection so `GET /rooms` does not replay the world (D5).
    """create table if not exists rooms (
         room_id         uuid primary key,
         room_type       text not null,
         status          text not null,
         max_players     int  not null,
         player_count    int  not null,
         players         jsonb not null default '[]'::jsonb,
         game_number     int,
         sequence_number int  not null,
         created_at      timestamptz not null,
         updated_at      timestamptz not null default now()
       )""",
    // Membership lives in the projection rather than in its own table: the session-events consumer
    // has to find every active room a player sits in, and that is the only query that needs it.
    "create index if not exists rooms_players_idx on rooms using gin (players)",
    "create index if not exists rooms_status_idx on rooms (status)",
    // P5: the earliest deadline this room is waiting on, written in the same transaction as the
    // events so it can never disagree with the log. The timer worker's only query reads it; nothing
    // else does. Rows written before this column existed stay NULL and are simply invisible to the
    // worker until their next write, which is what keeps an upgrade from firing a tick at every
    // room at once.
    "alter table rooms add column if not exists next_deadline timestamptz",
    "create index if not exists rooms_deadline_idx on rooms (next_deadline) where next_deadline is not null",
    // Keyed by (player, key), not by key alone: the header is client-generated, so two players are
    // free to pick the same string and neither may block the other.
    """create table if not exists idempotency_keys (
         player_id   text not null,
         key         text not null,
         room_id     uuid not null,
         response    jsonb not null,
         created_at  timestamptz not null default now(),
         primary key (player_id, key)
       )""",
    "create index if not exists idempotency_keys_created_idx on idempotency_keys (created_at)",
    // A fifth table, where plan D5 named four. Kafka is at-least-once, and D8 requires the
    // session-events consumer to be idempotent *by oldSessionId* — without somewhere to record what
    // has been seen there is nothing to be idempotent by, and a redelivery would re-open a
    // reconnection window that already expired. Generic on purpose: P5 and P6 consume too (§4.7).
    """create table if not exists consumed_events (
         source      text not null,
         event_key   text not null,
         consumed_at timestamptz not null default now(),
         primary key (source, event_key)
       )""",
)

fun migrate(dataSource: DataSource) {
    dataSource.connection.use { connection ->
        connection.autoCommit = true
        lock(connection)
        try {
            connection.createStatement().use { statement ->
                STATEMENTS.forEach { statement.execute(it) }
            }
        } finally {
            unlock(connection)
        }
    }
}

/** Retention per persistence-layer §1.4: a replay of a day-old key is a bug, not a retry. */
fun sweepIdempotencyKeys(dataSource: DataSource): Int =
    dataSource.connection.use { connection ->
        connection.prepareStatement("delete from idempotency_keys where created_at < now() - interval '24 hours'")
            .use { it.executeUpdate() }
    }

private fun lock(connection: Connection) =
    connection.prepareStatement("select pg_advisory_lock(?)").use {
        it.setInt(1, LOCK_ID)
        it.execute()
    }

private fun unlock(connection: Connection) =
    connection.prepareStatement("select pg_advisory_unlock(?)").use {
        it.setInt(1, LOCK_ID)
        it.execute()
    }
