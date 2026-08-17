"""Applying an event's effects: the `consumed_events` insert and every projection write, in ONE
transaction.

That is the whole idempotency argument. Delivery is at-least-once for real — the relay publishes
before it marks a row, so a crash redelivers — and these projections are counters, which double on a
replay unless the record of having seen the event lands or fails with them.
"""

from __future__ import annotations

import json
from typing import Any

from project import Effects, plan, plan_tournament

TOURNAMENT_TOPIC = "tournament.lifecycle.events"


class Store:
    def __init__(self, connection: Any) -> None:
        self.connection = connection

    def consume(self, source: str, event_key: str, event_type: str, body: dict[str, Any]) -> str:
        """Returns `duplicate`, `ignored` or `projected`."""
        # Which projection an event feeds is decided by the topic it arrived on, not by guessing
        # from its shape: the room and tournament catalogs are separate, and a name appearing in
        # both later should not silently pick a branch.
        planner = plan_tournament if source == TOURNAMENT_TOPIC else plan
        effects = planner(event_type, body)
        room_id = body.get("roomId") if source != TOURNAMENT_TOPIC else None

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
            self._apply_tournament(cursor, effects)
            self._apply_rounds(cursor, effects)
            self._apply_rooms(cursor, effects)
            self._apply_placements(cursor, effects)
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

    @staticmethod
    def _apply_tournament(cursor: Any, effects: Effects) -> None:
        """The bracket's header row. Forward-only status, `coalesce` for everything else.

        Every field is a fact the event stated, so applying the same event twice writes the same
        value — no counter to double, which is why this stays an atomic upsert and why this service
        would scale where ranking would not.
        """
        facts = effects.tournament
        if not facts:
            return
        cursor.execute(
            "insert into tournaments"
            " (tournament_id, status, status_rank, player_count, min_players, room_size,"
            "  round_count, champion, created_at, completed_at, last_event_at)"
            " values (%s, coalesce(%s, 'REGISTRATION'), coalesce(%s, 0), coalesce(%s, 0), %s, %s,"
            "         coalesce(%s, 0), %s, %s, %s, %s)"
            " on conflict (tournament_id) do update set"
            # Forward only: a replayed `TournamentCreated` must not put a finished bracket back
            # into registration.
            "   status = case when excluded.status_rank > tournaments.status_rank"
            "                 then excluded.status else tournaments.status end,"
            "   status_rank = greatest(tournaments.status_rank, excluded.status_rank),"
            "   player_count = greatest(tournaments.player_count, excluded.player_count),"
            "   min_players = coalesce(excluded.min_players, tournaments.min_players),"
            "   room_size = coalesce(excluded.room_size, tournaments.room_size),"
            "   round_count = greatest(tournaments.round_count, excluded.round_count),"
            "   champion = coalesce(excluded.champion, tournaments.champion),"
            "   created_at = least(tournaments.created_at, excluded.created_at),"
            "   completed_at = coalesce(excluded.completed_at, tournaments.completed_at),"
            "   last_event_at = greatest(tournaments.last_event_at, excluded.last_event_at)",
            (
                facts["tournament_id"],
                facts.get("status"),
                facts.get("status_rank"),
                facts.get("player_count"),
                facts.get("min_players"),
                facts.get("room_size"),
                facts.get("round_count"),
                facts.get("champion"),
                facts.get("created_at"),
                facts.get("completed_at"),
                facts.get("last_event_at"),
            ),
        )

    @staticmethod
    def _apply_rounds(cursor: Any, effects: Effects) -> None:
        for entry in effects.rounds:
            cursor.execute(
                "insert into tournament_rounds"
                " (tournament_id, round_number, room_count, advancing_total, complete)"
                " values (%s, %s, coalesce(%s, 0), %s, coalesce(%s, false))"
                " on conflict (tournament_id, round_number) do update set"
                "   room_count = greatest(tournament_rounds.room_count, excluded.room_count),"
                "   advancing_total = coalesce(excluded.advancing_total,"
                "                              tournament_rounds.advancing_total),"
                # A completed round never un-completes, whatever order the events arrive in.
                "   complete = tournament_rounds.complete or excluded.complete",
                (
                    entry["tournament_id"],
                    entry["round_number"],
                    entry.get("room_count"),
                    entry.get("advancing_total"),
                    entry.get("complete"),
                ),
            )

    @staticmethod
    def _apply_rooms(cursor: Any, effects: Effects) -> None:
        """`RoundStarted` names the players, `RoomResultRecorded` the advancers, `FinalRoomCreated`
        the final — three events writing one row, in any order, each filling only its own column."""
        for entry in effects.rooms:
            if not entry.get("room_id"):
                continue
            cursor.execute(
                "insert into tournament_rooms"
                " (room_id, tournament_id, round_number, players, advancing, is_final)"
                " values (%s, %s, %s, coalesce(%s::jsonb, '[]'::jsonb), %s::jsonb,"
                "         coalesce(%s, false))"
                " on conflict (room_id) do update set"
                "   tournament_id = coalesce(excluded.tournament_id,"
                "                            tournament_rooms.tournament_id),"
                "   round_number = greatest(tournament_rooms.round_number, excluded.round_number),"
                # The bigger list wins rather than the later one: an empty default must never erase
                # an assignment that a differently-ordered replay wrote first.
                "   players = case when jsonb_array_length(excluded.players)"
                "                       > jsonb_array_length(tournament_rooms.players)"
                "                  then excluded.players else tournament_rooms.players end,"
                "   advancing = coalesce(excluded.advancing, tournament_rooms.advancing),"
                "   is_final = tournament_rooms.is_final or excluded.is_final",
                (
                    entry["room_id"],
                    entry.get("tournament_id"),
                    entry.get("round_number") or 0,
                    json.dumps(entry["players"]) if entry.get("players") is not None else None,
                    json.dumps(entry["advancing"]) if entry.get("advancing") is not None else None,
                    entry.get("is_final"),
                ),
            )

    @staticmethod
    def _apply_placements(cursor: Any, effects: Effects) -> None:
        for entry in effects.placements:
            cursor.execute(
                "insert into tournament_placements (tournament_id, player_id, placement)"
                " values (%s, %s, %s)"
                " on conflict (tournament_id, player_id) do update set"
                "   placement = excluded.placement",
                (entry["tournament_id"], entry["player_id"], entry["placement"]),
            )
