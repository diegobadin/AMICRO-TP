package uno

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The best-of-three a tournament room plays (P7 F3). The Room owns the match because every game of
 * it is the same people in the same room (§3.2.2) — so these are engine properties, not something
 * the tournament service is trusted to compute.
 */
class MatchTest {

    private fun Table.matchEvents() = log.filterIsInstance<MatchCompleted>()

    @Test
    fun `a tournament room plays on after game one and reports exactly once`() {
        runBlocking {
            checkAll(200, Arb.long()) { seed ->
                val table = Table(listOf("a", "b"), seed)
                table.seatTournament(advanceCount = 1)
                table.playMatchOut()

                val matches = table.matchEvents()
                assertEquals(1, matches.size, "one room, one verdict (seed $seed)")
                assertTrue(table.state.gamesPlayed in 2..3, "best-of-three is decided by 2 or 3 games (seed $seed)")
                assertEquals(1, matches.first().advancingPlayers.size)
                assertEquals(RoomStatus.COMPLETED, table.state.status)
            }
        }
    }

    @Test
    fun `the verdict comes before the room closes, and nothing follows it`() {
        runBlocking {
            checkAll(100, Arb.long()) { seed ->
                val table = Table(listOf("a", "b"), seed)
                table.seatTournament()
                table.playMatchOut()

                val types = table.log.map { it::class.simpleName }
                val match = types.indexOf("MatchCompleted")
                val room = types.indexOf("RoomCompleted")
                assertTrue(match >= 0 && room == types.size - 1, "the room closes last (seed $seed): $types")
                assertTrue(match < room, "the verdict precedes the closing (seed $seed)")
            }
        }
    }

    /** The whole point of `advanceCount`: a round has to narrow, and the room is what narrows it. */
    @Test
    fun `only advanceCount players advance, and they are the top of the standings`() {
        runBlocking {
            checkAll(100, Arb.long()) { seed ->
                val table = Table(listOf("a", "b", "c", "d"), seed)
                table.seatTournament(advanceCount = 2)
                table.playMatchOut()

                val match = table.matchEvents().single()
                val ranked = standings(match.matchResults, table.state.game?.finishingOrder ?: emptyList())
                assertEquals(2, match.advancingPlayers.size, "seed $seed")
                assertEquals(ranked.take(2), match.advancingPlayers, "seed $seed")
            }
        }
    }

    @Test
    fun `a casual room never plays a second game and never reports a match`() {
        runBlocking {
            checkAll(100, Arb.long()) { seed ->
                val table = Table(listOf("a", "b"), seed)
                table.seat()
                table.playMatchOut()

                assertTrue(table.matchEvents().isEmpty(), "casual rooms have no match (seed $seed)")
                assertEquals(1, table.state.gamesPlayed, "seed $seed")
                assertEquals(null, table.state.matchScores, "and keep no score (seed $seed)")
            }
        }
    }

    /**
     * 2-0 with one game left: the third game cannot change who advances, so it is not played. The
     * predicate is over remaining games rather than a special case for two-nil, but two-nil is the
     * shape it has to get right.
     */
    @Test
    fun `a decided match does not play a dead rubber`() {
        val table = Table(listOf("a", "b"), seed = 11)
        table.seatTournament(advanceCount = 1)
        table.state.let { assertEquals(3, it.maxGames) }
        table.playMatchOut()

        val scores = table.matchEvents().single().matchResults
        val winner = scores.maxByOrNull { it.value.wins }!!
        if (winner.value.wins == 2 && scores.values.sumOf { it.wins } == 2) {
            assertEquals(2, table.state.gamesPlayed, "a 2-0 match stops at two games")
        }
    }

    /**
     * §6.8.5: a room that fell apart still reports, or its round waits on a result that never comes.
     *
     * It reports a *winner*, not an empty seat: ending a game for too few players hands the last one
     * standing the room (§6.8.5, "the last active player wins the match"), and every forfeit path
     * goes through that check one player at a time — so a started room always has somebody left.
     * The genuinely empty case belongs to a room that never started, which arrives at the tournament
     * as `RoomExpired` and is the saga's zero-advancer path, not the room's.
     */
    @Test
    fun `a room that falls apart still reports its round`() {
        val table = Table(listOf("a", "b", "c"), seed = 5)
        table.seatTournament(advanceCount = 1)
        table.send(LeaveRoom("a"))
        table.send(LeaveRoom("b"))

        val match = table.matchEvents().single()
        assertEquals(listOf("c"), match.advancingPlayers, "the round can only close if the room reports")
        assertTrue(
            match.advancingPlayers.all { table.state.player(it)?.isActive == true },
            "nobody who walked out advances out of a room they left",
        )
        assertEquals(RoomStatus.COMPLETED, table.state.status)
    }

