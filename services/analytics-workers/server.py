"""Entrypoint: migrate, start the projections, serve /health and /metrics.

The consumer runs in a thread beside the HTTP server, as ranking's does. Migration failure exits —
this service owns its schema, and there is nothing to back off toward when it cannot create it. The
consume loop is the opposite: it retries forever and never takes the process down.
"""

from __future__ import annotations

import os
import signal
import threading
from http.server import HTTPServer
from types import FrameType
from typing import Any

import psycopg
from confluent_kafka import Consumer
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

import consumer as consumer_module
from app import make_handler
from metrics import log_line
from schema import migrate
from store import Store

DEFAULT_PORT = 8090

_running = True


def dsn() -> str:
    return (
        f"host={os.environ.get('DATABASE_HOST', 'localhost')}"
        f" port={os.environ.get('DATABASE_PORT', '5432')}"
        f" dbname={os.environ.get('DATABASE_NAME', 'analytics')}"
        f" user={os.environ.get('DATABASE_USER', 'analytics')}"
        f" password={os.environ.get('ANALYTICS_DB_PASSWORD', '')}"
    )


def build_consumer() -> Any:
    return Consumer(
        {
            "bootstrap.servers": os.environ.get("KAFKA_BROKERS", "localhost:9092"),
            "group.id": consumer_module.GROUP_ID,
            # Committed by hand after the transaction lands, so a crash re-delivers rather than
            # skipping. The dedup insert is what makes that safe.
            "enable.auto.commit": False,
            # A projection that appears after the topic already has traffic has to catch up on the
            # history, not start counting from now.
            "auto.offset.reset": "earliest",
        }
    )


def metrics_body() -> tuple[bytes, str]:
    return generate_latest(), CONTENT_TYPE_LATEST


def main() -> None:
    global _running
    port = int(os.environ.get("PORT", DEFAULT_PORT))

    with psycopg.connect(dsn()) as migration_connection:
        migrate(migration_connection)
    log_line("info", "migrated")

    projection_connection = psycopg.connect(dsn())
    kafka = build_consumer()

    thread = threading.Thread(
        target=consumer_module.run,
        args=(kafka, Store(projection_connection), lambda: _running),
        daemon=True,
        name="analytics-projections",
    )
    thread.start()

    server = HTTPServer(("0.0.0.0", port), make_handler(metrics_body))  # noqa: S104

    def stop(_signum: int, _frame: FrameType | None) -> None:
        global _running
        _running = False
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    log_line("info", "listen", port=port)
    server.serve_forever()
    thread.join(timeout=10)
    kafka.close()


if __name__ == "__main__":
    main()
