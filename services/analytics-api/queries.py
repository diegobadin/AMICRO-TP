"""The read queries over the analytics schema.

The CQRS split of architecture §7.2 is two deployables over one schema: `analytics-workers` owns the
DDL and writes it, and this service only ever reads. The queries live here, with the reader, because
kaniko builds each service from its own directory — a shared module would need the build context
solved first, which the P5 handoff explicitly says is not worth it.

What keeps the two in step is not a shared file but a test: `tests/test_queries.py` builds the
schema by importing the WRITER's own `schema.py` off the checkout and then runs these queries
against it. A column renamed on the write side turns this service red, which is the same trick the
GameCompleted contract check plays between a producer and its consumers.
"""

from __future__ import annotations

from typing import Any


def player_stats(cursor: Any, player_id: str) -> dict[str, Any]:
    cursor.execute(
        "select games_played, games_won, games_abandoned, total_card_points, last_played_at"
        " from player_stats where player_id = %s",
        (player_id,),
    )
    row = cursor.fetchone()
    if row is None:
        # Not an error: analytics does not own the player registry and cannot tell a typo from
        # somebody who has not finished a game yet. Zeroes say which of the two this is.
        return {
            "playerId": player_id,
            "gamesPlayed": 0,
            "gamesWon": 0,
            "gamesAbandoned": 0,
            "totalCardPoints": 0,
            "lastPlayedAt": None,
        }
    return {
        "playerId": player_id,
        "gamesPlayed": row[0],
        "gamesWon": row[1],
        "gamesAbandoned": row[2],
        "totalCardPoints": row[3],
        "lastPlayedAt": row[4].isoformat() if row[4] else None,
    }


def room_stats(cursor: Any, room_id: str) -> dict[str, Any]:
    cursor.execute(
        "select room_type, status, players_seen, cards_played, cards_drawn, events_seen,"
        " first_event_at, last_event_at from room_activity where room_id = %s",
        (room_id,),
    )
    row = cursor.fetchone()
    activity = (
        None
        if row is None
        else {
            "roomType": row[0],
            "status": row[1],
            "playersSeen": row[2],
            "cardsPlayed": row[3],
            "cardsDrawn": row[4],
            "eventsSeen": row[5],
            "firstEventAt": row[6].isoformat() if row[6] else None,
            "lastEventAt": row[7].isoformat() if row[7] else None,
        }
    )
    cursor.execute(
        "select game_number, room_type, is_abandoned, finishing_order, card_point_totals,"
        " completed_at from room_games where room_id = %s order by game_number",
        (room_id,),
    )
    games = [
        {
            "gameNumber": game[0],
            "roomType": game[1],
            "isAbandoned": game[2],
            "finishingOrder": game[3],
            "cardPointTotals": game[4],
            "completedAt": game[5].isoformat(),
        }
        for game in cursor.fetchall()
    ]
    return {"roomId": room_id, "activity": activity, "games": games}


def overview(cursor: Any) -> dict[str, Any]:
    cursor.execute("select metric, value from overview order by metric")
    return {row[0]: row[1] for row in cursor.fetchall()}


def tournaments(cursor: Any, limit: int) -> list[dict[str, Any]]:
    """Every bracket this system has seen, newest first."""
    cursor.execute(
        "select tournament_id, status, player_count, round_count, champion, created_at,"
        " completed_at from tournaments order by created_at desc nulls last limit %s",
        (limit,),
    )
    return [
        {
            "tournamentId": str(row[0]),
            "status": row[1],
            "playerCount": row[2],
            "roundCount": row[3],
            "champion": row[4],
            "createdAt": row[5].isoformat() if row[5] else None,
            "completedAt": row[6].isoformat() if row[6] else None,
        }
        for row in cursor.fetchall()
    ]


def bracket(cursor: Any, tournament_id: str) -> dict[str, Any]:
    """The bracket P6 deliberately left unbuilt (D4), now that something writes it.

    Rounds carry their rooms rather than a flat list carrying a round number: a bracket is read
    round by round, and assembling it here means the CLI does not have to group anything.
    """
    cursor.execute(
        "select status, player_count, round_count, champion, created_at, completed_at"
        " from tournaments where tournament_id = %s",
        (tournament_id,),
    )
    header = cursor.fetchone()
    if header is None:
        # Same posture as an unknown player: analytics does not own the tournament registry, so an
        # id it has not projected yet is an empty bracket rather than an error.
        return {"tournamentId": tournament_id, "status": None, "rounds": [], "placements": []}

    cursor.execute(
        "select round_number, room_count, advancing_total, complete from tournament_rounds"
        " where tournament_id = %s order by round_number",
        (tournament_id,),
    )
    rounds = [
        {
            "roundNumber": row[0],
            "roomCount": row[1],
            "advancingTotal": row[2],
            "complete": row[3],
            "rooms": [],
        }
        for row in cursor.fetchall()
    ]
    by_number = {entry["roundNumber"]: entry for entry in rounds}

    cursor.execute(
        "select round_number, room_id, players, advancing, is_final from tournament_rooms"
        " where tournament_id = %s order by round_number, room_id",
        (tournament_id,),
    )
    for row in cursor.fetchall():
        room = {
            "roomId": str(row[1]),
            "players": row[2],
            "advancing": row[3],
            "isFinal": row[4],
        }
        # A room whose round has not been projected yet still belongs to the bracket: the events
        # arrive on one topic but nothing promises `RoundStarted` was applied first.
        entry = by_number.get(row[0])
        if entry is None:
            entry = {
                "roundNumber": row[0],
                "roomCount": 0,
                "advancingTotal": None,
                "complete": False,
                "rooms": [],
            }
            by_number[row[0]] = entry
            rounds.append(entry)
        entry["rooms"].append(room)

    cursor.execute(
        "select player_id, placement from tournament_placements where tournament_id = %s"
        " order by placement",
        (tournament_id,),
    )
    placements = [{"playerId": row[0], "placement": row[1]} for row in cursor.fetchall()]

    return {
        "tournamentId": tournament_id,
        "status": header[0],
        "playerCount": header[1],
        "roundCount": header[2],
        "champion": header[3],
        "createdAt": header[4].isoformat() if header[4] else None,
        "completedAt": header[5].isoformat() if header[5] else None,
        "rounds": sorted(rounds, key=lambda entry: entry["roundNumber"]),
        "placements": placements,
    }
