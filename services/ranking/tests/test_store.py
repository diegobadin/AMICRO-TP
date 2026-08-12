"""The store suite, against a real Postgres.

Idempotency cannot be proved against a fake: `insert ... on conflict do nothing` inside a
transaction IS the mechanism, and a stub that re-implements it would only test the stub. Without
TEST_DATABASE_URL these fail rather than skip — a silently-skipped proof is worse than no proof
(the same rule room-gameplay's suite follows).
"""

from __future__ import annotations

import json
import os
from typing import Any

import psycopg
import pytest

from consumer import handle
from schema import migrate
from store import Store

ROOM = "1c1b0b7e-0000-4000-8000-000000000000"


@pytest.fixture()
def connection() -> Any:
    url = os.environ.get("TEST_DATABASE_URL")
    assert url, "TEST_DATABASE_URL is required — this suite proves nothing without a database"
    conn = psycopg.connect(url)
    migrate(conn)
    with conn.cursor() as cursor:
        cursor.execute("truncate player_ratings, rating_changes, consumed_events")
    conn.commit()
    yield conn
    conn.close()


def game(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "type": "GameCompleted",
        "roomId": ROOM,
        "sequenceNumber": 42,
        "roomType": "CASUAL",
        "gameNumber": 1,
        "isAbandoned": False,
        "finishingOrder": ["alice", "bob"],
        "cardPointTotals": {"alice": 0, "bob": 17},
        "completedAt": "2026-08-12T12:00:00+00:00",
        "at": "2026-08-12T12:00:00+00:00",
    }
    body.update(overrides)
    return body


def test_a_finished_game_moves_both_ratings(connection: Any) -> None:
    store = Store(connection)
    assert store.consume_game_completed(f"{ROOM}:42", game(), True) == "applied"
    assert store.rating("alice") == {"playerId": "alice", "rating": 1016, "games": 1}
    assert store.rating("bob") == {"playerId": "bob", "rating": 984, "games": 1}


def test_a_replay_moves_nothing(connection: Any) -> None:
    store = Store(connection)
    store.consume_game_completed(f"{ROOM}:42", game(), True)
    before = store.rating("alice")
    assert store.consume_game_completed(f"{ROOM}:42", game(), True) == "duplicate"
    assert store.rating("alice") == before
    assert len(store.history("alice", 10)) == 1


def test_a_skipped_game_is_still_recorded(connection: Any) -> None:
    store = Store(connection)
    assert store.consume_game_completed(f"{ROOM}:7", game(), False) == "skipped"
    assert store.rating("alice")["games"] == 0
    # Recorded, so the redelivery is recognised rather than re-filtered.
    assert store.consume_game_completed(f"{ROOM}:7", game(), False) == "duplicate"


def test_history_records_the_card_points_it_did_not_score(connection: Any) -> None:
    store = Store(connection)
    store.consume_game_completed(f"{ROOM}:42", game(), True)
    change = store.history("bob", 10)[0]
    assert change["delta"] == -16
    assert change["cardPoints"] == 17
    assert change["gameNumber"] == 1


def test_leaderboard_orders_by_rating(connection: Any) -> None:
    store = Store(connection)
    store.consume_game_completed(f"{ROOM}:42", game(), True)
    board = store.leaderboard(10)
    assert [row["playerId"] for row in board] == ["alice", "bob"]
    assert board[0]["rank"] == 1


def test_two_games_accumulate(connection: Any) -> None:
    store = Store(connection)
    store.consume_game_completed(f"{ROOM}:42", game(), True)
    store.consume_game_completed(f"{ROOM}:99", game(sequenceNumber=99, gameNumber=2), True)
    assert store.rating("alice")["games"] == 2
    assert store.rating("alice")["rating"] > 1016
    assert len(store.history("alice", 10)) == 2


class FakeMessage:
    """The narrow slice of a confluent_kafka Message the consumer actually reads."""

    def __init__(self, body: dict[str, Any], event_id: str, event_type: str) -> None:
        self._body = json.dumps(body).encode()
        self._headers = [("ce-type", event_type.encode()), ("ce-id", event_id.encode())]

    def value(self) -> bytes:
        return self._body

    def headers(self) -> list[tuple[str, bytes]]:
        return self._headers


def test_handle_applies_a_casual_game_and_dedupes_its_replay(connection: Any) -> None:
    store = Store(connection)
    message = FakeMessage(game(), f"{ROOM}:42", "GameCompleted")
    assert handle(message, store) == "applied"
    assert handle(message, store) == "duplicate"
    assert store.rating("alice")["rating"] == 1016


def test_handle_ignores_a_room_lifecycle_event_without_recording_it(connection: Any) -> None:
    store = Store(connection)
    message = FakeMessage({"roomId": ROOM, "sequenceNumber": 1}, f"{ROOM}:1", "RoomCreated")
    assert handle(message, store) == "ignored"
    with connection.cursor() as cursor:
        cursor.execute("select count(*) from consumed_events")
        assert cursor.fetchone()[0] == 0
