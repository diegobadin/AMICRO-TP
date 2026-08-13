"""UnoArena analytics-api — the read side of the analytics CQRS split (architecture §7.2).

Read-only by construction: there is no verb but GET in this router and no statement but SELECT in
`queries.py`. `analytics-workers` owns the schema and is the only writer.

`/health` reports that the process is alive and nothing else — a liveness probe wired to the
database turns its outage into a restart loop (CHANGELOG-design.md §10.11). A read that cannot reach
Postgres is a 503 on that request, not a dead pod.
"""

from __future__ import annotations

import json
import re
from http.server import BaseHTTPRequestHandler
from typing import Any
from urllib.parse import urlsplit

import metrics

SERVICE = "analytics-api"

PLAYER = re.compile(r"^/stats/players/([^/]+)$")
ROOM = re.compile(r"^/stats/rooms/([^/]+)$")


def surface(path: str) -> str:
    """A bounded metric label. A raw URL would let anyone grow cardinality by inventing ids."""
    pathname = urlsplit(path).path
    if PLAYER.match(pathname):
        return "/stats/players/:id"
    if ROOM.match(pathname):
        return "/stats/rooms/:id"
    if pathname in ("/stats/overview", "/health", "/metrics"):
        return pathname
    return "unknown"


def route(method: str, path: str, reader: Any) -> tuple[int, dict[str, Any]]:
    """Pure router: map (path, reader) to (status_code, body).

    `method` is carried for symmetry with the other services and is always `GET`: the handler below
    defines `do_GET` and nothing else, so the stdlib answers `501` to every other verb before this
    function is reached. Read-only is a property of what exists, not of a branch here.
    """
    pathname = urlsplit(path).path
    if pathname == "/health":
        return 200, {"status": "ok", "service": SERVICE}

    match = PLAYER.match(pathname)
    if match:
        return 200, reader.player_stats(match.group(1))

    match = ROOM.match(pathname)
    if match:
        return 200, reader.room_stats(match.group(1))

    if pathname == "/stats/overview":
        return 200, {"overview": reader.overview()}

    return 404, {"error": "not_found", "service": SERVICE}


def make_handler(reader: Any, metrics_body: Any, log_line: Any) -> type[BaseHTTPRequestHandler]:
    """Build a BaseHTTPRequestHandler subclass wired to the pure router."""

    class Handler(BaseHTTPRequestHandler):
        # Silence the default stderr access log; we emit our own JSON log line.
        def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
            return

        def do_GET(self) -> None:  # noqa: N802
            correlation_id = self.headers.get("X-Correlation-Id", "")
            if urlsplit(self.path).path == "/metrics":
                body, content_type = metrics_body()
                self._send(200, body, content_type)
                return
            label = surface(self.path)
            try:
                status, body = route("GET", self.path, reader)
            except Exception as error:  # noqa: BLE001 — a failed read is a 503, not a dead process
                metrics.read_failures.inc()
                log_line("error", "read-failed", path=self.path, error=str(error))
                status, body = 503, {"error": "unavailable", "service": SERVICE}
            metrics.reads.labels(surface=label, status=str(status)).inc()
            self._send(status, json.dumps(body).encode("utf-8"), "application/json")
            log_line("info", f"GET {self.path}", status=status, correlationId=correlation_id)

        def _send(self, status: int, payload: bytes, content_type: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    return Handler


__all__ = ["SERVICE", "make_handler", "route"]
