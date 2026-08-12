"""Schema creation at startup, under an advisory lock, mirroring room-gameplay's `Migrate.kt`.

Every statement is idempotent so a later phase appends rather than rewrites. Failure here exits the
process: a service that cannot create its own tables has nothing to back off toward, which is
identity's posture and not the Go workers' — their loops retry against a schema somebody else owns.
"""

from __future__ import annotations

from typing import Any

# Arbitrary, ranking-specific: identity and room-gameplay hold different numbers.
LOCK_ID = 81420264

STATEMENTS = (
    """create table if not exists player_ratings (
         player_id  text primary key,
         rating     int not null,
         games      int not null default 0,
         updated_at timestamptz not null default now()
       )""",
    "create index if not exists player_ratings_rating_idx on player_ratings (rating desc)",
    # One row per player per game, which is what makes "why is my rating 987" answerable. The card
    # points are context, not an input to the delta (see elo.deltas).
    """create table if not exists rating_changes (
         id           bigserial primary key,
         player_id    text not null,
         room_id      uuid not null,
         game_number  int  not null,
         rating_before int not null,
         rating_after  int not null,
         delta         int not null,
         card_points   int,
         at            timestamptz not null default now()
       )""",
    "create index if not exists rating_changes_player_idx on rating_changes (player_id, id desc)",
    # The idempotency pattern P5 handed over: at-least-once is real, the relay publishes before it
    # marks, and `ce-id` = {roomId}:{sequenceNumber} is the log's primary key. Applying an Elo delta
    # twice is not a duplicate, it is a wrong rating, so this insert and the deltas share a
    # transaction.
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
            connection.commit()
        finally:
            cursor.execute("select pg_advisory_unlock(%s)", (LOCK_ID,))
            connection.commit()
