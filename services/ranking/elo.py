"""Elo, as a pure function over a finishing order. No I/O, no database, no Kafka.

Architecture §4 specifies Elo for casual games only, computed from `finishingOrder`. Elo is defined
for two players, so a four-player game needs a generalisation: every player is scored against every
other one, and the result is averaged over the N-1 pairings. That keeps the property that matters —
the deltas of a single game sum to zero — so ratings are moved around between players and never
minted.
"""

from __future__ import annotations

DEFAULT_K = 32
INITIAL_RATING = 1000


def expected_score(rating: int, opponent: int) -> float:
    """The standard logistic curve: how often `rating` beats `opponent` over the long run."""
    return 1.0 / (1.0 + 10 ** ((opponent - rating) / 400.0))


def deltas(ratings: list[int], k: int = DEFAULT_K) -> list[int]:
    """Rating changes for one game, given the players' ratings **in finishing order**.

    Position is the whole outcome: player i beat player j exactly when i finished ahead of j. The
    margin (`cardPointTotals`) deliberately does not enter — it is recorded on the history row
    instead, because letting it move the rating is a design choice the architecture never made.
    """
    n = len(ratings)
    if n < 2:
        return [0] * n
    out: list[int] = []
    for i, rating in enumerate(ratings):
        score = 0.0
        for j, opponent in enumerate(ratings):
            if i == j:
                continue
            actual = 1.0 if i < j else 0.0
            score += actual - expected_score(rating, opponent)
        out.append(round(k * score / (n - 1)))
    return out
