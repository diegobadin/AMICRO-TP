// Until P4 puts a gateway in front, room-gameplay validates identity's tokens itself, with the same
// symmetric secret (decision E1; the coupling is recorded in CHANGELOG-design.md). Only signature
// and expiry are checked here: revocation arrives asynchronously on `identity.session-events`,
// which is what disconnects a superseded player from a room rather than silently failing their
// next request.

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond

const val PLAYER_AUTH = "player"

data class Player(val playerId: String, val sessionId: String)

fun Application.installAuth(config: Config) {
    install(Authentication) {
        jwt(PLAYER_AUTH) {
            verifier(JWT.require(Algorithm.HMAC256(config.jwtSecret)).build())
            validate { credential ->
                val playerId = credential.payload.subject
                val sessionId = credential.payload.getClaim("sid").asString()
                if (playerId.isNullOrBlank() || sessionId.isNullOrBlank()) null
                else Player(playerId, sessionId)
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorBody("unauthorized"))
            }
        }
    }
}

fun ApplicationCall.player(): Player = authentication.principal<Player>()!!
