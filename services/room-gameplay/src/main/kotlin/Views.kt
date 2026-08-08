// The representations the API returns. Cards travel as their §5.F notation, so the string the CLI
// prints, the string in the API response and the string in `room_events` are all the same one.
//
// GameView is built for exactly one player: their own hand, and nothing but card *counts* for
// everyone else. The spectator privacy boundary P6 has to enforce depends on that being true here
// from the start, so it is a property of the only constructor rather than a filter applied later.

import kotlinx.serialization.Serializable
import uno.ConnectionStatus
import uno.GameStatus
import uno.RoomState
import uno.playableOn

@Serializable
data class RoomSummary(
    val roomId: String,
    val roomType: String,
    val status: String,
    val playerCount: Int,
    val maxPlayers: Int,
)

@Serializable
data class PlayerView(val playerId: String, val connection: String)

@Serializable
data class RoomView(
    val roomId: String,
    val roomType: String,
    val status: String,
    val maxPlayers: Int,
    val players: List<PlayerView>,
    val gameNumber: Int?,
    val sequenceNumber: Int,
)

@Serializable
data class OpponentView(
    val playerId: String,
    val cardCount: Int,
    val calledUno: Boolean,
    val connection: String,
)

@Serializable
data class ChallengeView(val targetPlayerId: String, val expiresAt: String)

@Serializable
data class GameView(
    val roomId: String,
    val gameNumber: Int,
    val status: String,
    val sequenceNumber: Int,
    val discardTop: String,
    val activeColor: String,
    val direction: String,
    val deckSize: Int,
    val currentPlayerId: String?,
    val yourTurn: Boolean,
    val hand: List<String>,
    val playable: List<Int>,
    val opponents: List<OpponentView>,
    val challengeWindow: ChallengeView?,
    val finishingOrder: List<String>,
    val turnDeadline: String?,
    val drewThisTurn: Boolean,
)

private fun ConnectionStatus.label(): String = when (this) {
    is ConnectionStatus.Connected -> "connected"
    is ConnectionStatus.Disconnected -> "disconnected"
    is ConnectionStatus.Forfeited -> "forfeited"
}

fun RoomState.view(): RoomView = RoomView(
    roomId = roomId,
    roomType = roomType.name,
    status = status.name,
    maxPlayers = maxPlayers,
    players = players.map { PlayerView(it.playerId, it.connection.label()) },
    gameNumber = game?.gameNumber,
    sequenceNumber = sequenceNumber,
)

fun RoomState.summary(): RoomSummary = RoomSummary(
    roomId = roomId,
    roomType = roomType.name,
    status = status.name,
    playerCount = players.size,
    maxPlayers = maxPlayers,
)

fun RoomState.gameView(forPlayer: String): GameView? {
    val game = game ?: return null
    val hand = game.hands[forPlayer]?.cards.orEmpty()
    val inPlay = game.status == GameStatus.IN_PROGRESS
    return GameView(
        roomId = roomId,
        gameNumber = game.gameNumber,
        status = game.status.name,
        sequenceNumber = sequenceNumber,
        discardTop = game.top.toString(),
        activeColor = game.activeColor.name,
        direction = game.turnOrder.direction.name,
        deckSize = game.deck.size,
        currentPlayerId = if (inPlay) game.currentPlayer else null,
        yourTurn = inPlay && game.currentPlayer == forPlayer,
        hand = hand.map { it.toString() },
        // Which cards are legal right now, so the CLI can mark them without reimplementing the rules.
        playable = hand.mapIndexedNotNull { index, card ->
            index.takeIf { inPlay && card.playableOn(game.top, game.activeColor) }
        },
        opponents = players.filter { it.playerId != forPlayer }.map { player ->
            val theirHand = game.hands[player.playerId]
            OpponentView(
                playerId = player.playerId,
                cardCount = theirHand?.size ?: 0,
                calledUno = theirHand?.hasCalledUno ?: false,
                connection = player.connection.label(),
            )
        },
        challengeWindow = game.challengeWindow?.let { ChallengeView(it.targetPlayerId, it.expiresAt.toString()) },
        finishingOrder = game.finishingOrder,
        turnDeadline = game.turnTimerDeadline?.toString(),
        drewThisTurn = game.drewThisTurn,
    )
}
