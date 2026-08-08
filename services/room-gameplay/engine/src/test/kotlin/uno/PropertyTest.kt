package uno

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-P3.2. These run over thousands of randomly generated games rather than scripted ones: the
 * rules are the program's biggest unknown (north-star R2), and a hand-written scenario only ever
 * proves the case its author already thought of.
 */
class PropertyTest {

    @Test
    fun `the card multiset is conserved across every transition`() {
        val ran = runBlocking {
            checkAll(Arb.long()) { seed ->
                val table = Table(listOf("a", "b", "c"), seed)
                table.seat()
                table.state.assertConserved("after deal")
                var steps = 0
                while (steps++ < 3000 && table.state.game?.status == GameStatus.IN_PROGRESS) {
                    table.tick()
                    table.step()
                    table.state.assertConserved("after step $steps of seed $seed")
                }
            }
        }
        assertEquals(1000, ran.attempts(), "the property suite has to actually generate games")
    }

    @Test
    fun `the active colour is always one a player could match`() {
        runBlocking {
            checkAll(Arb.long()) { seed ->
                val table = Table(listOf("a", "b"), seed)
                table.seat()
                var steps = 0
                while (steps++ < 3000 && table.state.game?.status == GameStatus.IN_PROGRESS) {
                    val game = table.state.game!!
                    assertTrue(
                        game.activeColor.isPlayable,
                        "seed $seed step $steps: active colour was ${game.activeColor}",
                    )
                    table.tick()
                    table.step()
                }
            }
        }
    }

    @Test
    fun `the player holding the turn always has something legal to do`() {
        runBlocking {
            checkAll(Arb.long()) { seed ->
                val table = Table(listOf("a", "b", "c"), seed)
                table.seat()
                var steps = 0
                while (steps++ < 3000 && table.state.game?.status == GameStatus.IN_PROGRESS) {
                    val actor = table.state.game!!.currentPlayer
                    val before = table.state.sequenceNumber
                    table.tick()
                    val decision = table.step()
                    assertTrue(
                        decision is Decision.Accepted,
                        "seed $seed step $steps: $actor was stuck — ${(decision as? Decision.Rejected)?.reason}",
                    )
                    assertTrue(table.state.sequenceNumber > before, "seed $seed step $steps made no progress")
                }
            }
        }
    }

    @Test
    fun `at most one challenge window is open, and only on a player holding one card`() {
        runBlocking {
            checkAll(Arb.long()) { seed ->
                val table = Table(listOf("a", "b", "c"), seed)
                table.seat()
                var steps = 0
                while (steps++ < 3000 && table.state.game?.status == GameStatus.IN_PROGRESS) {
                    val game = table.state.game!!
                    game.challengeWindow?.let { window ->
                        assertEquals(
                            1,
                            game.hands.getValue(window.targetPlayerId).size,
                            "seed $seed: window open on a player who is not at one card",
                        )
                    }
                    table.tick()
                    table.step()
                }
            }
        }
    }

    @Test
    fun `turn order only ever moves to a seat that is still in the ring`() {
        runBlocking {
            checkAll(Arb.long()) { seed ->
                val table = Table(listOf("a", "b", "c", "d"), seed)
                table.seat()
                var steps = 0
                while (steps++ < 3000 && table.state.game?.status == GameStatus.IN_PROGRESS) {
                    val game = table.state.game!!
                    assertTrue(
                        game.turnOrder.currentIndex in game.turnOrder.activePlayers.indices,
                        "seed $seed: turn index out of the ring",
                    )
                    assertTrue(
                        game.hands.containsKey(game.currentPlayer),
                        "seed $seed: turn handed to someone with no hand",
                    )
                    table.tick()
                    table.step()
                }
            }
        }
    }

    @Test
    fun `a game always reaches an ending`() {
        runBlocking {
            checkAll(Arb.long()) { seed ->
                val table = Table(listOf("a", "b"), seed).playOut()
                assertEquals(
                    GameStatus.COMPLETED,
                    table.state.game?.status,
                    "seed $seed did not finish within the step budget",
                )
                assertEquals(RoomStatus.COMPLETED, table.state.status, "casual room should close with its game")
            }
        }
    }

    @Test
    fun `every player count from two to ten plays through`() {
        runBlocking {
            checkAll(Arb.int(2..10), Arb.long()) { count, seed ->
                val players = (1..count).map { "p$it" }
                val table = Table(players, seed).playOut()
                assertEquals(GameStatus.COMPLETED, table.state.game?.status, "$count players, seed $seed")
                table.state.assertConserved("$count players, seed $seed")
            }
        }
    }
}

/**
 * AC-P3.2, second half: the log is the authority. If an aggregate rebuilt from events ever disagrees
 * with the one that was served, the bug is in the code and not in the log — so this compares the
 * whole state, deck order included.
 */
class ReplayTest {

    @Test
    fun `replaying the log reproduces the served state exactly, deck order included`() {
        val ran = runBlocking {
            checkAll(Arb.long()) { seed ->
                val table = Table(listOf("a", "b", "c"), seed).playOut()
                val rebuilt = replay(table.log, roomId = "room-1")
                assertEquals(table.state, rebuilt, "seed $seed: replay diverged")
                assertEquals(
                    table.state.game?.deck?.cards,
                    rebuilt.game?.deck?.cards,
                    "seed $seed: deck order diverged",
                )
            }
        }
        assertEquals(1000, ran.attempts())
    }

    @Test
    fun `replaying twice gives an identical aggregate`() {
        runBlocking {
            checkAll(Arb.long()) { seed ->
                val table = Table(listOf("a", "b"), seed).playOut()
                assertEquals(replay(table.log, "room-1"), replay(table.log, "room-1"), "seed $seed")
            }
        }
    }

    @Test
    fun `replaying a prefix matches the state that prefix produced`() {
        runBlocking {
            checkAll(Arb.long()) { seed ->
                val table = Table(listOf("a", "b"), seed)
                table.seat()
                var steps = 0
                while (steps++ < 200 && table.state.game?.status == GameStatus.IN_PROGRESS) {
                    table.tick()
                    table.step()
                    assertEquals(
                        table.state,
                        replay(table.log, "room-1"),
                        "seed $seed diverged at step $steps",
                    )
                }
            }
        }
    }

    @Test
    fun `the sequence number is exactly the number of events in the log`() {
        runBlocking {
            checkAll(Arb.long()) { seed ->
                val table = Table(listOf("a", "b", "c"), seed).playOut()
                assertEquals(table.log.size, table.state.sequenceNumber, "seed $seed")
            }
        }
    }
}
