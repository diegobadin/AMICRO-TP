from app import route


def test_health_returns_ok_with_service_name() -> None:
    status, body = route("GET", "/health")
    assert status == 200
    assert body == {"status": "ok", "service": "ranking"}


def test_sample_rating_returns_canned_elo() -> None:
    status, body = route("GET", "/players/sample/rating")
    assert status == 200
    assert body == {"elo": 1000}


def test_unknown_path_returns_404() -> None:
    status, body = route("GET", "/nope")
    assert status == 404
    assert body["error"] == "not_found"
