package uno

import java.time.Instant

/**
 * Names and payload fields match `docs/design/04-commands-events.md` exactly (plan D4): these
 * become the Kafka payloads in P5 and the consumers' contract in P6/P7, so renaming one later is a
 * breaking change disguised as a refactor.
 *
 * `at` is the server-authoritative timestamp of §3.2.6. Deadlines are derived from it during
 * replay rather than recomputed from the current clock, which is what keeps a rebuilt aggregate
 * identical to the one that was served.
 */
sealed interface Event {
    val at: Instant
}

/** Events carrying deck order, hand contents or a seed never reach the outbox (§2.2). */
sealed interface PrivateEvent

data class RoomCreated(
    val roomType: RoomType,
    val creatorId: String,
    val maxPlayers: Int,
    override val at: Instant,
) : Event

data class PlayerJoined(val playerId: String, val playerCount: Int, override val at: Instant) : Event

/**
 * Not in the catalog: leaving a room that has not started is neither a forfeit nor a disconnection,
 * and the architecture's resource table maps `DELETE /rooms/{id}/players/{pid}` to it. Additive,
 * recorded in CHANGELOG-design.md.
 */
data class PlayerLeft(val playerId: String, val playerCount: Int, override val at: Instant) : Event

data class GameStarted(
    val gameNumber: Int,
    val playerOrder: List<String>,
    val initialDiscardCard: Card,
    val initialColor: Color,
    val seed: Long,
    val turnTimeoutSeconds: Long,
    override val at: Instant,
) : Event, PrivateEvent

data class CardPlayed(
    val playerId: String,
    val card: Card,
    val newDiscardTop: Card,
    val playerCardCount: Int,
    val chosenColor: Color?,
    val nextPlayerId: String,
    override val at: Instant,
) : Event

/** The card's identity stays out of the payload; replay takes it from the seeded deck (§4.1). */
data class CardDrawn(val playerId: String, val newCardCount: Int, override val at: Instant) : Event

data class TurnPassed(val playerId: String, val nextPlayerId: String, override val at: Instant) : Event

data class ForcedDraw(
    val targetPlayerId: String,
    val cardCount: Int,
    val newHandSize: Int,
    val reason: String,
    override val at: Instant,
) : Event

data class DirectionReversed(val newDirection: Direction, override val at: Instant) : Event

data class TurnTimedOut(val playerId: String, val autoAction: String, override val at: Instant) : Event

data class UnoCallMade(val playerId: String, override val at: Instant) : Event

data class ChallengeWindowOpened(
    val targetPlayerId: String,
    val targetCalledUno: Boolean,
    val expiresAt: Instant,
    override val at: Instant,
) : Event

data class ChallengeWindowClosed(
    val targetPlayerId: String,
    val reason: String,
    override val at: Instant,
) : Event

data class UnoChallengeIssued(
    val challengerId: String,
    val targetPlayerId: String,
    override val at: Instant,
) : Event

data class UnoChallengeResolved(
    val challengerId: String,
    val targetPlayerId: String,
    val challengeSucceeded: Boolean,
    val penaltyPlayerId: String?,
    val penaltyCardCount: Int,
    override val at: Instant,
) : Event

data class TurnSkipped(
    val skippedPlayerId: String,
    val nextPlayerId: String,
    val reason: String,
    override val at: Instant,
) : Event

data class DeckRecycled(val newDeckSize: Int, val seed: Long, override val at: Instant) : Event, PrivateEvent

data class PlayerDisconnected(
    val playerId: String,
    val reconnectionDeadline: Instant,
    override val at: Instant,
) : Event

data class PlayerReconnected(val playerId: String, override val at: Instant) : Event

data class PlayerForfeited(
    val playerId: String,
    val reason: String,
    val isTournament: Boolean,
    override val at: Instant,
) : Event

data class GameCompleted(
    val roomType: RoomType,
    val gameNumber: Int,
    val finishingOrder: List<String>,
    val cardPointTotals: Map<String, Int>,
    val isAbandoned: Boolean,
    val completedAt: Instant,
    override val at: Instant,
) : Event

data class RoomCompleted(
    val roomType: RoomType,
    val finalResults: List<String>,
    override val at: Instant,
) : Event
