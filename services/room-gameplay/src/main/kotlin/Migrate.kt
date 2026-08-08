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
    """create table if not exists idempotency_keys (
         key         text primary key,
         player_id   text not null,
         room_id     uuid not null,
         response    jsonb not null,
         created_at  timestamptz not null default now()
       )""",
    "create index if not exists idempotency_keys_created_idx on idempotency_keys (created_at)",
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
