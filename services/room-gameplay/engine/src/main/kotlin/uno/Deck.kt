package uno

import kotlin.random.Random

/**
 * The only randomness in the engine is a seed the server generates and records in `GameStarted`
 * and in every `DeckRecycled` (plan D2). Everything downstream — the shuffle, the deal, the initial
 * discard — is a pure function of that seed, which is what makes replaying the log reproduce the
 * exact deck order rather than merely a plausible one.
 *
 * Fisher-Yates is written out rather than delegated to `shuffled(Random)` on purpose: replay
 * stability is a property of this code, not of whichever implementation the stdlib happens to use.
 */
fun <T> List<T>.shuffledWith(seed: Long): List<T> {
    val out = toMutableList()
    val rng = Random(seed)
    for (i in out.indices.reversed()) {
        val j = rng.nextInt(i + 1)
        val swap = out[i]
        out[i] = out[j]
        out[j] = swap
    }
    return out
}

/** The draw pile, top first. */
data class Deck(val cards: List<Card>) {
    val size: Int get() = cards.size
    val isEmpty: Boolean get() = cards.isEmpty()

    fun draw(count: Int): Pair<List<Card>, Deck> =
        cards.take(count) to Deck(cards.drop(count))

    companion object {
        fun shuffled(seed: Long): Deck = Deck(FULL_DECK.shuffledWith(seed))
    }
}

data class Hand(val cards: List<Card> = emptyList(), val hasCalledUno: Boolean = false) {
    val size: Int get() = cards.size

    /** Invariant §3.2.3: the call is only good for the hand it was made on. */
    fun add(drawn: List<Card>): Hand = Hand(cards + drawn, hasCalledUno = false)

    fun remove(card: Card): Hand? {
        val index = cards.indexOf(card)
        if (index < 0) return null
        return Hand(cards.filterIndexed { i, _ -> i != index }, hasCalledUno = false)
    }

    val points: Int get() = cards.sumOf { it.points }
}

/**
 * Dealing is part of the seeded derivation: `handSize` cards to each player in seating order, then
 * the initial discard. Wild Draw Four may not start the discard pile (invariant 14), so it is
 * buried and the next card taken — done here so the deal is one deterministic step.
 */
data class Deal(val hands: Map<String, Hand>, val deck: Deck, val discard: List<Card>)

fun deal(players: List<String>, seed: Long, handSize: Int = 7): Deal {
    var deck = Deck.shuffled(seed)
    val hands = LinkedHashMap<String, Hand>()
    for (player in players) {
        val (cards, rest) = deck.draw(handSize)
        hands[player] = Hand(cards)
        deck = rest
    }
    val buried = mutableListOf<Card>()
    var top: Card
    while (true) {
        val (drawn, rest) = deck.draw(1)
        deck = rest
        top = drawn.single()
        if (top.face != Face.WILD_DRAW_FOUR) break
        buried += top
    }
    // A buried Wild Draw Four goes back under the pile, so no card ever leaves the game.
    return Deal(hands, Deck(deck.cards + buried), listOf(top))
}
