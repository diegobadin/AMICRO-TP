"""UnoArena ranking — the read side. Routing stays a pure function of (method, path, store).

`/health` reports that the process is alive and nothing else. A liveness probe wired to a dependency
turns that dependency's outage into a restart loop, which is the opposite of what a service with a
retrying consumer should do (CHANGELOG-design.md §10.11). Whether the database and the broker are
answering is on `/metrics`, where an alert can read it without killing a pod.
"""

from __future__ import annotations

import json
import re
from http.server import BaseHTTPRequestHandler
from typing import Any
from urllib.parse import parse_qs, urlsplit

from metrics import SERVICE, log_line

RATING = re.compile(r"^/players/([^/]+)/rating$")
HISTORY = re.compile(r"^/players/([^/]+)/rating-history$")

DEFAULT_LIMIT = 20
MAX_LIMIT = 100


def _limit(query: dict[str, list[str]]) -> int:
    raw = query.get("limit", [])
    if not raw:
        return DEFAULT_LIMIT
    try:
        value = int(raw[0])
    except ValueError:
        return DEFAULT_LIMIT
    return max(1, min(MAX_LIMIT, value))


def route(method: str, path: str, store: Any) -> tuple[int, dict[str, Any]]:
    """Pure router: map (path, store) to (status_code, body).

    `method` is always `GET`: the handler below defines `do_GET` and nothing else, so the stdlib
    answers `501` to every other verb before this function is reached. Ranking is a read model, and
    that is enforced by what exists rather than by a branch here.
    """
    parts = urlsplit(path)
    query = parse_qs(parts.query)

    if parts.path == "/health":
        return 200, {"status": "ok", "service": SERVICE}

    match = RATING.match(parts.path)
    if match:
        return 200, store.rating(match.group(1))

    match = HISTORY.match(parts.path)
    if match:
        player = match.group(1)
        return 200, {"playerId": player, "changes": store.history(player, _limit(query))}

    if parts.path == "/leaderboard":
        return 200, {"leaderboard": store.leaderboard(_limit(query))}

    return 404, {"error": "not_found", "service": SERVICE}


def make_handler(store: Any, metrics_body: Any) -> type[BaseHTTPRequestHandler]:
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
            try:
                status, body = route("GET", self.path, store)
            except Exception as error:  # noqa: BLE001 — a failed read is a 503, not a dead process
                log_line("error", "read-failed", path=self.path, error=str(error))
                status, body = 503, {"error": "unavailable", "service": SERVICE}
            self._send(status, json.dumps(body).encode("utf-8"), "application/json")
            log_line("info", f"GET {self.path}", status=status, correlationId=correlation_id)

        def _send(self, status: int, payload: bytes, content_type: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    return Handler
