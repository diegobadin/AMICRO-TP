from app import route


def test_health_returns_ok_with_service_name() -> None:
    status, body = route("GET", "/health")
    assert status == 200
    assert body == {"status": "ok", "service": "analytics-api"}


def test_sample_bracket_returns_canned_body() -> None:
    status, body = route("GET", "/tournaments/sample/bracket")
    assert status == 200
    assert body == {"bracket": []}


def test_unknown_path_returns_404() -> None:
    status, body = route("GET", "/nope")
    assert status == 404
    assert body["error"] == "not_found"
