"""The projection suite, against a real Postgres.

These projections are counters, and "a replay does not double-count" is a property of the
transaction, not of any branch in `project.py`. A fake would only test the fake. Without
TEST_DATABASE_URL these fail rather than skip.
"""

from __future__ import annotations

import json
import os
import random
from typing import Any

import psycopg
import pytest

from schema import migrate
from store import Store

ROOM = "1c1b0b7e-0000-4000-8000-000000000000"
PUBLIC = "room.public.events"
LIFECYCLE = "room.lifecycle.events"


@pytest.fixture()
def connection() -> Any:
    url = os.environ.get("TEST_DATABASE_URL")
    assert url, "TEST_DATABASE_URL is required — this suite proves nothing without a database"
    conn = psycopg.connect(url)
    migrate(conn)
    with conn.cursor() as cursor:
        cursor.execute(
            "truncate player_stats, room_games, room_activity, overview, consumed_events"
        )
    conn.commit()
    migrate(conn)  # re-seed the overview rows the truncate removed
    yield conn
    conn.close()


def event(topic: str, type_: str, seq: int, **fields: Any) -> tuple[str, str, str, dict[str, Any]]:
    body: dict[str, Any] = {
        "type": type_,
        "roomId": ROOM,
        "sequenceNumber": seq,
        "at": f"2026-08-12T12:00:{seq:02d}+00:00",
    }
    body.update(fields)
    return topic, f"{ROOM}:{seq}", type_, body


def game_log() -> list[tuple[str, str, str, dict[str, Any]]]:
    return [
        event(LIFECYCLE, "RoomCreated", 0, roomType="CASUAL", creatorId="alice", maxPlayers=4),
        event(PUBLIC, "PlayerJoined", 1, playerId="alice", playerCount=1),
        event(PUBLIC, "PlayerJoined", 2, playerId="bob", playerCount=2),
        event(PUBLIC, "GameStarted", 3, gameNumber=1, playerOrder=["alice", "bob"]),
        event(PUBLIC, "CardPlayed", 4, playerId="alice", playerCardCount=6),
        event(PUBLIC, "CardDrawn", 5, playerId="bob", newCardCount=8),
        event(PUBLIC, "UnoCallMade", 6, playerId="alice"),
        event(
            LIFECYCLE,
            "GameCompleted",
            7,
            roomType="CASUAL",
            gameNumber=1,
            finishingOrder=["alice", "bob"],
            cardPointTotals={"alice": 0, "bob": 17},
            isAbandoned=False,
            completedAt="2026-08-12T12:05:00+00:00",
        ),
        event(LIFECYCLE, "RoomCompleted", 8, roomType="CASUAL", finalResults=["alice", "bob"]),
    ]


def overview(connection: Any) -> dict[str, int]:
    with connection.cursor() as cursor:
        cursor.execute("select metric, value from overview")
        return {row[0]: row[1] for row in cursor.fetchall()}


def activity(connection: Any) -> dict[str, Any]:
    with connection.cursor() as cursor:
        cursor.execute(
            "select status, players_seen, cards_played, cards_drawn, events_seen,"
            " first_event_at, last_event_at from room_activity where room_id = %s",
            (ROOM,),
        )
        row = cursor.fetchone()
    assert row is not None
    return {
        "status": row[0],
        "players_seen": row[1],
        "cards_played": row[2],
        "cards_drawn": row[3],
        "events_seen": row[4],
        "first_event_at": row[5],
        "last_event_at": row[6],
    }


def test_a_played_game_lands_in_all_three_projections(connection: Any) -> None:
    store = Store(connection)
    for args in game_log():
        store.consume(*args)

    counts = overview(connection)
    assert counts["rooms_created"] == 1
    assert counts["games_completed"] == 1
    assert counts["games_abandoned"] == 0
    assert counts["cards_played"] == 1
    assert counts["cards_drawn"] == 1
    assert counts["uno_calls"] == 1
    assert counts["rooms_completed"] == 1

    assert activity(connection)["status"] == "COMPLETED"
    assert activity(connection)["players_seen"] == 2
    assert activity(connection)["events_seen"] == len(game_log())

    with connection.cursor() as cursor:
        cursor.execute(
            "select games_played, games_won, total_card_points from player_stats"
            " order by player_id"
        )
        assert cursor.fetchall() == [(1, 1, 0), (1, 0, 17)]
        cursor.execute("select finishing_order from room_games where room_id = %s", (ROOM,))
        assert json.loads(json.dumps(cursor.fetchone()[0])) == ["alice", "bob"]


def test_a_replay_changes_no_count(connection: Any) -> None:
    store = Store(connection)
    for args in game_log():
        store.consume(*args)
    before = (overview(connection), activity(connection))

    for args in game_log():
        assert store.consume(*args) == "duplicate"
    assert (overview(connection), activity(connection)) == before

    with connection.cursor() as cursor:
        cursor.execute("select games_played from player_stats where player_id = 'alice'")
        assert cursor.fetchone()[0] == 1


def test_an_abandoned_game_is_not_a_win(connection: Any) -> None:
    store = Store(connection)
    store.consume(
        *event(
            LIFECYCLE,
            "GameCompleted",
            7,
            roomType="CASUAL",
            gameNumber=1,
            finishingOrder=["alice", "bob"],
            cardPointTotals={"alice": 3, "bob": 17},
            isAbandoned=True,
            completedAt="2026-08-12T12:05:00+00:00",
        )
    )
    with connection.cursor() as cursor:
        cursor.execute("select games_won, games_abandoned from player_stats order by player_id")
        assert cursor.fetchall() == [(0, 1), (0, 1)]
    assert overview(connection)["games_abandoned"] == 1


def test_the_projection_is_order_independent(connection: Any) -> None:
    # The cross-topic interleaving, end to end: a room's log arrives on two topics with no ordering
    # between them, so every shuffle consistent with per-topic order must produce the same numbers.
    store = Store(connection)
    for args in game_log():
        store.consume(*args)
    expected = (overview(connection), activity(connection))

    for attempt in range(20):
        with connection.cursor() as cursor:
            cursor.execute(
                "truncate player_stats, room_games, room_activity, overview, consumed_events"
            )
        connection.commit()
        migrate(connection)

        lifecycle = [e for e in game_log() if e[0] == LIFECYCLE]
        public = [e for e in game_log() if e[0] == PUBLIC]
        shuffled: list[Any] = []
        rng = random.Random(attempt)  # noqa: S311 — reproducible interleavings, not cryptography
        while lifecycle or public:
            source = lifecycle if (not public or (lifecycle and rng.random() < 0.5)) else public
            shuffled.append(source.pop(0))
        for args in shuffled:
            store.consume(*args)

        assert (overview(connection), activity(connection)) == expected, f"attempt {attempt}"


def test_a_late_game_started_does_not_reopen_a_finished_room(connection: Any) -> None:
    store = Store(connection)
    log = {type_: args for args in game_log() for type_ in [args[2]]}
    store.consume(*log["GameCompleted"])
    store.consume(*log["RoomCompleted"])
    assert activity(connection)["status"] == "COMPLETED"
    store.consume(*log["GameStarted"])
    assert activity(connection)["status"] == "COMPLETED"
