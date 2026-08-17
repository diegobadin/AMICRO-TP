"""What each event does to the read models, as data rather than as code that reaches a database.

`plan(event)` returns the effects an event has; `store.py` applies them in one transaction with the
`consumed_events` insert. Keeping the decision pure means the projections can be tested by reading a
list, and it makes "does this event double-count on replay?" a question about the transaction rather
than about every branch.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

# Terminal states share a rank: a room is finished either way, and nothing follows.
STATUS_RANK = {"WAITING": 0, "IN_PROGRESS": 1, "COMPLETED": 2, "EXPIRED": 2}

# The same idea for a tournament (P7). Registration → in progress → completed, and never back.
TOURNAMENT_STATUS_RANK = {"REGISTRATION": 0, "IN_PROGRESS": 1, "COMPLETED": 2}


@dataclass
class Effects:
    """Everything one event changes. Counters are increments; the rest are statements of fact."""

    overview: dict[str, int] = field(default_factory=dict)
    activity: dict[str, Any] = field(default_factory=dict)
    activity_counters: dict[str, int] = field(default_factory=dict)
    game: dict[str, Any] | None = None
    players: list[dict[str, Any]] = field(default_factory=list)
    # P7's bracket. All statements of fact, never increments — see the note in `schema.py`.
    tournament: dict[str, Any] | None = None
    rounds: list[dict[str, Any]] = field(default_factory=list)
    rooms: list[dict[str, Any]] = field(default_factory=list)
    placements: list[dict[str, Any]] = field(default_factory=list)


def plan_tournament(event_type: str, body: dict[str, Any]) -> Effects:
    """The bracket projection: `tournament.lifecycle.events` → the four bracket tables.

    Separate from `plan` because a tournament event shares no fields with a room event — no
    `roomId`, no per-room activity — and folding them into one function would mean a growing chain
    of "which kind of event is this" before either branch could start.
    """
    effects = Effects()
    tournament_id = body.get("tournamentId")
    if not tournament_id:
        return effects
    at = body.get("at")
    facts: dict[str, Any] = {"tournament_id": tournament_id, "last_event_at": at}

    if event_type == "TournamentCreated":
        config = body.get("config") or {}
        facts.update(
            status="REGISTRATION",
            status_rank=TOURNAMENT_STATUS_RANK["REGISTRATION"],
            min_players=config.get("minPlayers"),
            room_size=config.get("roomSize"),
            created_at=at,
        )
        effects.overview["tournaments_created"] = 1
    elif event_type == "PlayerRegistered":
        # The count the event states, not one this service keeps: a replay then writes the same
        # number instead of adding to it.
        facts["player_count"] = body.get("registeredCount")
    elif event_type == "TournamentStarted":
        facts.update(
            status="IN_PROGRESS",
            status_rank=TOURNAMENT_STATUS_RANK["IN_PROGRESS"],
            player_count=body.get("totalPlayers"),
            round_count=body.get("roundCount"),
        )
        effects.overview["tournaments_started"] = 1
    elif event_type == "RoundStarted":
        effects.rounds.append(
            {
                "tournament_id": tournament_id,
                "round_number": body.get("roundNumber"),
                "room_count": body.get("roomCount") or 0,
            }
        )
        for room_id, players in (body.get("assignments") or {}).items():
            effects.rooms.append(
                {
                    "room_id": room_id,
                    "tournament_id": tournament_id,
                    "round_number": body.get("roundNumber"),
                    "players": players,
                }
            )
    # Both of these name a room the round already created — but "already" is a delivery order, not
    # a guarantee, so each carries the tournament it belongs to. Without it the row cannot be
    # inserted at all when it arrives first, which is what a reversed replay does.
    elif event_type == "FinalRoomCreated":
        effects.rooms.append(
            {"room_id": body.get("roomId"), "tournament_id": tournament_id, "is_final": True}
        )
    elif event_type == "RoomResultRecorded":
        effects.rooms.append(
            {
                "room_id": body.get("roomId"),
                "tournament_id": tournament_id,
                "round_number": body.get("roundNumber"),
                "advancing": body.get("advancingPlayers") or [],
            }
        )
    elif event_type == "RoundCompleted":
        effects.rounds.append(
            {
                "tournament_id": tournament_id,
                "round_number": body.get("roundNumber"),
                "advancing_total": body.get("advancingPlayersTotal"),
                "complete": True,
            }
        )
    elif event_type == "TournamentCompleted":
        facts.update(
            status="COMPLETED",
            status_rank=TOURNAMENT_STATUS_RANK["COMPLETED"],
            champion=body.get("champion"),
            completed_at=at,
        )
        effects.overview["tournaments_completed"] = 1
        for position, player in enumerate(body.get("finalPlacements") or []):
            effects.placements.append(
                {"tournament_id": tournament_id, "player_id": player, "placement": position + 1}
            )

    effects.tournament = facts
    return effects


def plan(event_type: str, body: dict[str, Any]) -> Effects:
    effects = Effects()
    at = body.get("at")
    effects.activity["last_event_at"] = at
    effects.activity_counters["events_seen"] = 1

    if event_type == "RoomCreated":
        effects.overview["rooms_created"] = 1
        effects.activity["room_type"] = body.get("roomType")
        effects.activity["status"] = "WAITING"
        effects.activity["status_rank"] = STATUS_RANK["WAITING"]
        effects.activity["first_event_at"] = at

    elif event_type == "PlayerJoined":
        effects.activity_counters["players_seen"] = 1

    elif event_type == "GameStarted":
        effects.activity["status"] = "IN_PROGRESS"
        effects.activity["status_rank"] = STATUS_RANK["IN_PROGRESS"]

    elif event_type == "CardPlayed":
        effects.overview["cards_played"] = 1
        effects.activity_counters["cards_played"] = 1

    elif event_type == "CardDrawn":
        effects.overview["cards_drawn"] = 1
        effects.activity_counters["cards_drawn"] = 1

    elif event_type == "TurnTimedOut":
        # Counted apart from the draw and pass it performs for the player. A timeout that looks like
        # the player acting is the shape that cost P5 an evening, and it would be just as wrong in a
        # statistic as it was in the forfeit streak.
        effects.overview["turns_timed_out"] = 1

    elif event_type == "UnoCallMade":
        effects.overview["uno_calls"] = 1

    elif event_type == "PlayerForfeited":
        effects.overview["players_forfeited"] = 1

    elif event_type == "GameCompleted":
        finishing_order = list(body.get("finishingOrder") or [])
        card_points: dict[str, int] = body.get("cardPointTotals") or {}
        abandoned = bool(body.get("isAbandoned"))
        effects.overview["games_completed"] = 1
        if abandoned:
            effects.overview["games_abandoned"] = 1
        effects.game = {
            "room_id": body.get("roomId"),
            "game_number": body.get("gameNumber"),
            "room_type": body.get("roomType"),
            "is_abandoned": abandoned,
            "finishing_order": finishing_order,
            "card_point_totals": card_points,
            "completed_at": body.get("completedAt") or at,
        }
        for position, player in enumerate(finishing_order):
            effects.players.append(
                {
                    "player_id": player,
                    "won": position == 0 and not abandoned,
                    "abandoned": abandoned,
                    "card_points": int(card_points.get(player, 0)),
                    "at": body.get("completedAt") or at,
                }
            )

    elif event_type == "RoomCompleted":
        effects.overview["rooms_completed"] = 1
        effects.activity["status"] = "COMPLETED"
        effects.activity["status_rank"] = STATUS_RANK["COMPLETED"]

    elif event_type == "RoomExpired":
        effects.overview["rooms_expired"] = 1
        effects.activity["status"] = "EXPIRED"
        effects.activity["status_rank"] = STATUS_RANK["EXPIRED"]

    return effects
