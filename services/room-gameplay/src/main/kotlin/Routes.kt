// The REST surface of Architecture §2.3.1. Endpoints name resources, not actions: joining is
// asserting a membership, and a move is a row appended to the immutable log that also happens to be
// the game record. Everything below /rooms requires a valid player token.

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import uno.CreateRoom
import uno.GameCompleted
import uno.GameStarted
import uno.JoinRoom
import uno.LeaveRoom
import uno.ReconnectPlayer
import uno.Rejection
import uno.RoomCreated
import uno.RoomState
import uno.RoomType
import uno.StartGame

@Serializable
data class CreateRoomBody(val roomType: String? = null, val maxPlayers: Int? = null)

/**
 * Engine rejections are domain conflicts, so they are `409` by default (Architecture §2.3.1). The
 * two exceptions are about addressing rather than state: a room that does not exist is a `404`, and
 * so is acting on a room you are not in.
 */
private fun Rejection.status(): HttpStatusCode = when (this) {
    Rejection.ROOM_NOT_FOUND -> HttpStatusCode.NotFound
    Rejection.NOT_A_MEMBER -> HttpStatusCode.NotFound
    else -> HttpStatusCode.Conflict
}

private suspend fun ApplicationCall.refuse(reason: Rejection) =
    respond(reason.status(), ErrorBody(reason.name.lowercase()))

private suspend fun ApplicationCall.stale(state: RoomState) {
    response.header("ETag", "\"${state.sequenceNumber}\"")
    respond(HttpStatusCode.PreconditionFailed, state.view())
}

private fun ApplicationCall.roomId(): UUID? = runCatching { UUID.fromString(parameters["roomId"]) }.getOrNull()

/**
 * Counted from the events that were actually committed, so a room created by an idempotent replay
 * or a game auto-started by someone else's join is counted once and in the right place. Two of
 * these are P8's required business metrics, so getting them from the log rather than from the
 * request that happened to trigger them matters.
 */
private fun countBusiness(outcome: Outcome) {
    outcome.events.forEach { event ->
        when (event) {
            is RoomCreated -> Metrics.roomsCreated.increment()
            is GameStarted -> Metrics.gamesStarted.increment()
            is GameCompleted -> Metrics.gamesCompleted.increment()
            else -> Unit
        }
    }
    if (outcome is Outcome.Refused) Metrics.rejection(outcome.reason.name.lowercase()).increment()
}

