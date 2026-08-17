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
            "truncate player_stats, room_games, room_activity, overview, consumed_events,"
            " tournaments, tournament_rounds, tournament_rooms, tournament_placements"
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
                "truncate player_stats, room_games, room_activity, overview, consumed_events,"
            " tournaments, tournament_rounds, tournament_rooms, tournament_placements"
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


# ---------------------------------------------------------------- P7: the bracket

TOURNAMENT_TOPIC = "tournament.lifecycle.events"
TOURNEY = "3c3c0b7e-0000-4000-8000-000000000000"
ROOM_A = "4a4a0b7e-0000-4000-8000-000000000001"
ROOM_B = "4a4a0b7e-0000-4000-8000-000000000002"
ROOM_F = "4a4a0b7e-0000-4000-8000-000000000003"


def bracket_log() -> list[tuple[str, dict[str, Any]]]:
    """One whole tournament, in the order it happens: four players, two rooms, then a final."""
    at = "2026-08-17T12:00:00+00:00"
    return [
        (
            "TournamentCreated",
            {
                "tournamentId": TOURNEY,
                "config": {"minPlayers": 4, "roomSize": 2, "advanceCount": 1},
                "at": at,
            },
        ),
        ("PlayerRegistered", {"tournamentId": TOURNEY, "registeredCount": 1, "at": at}),
        ("PlayerRegistered", {"tournamentId": TOURNEY, "registeredCount": 4, "at": at}),
        (
            "TournamentStarted",
            {"tournamentId": TOURNEY, "totalPlayers": 4, "roundCount": 2, "at": at},
        ),
        (
            "RoundStarted",
            {
                "tournamentId": TOURNEY,
                "roundNumber": 1,
                "roomCount": 2,
                "roomIds": [ROOM_A, ROOM_B],
                "assignments": {ROOM_A: ["alice", "bob"], ROOM_B: ["carol", "dave"]},
                "at": at,
            },
        ),
        (
            "RoomResultRecorded",
            {"tournamentId": TOURNEY, "roundNumber": 1, "roomId": ROOM_A,
             "advancingPlayers": ["alice"], "at": at},
        ),
        (
            "RoomResultRecorded",
            {"tournamentId": TOURNEY, "roundNumber": 1, "roomId": ROOM_B,
             "advancingPlayers": ["carol"], "at": at},
        ),
        (
            "RoundCompleted",
            {"tournamentId": TOURNEY, "roundNumber": 1, "advancingPlayersTotal": 2, "at": at},
        ),
        (
            "RoundStarted",
            {
                "tournamentId": TOURNEY,
                "roundNumber": 2,
                "roomCount": 1,
                "roomIds": [ROOM_F],
                "assignments": {ROOM_F: ["alice", "carol"]},
                "at": at,
            },
        ),
        (
            "FinalRoomCreated",
            {"tournamentId": TOURNEY, "roomId": ROOM_F, "finalists": ["alice", "carol"], "at": at},
        ),
        (
            "RoomResultRecorded",
            {"tournamentId": TOURNEY, "roundNumber": 2, "roomId": ROOM_F,
             "advancingPlayers": ["carol"], "at": at},
        ),
        (
            "RoundCompleted",
            {"tournamentId": TOURNEY, "roundNumber": 2, "advancingPlayersTotal": 1, "at": at},
        ),
        (
            "TournamentCompleted",
            {"tournamentId": TOURNEY, "champion": "carol",
             "finalPlacements": ["carol", "alice", "bob", "dave"], "at": at},
        ),
    ]


def project_bracket(store: Store, log: list[tuple[str, dict[str, Any]]], prefix: str = "") -> None:
    for index, (event_type, body) in enumerate(log):
        store.consume(TOURNAMENT_TOPIC, f"{prefix}{TOURNEY}:{index}", event_type, body)


def rows(connection: Any, sql: str, params: tuple[Any, ...] = ()) -> list[tuple[Any, ...]]:
    with connection.cursor() as cursor:
        cursor.execute(sql, params)
        fetched: list[tuple[Any, ...]] = cursor.fetchall()
    return fetched


def test_a_whole_bracket_is_projected(connection: Any) -> None:
    store = Store(connection)
    project_bracket(store, bracket_log())

    assert rows(
        connection,
        "select status, player_count, champion from tournaments where tournament_id = %s",
        (TOURNEY,),
    ) == [("COMPLETED", 4, "carol")]
    assert rows(
        connection,
        "select round_number, room_count, advancing_total, complete from tournament_rounds"
        " where tournament_id = %s order by round_number",
        (TOURNEY,),
    ) == [(1, 2, 2, True), (2, 1, 1, True)]
    assert rows(
        connection,
        "select count(*) from tournament_rooms where tournament_id = %s",
        (TOURNEY,),
    ) == [(3,)]
    assert rows(
        connection,
        "select player_id from tournament_placements where tournament_id = %s order by placement",
        (TOURNEY,),
    ) == [("carol",), ("alice",), ("bob",), ("dave",)]
    assert rows(
        connection, "select is_final from tournament_rooms where room_id = %s", (ROOM_F,)
    ) == [(True,)]


def snapshot(connection: Any) -> list[tuple[Any, ...]]:
    return (
        rows(connection, "select * from tournaments")
        + rows(connection, "select * from tournament_rounds order by round_number")
        + rows(connection, "select * from tournament_rooms order by room_id")
    )


def test_a_replayed_bracket_changes_nothing(connection: Any) -> None:
    """The point of writing facts rather than increments: the same events again change nothing.

    Twice over — once with the same dedup keys, which are recognised and dropped, and once with
    fresh ones, which are applied for real and must still land on the same rows.
    """
    store = Store(connection)
    log = bracket_log()
    project_bracket(store, log)
    before = snapshot(connection)

    project_bracket(store, log)
    project_bracket(store, log, prefix="replayed-")

    assert snapshot(connection) == before


def test_a_bracket_survives_any_order_of_its_events(connection: Any) -> None:
    """Delivery order is not a guarantee a projection may rely on — the P6 lesson, applied to the
    stream P7 added. Reversed is the worst case: the champion arrives before the field exists."""
    store = Store(connection)
    project_bracket(store, list(reversed(bracket_log())))

    assert rows(
        connection,
        "select status, champion from tournaments where tournament_id = %s",
        (TOURNEY,),
    ) == [("COMPLETED", "carol")]
    # The room keeps its players even though `RoundStarted` arrived after the result did.
    assert rows(
        connection, "select players, advancing from tournament_rooms where room_id = %s", (ROOM_A,)
    ) == [(["alice", "bob"], ["alice"])]
    assert rows(
        connection,
        "select complete from tournament_rounds where tournament_id = %s and round_number = 1",
        (TOURNEY,),
    ) == [(True,)]


def test_a_late_creation_does_not_reopen_a_finished_bracket(connection: Any) -> None:
    store = Store(connection)
    log = bracket_log()
    project_bracket(store, log)

    store.consume(TOURNAMENT_TOPIC, "late-creation", *log[0])

    assert rows(
        connection, "select status from tournaments where tournament_id = %s", (TOURNEY,)
    ) == [("COMPLETED",)]
