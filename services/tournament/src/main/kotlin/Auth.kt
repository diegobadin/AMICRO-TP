// The same trust boundary room-gameplay sits behind, for the same reason: this service is
// ClusterIP, the gateway is the only way in from outside, and the gateway builds `X-Player-Id` /
// `X-Session-Id` from scratch on every request so a client cannot supply its own. Either header
// alone is a 401, which is correct rather than a bug.
//
// No signing key lives here. A tournament has no need to validate a token the gateway already did.

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.authentication
import io.ktor.server.response.respond

const val PLAYER_AUTH = "player"
const val PLAYER_HEADER = "X-Player-Id"
const val SESSION_HEADER = "X-Session-Id"

/** A system id is refused here as firmly as a player id is on room-gameplay's internal routes. */
const val SYSTEM_PREFIX = "system:"

data class Player(val playerId: String, val sessionId: String)

class GatewayIdentity(config: Config) : AuthenticationProvider(config) {

    class Config(name: String) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val playerId = context.call.request.headers[PLAYER_HEADER]
        val sessionId = context.call.request.headers[SESSION_HEADER]
        if (playerId.isNullOrBlank() || sessionId.isNullOrBlank() || playerId.startsWith(SYSTEM_PREFIX)) {
            context.challenge(PLAYER_AUTH, AuthenticationFailedCause.NoCredentials) { challenge, call ->
                call.respond(HttpStatusCode.Unauthorized, ErrorBody("unauthorized"))
                challenge.complete()
            }
            return
        }
        context.principal(Player(playerId, sessionId))
    }
}

fun Application.installAuth() {
    install(Authentication) {
        register(GatewayIdentity(GatewayIdentity.Config(PLAYER_AUTH)))
    }
}

fun ApplicationCall.player(): Player = authentication.principal<Player>()!!
