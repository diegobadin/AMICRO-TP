// The REST surface of architecture §3.3.1, plus the two additive reads the CLI needs. Every route
// requires the gateway's headers: this service is ClusterIP and the gateway is the only door, so
// `X-Player-Id` + `X-Session-Id` is the whole of authentication here, exactly as in room-gameplay.
//
// `CreateTournament` is "Admin/System" in the catalog. There is no admin role in identity and P7
// does not invent one, so any session may open a tournament — recorded as out of scope rather than
// quietly assumed.

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

private fun ApplicationCall.tournamentId(): UUID? =
    runCatching { UUID.fromString(parameters["tournamentId"]) }.getOrNull()

private fun Rejection.status(): HttpStatusCode = when (this) {
    Rejection.TOURNAMENT_NOT_FOUND -> HttpStatusCode.NotFound
    Rejection.UNKNOWN_ROOM -> HttpStatusCode.NotFound
    else -> HttpStatusCode.Conflict
}

private suspend fun ApplicationCall.refuse(reason: Rejection) =
    respond(reason.status(), ErrorBody(reason.name.lowercase()))

fun Route.tournamentRoutes(tournaments: Tournaments) {
    authenticate(PLAYER_AUTH) {
        get("/tournaments") { call.respond(tournaments.open()) }

        post("/tournaments") {
            val outcome = tournaments.create(call.correlationId())
            if (outcome !is Outcome.Ok) return@post call.respond(HttpStatusCode.InternalServerError, ErrorBody("create_failed"))
            call.response.header("Location", "/tournaments/${outcome.state.tournamentId}")
            call.respond(HttpStatusCode.Created, outcome.state.view())
        }

        route("/tournaments/{tournamentId}") {
            get {
                val id = call.tournamentId() ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("tournament_not_found"))
                val loaded = tournaments.load(id)
                if (!loaded.found) return@get call.respond(HttpStatusCode.NotFound, ErrorBody("tournament_not_found"))
                call.respond(loaded.state.view())
            }

            /** Where am I? The only question the CLI's wait loop asks. */
            get("/players/{playerId}") {
                val id = call.tournamentId() ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("tournament_not_found"))
                val loaded = tournaments.load(id)
                if (!loaded.found) return@get call.respond(HttpStatusCode.NotFound, ErrorBody("tournament_not_found"))
                call.respond(loaded.state.placementOf(call.player().playerId))
            }

            get("/bracket") {
                val id = call.tournamentId() ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("tournament_not_found"))
                call.respond(tournaments.bracket(id))
            }

            post("/register") {
                val id = call.tournamentId() ?: return@post call.respond(HttpStatusCode.NotFound, ErrorBody("tournament_not_found"))
                when (val outcome = tournaments.submit(id, RegisterPlayer(call.player().playerId), call.correlationId())) {
                    is Outcome.Ok -> call.respond(HttpStatusCode.Created, outcome.state.view())
                    is Outcome.Refused -> call.refuse(outcome.reason)
                    Outcome.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorBody("tournament_not_found"))
                }
            }

            // Idempotent, like leaving a room: unregistering twice is still 204.
            delete("/register") {
                val id = call.tournamentId() ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorBody("tournament_not_found"))
                when (val outcome = tournaments.submit(id, UnregisterPlayer(call.player().playerId), call.correlationId())) {
                    is Outcome.Ok -> call.respond(HttpStatusCode.NoContent)
                    is Outcome.Refused ->
                        if (outcome.reason == Rejection.NOT_REGISTERED) call.respond(HttpStatusCode.NoContent)
                        else call.refuse(outcome.reason)
                    Outcome.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorBody("tournament_not_found"))
                }
            }

            get("/rounds/{roundNumber}") {
                val id = call.tournamentId() ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("tournament_not_found"))
                val loaded = tournaments.load(id)
                val number = call.parameters["roundNumber"]?.toIntOrNull()
                val round = number?.let { loaded.state.round(it) }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("round_not_found"))
                call.respond(
                    RoundView(
                        roundNumber = round.roundNumber,
                        isFinal = round.isFinal,
                        complete = round.complete,
                        rooms = round.rooms.map { RoomView(it.roomId, it.players, it.advancing) },
                    ),
                )
            }
        }
    }
}
