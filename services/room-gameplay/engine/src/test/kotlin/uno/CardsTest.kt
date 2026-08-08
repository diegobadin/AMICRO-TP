package uno

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardsTest {

    @Test
    fun `the deck is the standard 108-card composition`() {
        assertEquals(108, FULL_DECK.size)
        assertEquals(1, COMPOSITION[Card(Color.RED, Face.ZERO)])
        assertEquals(2, COMPOSITION[Card(Color.BLUE, Face.NINE)])
        assertEquals(2, COMPOSITION[Card(Color.GREEN, Face.SKIP)])
        assertEquals(4, COMPOSITION[Card(Color.WILD, Face.WILD)])
        assertEquals(4, COMPOSITION[Card(Color.WILD, Face.WILD_DRAW_FOUR)])
        assertEquals(25 * 4 + 8, FULL_DECK.size)
    }

    @Test
    fun `cards print in the canonical notation of Client-Checkpoint 5F`() {
        assertEquals("R5", Card(Color.RED, Face.FIVE).toString())
        assertEquals("G0", Card(Color.GREEN, Face.ZERO).toString())
        assertEquals("BSKIP", Card(Color.BLUE, Face.SKIP).toString())
        assertEquals("YREV", Card(Color.YELLOW, Face.REVERSE).toString())
        assertEquals("Y+2", Card(Color.YELLOW, Face.DRAW_TWO).toString())
        assertEquals("WILD", Card(Color.WILD, Face.WILD).toString())
        assertEquals("WILD+4", Card(Color.WILD, Face.WILD_DRAW_FOUR).toString())
    }

    @Test
    fun `every card in the deck round-trips through its notation`() {
        for (card in FULL_DECK.distinct()) {
            assertEquals(card, Card.parse(card.toString()), "round-trip failed for $card")
        }
    }

    @Test
    fun `notations that are not cards do not parse`() {
        assertNull(Card.parse("REV"))       // colourless action card
        assertNull(Card.parse("RWILD"))     // coloured wild
        assertNull(Card.parse("R10"))
        assertNull(Card.parse(""))
    }

    @Test
    fun `legality follows colour, face or wildness`() {
        val top = Card(Color.RED, Face.FIVE)
        assertTrue(Card(Color.RED, Face.SKIP).playableOn(top, Color.RED))
        assertTrue(Card(Color.BLUE, Face.FIVE).playableOn(top, Color.RED))
        assertTrue(Card(Color.WILD, Face.WILD).playableOn(top, Color.RED))
        assertFalse(Card(Color.BLUE, Face.NINE).playableOn(top, Color.RED))
        // A wild set the active colour to blue; the red top card no longer admits red.
        assertTrue(Card(Color.BLUE, Face.NINE).playableOn(Card(Color.WILD, Face.WILD), Color.BLUE))
        assertFalse(Card(Color.RED, Face.NINE).playableOn(Card(Color.WILD, Face.WILD), Color.BLUE))
    }
}

class DeckTest {

    // Block body, not `= runBlocking {...}`: checkAll returns a PropertyContext, and a @Test method
    // that returns a value is not run at all — the suite goes green having proved nothing.
    @Test
    fun `shuffling is a permutation and the same seed gives the same order`() {
        val ran = runBlocking {
            checkAll(Arb.long()) { seed ->
                val shuffled = Deck.shuffled(seed).cards
                assertEquals(FULL_DECK.groupingBy { it }.eachCount(), shuffled.groupingBy { it }.eachCount())
                assertEquals(shuffled, Deck.shuffled(seed).cards)
            }
        }
        assertEquals(1000, ran.attempts(), "the property suite has to actually generate cases")
    }

    @Test
    fun `different seeds generally give different orders`() {
        val orders = (1L..50L).map { Deck.shuffled(it).cards }.toSet()
        assertEquals(50, orders.size)
    }

    @Test
    fun `dealing conserves the deck`() {
        runBlocking {
            checkAll(Arb.long()) { seed ->
                val players = listOf("a", "b", "c", "d")
                val dealt = deal(players, seed)
                val all = dealt.hands.values.flatMap { it.cards } + dealt.deck.cards + dealt.discard
                assertEquals(FULL_DECK.groupingBy { it }.eachCount(), all.groupingBy { it }.eachCount())
                assertEquals(108, all.size)
                players.forEach { assertEquals(7, dealt.hands.getValue(it).size) }
            }
        }
    }

    @Test
    fun `a wild draw four never starts the discard pile`() {
        runBlocking {
            checkAll(Arb.long()) { seed ->
                val dealt = deal(listOf("a", "b"), seed)
                assertFalse(dealt.discard.last().face == Face.WILD_DRAW_FOUR)
            }
        }
    }

    @Test
    fun `drawing from a hand clears the uno call`() {
        val hand = Hand(listOf(Card(Color.RED, Face.FIVE)), hasCalledUno = true)
        assertFalse(hand.add(listOf(Card(Color.BLUE, Face.ONE))).hasCalledUno)
    }

    @Test
    fun `removing a card takes exactly one copy`() {
        val red5 = Card(Color.RED, Face.FIVE)
        val hand = Hand(listOf(red5, red5, Card(Color.BLUE, Face.ONE)))
        val after = hand.remove(red5)!!
        assertEquals(listOf(red5, Card(Color.BLUE, Face.ONE)), after.cards)
        assertNull(Hand(emptyList()).remove(red5))
    }
}

class TurnOrderTest {

    private val four = TurnOrder(listOf("a", "b", "c", "d"))

    @Test
    fun `advance follows the direction and wraps`() {
        assertEquals("b", four.advance().current)
        assertEquals("c", four.advance(2).current)
        assertEquals("d", four.reversed().advance().current)
        assertEquals("a", four.advance(4).current)
    }

    @Test
    fun `with two players a reverse leaves the same player next`() {
        val two = TurnOrder(listOf("a", "b"))
        assertEquals(two.peek(), two.reversed().peek())
    }

    @Test
    fun `removing a seat keeps the relative order of the rest`() {
        val after = four.remove("c")
        assertEquals(listOf("a", "b", "d"), after.activePlayers)
        assertEquals("a", after.current)
    }

    @Test
    fun `removing the player to act passes the turn along the direction`() {
        val at = four.advance()               // b to act
        assertEquals("c", at.remove("b").current)
        assertEquals("a", at.reversed().remove("b").current)
    }

    @Test
    fun `removing someone else leaves the player to act untouched`() {
        runBlocking {
            checkAll(Arb.long()) { seed ->
                val order = TurnOrder(listOf("a", "b", "c", "d"), currentIndex = (seed.toInt() and 3))
                val victim = listOf("a", "b", "c", "d").first { it != order.current }
                assertEquals(order.current, order.remove(victim).current)
            }
        }
    }
}
