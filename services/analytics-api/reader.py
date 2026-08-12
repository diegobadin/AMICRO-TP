"""A connection and the read queries over it. Nothing here writes."""

from __future__ import annotations

from typing import Any

import queries


class Reader:
    def __init__(self, connection: Any) -> None:
        self.connection = connection

    def player_stats(self, player_id: str) -> dict[str, Any]:
        with self.connection.cursor() as cursor:
            return queries.player_stats(cursor, player_id)

    def room_stats(self, room_id: str) -> dict[str, Any]:
        with self.connection.cursor() as cursor:
            return queries.room_stats(cursor, room_id)

    def overview(self) -> dict[str, Any]:
        with self.connection.cursor() as cursor:
            return queries.overview(cursor)
