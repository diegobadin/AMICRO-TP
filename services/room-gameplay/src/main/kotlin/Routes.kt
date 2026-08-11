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
import uno.CallUno
import uno.Card
import uno.ChallengeUno
import uno.Color
import uno.Command
import uno.DrawCard
import uno.Event
import uno.GameCompleted
import uno.GameStarted
import uno.JoinRoom
import uno.LeaveRoom
import uno.PassTurn
import uno.PlayCard
import uno.PlayerForfeited
import uno.ReconnectPlayer
import uno.Rejection
import uno.RoomCreated
import uno.RoomExpired
import uno.RoomState
import uno.StartGame
import uno.Tick

@Serializable
data class CreateRoomBody(val roomType: String? = null, val maxPlayers: Int? = null)

/**
 * One move shape for the whole log (§2.3.1). `type` is the wire name; `card` is the §5.F notation,
 * so what the player typed, what the API carries and what lands in `room_events` never diverge.
 */
@Serializable
data class MoveBody(
    val type: String,
    val card: String? = null,
    val chosenColor: String? = null,
    val callingUno: Boolean = false,
    val targetPlayerId: String? = null,
)

private fun MoveBody.toCommand(playerId: String): Command? = when (type) {
    "play_card" -> Card.parse(card ?: return null)?.let {
        PlayCard(playerId, it, chosenColor?.let { c -> runCatching { Color.valueOf(c.uppercase()) }.getOrNull() }, callingUno)
    }
    "draw_card" -> DrawCard(playerId)
    "pass" -> PassTurn(playerId)
    "call_uno" -> CallUno(playerId)
    "challenge_uno" -> ChallengeUno(playerId, targetPlayerId ?: return null)
    else -> null
}

/** `If-Match: "42"` and the weak form both mean sequence 42. */
private fun parseETag(header: String?): Int? =
    header?.trim()?.removePrefix("W/")?.trim('"')?.toIntOrNull()

/** Ktor's enum stops at 426, and RFC 6585's 428 is what an absent If-Match has to answer. */
private val PreconditionRequired = HttpStatusCode(428, "Precondition Required")

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

/** The sequence number is the entity tag; every response that carries state carries it. */
private fun ApplicationCall.tag(state: RoomState) =
    response.header("ETag", "\"${state.sequenceNumber}\"")

private suspend fun ApplicationCall.stale(state: RoomState) {
    tag(state)
    respond(HttpStatusCode.PreconditionFailed, state.view())
}

private fun ApplicationCall.roomId(): UUID? = runCatching { UUID.fromString(parameters["roomId"]) }.getOrNull()

/**
 * The three membership verbs share one guard: a real room id, and a URL that names the caller. A
 * player may only assert their own membership — `{playerId}` is addressing, not authorisation.
 * Answers the call and returns null when either fails, so each handler starts with one line.
 */
private suspend fun ApplicationCall.ownMembership(): Pair<UUID, String>? {
    val roomId = roomId() ?: run {
        respond(HttpStatusCode.NotFound, ErrorBody("room_not_found"))
        return null
    }
    val playerId = player().playerId
    if (parameters["playerId"] != playerId) {
        respond(HttpStatusCode.Forbidden, ErrorBody("not_your_membership"))
        return null
    }
    return roomId to playerId
}

/**
 * Counted from the events that were actually committed, so a room created by an idempotent replay
 * or a game auto-started by someone else's join is counted once and in the right place. Two of
 * these are P8's required business metrics, so getting them from the log rather than from the
 * request that happened to trigger them matters — and it is why **every** mutating route calls
 * this, including leave: leaving a two-player game forfeits, which ends the game (invariant 7) and
 * emits a `GameCompleted` no one requested.
 */
private fun countBusiness(events: List<Event>, refused: Rejection? = null) {
    events.forEach { event ->
        when (event) {
            is RoomCreated -> Metrics.roomsCreated.increment()
            is GameStarted -> Metrics.gamesStarted.increment()
            is GameCompleted -> Metrics.gamesCompleted.increment()
            is RoomExpired -> Metrics.roomsExpired.increment()
            // Counted from the event rather than from the tick, because a forfeit for idleness also
            // arrives on the command of whoever happens to wake the room up first.
            is PlayerForfeited -> if (event.reason == "idle") Metrics.idleForfeits.increment()
            else -> Unit
        }
    }
    refused?.let { Metrics.rejection(it.name.lowercase()).increment() }
}

