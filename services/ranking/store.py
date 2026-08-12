"""Every database access ranking makes. The consumer's write path is one transaction by design.

`SOURCE` is the topic the events arrive on, and it is the `source` half of `consumed_events`'
primary key — the same shape room-gameplay uses for its own consumer, so two sources can carry the
same event key without colliding.
"""

from __future__ import annotations

from typing import Any

from elo import INITIAL_RATING, deltas

SOURCE = "room.lifecycle.events"


class Store:
    def __init__(self, connection: Any) -> None:
        self.connection = connection

    def consume_game_completed(self, event_key: str, body: dict[str, Any], apply: bool) -> str:
        """Record the event and, if it passed the filters, move the ratings. One transaction.

        Returns `duplicate`, `skipped` or `applied`. A skipped event is still recorded, so a
        redelivery of a tournament or abandoned game costs one failed insert rather than a re-run of
        the filters against a rating table.
        """
        with self.connection.transaction(), self.connection.cursor() as cursor:
            cursor.execute(
                "insert into consumed_events (source, event_key) values (%s, %s)"
                " on conflict do nothing",
                (SOURCE, event_key),
            )
            if cursor.rowcount == 0:
                return "duplicate"
            if not apply:
                return "skipped"

            players: list[str] = list(body["finishingOrder"])
            card_points: dict[str, int] = body.get("cardPointTotals") or {}
            before = [self._rating_of(cursor, player) for player in players]
            for player, rating_before, delta in zip(players, before, deltas(before), strict=True):
                rating_after = rating_before + delta
                cursor.execute(
                    "insert into player_ratings (player_id, rating, games, updated_at)"
                    " values (%s, %s, 1, now())"
                    " on conflict (player_id) do update"
                    " set rating = excluded.rating,"
                    "     games = player_ratings.games + 1,"
                    "     updated_at = now()",
                    (player, rating_after),
                )
                cursor.execute(
                    "insert into rating_changes"
                    " (player_id, room_id, game_number, rating_before, rating_after, delta,"
                    "  card_points, at)"
                    " values (%s, %s, %s, %s, %s, %s, %s, %s)",
                    (
                        player,
                        body["roomId"],
                        body["gameNumber"],
                        rating_before,
                        rating_after,
                        delta,
                        card_points.get(player),
                        body["completedAt"],
                    ),
                )
            return "applied"

    @staticmethod
    def _rating_of(cursor: Any, player: str) -> int:
        cursor.execute("select rating from player_ratings where player_id = %s", (player,))
        row = cursor.fetchone()
        return INITIAL_RATING if row is None else int(row[0])

    # Reads. A player ranking has never seen is not an error: ranking does not own the player
    # registry (identity does) and could not tell a typo from a newcomer. It answers with the
    # rating everybody starts at, and `games: 0` says which of the two this is.
    def rating(self, player: str) -> dict[str, Any]:
        with self.connection.cursor() as cursor:
            cursor.execute(
                "select rating, games from player_ratings where player_id = %s", (player,)
            )
            row = cursor.fetchone()
        if row is None:
            return {"playerId": player, "rating": INITIAL_RATING, "games": 0}
        return {"playerId": player, "rating": int(row[0]), "games": int(row[1])}

    def history(self, player: str, limit: int) -> list[dict[str, Any]]:
        with self.connection.cursor() as cursor:
            cursor.execute(
                "select room_id, game_number, rating_before, rating_after, delta, card_points, at"
                " from rating_changes where player_id = %s order by id desc limit %s",
                (player, limit),
            )
            rows = cursor.fetchall()
        return [
            {
                "roomId": str(row[0]),
                "gameNumber": row[1],
                "ratingBefore": row[2],
                "ratingAfter": row[3],
                "delta": row[4],
                "cardPoints": row[5],
                "at": row[6].isoformat(),
            }
            for row in rows
        ]

    def leaderboard(self, limit: int) -> list[dict[str, Any]]:
        with self.connection.cursor() as cursor:
            cursor.execute(
                "select player_id, rating, games from player_ratings"
                " order by rating desc, player_id asc limit %s",
                (limit,),
            )
            rows = cursor.fetchall()
        return [
            {"rank": i + 1, "playerId": row[0], "rating": int(row[1]), "games": int(row[2])}
            for i, row in enumerate(rows)
        ]
