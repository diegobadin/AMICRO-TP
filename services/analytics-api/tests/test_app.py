from typing import Any

from app import route


class StubReader:
    def __init__(self) -> None:
        self.calls: list[tuple[str, Any]] = []

    def player_stats(self, player_id: str) -> dict[str, Any]:
        self.calls.append(("player", player_id))
        return {"playerId": player_id, "gamesPlayed": 3}

    def room_stats(self, room_id: str) -> dict[str, Any]:
        self.calls.append(("room", room_id))
        return {"roomId": room_id, "games": []}

    def overview(self) -> dict[str, Any]:
        self.calls.append(("overview", None))
        return {"games_completed": 7}


def test_health_returns_ok_with_service_name() -> None:
    status, body = route("GET", "/health", StubReader())
    assert status == 200
    assert body == {"status": "ok", "service": "analytics-api"}


def test_the_three_stats_surfaces_reach_the_reader() -> None:
    reader = StubReader()
    assert route("GET", "/stats/players/alice", reader)[0] == 200
    assert route("GET", "/stats/rooms/room-1", reader)[0] == 200
    status, body = route("GET", "/stats/overview", reader)
    assert status == 200
    assert body == {"overview": {"games_completed": 7}}
    assert reader.calls == [("player", "alice"), ("room", "room-1"), ("overview", None)]


def test_a_query_string_does_not_break_matching() -> None:
    assert route("GET", "/stats/overview?window=1h", StubReader())[0] == 200


def test_it_is_read_only() -> None:
    # Read-only is structural, not a branch: the handler defines `do_GET` and nothing else, so the
    # stdlib answers 501 to any other verb without the router being consulted. What this asserts is
    # the part that could regress — that no route reaches a writer.
    import app

    handler = app.make_handler(StubReader(), lambda: (b"", "text/plain"), lambda *a, **k: None)
    assert not [name for name in dir(handler) if name.startswith("do_") and name != "do_GET"]


def test_unknown_path_returns_404() -> None:
    status, body = route("GET", "/nope", StubReader())
    assert status == 404
    assert body["error"] == "not_found"


def test_the_metric_label_is_bounded() -> None:
    # An id in a metric label lets anyone grow the cardinality by inventing paths.
    from app import surface

    assert surface("/stats/players/alice") == "/stats/players/:id"
    assert surface("/stats/rooms/1c1b0b7e-0000-4000-8000-000000000000") == "/stats/rooms/:id"
    assert surface("/stats/overview") == "/stats/overview"
    assert surface("/whatever") == "unknown"
