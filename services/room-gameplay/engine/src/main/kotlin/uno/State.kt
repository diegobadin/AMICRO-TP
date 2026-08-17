package uno

import java.time.Instant

@kotlinx.serialization.Serializable
enum class RoomType { CASUAL, TOURNAMENT }

/**
 * §3.2's `Tournament { tournamentId, roundNumber }`. `advanceCount` rides along because the number
 * of seats that advance is a per-room property the tournament sets when it provisions the room —
 * the room decides who advances, and it cannot do that without knowing how many.
 */
@kotlinx.serialization.Serializable
data class TournamentLink(val tournamentId: String, val roundNumber: Int, val advanceCount: Int)

/** §3.2's per-player match record, cumulative across the games of one tournament room. */
@kotlinx.serialization.Serializable
data class MatchScore(val wins: Int = 0, val losses: Int = 0, val cardPoints: Int = 0)

/**
 * §6.8.3's tiebreakers in order: match wins, then cumulative card points, then who went out first in
 * the last game. The design's third key is "earliest final-game completion time" — one game
 * completes at a single instant for everyone here, so the finishing order is that same fact at
 * better resolution. `playerId` last, which is §8's assumption and makes the order total: two
 * players with identical records still rank, deterministically, on every replay.
 */
/**
 * True once the games left cannot change who is holding the advancing seats — a best-of-three at
 * 2-0 does not play a third game (§3.2's game-count boundary).
 *
 * Strictly less, not less-or-equal: a player who can still draw **level** with the last advancing
 * seat would then be separated by card points, and a match decided by a tiebreak that has not
 * happened yet is not decided. The difference only shows in a room bigger than two, which is
 * exactly why it is a function with its own test rather than a condition inside the flow.
 */
fun matchDecided(state: RoomState): Boolean {
    val scores = state.matchScores ?: return true
    val advance = state.tournament?.advanceCount ?: return true
    val remaining = state.maxGames - state.gamesPlayed
    val ranked = standings(scores, state.game?.finishingOrder ?: emptyList())
    if (remaining <= 0 || ranked.size <= advance) return true
    val boundary = scores.getValue(ranked[advance - 1]).wins
    return ranked.drop(advance).all { scores.getValue(it).wins + remaining < boundary }
}

fun standings(scores: Map<String, MatchScore>, lastGameOrder: List<String>): List<String> {
    val finishedAt = { player: String -> lastGameOrder.indexOf(player).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    return scores.keys.sortedWith(
        compareByDescending<String> { scores.getValue(it).wins }
            .thenBy { scores.getValue(it).cardPoints }
            .thenBy(finishedAt)
            .thenBy { it },
    )
}
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
    /** Turns each player has let lapse in a row; a seat nobody is sitting in is given up (P5 E2). */
    val consecutiveTimeouts: Map<String, Int> = emptyMap(),
    /**
     * Whose timeout is mid-batch. A timeout draws and passes *for* the player, so without this the
     * events it emits would look like the player acting and clear the streak that same instant —
     * the counter would never reach two.
     */
    val timingOut: String? = null,
) {
    val top: Card get() = discard.last()
    val currentPlayer: String get() = turnOrder.current

    fun timeouts(playerId: String): Int = consecutiveTimeouts[playerId] ?: 0
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
    /** Set only for a room a tournament provisioned; null is what makes a room casual in practice. */
    val tournament: TournamentLink? = null,
    val status: RoomStatus = RoomStatus.WAITING,
    val maxPlayers: Int = 10,
    val creatorId: String? = null,
    val players: List<RoomPlayer> = emptyList(),
    val game: Game? = null,
    /** Null for a casual room, which plays one game and has no series to keep score of. */
    val matchScores: Map<String, MatchScore>? = null,
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
