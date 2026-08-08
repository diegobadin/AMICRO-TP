package uno

/**
 * Cards are written and transmitted in the canonical display notation of Client-Checkpoint §5.F
 * (`R5`, `BSKIP`, `Y+2`, `WILD+4`). Using it as the wire format too means the string the faculty
 * reads in the CLI is the same one in the API response and in the event store, with no translation
 * table to get wrong.
 */
enum class Color(val code: String) {
    RED("R"), YELLOW("Y"), GREEN("G"), BLUE("B"), WILD("");

    val isPlayable: Boolean get() = this != WILD
}

enum class Face(val code: String, val points: Int) {
    ZERO("0", 0), ONE("1", 1), TWO("2", 2), THREE("3", 3), FOUR("4", 4),
    FIVE("5", 5), SIX("6", 6), SEVEN("7", 7), EIGHT("8", 8), NINE("9", 9),
    SKIP("SKIP", 20), REVERSE("REV", 20), DRAW_TWO("+2", 20),
    WILD("WILD", 50), WILD_DRAW_FOUR("WILD+4", 50);

    val isWild: Boolean get() = this == WILD || this == WILD_DRAW_FOUR
}

data class Card(val color: Color, val face: Face) {
    val points: Int get() = face.points

    /** §5.F: wilds are colourless until played, so they carry no colour prefix. */
    override fun toString(): String = if (face.isWild) face.code else color.code + face.code

    companion object {
        // Only the combinations that exist: a coloured wild or a colourless number is not a card,
        // and parsing must reject both rather than invent them.
        private val BY_NOTATION: Map<String, Card> = Color.entries.flatMap { color ->
            Face.entries
                .filter { face -> face.isWild != color.isPlayable }
                .map { face -> Card(color, face) }
        }.associateBy { it.toString() }

        fun parse(notation: String): Card? = BY_NOTATION[notation.uppercase()]
    }
}

/**
 * The standard 108-card deck: per colour one 0, two each of 1-9, two Skip, two Reverse, two Draw
 * Two; plus four Wild and four Wild Draw Four. Held as a multiset because that composition is what
 * the conservation property checks against after every transition.
 */
val COMPOSITION: Map<Card, Int> = buildMap {
    for (color in Color.entries.filter { it.isPlayable }) {
        put(Card(color, Face.ZERO), 1)
        for (face in listOf(
            Face.ONE, Face.TWO, Face.THREE, Face.FOUR, Face.FIVE, Face.SIX, Face.SEVEN,
            Face.EIGHT, Face.NINE, Face.SKIP, Face.REVERSE, Face.DRAW_TWO,
        )) {
            put(Card(color, face), 2)
        }
    }
    put(Card(Color.WILD, Face.WILD), 4)
    put(Card(Color.WILD, Face.WILD_DRAW_FOUR), 4)
}

val FULL_DECK: List<Card> = COMPOSITION.flatMap { (card, count) -> List(count) { card } }

/** A card is legal on a discard top given the active colour (invariant 3). */
fun Card.playableOn(top: Card, activeColor: Color): Boolean =
    face.isWild || color == activeColor || face == top.face
