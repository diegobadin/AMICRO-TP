// What the API answers with. Separate types from the aggregate on purpose: the state carries a
// per-round structure the CLI has no use for, and a view type with nowhere to put a field is the
// same structural guarantee P6's spectator relies on.

import kotlinx.serialization.Serializable

@Serializable
data class TournamentSummary(
    val tournamentId: String,
    val status: String,
    val playerCount: Int,
    val minPlayers: Int,
    val currentRound: Int,
)

@Serializable
data class BracketRoom(
    val roundNumber: Int,
    val roomId: String,
    val players: List<String>,
    val advancing: List<String>?,
    val isFinal: Boolean,
)

@Serializable
data class RoundView(
    val roundNumber: Int,
    val isFinal: Boolean,
    val complete: Boolean,
    val rooms: List<RoomView>,
)

@Serializable
data class RoomView(
    val roomId: String,
    val players: List<String>,
    val advancing: List<String>?,
)

@Serializable
data class TournamentView(
    val tournamentId: String,
    val status: String,
    val minPlayers: Int,
    val roomSize: Int,
    val registered: List<String>,
    val currentRound: Int,
    val rounds: List<RoundView>,
    val champion: String? = null,
    val finalPlacements: List<String> = emptyList(),
)

/**
 * Where a given player is right now — the one question `tournament register` asks on a loop, and
 * the CLI should not have to search a bracket to answer it.
 */
@Serializable
data class PlayerPlacement(
    val tournamentId: String,
    val status: String,
    val roundNumber: Int? = null,
    val roomId: String? = null,
    val eliminated: Boolean = false,
    val champion: Boolean = false,
)

fun TournamentState.view(): TournamentView = TournamentView(
    tournamentId = tournamentId,
    status = status.name,
    minPlayers = config.minPlayers,
    roomSize = config.roomSize,
    registered = registered,
    currentRound = currentRound?.roundNumber ?: 0,
    rounds = rounds.map { round ->
        RoundView(
            roundNumber = round.roundNumber,
            isFinal = round.isFinal,
            complete = round.complete,
            rooms = round.rooms.map { RoomView(it.roomId, it.players, it.advancing) },
        )
    },
    champion = champion,
    finalPlacements = finalPlacements,
)

/**
 * A player is in the room of the latest round that seated them. "Eliminated" is the honest answer
 * only once the round they were in has reported: until then they are still playing, and a client
 * that showed otherwise would be guessing.
 */
fun TournamentState.placementOf(playerId: String): PlayerPlacement {
    val seated = rounds.lastOrNull { round -> round.rooms.any { playerId in it.players } }
    val room = seated?.rooms?.firstOrNull { playerId in it.players }
    val eliminated = when {
        status == TournamentStatus.COMPLETED -> champion != playerId
        room?.advancing != null -> playerId !in room.advancing
        else -> false
    }
    return PlayerPlacement(
        tournamentId = tournamentId,
        status = status.name,
        roundNumber = seated?.roundNumber,
        roomId = room?.roomId,
        eliminated = eliminated,
        champion = status == TournamentStatus.COMPLETED && champion == playerId,
    )
}
