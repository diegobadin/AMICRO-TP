package uno

import java.time.Instant

enum class RoomType { CASUAL, TOURNAMENT }
enum class RoomStatus { WAITING, IN_PROGRESS, COMPLETED }
enum class GameStatus { IN_PROGRESS, COMPLETED }

/** §3.2.7. `Forfeited` is terminal: a forfeited seat is never reconnected. */
sealed interface ConnectionStatus {
    data object Connected : ConnectionStatus
    data class Disconnected(val since: Instant, val deadline: Instant) : ConnectionStatus
    data object Forfeited : ConnectionStatus
}

data class RoomPlayer(
    val playerId: String,
    val connection: ConnectionStatus = ConnectionStatus.Connected,
    val joinedAt: Instant,
) {
    val isActive: Boolean get() = connection != ConnectionStatus.Forfeited
}

data class ChallengeWindow(
    val targetPlayerId: String,
    val targetCalledUno: Boolean,
    val openedAt: Instant,
    val expiresAt: Instant,
)

data class Game(
    val gameNumber: Int,
    val status: GameStatus,
    val deck: Deck,
    val discard: List<Card>,
    val hands: Map<String, Hand>,
    val turnOrder: TurnOrder,
    val activeColor: Color,
    val challengeWindow: ChallengeWindow?,
    val finishingOrder: List<String>,
    val turnTimerDeadline: Instant?,
    val turnTimeoutSeconds: Long,
    /** `PassTurn` is only legal once the player has taken their draw for this turn (§4.1). */
    val drewThisTurn: Boolean,
    val completedAt: Instant?,
) {
    val top: Card get() = discard.last()
    val currentPlayer: String get() = turnOrder.current
}

/**
 * The Room aggregate (§3.2.1) — the consistency boundary the whole platform serialises through.
 * `sequenceNumber` counts events, not commands: it is the value the unique index on
 * `(room_id, sequence_number)` enforces, which is what makes optimistic concurrency correct at any
 * replica count (E6).
 */
data class RoomState(
    val roomId: String = "",
    val roomType: RoomType = RoomType.CASUAL,
    val status: RoomStatus = RoomStatus.WAITING,
    val maxPlayers: Int = 10,
    val creatorId: String? = null,
    val players: List<RoomPlayer> = emptyList(),
    val game: Game? = null,
    val gamesPlayed: Int = 0,
    val sequenceNumber: Int = 0,
    val createdAt: Instant? = null,
) {
    val exists: Boolean get() = createdAt != null
    val maxGames: Int get() = if (roomType == RoomType.CASUAL) 1 else 3

    fun player(playerId: String): RoomPlayer? = players.firstOrNull { it.playerId == playerId }
    val activePlayers: List<RoomPlayer> get() = players.filter { it.isActive }
}

/** Domain constants, not configuration: these are rules, and a drill that changed them would be
 *  drilling a different game. Only the turn timer is a lever (plan D11). */
const val CHALLENGE_WINDOW_SECONDS = 5L
const val RECONNECTION_WINDOW_SECONDS = 60L
const val UNO_PENALTY_CARDS = 2
const val STARTING_HAND_SIZE = 7
const val MIN_PLAYERS_TO_PLAY = 2
