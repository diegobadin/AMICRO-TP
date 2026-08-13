"""Entrypoint: serve the reads. It opens no connection here — see `reader.py`.

No migration either. `analytics-workers` owns the schema and is the only writer; this service
reading a table that does not exist yet is a 503 until the writer has migrated, which is the honest
answer and not a reason to crash — the P5 drill found both Go workers doing exactly this correctly.
"""

from __future__ import annotations

import os
import signal
import threading
from http.server import HTTPServer
from types import FrameType

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
    # The Reader connects on the first read and reconnects after a failure — never here. Connecting
    # at startup would crash-loop this pod for as long as Postgres takes to come up, which on an
    # empty cluster is exactly as long as the service that owns the schema takes to migrate it.
    server = HTTPServer(("0.0.0.0", port), make_handler(Reader(dsn), metrics_body, log_line))  # noqa: S104

    def stop(_signum: int, _frame: FrameType | None) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    log_line("info", "listen", port=port)
    server.serve_forever()


if __name__ == "__main__":
    main()