    @Test
    fun `a walkout ends the match immediately and only the player still there advances`() {
        val table = Table(listOf("a", "b"), seed = 5)
        table.seatTournament(advanceCount = 1)
        table.send(LeaveRoom("a"))

        val match = table.matchEvents().single()
        assertEquals(listOf("b"), match.advancingPlayers, "the last one standing takes the seat")
        assertEquals(1, table.state.gamesPlayed, "the remaining games are not played out against nobody")
    }

    /**
     * The boundary the generated games never reach: with two players a rival who can draw level is
     * also a rival who can overtake, so `<` and `<=` agree and a sloppy predicate passes anyway.
     * It takes a bigger room for them to disagree — and that disagreement decides whether a match
     * that is still live gets cut short.
     */
    @Test
    fun `a rival who can still draw level keeps the match alive`() {
        // Two of three games played, so exactly two wins are shared out and one game is left.
        val room = RoomState(
            roomId = "room-1",
            roomType = RoomType.TOURNAMENT,
            tournament = TournamentLink("t-1", 1, advanceCount = 2),
            gamesPlayed = 2,
        )

        // a and b hold the two seats with one win each; c has none. Winning the last game puts c
        // level with b on wins — and card points, not arithmetic, would then decide the seat.
        val live = room.copy(
            matchScores = mapOf(
                "a" to MatchScore(wins = 1, losses = 1),
                "b" to MatchScore(wins = 1, losses = 1),
                "c" to MatchScore(wins = 0, losses = 2),
                "d" to MatchScore(wins = 0, losses = 2),
            ),
        )
        assertFalse(matchDecided(live), "c can draw level with b, and a tiebreak that has not happened is not a result")

        // One seat, and a is two clear with one game left: nobody can reach them.
        val over = room.copy(
            tournament = TournamentLink("t-1", 1, advanceCount = 1),
            matchScores = mapOf(
                "a" to MatchScore(wins = 2, losses = 0),
                "b" to MatchScore(wins = 0, losses = 2),
                "c" to MatchScore(wins = 0, losses = 2),
                "d" to MatchScore(wins = 0, losses = 2),
            ),
        )
        assertTrue(matchDecided(over), "nobody outside the seat can reach it")
    }

    @Test
    fun `the last game of a best-of-three always ends the match`() {
        val room = RoomState(
            roomId = "room-1",
            roomType = RoomType.TOURNAMENT,
            tournament = TournamentLink("t-1", 1, advanceCount = 1),
            gamesPlayed = 3,
            matchScores = mapOf("a" to MatchScore(wins = 2), "b" to MatchScore(wins = 1)),
        )
        assertTrue(matchDecided(room), "there is nothing left to play")
    }

    @Test
    fun `standings rank by wins, then card points, then who went out first, then id`() {
        val scores = mapOf(
            "a" to MatchScore(wins = 1, cardPoints = 30),
            "b" to MatchScore(wins = 2, cardPoints = 90),
            "c" to MatchScore(wins = 1, cardPoints = 10),
            "d" to MatchScore(wins = 1, cardPoints = 10),
        )
        // c and d are identical on both real keys; the last game separates them, and d went out first.
        assertEquals(listOf("b", "d", "c", "a"), standings(scores, lastGameOrder = listOf("d", "c")))
    }

    @Test
    fun `identical records still rank, deterministically`() {
        val scores = mapOf("b" to MatchScore(1, 1, 20), "a" to MatchScore(1, 1, 20))
        assertEquals(listOf("a", "b"), standings(scores, lastGameOrder = emptyList()))
        assertFalse(standings(scores, emptyList()) != standings(scores, emptyList()), "and the same on every replay")
    }
}
