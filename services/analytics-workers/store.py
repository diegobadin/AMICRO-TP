"""Applying an event's effects: the `consumed_events` insert and every projection write, in ONE
transaction.

That is the whole idempotency argument. Delivery is at-least-once for real — the relay publishes
before it marks a row, so a crash redelivers — and these projections are counters, which double on a
replay unless the record of having seen the event lands or fails with them.
"""

from __future__ import annotations

import json
from typing import Any

from project import Effects, plan


class Store:
    def __init__(self, connection: Any) -> None:
        self.connection = connection

    def consume(self, source: str, event_key: str, event_type: str, body: dict[str, Any]) -> str:
        """Returns `duplicate`, `ignored` or `projected`."""
        effects = plan(event_type, body)
        room_id = body.get("roomId")

        with self.connection.transaction(), self.connection.cursor() as cursor:
            cursor.execute(
                "insert into consumed_events (source, event_key) values (%s, %s)"
                " on conflict do nothing",
                (source, event_key),
            )
            if cursor.rowcount == 0:
                return "duplicate"

            self._apply_overview(cursor, effects)
            if room_id:
                self._apply_activity(cursor, room_id, effects)
            self._apply_game(cursor, effects)
            self._apply_players(cursor, effects)
            return "projected"

    @staticmethod
    def _apply_overview(cursor: Any, effects: Effects) -> None:
        for metric, increment in effects.overview.items():
            cursor.execute(
                "insert into overview (metric, value) values (%s, %s)"
                " on conflict (metric) do update set value = overview.value + excluded.value",
                (metric, increment),
            )

    @staticmethod
    def _apply_activity(cursor: Any, room_id: str, effects: Effects) -> None:
        counters = effects.activity_counters
        facts = effects.activity
        cursor.execute(
            "insert into room_activity"
            " (room_id, room_type, status, status_rank, players_seen, cards_played, cards_drawn,"
            "  events_seen, first_event_at, last_event_at)"
            " values (%s, %s, coalesce(%s, 'WAITING'), coalesce(%s, 0), %s, %s, %s, %s, %s, %s)"
            " on conflict (room_id) do update set"
            "   room_type = coalesce(excluded.room_type, room_activity.room_type),"
            # Forward only. A late `GameStarted` after a `GameCompleted` must not reopen the room —
            # the same cross-topic interleaving the spectator's dedup set exists for.
            "   status = case when excluded.status_rank > room_activity.status_rank"
            "                 then excluded.status else room_activity.status end,"
            "   status_rank = greatest(room_activity.status_rank, excluded.status_rank),"
            "   players_seen = room_activity.players_seen + excluded.players_seen,"
            "   cards_played = room_activity.cards_played + excluded.cards_played,"
            "   cards_drawn = room_activity.cards_drawn + excluded.cards_drawn,"
            "   events_seen = room_activity.events_seen + excluded.events_seen,"
            # least/greatest ignore NULLs, so the window is right whatever order the events land in.
            "   first_event_at = least(room_activity.first_event_at, excluded.first_event_at),"
            "   last_event_at = greatest(room_activity.last_event_at, excluded.last_event_at)",
            (
                room_id,
                facts.get("room_type"),
                facts.get("status"),
                facts.get("status_rank"),
                counters.get("players_seen", 0),
                counters.get("cards_played", 0),
                counters.get("cards_drawn", 0),
                counters.get("events_seen", 0),
                facts.get("first_event_at") or facts.get("last_event_at"),
                facts.get("last_event_at"),
            ),
        )

    @staticmethod
    def _apply_game(cursor: Any, effects: Effects) -> None:
        game = effects.game
        if not game:
            return
        cursor.execute(
            "insert into room_games"
            " (room_id, game_number, room_type, is_abandoned, finishing_order, card_point_totals,"
            "  completed_at)"
            " values (%s, %s, %s, %s, %s, %s, %s)"
            " on conflict (room_id, game_number) do nothing",
            (
                game["room_id"],
                game["game_number"],
                game["room_type"],
                game["is_abandoned"],
                json.dumps(game["finishing_order"]),
                json.dumps(game["card_point_totals"]),
                game["completed_at"],
            ),
        )

    @staticmethod
    def _apply_players(cursor: Any, effects: Effects) -> None:
        for entry in effects.players:
            cursor.execute(
                "insert into player_stats"
                " (player_id, games_played, games_won, games_abandoned, total_card_points,"
                "  last_played_at)"
                " values (%s, 1, %s, %s, %s, %s)"
                " on conflict (player_id) do update set"
                "   games_played = player_stats.games_played + 1,"
                "   games_won = player_stats.games_won + excluded.games_won,"
                "   games_abandoned = player_stats.games_abandoned + excluded.games_abandoned,"
                "   total_card_points = player_stats.total_card_points"
                "                       + excluded.total_card_points,"
                "   last_played_at = greatest(player_stats.last_played_at,"
                "                             excluded.last_played_at)",
                (
                    entry["player_id"],
                    1 if entry["won"] else 0,
                    1 if entry["abandoned"] else 0,
                    entry["card_points"],
                    entry["at"],
                ),
            )
