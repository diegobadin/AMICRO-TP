from typing import Any

from consumer import classify, header_value


def game(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "type": "GameCompleted",
        "roomType": "CASUAL",
        "isAbandoned": False,
        "finishingOrder": ["alice", "bob"],
    }
    body.update(overrides)
    return body


def test_a_casual_finished_game_is_scored() -> None:
    assert classify("GameCompleted", game()) == (True, "casual")


def test_a_tournament_game_is_skipped() -> None:
    assert classify("GameCompleted", game(roomType="TOURNAMENT")) == (False, "tournament")


def test_an_abandoned_game_is_skipped() -> None:
    assert classify("GameCompleted", game(isAbandoned=True)) == (False, "abandoned")


def test_the_catalog_spelling_is_not_the_wire_spelling() -> None:
    # `roomType` travels as the Kotlin enum name (CHANGELOG-design.md §10.5). The catalog writes
    # `Casual`; anything that is not `CASUAL` is not a casual game as far as this consumer is
    # concerned, and a filter written against the catalog would silently rank nothing.
    assert classify("GameCompleted", game(roomType="Casual")) == (False, "tournament")


def test_other_lifecycle_events_are_ignored() -> None:
    for event_type in ("RoomCreated", "RoomCompleted", "RoomExpired"):
        assert classify(event_type, {}) == (False, "not_game_completed")


def test_a_game_with_one_finisher_moves_nothing() -> None:
    assert classify("GameCompleted", game(finishingOrder=["alice"])) == (False, "too_few_players")


def test_header_value_reads_the_cloudevents_id() -> None:
    headers = [("ce-type", b"GameCompleted"), ("ce-id", b"room-1:42")]
    assert header_value(headers, "ce-id") == "room-1:42"
    assert header_value(headers, "ce-absent") is None
    assert header_value(None, "ce-id") is None
