from typing import Any

from project import STATUS_RANK, plan


def game(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "roomId": "1c1b0b7e-0000-4000-8000-000000000000",
        "sequenceNumber": 42,
        "roomType": "CASUAL",
        "gameNumber": 1,
        "isAbandoned": False,
        "finishingOrder": ["alice", "bob"],
        "cardPointTotals": {"alice": 0, "bob": 17},
        "completedAt": "2026-08-12T12:05:00+00:00",
        "at": "2026-08-12T12:05:00+00:00",
    }
    body.update(overrides)
    return body


def test_every_event_advances_the_room_activity_window() -> None:
    effects = plan("CardPlayed", {"roomId": "r", "at": "2026-08-12T12:00:00Z"})
    assert effects.activity_counters["events_seen"] == 1
    assert effects.activity["last_event_at"] == "2026-08-12T12:00:00Z"


def test_a_finished_game_writes_one_game_row_and_one_row_per_player() -> None:
    effects = plan("GameCompleted", game())
    assert effects.game is not None
    assert effects.game["game_number"] == 1
    assert [p["player_id"] for p in effects.players] == ["alice", "bob"]
    assert effects.players[0]["won"] is True
    assert effects.players[1]["won"] is False
    assert effects.players[1]["card_points"] == 17


def test_an_abandoned_game_has_no_winner() -> None:
    effects = plan("GameCompleted", game(isAbandoned=True))
    assert all(not p["won"] for p in effects.players)
    assert all(p["abandoned"] for p in effects.players)
    assert effects.overview["games_abandoned"] == 1


def test_a_timeout_is_counted_apart_from_the_draw_it_performs() -> None:
    # `TurnTimedOut` emits CardDrawn + TurnPassed on the player's behalf. Counting the timeout as a
    # player action is the same mistake that broke P5's forfeit streak, in statistical form.
    timeout = plan("TurnTimedOut", {"roomId": "r", "playerId": "alice", "autoAction": "draw"})
    assert timeout.overview == {"turns_timed_out": 1}
    assert "cards_drawn" not in timeout.overview


def test_card_events_feed_both_the_room_and_the_global_counters() -> None:
    played = plan("CardPlayed", {"roomId": "r"})
    assert played.overview["cards_played"] == 1
    assert played.activity_counters["cards_played"] == 1


def test_status_only_ever_moves_forward() -> None:
    assert STATUS_RANK["WAITING"] < STATUS_RANK["IN_PROGRESS"] < STATUS_RANK["COMPLETED"]
    # A room is finished either way, so the two terminal states are the same rank and neither can
    # overwrite the other on a late delivery.
    assert STATUS_RANK["COMPLETED"] == STATUS_RANK["EXPIRED"]


def test_an_unknown_event_changes_nothing_but_the_window() -> None:
    # `MatchCompleted` is the one P7 actually added to this topic; the invented name keeps the
    # property honest for whatever P8 adds next.
    for event_type in ("MatchCompleted", "SomethingP8Adds"):
        effects = plan(event_type, {"roomId": "r", "at": "2026-08-12T12:00:00Z"})
        assert not effects.overview
        assert effects.game is None
        assert not effects.players
        # Not "empty": the room was active, and that is true whether or not this service understands
        # what happened.
        assert effects.activity_counters["events_seen"] == 1


def test_the_event_name_comes_from_the_body_not_the_cloudevents_uri() -> None:
    # The relay writes `ce-type: com.unoarena.room.GameCompleted.v1` — a reverse-DNS URI, not the
    # catalog's bare name. Classifying on the header skipped every event in the P6 drill.
    from consumer import event_name

    headers = [("ce-type", b"com.unoarena.room.GameCompleted.v1")]
    assert event_name(headers, {"type": "GameCompleted"}) == "GameCompleted"
    assert event_name(headers, {}) == "GameCompleted"
    assert plan(event_name(headers, game()), game()).game is not None


def test_the_lag_gauge_accepts_the_label_the_consumer_gives_it() -> None:
    # It was copied from ranking's unlabelled gauge, and `refresh_lag` calls `.labels(topic=...)`.
    # That threw on every loop iteration BEFORE poll() was reached, so the projections stopped dead
    # while the pod stayed Healthy — 326 errors before the drill noticed.
    import metrics

    metrics.consumer_lag.labels(topic="room.public.events").set(0)
    metrics.consumer_lag.labels(topic="room.lifecycle.events").set(3)
