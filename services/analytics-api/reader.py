"""A connection and the read queries over it. Nothing here writes.

The connection is opened **lazily and re-opened on failure**, never once at startup. This service
reads a schema it does not own — `analytics-workers` creates it — so the database being absent when
this pod starts is normal cold-start ordering, not a reason to die. The P5 drill found both Go
workers getting this right (0 restarts through exactly that outage); P6's own drill found this file
getting it wrong, crash-looping five times before Postgres was up while the docstring beside it
promised a 503.

A read that cannot reach the database is a 503 on that request. `/health` stays 200 throughout,
because the process is alive — and a liveness probe wired to a dependency turns that dependency's
outage into a restart loop (CHANGELOG-design.md §10.11).
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

import psycopg

import queries


class Reader:
    def __init__(self, dsn: Callable[[], str]) -> None:
        self._dsn = dsn
        self._connection: Any = None

    def _connect(self) -> Any:
        if self._connection is None or self._connection.closed:
            self._connection = psycopg.connect(self._dsn(), autocommit=True)
        return self._connection

    def _read(self, query: Callable[[Any], dict[str, Any]]) -> dict[str, Any]:
        try:
            with self._connect().cursor() as cursor:
                result: dict[str, Any] = query(cursor)
                return result
        except psycopg.Error:
            # Drop it so the next request reconnects instead of reusing a socket the server has
            # already given up on. A pod that survives a database restart only by being restarted
            # itself is the failure this class exists to avoid.
            if self._connection is not None:
                try:
                    self._connection.close()
                finally:
                    self._connection = None
            raise

    def player_stats(self, player_id: str) -> dict[str, Any]:
        return self._read(lambda cursor: queries.player_stats(cursor, player_id))

    def room_stats(self, room_id: str) -> dict[str, Any]:
        return self._read(lambda cursor: queries.room_stats(cursor, room_id))

    def overview(self) -> dict[str, Any]:
        return self._read(queries.overview)

    def bracket(self, tournament_id: str) -> dict[str, Any]:
        return self._read(lambda cursor: queries.bracket(cursor, tournament_id))

    def tournaments(self, limit: int) -> dict[str, Any]:
        return self._read(lambda cursor: {"tournaments": queries.tournaments(cursor, limit)})
