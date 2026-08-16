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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

const val PLAYER_AUTH = "player"
const val SYSTEM_AUTH = "system"
const val PLAYER_HEADER = "X-Player-Id"
const val SESSION_HEADER = "X-Session-Id"
const val INTERNAL_TOKEN_HEADER = "X-Internal-Token"

/**
 * P5: the timer worker calls in as `system:timer-worker`. The gateway builds `X-Player-Id` from a
 * validated token's subject — an identity-issued uuid — so a player can never present one of these;
 * the whitelist is what makes that true. The prefix is the second lock on the same door, and it
 * swings both ways: a system id is refused on a player route as firmly as a player id is on the
 * internal one.
 *
 * P7: the prefix is not a credential. Anything already inside the cluster can spell `system:`, and
 * `/internal` now provisions rooms that seat other people — a capability worth more than a tick. So
 * a shared token is required as well. The threat model answers this with a NetworkPolicy (T4/D6),
 * which kindnet does not enforce and the demo cluster therefore does not have; a header the caller
 * must know works everywhere the service runs.
 */
const val SYSTEM_PREFIX = "system:"

data class Player(val playerId: String, val sessionId: String)

/**
 * A provider rather than a check inside each handler: it keeps the single gate the JWT plugin gave
 * us, so a route cannot be added that forgets to identify its caller.
 */
class GatewayIdentity(config: Config) : AuthenticationProvider(config) {
    private val system = config.system
    private val expectedToken = config.expectedToken

    class Config(
        name: String,
        val system: Boolean = false,
        /** Blank means no caller can satisfy it: an unset secret closes the door rather than opening it. */
        val expectedToken: String? = null,
    ) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val playerId = context.call.request.headers[PLAYER_HEADER]
        val sessionId = context.call.request.headers[SESSION_HEADER]
        if (playerId.isNullOrBlank() || sessionId.isNullOrBlank() ||
            playerId.startsWith(SYSTEM_PREFIX) != system ||
            (system && !tokenMatches(context.call.request.headers[INTERNAL_TOKEN_HEADER]))
        ) {
            val scheme = if (system) SYSTEM_AUTH else PLAYER_AUTH
            context.challenge(scheme, AuthenticationFailedCause.NoCredentials) { challenge, call ->
                call.respond(HttpStatusCode.Unauthorized, ErrorBody("unauthorized"))
                challenge.complete()
            }
            return
        }
        context.principal(Player(playerId, sessionId))
    }

    /** Constant time, because a token compared byte by byte is a token that can be guessed one byte at a time. */
    private fun tokenMatches(presented: String?): Boolean {
        if (expectedToken.isNullOrBlank() || presented.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            presented.toByteArray(StandardCharsets.UTF_8),
            expectedToken.toByteArray(StandardCharsets.UTF_8),
        )
    }
}

fun Application.installAuth(internalToken: String? = null) {
    install(Authentication) {
        register(GatewayIdentity(GatewayIdentity.Config(PLAYER_AUTH)))
        register(GatewayIdentity(GatewayIdentity.Config(SYSTEM_AUTH, system = true, expectedToken = internalToken)))
    }
}

fun ApplicationCall.player(): Player = authentication.principal<Player>()!!
