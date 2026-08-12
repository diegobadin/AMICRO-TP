"""Entrypoint: connect, then serve the reads.

No migration here. `analytics-workers` owns the schema and is the only writer; this service reading
a table that does not exist yet is a 503 until the writer has migrated, which is the honest answer
and not a reason to crash — the P5 drill found both Go workers doing exactly this correctly.
"""

from __future__ import annotations

import os
import signal
import threading
from http.server import HTTPServer
from types import FrameType

import psycopg
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from app import make_handler
from metrics import log_line
from reader import Reader

DEFAULT_PORT = 8091


def dsn() -> str:
    return (
        f"host={os.environ.get('DATABASE_HOST', 'localhost')}"
        f" port={os.environ.get('DATABASE_PORT', '5432')}"
        f" dbname={os.environ.get('DATABASE_NAME', 'analytics')}"
        f" user={os.environ.get('DATABASE_USER', 'analytics')}"
        f" password={os.environ.get('ANALYTICS_DB_PASSWORD', '')}"
    )


def metrics_body() -> tuple[bytes, str]:
    return generate_latest(), CONTENT_TYPE_LATEST


def main() -> None:
    port = int(os.environ.get("PORT", DEFAULT_PORT))
    # autocommit: every statement here is a SELECT, so a transaction would only hold a snapshot
    # open across requests and keep the reader looking at a database that has moved on.
    connection = psycopg.connect(dsn(), autocommit=True)
    server = HTTPServer(("0.0.0.0", port), make_handler(Reader(connection), metrics_body, log_line))  # noqa: S104

    def stop(_signum: int, _frame: FrameType | None) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    log_line("info", "listen", port=port)
    server.serve_forever()


if __name__ == "__main__":
    main()
