"""The three read models of architecture §7, plus the idempotency table every consumer needs.

Runs at startup under an advisory lock, mirroring room-gameplay's `Migrate.kt`. Every statement is
idempotent so P7 appends its bracket tables rather than rewriting these.

`analytics-api` reads these and writes nothing — the CQRS split of §7.2 is two deployables over one
schema, and this is the side that owns it.
"""

from __future__ import annotations

from typing import Any

# Arbitrary, analytics-specific: identity, room-gameplay and ranking hold different numbers.
LOCK_ID = 81420265

# The global counters. Enumerated here rather than created on demand so `/stats/overview` answers
# with zeros from an empty cluster instead of an empty object — a dashboard panel that disappears
# when nothing has happened yet is indistinguishable from one that is broken.
OVERVIEW_METRICS = (
    "rooms_created",
    "rooms_completed",
    "rooms_expired",
    "games_completed",
    "games_abandoned",
    "cards_played",
    "cards_drawn",
    "turns_timed_out",
    "players_forfeited",
    "uno_calls",
)

STATEMENTS = (
    """create table if not exists player_stats (
         player_id         text primary key,
         games_played      int not null default 0,
         games_won         int not null default 0,
         games_abandoned   int not null default 0,
         total_card_points int not null default 0,
         last_played_at    timestamptz
       )""",
    # One row per finished game, straight from `GameCompleted`. Nothing here is derived: every
    # column is a field the event states, which is what keeps a replay idempotent.
    """create table if not exists room_games (
         room_id        uuid not null,
         game_number    int  not null,
         room_type      text not null,
         is_abandoned   boolean not null,
         finishing_order jsonb not null,
         card_point_totals jsonb not null,
         completed_at   timestamptz not null,
         primary key (room_id, game_number)
       )""",
    "create index if not exists room_games_completed_idx on room_games (completed_at desc)",
    # Activity is per ROOM, not per game: `CardPlayed` and `CardDrawn` carry no `gameNumber`, and
    # deciding which game was open at sequence N would be room-gameplay's state re-derived here.
    """create table if not exists room_activity (
         room_id       uuid primary key,
         room_type     text,
         status        text not null default 'WAITING',
         -- Status only ever moves forward. The room's log arrives on two topics with no ordering
         -- between them, so a late `GameStarted` can follow a `GameCompleted` and would otherwise
         -- put a finished room back in progress. Comparing ranks makes the write order-independent,
         -- which is what lets the projection be replayed or re-interleaved without lying.
         status_rank   int not null default 0,
         players_seen  int not null default 0,
         cards_played  int not null default 0,
         cards_drawn   int not null default 0,
         events_seen   int not null default 0,
         first_event_at timestamptz,
         last_event_at  timestamptz
       )""",
    """create table if not exists overview (
         metric text primary key,
         value  bigint not null default 0
       )""",
    """create table if not exists consumed_events (
         source      text not null,
         event_key   text not null,
         consumed_at timestamptz not null default now(),
         primary key (source, event_key)
       )""",
)


def migrate(connection: Any) -> None:
    with connection.cursor() as cursor:
        cursor.execute("select pg_advisory_lock(%s)", (LOCK_ID,))
        try:
            for statement in STATEMENTS:
                cursor.execute(statement)
            for metric in OVERVIEW_METRICS:
                cursor.execute(
                    "insert into overview (metric, value) values (%s, 0) on conflict do nothing",
                    (metric,),
                )
            connection.commit()
        finally:
            cursor.execute("select pg_advisory_unlock(%s)", (LOCK_ID,))
            connection.commit()
