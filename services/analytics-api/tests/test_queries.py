"""The read side, against a real Postgres carrying the WRITE side's own schema.

This is the seam of the CQRS split. The schema is imported from `analytics-workers` — off the
checkout, not out of the image — so a column renamed by the writer turns this service red. It is the
same trick the GameCompleted contract check plays between a producer and its consumers, and it is
the reason `queries.py` living here is duplication of a file rather than of a rule.

The runtime image never needs `analytics-workers`; only the test does. Without TEST_DATABASE_URL
these fail rather than skip.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any

import psycopg
import pytest

import queries

WRITER = Path(__file__).resolve().parents[2] / "analytics-workers"
sys.path.insert(0, str(WRITER))

from schema import migrate  # noqa: E402 — the path has to be set before this import can resolve

ROOM = "1c1b0b7e-0000-4000-8000-000000000000"


@pytest.fixture()
def cursor() -> Any:
    url = os.environ.get("TEST_DATABASE_URL")
    assert url, "TEST_DATABASE_URL is required — this suite proves nothing without a database"
    connection = psycopg.connect(url, autocommit=True)
    migrate(connection)
    with connection.cursor() as cur:
        cur.execute(
            "truncate player_stats, room_games, room_activity, overview,"
            " tournaments, tournament_rounds, tournament_rooms, tournament_placements"
        )
    migrate(connection)  # re-seed the overview rows the truncate removed
    with connection.cursor() as cur:
        yield cur
    connection.close()


def seed(cursor: Any) -> None:
    cursor.execute(
        "insert into player_stats"
        " (player_id, games_played, games_won, games_abandoned, total_card_points, last_played_at)"
        " values ('alice', 3, 2, 0, 12, '2026-08-12T12:05:00+00:00')"
    )
    cursor.execute(
        "insert into room_activity"
        " (room_id, room_type, status, status_rank, players_seen, cards_played, cards_drawn,"
        "  events_seen, first_event_at, last_event_at)"
        " values (%s, 'CASUAL', 'COMPLETED', 2, 2, 14, 6, 22,"
        "         '2026-08-12T12:00:00+00:00', '2026-08-12T12:05:00+00:00')",
        (ROOM,),
    )
    cursor.execute(
        "insert into room_games"
        " (room_id, game_number, room_type, is_abandoned, finishing_order, card_point_totals,"
        "  completed_at)"
        " values (%s, 1, 'CASUAL', false, %s, %s, '2026-08-12T12:05:00+00:00')",
        (ROOM, json.dumps(["alice", "bob"]), json.dumps({"alice": 0, "bob": 17})),
    )
    cursor.execute("update overview set value = 7 where metric = 'games_completed'")


def test_player_stats_reads_what_the_writer_wrote(cursor: Any) -> None:
    seed(cursor)
    stats = queries.player_stats(cursor, "alice")
    assert stats["gamesPlayed"] == 3
    assert stats["gamesWon"] == 2
    assert stats["totalCardPoints"] == 12
    assert stats["lastPlayedAt"].startswith("2026-08-12T12:05")


def test_an_unseen_player_is_zeroes_and_not_an_error(cursor: Any) -> None:
    stats = queries.player_stats(cursor, "nobody")
    assert stats == {
        "playerId": "nobody",
        "gamesPlayed": 0,
        "gamesWon": 0,
        "gamesAbandoned": 0,
        "totalCardPoints": 0,
        "lastPlayedAt": None,
    }


def test_room_stats_returns_activity_and_its_games(cursor: Any) -> None:
    seed(cursor)
    stats = queries.room_stats(cursor, ROOM)
    assert stats["activity"]["status"] == "COMPLETED"
    assert stats["activity"]["cardsPlayed"] == 14
    assert len(stats["games"]) == 1
    assert stats["games"][0]["finishingOrder"] == ["alice", "bob"]
    assert stats["games"][0]["cardPointTotals"] == {"alice": 0, "bob": 17}


def test_an_unseen_room_has_no_activity_and_no_games(cursor: Any) -> None:
    stats = queries.room_stats(cursor, "2c1b0b7e-0000-4000-8000-000000000000")
    assert stats["activity"] is None
    assert stats["games"] == []


def test_overview_answers_with_zeroes_from_an_empty_cluster(cursor: Any) -> None:
    # Every metric is seeded at 0 by the migration, so a dashboard panel shows a zero rather than
    # vanishing — a missing series and a broken query look identical on a graph.
    counts = queries.overview(cursor)
    assert counts["games_completed"] == 0
    assert counts["rooms_created"] == 0
    assert "cards_played" in counts


def test_overview_reflects_the_writer(cursor: Any) -> None:
    seed(cursor)
    assert queries.overview(cursor)["games_completed"] == 7


# ---------------------------------------------------------------- P7: the bracket

TOURNEY = "3c3c0b7e-0000-4000-8000-000000000000"
ROOM_A = "4a4a0b7e-0000-4000-8000-000000000001"
ROOM_F = "4a4a0b7e-0000-4000-8000-000000000003"


def seed_bracket(cursor: Any) -> None:
    """Written the way the writer writes it, into the writer's own tables. If a column is renamed
    on that side this fails to insert, which is the whole point of building the schema from
    `analytics-workers/schema.py` rather than from a copy kept here."""
    cursor.execute(
        "insert into tournaments (tournament_id, status, status_rank, player_count, round_count,"
        " champion, created_at, completed_at)"
        " values (%s, 'COMPLETED', 2, 4, 2, 'carol', now(), now())",
        (TOURNEY,),
    )
    cursor.execute(
        "insert into tournament_rounds (tournament_id, round_number, room_count, advancing_total,"
        " complete) values (%s, 1, 2, 2, true), (%s, 2, 1, 1, true)",
        (TOURNEY, TOURNEY),
    )
    cursor.execute(
        "insert into tournament_rooms (room_id, tournament_id, round_number, players, advancing,"
        " is_final) values (%s, %s, 1, '[\"alice\",\"bob\"]'::jsonb, '[\"alice\"]'::jsonb, false),"
        " (%s, %s, 2, '[\"alice\",\"carol\"]'::jsonb, '[\"carol\"]'::jsonb, true)",
        (ROOM_A, TOURNEY, ROOM_F, TOURNEY),
    )
    cursor.execute(
        "insert into tournament_placements (tournament_id, player_id, placement)"
        " values (%s, 'carol', 1), (%s, 'alice', 2)",
        (TOURNEY, TOURNEY),
    )


def test_a_bracket_reads_round_by_round(cursor: Any) -> None:
    seed_bracket(cursor)
    bracket = queries.bracket(cursor, TOURNEY)

    assert bracket["champion"] == "carol"
    assert [entry["roundNumber"] for entry in bracket["rounds"]] == [1, 2]
    assert bracket["rounds"][0]["rooms"][0]["players"] == ["alice", "bob"]
    assert bracket["rounds"][1]["rooms"][0]["isFinal"] is True
    assert [p["playerId"] for p in bracket["placements"]] == ["carol", "alice"]


def test_a_tournament_analytics_has_never_seen_is_an_empty_bracket(cursor: Any) -> None:
    bracket = queries.bracket(cursor, "9c9c0b7e-0000-4000-8000-000000000000")
    assert bracket["status"] is None
    assert bracket["rounds"] == []
    assert bracket["placements"] == []


def test_the_tournament_list_is_newest_first(cursor: Any) -> None:
    seed_bracket(cursor)
    listed = queries.tournaments(cursor, 10)
    assert [entry["tournamentId"] for entry in listed] == [TOURNEY]
    assert listed[0]["status"] == "COMPLETED"
