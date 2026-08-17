"""The store suite, against a real Postgres.

Idempotency cannot be proved against a fake: `insert ... on conflict do nothing` inside a
transaction IS the mechanism, and a stub that re-implements it would only test the stub. Without
TEST_DATABASE_URL these fail rather than skip — a silently-skipped proof is worse than no proof
(the same rule room-gameplay's suite follows).
"""

from __future__ import annotations

import json
import os
import threading
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
        cursor.execute(
            "truncate player_ratings, rating_changes, placement_changes, consumed_events"
        )
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
    alice = store.rating("alice")
    assert (alice["rating"], alice["games"]) == (1016, 1)
    assert store.rating("bob")["rating"] == 984
    # A casual game leaves the tournament rating exactly where it was: §4.5 keeps the two apart,
    # and this is that rule seen from the Elo side.
    assert (alice["placementRating"], alice["tournaments"]) == (1000, 0)


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


# ---------------------------------------------------------------- P7: the placement rating

TOURNAMENT = "2b2b0b7e-0000-4000-8000-000000000000"


def tournament(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "type": "TournamentCompleted",
        "tournamentId": TOURNAMENT,
        "sequenceNumber": 12,
        "champion": "alice",
        "finalPlacements": ["alice", "bob", "carol", "dave"],
        "at": "2026-08-17T12:00:00+00:00",
    }
    body.update(overrides)
    return body


def test_a_finished_tournament_moves_placement_ratings(connection: Any) -> None:
    store = Store(connection)
    assert store.consume_tournament_completed(f"{TOURNAMENT}:12", tournament(), True) == "applied"

    champion = store.rating("alice")
    last = store.rating("dave")
    assert champion["placementRating"] > 1000 > last["placementRating"]
    assert champion["tournaments"] == 1
    # The pot is conserved: a tournament redistributes rating, it does not mint any.
    total = sum(store.rating(p)["placementRating"] for p in ("alice", "bob", "carol", "dave"))
    assert total == 4000


def test_a_tournament_never_touches_elo(connection: Any) -> None:
    store = Store(connection)
    store.consume_game_completed(f"{ROOM}:42", game(), True)
    elo_before = store.rating("alice")["rating"]

    store.consume_tournament_completed(f"{TOURNAMENT}:12", tournament(), True)

    # Elo is casual-only (§4.5), in both directions.
    assert store.rating("alice")["rating"] == elo_before
    assert store.rating("alice")["games"] == 1


def test_a_replayed_tournament_moves_nothing(connection: Any) -> None:
    store = Store(connection)
    store.consume_tournament_completed(f"{TOURNAMENT}:12", tournament(), True)
    before = store.rating("alice")
    assert store.consume_tournament_completed(f"{TOURNAMENT}:12", tournament(), True) == "duplicate"
    assert store.rating("alice") == before


def test_the_two_streams_cannot_collide_on_a_shared_event_key(connection: Any) -> None:
    """`consumed_events` is keyed (source, event_key). The same key on both topics is two events."""
    store = Store(connection)
    key = "same-key:1"
    assert store.consume_game_completed(key, game(), True) == "applied"
    assert store.consume_tournament_completed(key, tournament(), True) == "applied"


def test_history_shows_both_kinds_of_change(connection: Any) -> None:
    store = Store(connection)
    store.consume_game_completed(f"{ROOM}:42", game(), True)
    store.consume_tournament_completed(f"{TOURNAMENT}:12", tournament(), True)

    history = store.history("alice", 10)
    kinds = {row["kind"] for row in history}
    assert kinds == {"elo", "placement"}
    placement_row = next(row for row in history if row["kind"] == "placement")
    assert placement_row["placement"] == 1
    assert placement_row["fieldSize"] == 4
    assert placement_row["tournamentId"] == TOURNAMENT


def test_a_player_who_only_played_a_tournament_is_not_on_the_elo_board(connection: Any) -> None:
    """Locking a rating row creates it, so a tournament gives every finalist a row. 1000 after no
    games is a default, not a ranking, and the Elo board has never listed one."""
    store = Store(connection)
    store.consume_tournament_completed(f"{TOURNAMENT}:12", tournament(), True)

    assert store.leaderboard(10) == []
    assert store.rating("alice")["placementRating"] > 1000


def test_two_concurrent_games_both_land_on_a_shared_player() -> None:
    """E4, and the reason ranking's replica count stopped being a trap.

    Two connections apply two different games at the same time, both involving `alice`. Under the
    old read-modify-write both would read her rating, compute from the same starting point, and
    the second commit would overwrite the first — one game's Elo silently gone, with nothing to
    recompute it from. `_lock_ratings` makes the second transaction wait for the first, so it
    reads the value the first one wrote.

    Real connections and real concurrency: a mocked cursor cannot demonstrate a lost update, which
    is precisely the thing being claimed.
    """
    url = os.environ.get("TEST_DATABASE_URL")
    assert url, "TEST_DATABASE_URL is required"
    setup = psycopg.connect(url)
    migrate(setup)
    with setup.cursor() as cursor:
        cursor.execute(
            "truncate player_ratings, rating_changes, placement_changes, consumed_events"
        )
    setup.commit()
    setup.close()

    first, second = psycopg.connect(url), psycopg.connect(url)
    results: list[str] = []

    def apply(conn: Any, key: str, opponent: str) -> None:
        results.append(
            Store(conn).consume_game_completed(
                key,
                game(finishingOrder=["alice", opponent], cardPointTotals={"alice": 0, opponent: 9}),
                True,
            )
        )

    try:
        threads = [
            threading.Thread(target=apply, args=(first, f"{ROOM}:1", "bob")),
            threading.Thread(target=apply, args=(second, f"{ROOM}:2", "carol")),
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=20)

        assert results == ["applied", "applied"]
        reader = psycopg.connect(url, autocommit=True)
        try:
            store = Store(reader)
            assert store.rating("alice")["games"] == 2, "both games counted"
            changes = [row for row in store.history("alice", 10) if row["kind"] == "elo"]
            assert len(changes) == 2
            # The chain has no gap: the second change starts where the first one ended, which is
            # exactly what a lost update would break.
            chain = sorted(changes, key=lambda row: row["ratingBefore"])
            assert chain[0]["ratingAfter"] == chain[1]["ratingBefore"], (
                f"an update was lost: {chain}"
            )
        finally:
            reader.close()
    finally:
        first.close()
        second.close()
