@file:UseSerializers(CardSerializer::class, InstantSerializer::class)

package uno

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
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
@Serializable
sealed interface Event {
    val at: Instant
}

/** Events carrying deck order, hand contents or a seed never reach the outbox (§2.2). */
sealed interface PrivateEvent

@Serializable
@SerialName("RoomCreated")
data class RoomCreated(
    val roomType: RoomType,
    val creatorId: String,
    val maxPlayers: Int,
    override val at: Instant,
    val tournament: TournamentLink? = null,
) : Event

@Serializable
@SerialName("PlayerJoined")
data class PlayerJoined(val playerId: String, val playerCount: Int, override val at: Instant) : Event

/**
 * Not in the catalog: leaving a room that has not started is neither a forfeit nor a disconnection,
 * and the architecture's resource table maps `DELETE /rooms/{id}/players/{pid}` to it. Additive,
 * recorded in CHANGELOG-design.md.
 */
@Serializable
@SerialName("PlayerLeft")
data class PlayerLeft(val playerId: String, val playerCount: Int, override val at: Instant) : Event

@Serializable
@SerialName("GameStarted")
data class GameStarted(
    val gameNumber: Int,
    val playerOrder: List<String>,
    val initialDiscardCard: Card,
    val initialColor: Color,
    val seed: Long,
    val turnTimeoutSeconds: Long,
    override val at: Instant,
) : Event, PrivateEvent

@Serializable
@SerialName("CardPlayed")
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
@Serializable
@SerialName("CardDrawn")
data class CardDrawn(val playerId: String, val newCardCount: Int, override val at: Instant) : Event

@Serializable
@SerialName("TurnPassed")
data class TurnPassed(val playerId: String, val nextPlayerId: String, override val at: Instant) : Event

@Serializable
@SerialName("ForcedDraw")
data class ForcedDraw(
    val targetPlayerId: String,
    val cardCount: Int,
    val newHandSize: Int,
    val reason: String,
    override val at: Instant,
) : Event

@Serializable
@SerialName("DirectionReversed")
data class DirectionReversed(val newDirection: Direction, override val at: Instant) : Event

@Serializable
@SerialName("TurnTimedOut")
data class TurnTimedOut(val playerId: String, val autoAction: String, override val at: Instant) : Event

@Serializable
@SerialName("UnoCallMade")
data class UnoCallMade(val playerId: String, override val at: Instant) : Event

@Serializable
@SerialName("ChallengeWindowOpened")
data class ChallengeWindowOpened(
    val targetPlayerId: String,
    val targetCalledUno: Boolean,
    val expiresAt: Instant,
    override val at: Instant,
) : Event

@Serializable
@SerialName("ChallengeWindowClosed")
data class ChallengeWindowClosed(
    val targetPlayerId: String,
    val reason: String,
    override val at: Instant,
) : Event

@Serializable
@SerialName("UnoChallengeIssued")
data class UnoChallengeIssued(
    val challengerId: String,
    val targetPlayerId: String,
    override val at: Instant,
) : Event

@Serializable
@SerialName("UnoChallengeResolved")
data class UnoChallengeResolved(
    val challengerId: String,
    val targetPlayerId: String,
    val challengeSucceeded: Boolean,
    val penaltyPlayerId: String?,
    val penaltyCardCount: Int,
    override val at: Instant,
) : Event

@Serializable
@SerialName("TurnSkipped")
data class TurnSkipped(
    val skippedPlayerId: String,
    val nextPlayerId: String,
    val reason: String,
    override val at: Instant,
) : Event

@Serializable
@SerialName("DeckRecycled")
data class DeckRecycled(val newDeckSize: Int, val seed: Long, override val at: Instant) : Event, PrivateEvent

@Serializable
@SerialName("PlayerDisconnected")
data class PlayerDisconnected(
    val playerId: String,
    val reconnectionDeadline: Instant,
    override val at: Instant,
) : Event

@Serializable
@SerialName("PlayerReconnected")
data class PlayerReconnected(val playerId: String, override val at: Instant) : Event

@Serializable
@SerialName("PlayerForfeited")
data class PlayerForfeited(
    val playerId: String,
    val reason: String,
    val isTournament: Boolean,
    override val at: Instant,
) : Event

@Serializable
@SerialName("GameCompleted")
data class GameCompleted(
    val roomType: RoomType,
    val gameNumber: Int,
    val finishingOrder: List<String>,
    val cardPointTotals: Map<String, Int>,
    val isAbandoned: Boolean,
    val completedAt: Instant,
    override val at: Instant,
) : Event

/**
 * A room that never filled, closed by the clock rather than by a game (architecture SG3). Not in
 * the catalog's room-gameplay list because nothing used to be able to emit it — the aggregate only
 * ever looked at deadlines when a command arrived, and a room nobody was in received none.
 */
@Serializable
@SerialName("RoomExpired")
data class RoomExpired(val reason: String, override val at: Instant) : Event

/**
 * The best-of-three verdict for one tournament room (§3.2.2). The Room owns this, not Tournament:
 * every game of the match is played by the same people in the same room, so the scores are the
 * room's own state and the tournament only needs the result. `advancingPlayers` is empty when
 * nobody is left to advance — an all-forfeit room still reports, which is what lets its round close.
 */
@Serializable
@SerialName("MatchCompleted")
data class MatchCompleted(
    val matchResults: Map<String, MatchScore>,
    val advancingPlayers: List<String>,
    override val at: Instant,
) : Event

@Serializable
@SerialName("RoomCompleted")
data class RoomCompleted(
    val roomType: RoomType,
    val finalResults: List<String>,
    override val at: Instant,
) : Event
