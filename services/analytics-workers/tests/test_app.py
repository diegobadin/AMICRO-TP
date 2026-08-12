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


def test_this_worker_serves_no_reads() -> None:
    # The query side is analytics-api. A projection worker that also answered /stats would be two
    # services in one process, which is the split architecture §7.2 exists to make.
    for path in ("/stats/overview", "/stats/players/alice", "/stats/rooms/r"):
        assert route("GET", path)[0] == 404
