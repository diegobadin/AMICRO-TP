"""Every database access ranking makes. The consumer's write path is one transaction by design.

`SOURCE` is the topic the events arrive on, and it is the `source` half of `consumed_events`'
primary key — the same shape room-gameplay uses for its own consumer, so two sources can carry the
same event key without colliding.
"""

from __future__ import annotations

from typing import Any

import placement
from elo import INITIAL_RATING, deltas

SOURCE = "room.lifecycle.events"
# P7's second stream. The `source` half of the `consumed_events` key is what keeps the two apart:
# a room's `ce-id` and a tournament's could collide and it would still not matter.
TOURNAMENT_SOURCE = "tournament.lifecycle.events"


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
            before = self._lock_ratings(cursor, players)["rating"]
            for player, rating_before, delta in zip(players, before, deltas(before), strict=True):
                rating_after = rating_before + delta
                cursor.execute(
                    "update player_ratings"
                    " set rating = %s, games = games + 1, updated_at = now()"
                    " where player_id = %s",
                    (rating_after, player),
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

    def consume_tournament_completed(
        self, event_key: str, body: dict[str, Any], apply: bool
    ) -> str:
        """P7: the placement rating from `TournamentCompleted`. One transaction, same as Elo.

        Elo is untouched here, in either direction: §4.5 makes it casual-only, and a tournament
        moving it would be that rule broken from the other side.
        """
        with self.connection.transaction(), self.connection.cursor() as cursor:
            cursor.execute(
                "insert into consumed_events (source, event_key) values (%s, %s)"
                " on conflict do nothing",
                (TOURNAMENT_SOURCE, event_key),
            )
            if cursor.rowcount == 0:
                return "duplicate"
            if not apply:
                return "skipped"

            standing: list[str] = list(body["finalPlacements"])
            changes = placement.placements_of(standing)
            before = self._lock_ratings(cursor, standing)["placement_rating"]
            for (player, position, delta), rating_before in zip(changes, before, strict=True):
                rating_after = rating_before + delta
                cursor.execute(
                    "update player_ratings"
                    " set placement_rating = %s, tournaments = tournaments + 1, updated_at = now()"
                    " where player_id = %s",
                    (rating_after, player),
                )
                cursor.execute(
                    "insert into placement_changes"
                    " (player_id, tournament_id, placement, field_size, rating_before,"
                    "  rating_after, delta, at)"
                    " values (%s, %s, %s, %s, %s, %s, %s, %s)",
                    (
                        player,
                        body["tournamentId"],
                        position,
                        len(standing),
                        rating_before,
                        rating_after,
                        delta,
                        body["at"],
                    ),
                )
            return "applied"

    @staticmethod
    def _lock_ratings(cursor: Any, players: list[str]) -> dict[str, list[int]]:
        """Every player's current ratings, with their rows locked for the rest of the transaction.

        This is what makes applying a rating safe under concurrency, and it is why ranking's replica
        count stopped being a trap in P7. Read-modify-write at READ COMMITTED does not stop a second
        consumer reading the same rating before either commits, so two games finishing at once for
        one player would lose an update — silently, and for ever, because nothing recomputes a
        rating from the log.

        Two details carry the weight:
          * the rows are created first, because `for update` cannot lock a row that does not exist
            and a newcomer has none — the classic gap in a naive `select ... for update`;
          * they are locked in a deterministic order, so two transactions holding overlapping player
            sets queue up instead of deadlocking.

        Returns the values in the caller's order, which is the order the deltas are computed in.
        """
        if not players:
            return {"rating": [], "placement_rating": []}
        ordered = sorted(set(players))
        cursor.executemany(
            "insert into player_ratings (player_id, rating, placement_rating)"
            " values (%s, %s, %s) on conflict do nothing",
            [(player, INITIAL_RATING, placement.INITIAL_PLACEMENT_RATING) for player in ordered],
        )
        cursor.execute(
            "select player_id, rating, placement_rating from player_ratings"
            " where player_id = any(%s) order by player_id for update",
            (ordered,),
        )
        found = {row[0]: (int(row[1]), int(row[2])) for row in cursor.fetchall()}
        return {
            "rating": [found[player][0] for player in players],
            "placement_rating": [found[player][1] for player in players],
        }

    # Reads. A player ranking has never seen is not an error: ranking does not own the player
    # registry (identity does) and could not tell a typo from a newcomer. It answers with the
    # rating everybody starts at, and `games: 0` says which of the two this is.
    def rating(self, player: str) -> dict[str, Any]:
        with self.connection.cursor() as cursor:
            cursor.execute(
                "select rating, games, placement_rating, tournaments from player_ratings"
                " where player_id = %s",
                (player,),
            )
            row = cursor.fetchone()
        if row is None:
            return {
                "playerId": player,
                "rating": INITIAL_RATING,
                "games": 0,
                "placementRating": placement.INITIAL_PLACEMENT_RATING,
                "tournaments": 0,
            }
        return {
            "playerId": player,
            "rating": int(row[0]),
            "games": int(row[1]),
            # Two ratings, never one blended number: they answer different questions and §4.5 keeps
            # them apart on purpose.
            "placementRating": int(row[2]),
            "tournaments": int(row[3]),
        }

    def history(self, player: str, limit: int) -> list[dict[str, Any]]:
        """Both kinds of change, newest first. `kind` is what tells them apart.

        A player asking "why is my rating what it is" does not care which table an answer lives in,
        but they do care which rating moved — so the discriminator is explicit rather than inferred
        from which fields happen to be null.
        """
        with self.connection.cursor() as cursor:
            cursor.execute(
                "select 'elo' as kind, room_id::text, game_number, rating_before, rating_after,"
                "       delta, card_points, at"
                " from rating_changes where player_id = %s"
                " union all"
                " select 'placement', tournament_id::text, placement, rating_before, rating_after,"
                "        delta, field_size, at"
                " from placement_changes where player_id = %s"
                " order by at desc limit %s",
                (player, player, limit),
            )
            rows = cursor.fetchall()
        return [
            {
                "kind": row[0],
                "roomId": row[1],
                "gameNumber": row[2],
                "ratingBefore": row[3],
                "ratingAfter": row[4],
                "delta": row[5],
                "cardPoints": row[6],
                "at": row[7].isoformat(),
            }
            if row[0] == "elo"
            else {
                "kind": row[0],
                "tournamentId": row[1],
                "placement": row[2],
                "ratingBefore": row[3],
                "ratingAfter": row[4],
                "delta": row[5],
                "fieldSize": row[6],
                "at": row[7].isoformat(),
            }
            for row in rows
        ]

    def leaderboard(self, limit: int) -> list[dict[str, Any]]:
        with self.connection.cursor() as cursor:
            cursor.execute(
                # `games > 0` keeps this the Elo board it has always been. Until P7 a row only
                # existed once Elo had been applied, so the filter was implicit; now a player who
                # has only ever played a tournament has a row too, and 1000 after no games is not
                # a ranking, it is a default.
                "select player_id, rating, games from player_ratings where games > 0"
                " order by rating desc, player_id asc limit %s",
                (limit,),
            )
            rows = cursor.fetchall()
        return [
            {"rank": i + 1, "playerId": row[0], "rating": int(row[1]), "games": int(row[2])}
            for i, row in enumerate(rows)
        ]
