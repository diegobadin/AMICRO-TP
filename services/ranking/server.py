"""Entrypoint: migrate, start the consumer, serve the reads.

One deployable does both halves of architecture §4.2 — the consumer group that applies Elo and the
query API that serves it. They are separate concerns sharing a process, so they get separate
database connections: the consumer's is inside a transaction for most of its life, and a read that
had to wait for it would be a reader blocked by a writer for no reason.
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

DEFAULT_PORT = 8084

_running = True


def dsn() -> str:
    return (
        f"host={os.environ.get('DATABASE_HOST', 'localhost')}"
        f" port={os.environ.get('DATABASE_PORT', '5432')}"
        f" dbname={os.environ.get('DATABASE_NAME', 'ranking')}"
        f" user={os.environ.get('DATABASE_USER', 'ranking')}"
        f" password={os.environ.get('RANKING_DB_PASSWORD', '')}"
    )


def build_consumer() -> Any:
    return Consumer(
        {
            "bootstrap.servers": os.environ.get("KAFKA_BROKERS", "localhost:9092"),
            "group.id": consumer_module.GROUP_ID,
            # Offsets are committed by hand after the transaction lands, so a crash re-delivers
            # instead of skipping. The dedup insert is what makes that safe.
            "enable.auto.commit": False,
            # A consumer group that appears after the topic already has traffic must score the games
            # it missed, not just the ones from now on.
            "auto.offset.reset": "earliest",
        }
    )


def metrics_body() -> tuple[bytes, str]:
    return generate_latest(), CONTENT_TYPE_LATEST


def main() -> None:
    global _running
    port = int(os.environ.get("PORT", DEFAULT_PORT))

    # Failure here exits: a service that cannot create its own schema has nothing to back off
    # toward. The consume loop below is the opposite — it retries forever and never crashes.
    with psycopg.connect(dsn()) as migration_connection:
        migrate(migration_connection)
    log_line("info", "migrated")

    consumer_connection = psycopg.connect(dsn())
    read_connection = psycopg.connect(dsn(), autocommit=True)
    kafka = build_consumer()

    thread = threading.Thread(
        target=consumer_module.run,
        args=(kafka, Store(consumer_connection), lambda: _running),
        daemon=True,
        name="ranking-consumer",
    )
    thread.start()

    server = HTTPServer(("0.0.0.0", port), make_handler(Store(read_connection), metrics_body))  # noqa: S104

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