fun Route.roomRoutes(rooms: Rooms) {
    authenticate(PLAYER_AUTH) {
        // Additive read the architecture's resource table does not have (plan D5b): `room list`
        // needs a collection, and replaying every room to answer it would be absurd — so it comes
        // from the `rooms` projection.
        get("/rooms") { call.respond(rooms.listJoinable()) }

        post("/rooms") {
            val player = call.player()
            val body = call.receiveNullable<CreateRoomBody>() ?: CreateRoomBody()
            val key = call.request.headers["Idempotency-Key"]

            // A replayed key returns the original representation as 200 rather than a second room.
            key?.let { existing ->
                rooms.findIdempotent(existing, player.playerId)?.let { stored ->
                    return@post call.respond(HttpStatusCode.OK, Json.parseToJsonElement(stored))
                }
            }

            val roomId = UUID.randomUUID()
            val command = CreateRoom(
                roomId = roomId.toString(),
                creatorId = player.playerId,
                roomType = RoomType.CASUAL,
                maxPlayers = body.maxPlayers?.coerceIn(2, 10) ?: 10,
            )
            val outcome = rooms.submit(
                roomId = roomId,
                command = command,
                correlationId = call.correlationId(),
                idempotency = key?.let { k -> { state -> IdempotentCreate(k, player.playerId, Json.encodeToString(state.view())) } },
            )
            countBusiness(outcome)
            when (outcome) {
                is Outcome.Ok -> {
                    call.response.header("Location", "/rooms/$roomId")
                    call.response.header("ETag", "\"${outcome.state.sequenceNumber}\"")
                    call.respond(HttpStatusCode.Created, outcome.state.view())
                }
                // Two requests carrying the same key raced; the winner's response is now stored.
                is Outcome.Stale -> {
                    val stored = key?.let { rooms.findIdempotent(it, player.playerId) }
                    if (stored != null) call.respond(HttpStatusCode.OK, Json.parseToJsonElement(stored))
                    else call.respond(HttpStatusCode.Conflict, ErrorBody("room_creation_conflict"))
                }
                is Outcome.Refused -> call.refuse(outcome.reason)
            }
        }

        route("/rooms/{roomId}") {
            get {
                val roomId = call.roomId() ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("room_not_found"))
                val loaded = rooms.load(roomId)
                if (!loaded.found) return@get call.respond(HttpStatusCode.NotFound, ErrorBody("room_not_found"))
                call.response.header("ETag", "\"${loaded.state.sequenceNumber}\"")
                call.respond(loaded.state.view())
            }

            // Join = asserting a membership resource, so it is a PUT-shaped POST on the member URL.
            post("/players/{playerId}") {
                val roomId = call.roomId() ?: return@post call.respond(HttpStatusCode.NotFound, ErrorBody("room_not_found"))
                val player = call.player()
                if (call.parameters["playerId"] != player.playerId) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorBody("not_your_membership"))
                }
                val outcome = rooms.submit(roomId, JoinRoom(player.playerId), call.correlationId())
                countBusiness(outcome)
                when (outcome) {
                    is Outcome.Ok -> {
                        call.response.header("ETag", "\"${outcome.state.sequenceNumber}\"")
                        call.respond(HttpStatusCode.Created, outcome.state.view())
                    }
                    is Outcome.Refused -> call.refuse(outcome.reason)
                    is Outcome.Stale -> call.stale(outcome.state)
                }
            }

            // Leaving is idempotent: deleting a membership that is not there still returns 204.
            delete("/players/{playerId}") {
                val roomId = call.roomId() ?: return@delete call.respond(HttpStatusCode.NoContent)
                val player = call.player()
                if (call.parameters["playerId"] != player.playerId) {
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorBody("not_your_membership"))
                }
                when (val outcome = rooms.submit(roomId, LeaveRoom(player.playerId), call.correlationId())) {
                    is Outcome.Ok -> call.respond(HttpStatusCode.NoContent)
                    is Outcome.Refused ->
                        if (outcome.reason == Rejection.NOT_A_MEMBER || outcome.reason == Rejection.ROOM_NOT_FOUND) {
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.refuse(outcome.reason)
                        }
                    is Outcome.Stale -> call.stale(outcome.state)
                }
            }

            // Reconnect: a partial state transition of the membership that returns the player-scoped
            // game state, so a client that dropped can rehydrate in one call.
            patch("/players/{playerId}") {
                val roomId = call.roomId() ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorBody("room_not_found"))
                val player = call.player()
                if (call.parameters["playerId"] != player.playerId) {
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorBody("not_your_membership"))
                }
                when (val outcome = rooms.submit(roomId, ReconnectPlayer(player.playerId), call.correlationId())) {
                    is Outcome.Ok -> {
                        call.response.header("ETag", "\"${outcome.state.sequenceNumber}\"")
                        val game = outcome.state.gameView(player.playerId)
                        if (game != null) call.respond(game) else call.respond(outcome.state.view())
                    }
                    is Outcome.Refused -> call.refuse(outcome.reason)
                    is Outcome.Stale -> call.stale(outcome.state)
                }
            }

            // Explicit start, for a host who does not want to wait for the auto-start at minPlayers.
            post("/games") {
                val roomId = call.roomId() ?: return@post call.respond(HttpStatusCode.NotFound, ErrorBody("room_not_found"))
                val player = call.player()
                val outcome = rooms.submit(roomId, StartGame(player.playerId), call.correlationId())
                countBusiness(outcome)
                when (outcome) {
                    is Outcome.Ok -> {
                        val gameNumber = outcome.state.game?.gameNumber ?: 1
                        call.response.header("Location", "/rooms/$roomId/games/$gameNumber")
                        call.response.header("ETag", "\"${outcome.state.sequenceNumber}\"")
                        call.respond(HttpStatusCode.Created, outcome.state.gameView(player.playerId) ?: outcome.state.view())
                    }
                    is Outcome.Refused -> call.refuse(outcome.reason)
                    is Outcome.Stale -> call.stale(outcome.state)
                }
            }
        }
    }
}
