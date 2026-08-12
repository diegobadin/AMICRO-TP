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
        cur.execute("truncate player_stats, room_games, room_activity, overview")
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
