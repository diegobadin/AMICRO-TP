"""UnoArena analytics projection workers.

This deployable has no read API — that is `analytics-api`, the query side of the same CQRS split
(architecture §7.2). What it exposes over HTTP is what a worker needs to be operable: `/health` for
the kubelet and `/metrics` for Prometheus.

`/health` reports that the process is alive and nothing else. A liveness probe wired to Kafka or
Postgres turns their outage into a restart loop, which is the opposite of what a consumer with its
own retry should do (CHANGELOG-design.md §10.11). Whether they answer is on `/metrics`.
"""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler
from typing import Any
from urllib.parse import urlsplit

from metrics import SERVICE, log_line


def route(method: str, path: str) -> tuple[int, dict[str, Any]]:
    """Pure router: map (method, path) to (status_code, body).

    Worker — only /health is served; everything else is a 404.
    """
    if method == "GET" and urlsplit(path).path == "/health":
        return 200, {"status": "ok", "service": SERVICE}
    return 404, {"error": "not_found", "service": SERVICE}


def make_handler(metrics_body: Any) -> type[BaseHTTPRequestHandler]:
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
            status, payload = route("GET", self.path)
            self._send(status, json.dumps(payload).encode("utf-8"), "application/json")
            # Not the kubelet's probes: they arrive every few seconds and would drown the lines
            # that matter. A health probe is not an event worth a log record.
            if urlsplit(self.path).path != "/health":
                log_line("info", f"GET {self.path}", status=status, correlationId=correlation_id)

        def _send(self, status: int, payload: bytes, content_type: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    return Handler


__all__ = ["SERVICE", "make_handler", "route"]
