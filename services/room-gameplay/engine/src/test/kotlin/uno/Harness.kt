package uno

import java.time.Instant
import kotlin.random.Random

val T0: Instant = Instant.parse("2026-08-08T12:00:00Z")

/**
 * Drives a whole game by picking a legal action for whoever holds the turn, so the property suites
 * explore real play rather than hand-written scripts. Every accepted command's events are kept, so
 * the same run can be checked for invariants and then replayed from its log.
 */
class Table(
    val players: List<String>,
    seed: Long,
    val config: EngineConfig = EngineConfig(minPlayers = 2, turnTimeoutSeconds = 30),
) {
    private val rng = Random(seed)
    var state: RoomState = RoomState(roomId = "room-1")
        private set
    val log = mutableListOf<Event>()
    var now: Instant = T0
        private set

    fun send(command: Command, at: Instant = now): Decision {
        val decision = decide(state, command, at, rng.nextLong(), config)
        decision.events.forEach { state = evolve(state, it) }
        log += decision.events
        return decision
    }

    fun tick(seconds: Long = 1) { now = now.plusSeconds(seconds) }

    fun seat() {
        send(CreateRoom(roomId = "room-1", creatorId = players.first(), maxPlayers = players.size))
        // minPlayers is the whole table here, so the auto-start (E3) fires on the last join and
        // every generated game starts with everyone seated.
        players.drop(1).forEach { tick(); send(JoinRoom(it)) }
    }

    private fun game(): Game? = state.game?.takeIf { it.status == GameStatus.IN_PROGRESS }

    /**
     * One *legal* action for the current player — the property suites generate legal games, so
     * every command this sends is expected to be accepted. Rejections are the edge-case suite's
     * subject, and mixing them in here would hide a genuinely stuck aggregate behind an expected no-op.
     */
    fun step(): Decision? {
        val game = game() ?: return null
        val actor = game.currentPlayer

        // Someone else may take the open window before the turn moves on and closes it.
        game.challengeWindow?.let { window ->
            val target = game.hands.getValue(window.targetPlayerId)
            val challenger = players.firstOrNull { it != window.targetPlayerId && state.player(it)?.isActive == true }
            if (challenger != null && target.size == 1 && !target.hasCalledUno && rng.nextInt(3) == 0) {
                return send(ChallengeUno(challenger, window.targetPlayerId))
            }
        }

        val hand = game.hands.getValue(actor)
        val playable = hand.cards.filter { it.playableOn(game.top, game.activeColor) }
        if (playable.isEmpty()) {
            return if (!game.drewThisTurn) send(DrawCard(actor)) else send(PassTurn(actor))
        }
        val card = playable[rng.nextInt(playable.size)]
        val color = if (card.face.isWild) Color.entries.filter { it.isPlayable }[rng.nextInt(4)] else null
        // Call Uno some of the time, so both the safe and the challengeable path get exercised.
        val callingUno = hand.size == 2 && rng.nextBoolean()
        return send(PlayCard(actor, card, color, callingUno))
    }

    fun playOut(maxSteps: Int = 3000): Table {
        seat()
        var steps = 0
        while (steps++ < maxSteps && game() != null) {
            tick()
            step()
        }
        return this
    }
}

/** deck + every hand + the discard pile, which must always be the 108-card composition. */
fun RoomState.allCards(): List<Card> {
    val game = game ?: return emptyList()
    return game.deck.cards + game.hands.values.flatMap { it.cards } + game.discard
}

fun RoomState.assertConserved(context: String) {
    val game = game ?: return
    val counted = allCards().groupingBy { it }.eachCount()
    check(counted == COMPOSITION) {
        "$context: cards were created or destroyed — deck=${game.deck.size} " +
            "hands=${game.hands.values.sumOf { it.size }} discard=${game.discard.size}"
    }
}

/** Builds a game position directly, for the edge cases a seeded deal will not hand you. */
fun position(
    hands: Map<String, Hand>,
    top: Card,
    activeColor: Color = top.color,
    deck: List<Card> = FULL_DECK.take(20),
    buried: List<Card> = emptyList(),
    current: String = hands.keys.first(),
    direction: Direction = Direction.CLOCKWISE,
    challengeWindow: ChallengeWindow? = null,
    turnTimeoutSeconds: Long = 30,
    at: Instant = T0,
): RoomState = RoomState(
    roomId = "room-1",
    status = RoomStatus.IN_PROGRESS,
    maxPlayers = 10,
    creatorId = hands.keys.first(),
    players = hands.keys.map { RoomPlayer(it, joinedAt = at) },
    createdAt = at,
    sequenceNumber = 10,
    game = Game(
        gameNumber = 1,
        status = GameStatus.IN_PROGRESS,
        deck = Deck(deck),
        discard = buried + top,
        hands = hands,
        turnOrder = TurnOrder(hands.keys.toList(), hands.keys.indexOf(current), direction),
        activeColor = activeColor,
        challengeWindow = challengeWindow,
        finishingOrder = emptyList(),
        turnTimerDeadline = at.plusSeconds(turnTimeoutSeconds),
        turnTimeoutSeconds = turnTimeoutSeconds,
        drewThisTurn = false,
        completedAt = null,
    ),
)

fun hand(vararg notation: String): Hand = Hand(notation.map { Card.parse(it)!! })

fun card(notation: String): Card = Card.parse(notation)!!

fun Decision.rejection(): Rejection? = (this as? Decision.Rejected)?.reason
inline fun <reified T : Event> Decision.event(): T? = events.filterIsInstance<T>().firstOrNull()
inline fun <reified T : Event> Decision.all(): List<T> = events.filterIsInstance<T>()

/** The first-card rule needs a specific opening discard, and the deal derives it from the seed. */
fun seedDealing(players: List<String>, predicate: (Card) -> Boolean): Long =
    (0L..500_000L).first { predicate(deal(players, it, STARTING_HAND_SIZE).discard.last()) }
