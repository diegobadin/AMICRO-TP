// The gateway validates identity's tokens now, and this service trusts the identity it passes down
// as `X-Player-Id` / `X-Session-Id` (CHANGELOG-design.md §8.9, closed in P4). room-gameplay holds no
// signing key any more: it is ClusterIP, the gateway is the only way in, and the gateway builds
// these headers from scratch on every request so a client cannot supply its own.
//
// Revocation still arrives asynchronously on `identity.session-events`, which is what disconnects a
// superseded player from a room rather than silently failing their next request.

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

data class Player(val playerId: String, val sessionId: String)

/**
 * A provider rather than a check inside each handler: it keeps the single gate the JWT plugin gave
 * us, so a route cannot be added that forgets to identify its caller.
 */
class GatewayIdentity(config: Config) : AuthenticationProvider(config) {
    class Config(name: String) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val playerId = context.call.request.headers[PLAYER_HEADER]
        val sessionId = context.call.request.headers[SESSION_HEADER]
        if (playerId.isNullOrBlank() || sessionId.isNullOrBlank()) {
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
