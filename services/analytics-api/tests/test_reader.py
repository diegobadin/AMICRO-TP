"""The reader's cold-start posture: it must not need a database to exist yet.

The P6 drill caught this crash-looping five times on an empty cluster while its own docstring
promised a 503 — the "a service that reads a schema it does not own must tolerate its absence"
lesson, arriving for the third time in this repo.
"""

from __future__ import annotations

import psycopg
import pytest

from reader import Reader


def test_constructing_a_reader_opens_no_connection() -> None:
    # If this ever connects eagerly again, this test fails without a database in sight.
    Reader(lambda: "host=127.0.0.1 port=1 dbname=nope user=nope connect_timeout=1")


def test_a_read_against_a_dead_database_raises_rather_than_exits() -> None:
    reader = Reader(lambda: "host=127.0.0.1 port=1 dbname=nope user=nope connect_timeout=1")
    # An exception the handler turns into a 503. The process stays up either way — which is the
    # whole point, and what the crash at startup took away.
    with pytest.raises(psycopg.Error):
        reader.overview()


def test_it_retries_the_connection_on_the_next_read() -> None:
    attempts: list[str] = []

    def dsn() -> str:
        attempts.append("asked")
        return "host=127.0.0.1 port=1 dbname=nope user=nope connect_timeout=1"

    reader = Reader(dsn)
    for _ in range(2):
        with pytest.raises(psycopg.Error):
            reader.overview()
    # Two reads, two connection attempts: a failed connection is dropped rather than cached, so the
    # pod recovers on its own once the database comes back.
    assert len(attempts) == 2