private fun countBusiness(outcome: Outcome) =
    countBusiness(outcome.events, (outcome as? Outcome.Refused)?.reason)

fun Route.roomRoutes(rooms: Rooms) {
    authenticate(PLAYER_AUTH) {
        // Additive read the architecture's resource table does not have (plan D5b): `room list`
        // needs a collection, and replaying every room to answer it would be absurd — so it comes
        // from the `rooms` projection.
        get("/rooms") { call.respond(rooms.listJoinable()) }

        post("/rooms") {
            val player = call.player()
            val body = call.receiveNullable<CreateRoomBody>() ?: CreateRoomBody()
            val outcome = rooms.create(
                playerId = player.playerId,
                maxPlayers = body.maxPlayers?.coerceIn(2, 10) ?: 10,
                idempotencyKey = call.request.headers["Idempotency-Key"],
                correlationId = call.correlationId(),
                render = { state -> Json.encodeToString(state.view()) },
            )
            when (outcome) {
                is CreateOutcome.Created -> {
                    countBusiness(outcome.events)
                    call.response.header("Location", "/rooms/${outcome.roomId}")
                    call.tag(outcome.state)
                    call.respond(HttpStatusCode.Created, outcome.state.view())
                }
                // A replayed key returns the original representation, as 200 rather than 201: the
                // creation already happened, this request did not perform one.
                is CreateOutcome.Replayed ->
                    call.respond(HttpStatusCode.OK, Json.parseToJsonElement(outcome.response))
            }
        }

        route("/rooms/{roomId}") {
            get {
                val roomId = call.roomId() ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("room_not_found"))
                val loaded = rooms.load(roomId)
                if (!loaded.found) return@get call.respond(HttpStatusCode.NotFound, ErrorBody("room_not_found"))
                call.tag(loaded.state)
                call.respond(loaded.state.view())
            }

            // Join = asserting a membership resource, so it is a PUT-shaped POST on the member URL.
            post("/players/{playerId}") {
                val (roomId, playerId) = call.ownMembership() ?: return@post
                val outcome = rooms.submit(roomId, JoinRoom(playerId), call.correlationId())
                countBusiness(outcome)
                when (outcome) {
                    is Outcome.Ok -> {
                        call.tag(outcome.state)
                        call.respond(HttpStatusCode.Created, outcome.state.view())
                    }
                    is Outcome.Refused -> call.refuse(outcome.reason)
                    is Outcome.Stale -> call.stale(outcome.state)
                }
            }

            // Leaving is idempotent: deleting a membership that is not there still returns 204.
            delete("/players/{playerId}") {
                val (roomId, playerId) = call.ownMembership() ?: return@delete
                val outcome = rooms.submit(roomId, LeaveRoom(playerId), call.correlationId())
                countBusiness(outcome)
                when (outcome) {
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
                val (roomId, playerId) = call.ownMembership() ?: return@patch
                val outcome = rooms.submit(roomId, ReconnectPlayer(playerId), call.correlationId())
                countBusiness(outcome)
                when (outcome) {
                    is Outcome.Ok -> {
                        call.tag(outcome.state)
                        val game = outcome.state.gameView(playerId)
                        if (game != null) call.respond(game) else call.respond(outcome.state.view())
                    }
                    is Outcome.Refused -> call.refuse(outcome.reason)
                    is Outcome.Stale -> call.stale(outcome.state)
                }
            }

            /**
             * The player-scoped view, and the client's resync point. `If-None-Match` makes the poll
             * cheap (E4): while nothing has happened the answer is a `304` with no body, and P4
             * replaces the polling loop with SSE while keeping this endpoint as the reconnect read.
             */
            get("/games/{gameNumber}") {
                val roomId = call.roomId() ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("room_not_found"))
                val player = call.player()
                val loaded = rooms.load(roomId)
                if (!loaded.found) return@get call.respond(HttpStatusCode.NotFound, ErrorBody("room_not_found"))

                // Membership first: entitlement is checked before a hand is ever assembled, not
                // after. A stranger gets nothing, not an empty one.
                if (loaded.state.player(player.playerId) == null) {
                    return@get call.respond(HttpStatusCode.NotFound, ErrorBody("not_a_member"))
                }
                val view = loaded.state.gameView(player.playerId)
                    ?.takeIf { it.gameNumber == call.parameters["gameNumber"]?.toIntOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("no_game"))

                call.tag(loaded.state)
                if (parseETag(call.request.headers["If-None-Match"]) == loaded.state.sequenceNumber) {
                    return@get call.respond(HttpStatusCode.NotModified)
                }
                call.respond(view)
            }

            /**
             * A move is a row appended to the immutable log. `If-Match` is mandatory (§2.3.1): a
             * client that does not say which state it was looking at cannot be told its move raced
             * someone else's, and silently applying it is how a player loses a turn they never took.
             */
            post("/games/{gameNumber}/moves") {
                val roomId = call.roomId() ?: return@post call.respond(HttpStatusCode.NotFound, ErrorBody("room_not_found"))
                val player = call.player()
                val body = call.receiveNullable<MoveBody>()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("malformed_move"))

                val expected = parseETag(call.request.headers["If-Match"])
                    ?: return@post call.respond(PreconditionRequired, ErrorBody("if_match_required"))

                val command = body.toCommand(player.playerId)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("malformed_move"))

                val outcome = rooms.submit(roomId, command, call.correlationId(), expectedSequence = expected)
                countBusiness(outcome)
                when (outcome) {
                    is Outcome.Ok -> {
                        Metrics.move(body.type, "ok").increment()
                        val view = outcome.state.gameView(player.playerId)
                        call.tag(outcome.state)
                        call.response.header(
                            "Location",
                            "/rooms/$roomId/games/${view?.gameNumber ?: 1}/moves/${outcome.state.sequenceNumber}",
                        )
                        if (view != null) call.respond(HttpStatusCode.Created, view)
                        else call.respond(HttpStatusCode.Created, outcome.state.view())
                    }
                    is Outcome.Refused -> {
                        Metrics.move(body.type, "refused").increment()
                        call.tag(outcome.state)
                        call.refuse(outcome.reason)
                    }
                    // 412 carries the current state, so the loser of a race reconciles from the
                    // response instead of having to go and fetch it.
                    is Outcome.Stale -> {
                        Metrics.move(body.type, "stale").increment()
                        call.tag(outcome.state)
                        val view = outcome.state.gameView(player.playerId)
                        if (view != null) call.respond(HttpStatusCode.PreconditionFailed, view)
                        else call.stale(outcome.state)
                    }
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
                        call.tag(outcome.state)
                        call.respond(HttpStatusCode.Created, outcome.state.gameView(player.playerId) ?: outcome.state.view())
                    }
                    is Outcome.Refused -> call.refuse(outcome.reason)
                    is Outcome.Stale -> call.stale(outcome.state)
                }
            }
        }
    }
}

