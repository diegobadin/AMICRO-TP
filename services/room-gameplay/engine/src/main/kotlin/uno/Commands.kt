package uno

sealed interface Command

data class CreateRoom(
    val roomId: String,
    val creatorId: String,
    val roomType: RoomType = RoomType.CASUAL,
    val maxPlayers: Int = 10,
) : Command

data class JoinRoom(val playerId: String) : Command
data class LeaveRoom(val playerId: String) : Command
data class StartGame(val requestedBy: String?) : Command
data class PlayCard(
    val playerId: String,
    val card: Card,
    val chosenColor: Color? = null,
    val callingUno: Boolean = false,
) : Command

data class DrawCard(val playerId: String) : Command
data class PassTurn(val playerId: String) : Command
data class CallUno(val playerId: String) : Command
data class ChallengeUno(val challengerId: String, val targetPlayerId: String) : Command
data class ReconnectPlayer(val playerId: String) : Command
data class ForfeitPlayer(val playerId: String, val reason: String) : Command

/**
 * Raised by the `identity.session-events` consumer rather than by a player (§2.3.3). Idempotent by
 * design: a redelivered `SessionInvalidated` for someone already disconnected must not re-open a
 * window that has since expired (plan D8).
 */
data class DisconnectPlayer(val playerId: String, val reason: String) : Command

/**
 * Raised by the timer worker, and the only command that asks for nothing (P5 E1). `decide` expires
 * what is overdue before judging any command, so a tick is that step with no command behind it —
 * which is also why a duplicate tick is free: the second one finds nothing left to expire.
 */
data object Tick : Command

/**
 * A rejection is not an event. Appending one would consume the sequence number the client is about
 * to retry with, and AC-P3.3 requires a failed command to leave the log untouched — so the reason
 * travels in the HTTP response instead (§4.1 `StaleCommandRejected`, recorded as a delta).
 */
enum class Rejection {
    ROOM_NOT_FOUND,
    ROOM_ALREADY_EXISTS,
    ROOM_FULL,
    ROOM_ALREADY_STARTED,
    ROOM_COMPLETED,
    NOT_A_MEMBER,
    ALREADY_JOINED,
    NOT_ENOUGH_PLAYERS,
    GAME_IN_PROGRESS,
    NO_GAME_IN_PROGRESS,
    NOT_YOUR_TURN,
    CARD_NOT_IN_HAND,
    ILLEGAL_PLAY,
    WILD_NEEDS_COLOR,
    COLOR_ON_NON_WILD,
    MUST_DRAW_BEFORE_PASSING,
    ALREADY_DREW_THIS_TURN,
    UNO_CALL_NOT_AVAILABLE,
    NO_OPEN_CHALLENGE,
    CHALLENGE_NOT_VALID,
    NOT_DISCONNECTED,
    RECONNECTION_EXPIRED,
}

/**
 * A rejected command can still have produced events: the deadlines that expired before it was
 * judged are real state changes caused by time passing, not by the command, and dropping them would
 * mean a player never learns their turn timed out. Both outcomes therefore carry a list to persist.
 */
sealed interface Decision {
    val events: List<Event>

    data class Accepted(override val events: List<Event>) : Decision
    data class Rejected(val reason: Rejection, override val events: List<Event> = emptyList()) : Decision
}
