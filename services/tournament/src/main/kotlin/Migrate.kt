// The schema, created at startup under an advisory lock — room-gameplay's `Migrate.kt` shape.
//
// This service OWNS this schema, so a migration failure exits the process (identity's posture since
// P2, delta §11.12). The contrast is a service that only reads someone else's schema: those connect
// lazily and answer 503. Five or six restarts on a cold cluster are expected here, not a defect.

import javax.sql.DataSource

// Arbitrary and tournament-specific; identity, room-gameplay and ranking hold different numbers.
private const val LOCK_ID = 81420277

private val STATEMENTS = listOf(
    // The log. Same primary key as `room_events` and for the same reason: two writers that both
    // computed event N from the same state race here, and exactly one wins.
    """create table if not exists tournament_events (
         tournament_id   uuid not null,
         sequence_number int  not null,
         type            text not null,
         payload         jsonb not null,
         correlation_id  text,
         created_at      timestamptz not null default now(),
         primary key (tournament_id, sequence_number)
       )""",
    // Written in the same transaction as the events (architecture §2.5). The column names are the
    // relay's contract: it reads a key column and a sequence number, and P7 taught it which ones.
    """create table if not exists outbox (
         id              bigserial primary key,
         tournament_id   uuid not null,
         sequence_number int  not null,
         topic           text not null,
         event_type      text not null,
         payload         jsonb not null,
         correlation_id  text,
         created_at      timestamptz not null default now(),
         published_at    timestamptz
       )""",
    "create index if not exists outbox_unpublished_idx on outbox (id) where published_at is null",
    // A projection so listing open tournaments does not replay every aggregate (room-gameplay's D5b).
    """create table if not exists tournaments (
         tournament_id   uuid primary key,
         status          text not null,
         player_count    int  not null,
         min_players     int  not null,
         room_size       int  not null,
         advance_count   int  not null,
         current_round   int  not null default 0,
         sequence_number int  not null,
         created_at      timestamptz not null,
         updated_at      timestamptz not null default now()
       )""",
    "create index if not exists tournaments_status_idx on tournaments (status)",
    // The saga's index: `MatchCompleted` names a room, and nothing in it says which tournament the
    // room belongs to (the catalog's payload is `matchResults` + `advancingPlayers`). This is how a
    // room id becomes a round, and it is also the bracket's own table.
    """create table if not exists round_rooms (
         room_id       uuid primary key,
         tournament_id uuid not null,
         round_number  int  not null,
         players       jsonb not null,
         advancing     jsonb,
         is_final      boolean not null default false,
         created_at    timestamptz not null default now(),
         reported_at   timestamptz
       )""",
    "create index if not exists round_rooms_tournament_idx on round_rooms (tournament_id, round_number)",
    // At-least-once is real: the relay publishes before it marks, so a crash redelivers. Recording a
    // room result twice would advance a round twice, so this insert shares the transaction.
    """create table if not exists consumed_events (
         source      text not null,
         event_key   text not null,
         consumed_at timestamptz not null default now(),
         primary key (source, event_key)
       )""",
    """create table if not exists idempotency_keys (
         key         text primary key,
         player_id   text not null,
         response    text not null,
         created_at  timestamptz not null default now()
       )""",
)

fun migrate(dataSource: DataSource) {
    dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("select pg_advisory_lock($LOCK_ID)")
            try {
                STATEMENTS.forEach { statement.execute(it) }
            } finally {
                statement.execute("select pg_advisory_unlock($LOCK_ID)")
            }
        }
    }
}
