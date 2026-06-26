from app import route


def test_health_returns_ok_with_service_name() -> None:
    status, body = route("GET", "/health")
    assert status == 200
    assert body == {"status": "ok", "service": "analytics-workers"}


def test_unknown_path_returns_404() -> None:
    status, body = route("GET", "/anything")
    assert status == 404
    assert body["error"] == "not_found"
    assert body["service"] == "analytics-workers"
