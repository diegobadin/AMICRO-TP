"""The tournament placement rating, as a pure function over a final standing. No I/O.

Architecture §4.2 has ranking consume `TournamentCompleted` and apply a placement rating, without
saying how. This is the smallest rule that is defensible and auditable: a tournament is a fixed pot
of points redistributed by where you finished, so the deltas of one tournament sum to zero — the
same property `elo.deltas` has, and for the same reason. Ratings move between players; they are
never minted.

Deliberately NOT Elo. Elo asks "who did you beat, and were they better than you"; a placement asks
"how far did you get in this field". Mixing them would let a tournament move a casual rating, which
§4.5 forbids in the other direction and would be just as wrong here.
"""

from __future__ import annotations

INITIAL_PLACEMENT_RATING = 1000

# The pot one tournament redistributes, per player in the field. Small enough that a tournament is
# worth less than a run of games, large enough that winning one is visible.
PLACEMENT_K = 24


def deltas(field_size: int, k: int = PLACEMENT_K) -> list[int]:
    """Rating changes by finishing position, best first, for a field of `field_size`.

    Linear from +k at the top to -k at the bottom, then corrected so the list sums to exactly zero:
    rounding a symmetric curve does not have to land on zero by itself, and "the pot is conserved"
    is a property worth having exactly rather than approximately.
    """
    if field_size < 2:
        return [0] * max(0, field_size)

    raw = [k * (field_size - 1 - 2 * position) / (field_size - 1) for position in range(field_size)]
    out = [round(value) for value in raw]

    # Push the residue onto the middle of the field, where one point changes nobody's story.
    residue = sum(out)
    step = -1 if residue > 0 else 1
    index = field_size // 2
    while residue != 0:
        out[index] += step
        residue += step
        index = (index + 1) % field_size
    return out


def placements_of(final_placements: list[str]) -> list[tuple[str, int, int]]:
    """`(playerId, placement, delta)` for one tournament, placement counted from 1.

    `finalPlacements` arrives champion-first from the tournament, which is the only ordering the
    event promises — so position in that list IS the placement, and nothing here re-derives it.
    """
    changes = deltas(len(final_placements))
    return [
        (player, position + 1, changes[position])
        for position, player in enumerate(final_placements)
    ]