@Serializable
data class TickResult(val sequenceNumber: Int, val events: Int)

/**
 * The timer worker's only entry point (P5 E1). It carries no game knowledge — it says "this room's
 * clock has run out" and the aggregate decides what that means, which is why a tick that arrives
 * late, twice, or for nothing at all is an empty no-op rather than a second source of truth.
 *
 * Deliberately outside `/rooms`: no pattern in the gateway's whitelist matches an internal path, so
 * this is unreachable from outside the cluster, and `SYSTEM_AUTH` refuses a caller who is not the
 * worker even from inside it.
 */
fun Route.internalRoutes(rooms: Rooms) {
    authenticate(SYSTEM_AUTH) {
        post("/internal/rooms/{roomId}/tick") {
            val roomId = call.roomId()
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorBody("room_not_found"))
            val outcome = rooms.submit(roomId, Tick, call.correlationId())
            countBusiness(outcome)
            when (outcome) {
                is Outcome.Ok -> {
                    Metrics.timerTick(if (outcome.events.isEmpty()) "nothing_due" else "fired").increment()
                    call.respond(TickResult(outcome.state.sequenceNumber, outcome.events.size))
                }
                is Outcome.Refused -> {
                    Metrics.timerTick("refused").increment()
                    call.refuse(outcome.reason)
                }
                is Outcome.Stale -> {
                    Metrics.timerTick("contended").increment()
                    call.respond(TickResult(outcome.state.sequenceNumber, 0))
                }
            }
        }
    }
}
