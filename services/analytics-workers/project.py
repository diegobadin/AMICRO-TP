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


@dataclass
class Effects:
    """Everything one event changes. Counters are increments; the rest are statements of fact."""

    overview: dict[str, int] = field(default_factory=dict)
    activity: dict[str, Any] = field(default_factory=dict)
    activity_counters: dict[str, int] = field(default_factory=dict)
    game: dict[str, Any] | None = None
    players: list[dict[str, Any]] = field(default_factory=list)


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
