from typing import Any

from app import route


class StubStore:
    """Stands in for the database so routing is exercised without sockets or a server."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, Any]] = []

    def rating(self, player: str) -> dict[str, Any]:
        self.calls.append(("rating", player))
        return {"playerId": player, "rating": 1016, "games": 1}

    def history(self, player: str, limit: int) -> list[dict[str, Any]]:
        self.calls.append(("history", (player, limit)))
        return [{"delta": 16}]

    def leaderboard(self, limit: int) -> list[dict[str, Any]]:
        self.calls.append(("leaderboard", limit))
        return [{"rank": 1, "playerId": "alice", "rating": 1016, "games": 1}]


def test_health_returns_ok_with_service_name() -> None:
    status, body = route("GET", "/health", StubStore())
    assert status == 200
    assert body == {"status": "ok", "service": "ranking"}


def test_rating_reads_the_store() -> None:
    store = StubStore()
    status, body = route("GET", "/players/alice/rating", store)
    assert status == 200
    assert body["rating"] == 1016
    assert store.calls == [("rating", "alice")]


def test_history_defaults_and_clamps_the_limit() -> None:
    store = StubStore()
    route("GET", "/players/alice/rating-history", store)
    route("GET", "/players/alice/rating-history?limit=5", store)
    route("GET", "/players/alice/rating-history?limit=9999", store)
    route("GET", "/players/alice/rating-history?limit=nonsense", store)
    assert [call[1][1] for call in store.calls] == [20, 5, 100, 20]


def test_leaderboard_reads_the_store() -> None:
    store = StubStore()
    status, body = route("GET", "/leaderboard?limit=3", store)
    assert status == 200
    assert body["leaderboard"][0]["playerId"] == "alice"
    assert store.calls == [("leaderboard", 3)]


def test_unknown_path_returns_404() -> None:
    status, body = route("GET", "/nope", StubStore())
    assert status == 404
    assert body["error"] == "not_found"


def test_a_write_verb_is_not_a_route() -> None:
    # Ranking is a read model. Nothing here is writable through HTTP.
    status, _ = route("POST", "/players/alice/rating", StubStore())
    assert status == 404
