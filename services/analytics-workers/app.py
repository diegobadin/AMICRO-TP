"""UnoArena analytics-workers — placeholder worker (canned responses only, no real logic).

Routing is a pure function so it is trivially unit-testable without a running server.
This is a worker: it only exposes a health endpoint.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler
from typing import Any

SERVICE = "analytics-workers"


def route(method: str, path: str) -> tuple[int, dict[str, Any]]:
    """Pure router: map (method, path) to (status_code, body).

    Worker — only /health is served; everything else is a 404.
    """
    if method == "GET" and path == "/health":
        return 200, {"status": "ok", "service": SERVICE}
    return 404, {"error": "not_found", "service": SERVICE}


def _log(action: str, status: int, correlation_id: str) -> None:
    line = {
        "ts": datetime.now(timezone.utc).isoformat(),
        "level": "info",
        "service": SERVICE,
        "action": action,
        "status": status,
        "correlationId": correlation_id,
    }
    print(json.dumps(line), flush=True)


def make_handler() -> type[BaseHTTPRequestHandler]:
    """Build a BaseHTTPRequestHandler subclass wired to the pure router."""

    class Handler(BaseHTTPRequestHandler):
        # Silence the default stderr access log; we emit our own JSON log line.
        def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
            return

        def do_GET(self) -> None:  # noqa: N802
            correlation_id = self.headers.get("X-Correlation-Id", "")
            status, body = route("GET", self.path)
            payload = json.dumps(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            _log(f"GET {self.path}", status, correlation_id)

    return Handler


__all__ = ["SERVICE", "make_handler", "route"]
