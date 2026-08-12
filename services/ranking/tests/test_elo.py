from elo import INITIAL_RATING, deltas, expected_score


def test_equal_ratings_expect_a_coin_flip() -> None:
    assert expected_score(1000, 1000) == 0.5


def test_two_equal_players_swap_half_the_k_factor() -> None:
    # Both at 1000, K=32: the winner takes 32 * (1 - 0.5) = 16 and the loser gives up the same.
    assert deltas([INITIAL_RATING, INITIAL_RATING]) == [16, -16]


def test_four_equal_players_are_scored_by_position() -> None:
    # Each player is scored against the other three and averaged over N-1 pairings:
    # 1st: 32*(3*0.5)/3 = 16   2nd: 32*(0.5)/3 = 5   3rd: -5   4th: -16
    assert deltas([INITIAL_RATING] * 4) == [16, 5, -5, -16]


def test_beating_a_stronger_player_is_worth_more() -> None:
    underdog_win = deltas([1000, 1600])[0]
    favourite_win = deltas([1600, 1000])[0]
    assert underdog_win > favourite_win


def test_deltas_sum_to_zero() -> None:
    # Ratings are moved between players, never minted. Integer rounding is the only slack allowed,
    # and it is bounded by the number of players.
    for ratings in ([1000, 1000], [1200, 800], [1500, 1000, 900, 1100], [1000] * 6):
        assert abs(sum(deltas(ratings))) <= len(ratings)


def test_a_single_player_moves_nothing() -> None:
    assert deltas([1000]) == [0]
