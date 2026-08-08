import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Through fromEnv rather than the constructor, so the defaults the container relies on are the
// same ones these tests run against.
private val config = Config.fromEnv(mapOf("IDENTITY_JWT_SECRET" to "test-secret"))

fun token(
    playerId: String = "11111111-1111-1111-1111-111111111111",
    sessionId: String = "22222222-2222-2222-2222-222222222222",
    secret: String = config.jwtSecret,
    expiresAt: Date = Date(System.currentTimeMillis() + 60_000),
): String = JWT.create()
    .withSubject(playerId)
    .withClaim("sid", sessionId)
    .withExpiresAt(expiresAt)
    .sign(Algorithm.HMAC256(secret))

class HttpTest {

    @Test
    fun `health is open and names the service`() = testApplication {
        application { module(config) }
        val res = client.get("/health")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("""{"status":"ok","service":"room-gameplay"}""", res.bodyAsText())
    }

    @Test
    fun `metrics exposes the business counters`() = testApplication {
        application { module(config) }
        val body = client.get("/metrics").bodyAsText()
        assertTrue(body.contains("roomgameplay_command_duration_seconds"), "missing command duration histogram")
    }

    @Test
    fun `a room read without a token is rejected`() = testApplication {
        application { module(config) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/rooms").status)
    }

    @Test
    fun `a token signed by another cluster's secret is rejected`() = testApplication {
        application { module(config) }
        val res = client.get("/rooms") { header("Authorization", "Bearer ${token(secret = "someone-else")}") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `an expired token is rejected`() = testApplication {
        application { module(config) }
        val expired = token(expiresAt = Date(System.currentTimeMillis() - 1_000))
        val res = client.get("/rooms") { header("Authorization", "Bearer $expired") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `a valid identity token is accepted`() = testApplication {
        application { module(config) }
        val res = client.get("/rooms") { header("Authorization", "Bearer ${token()}") }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `the correlation id the client sent comes back`() = testApplication {
        application { module(config) }
        val res = client.get("/health") { header("X-Correlation-Id", "abc-123") }
        assertEquals("abc-123", res.headers["X-Correlation-Id"])
    }
}

class RouteLabelTest {

    @Test
    fun `ids collapse into their template so the metric label stays bounded`() {
        assertEquals("/rooms/{roomId}", routeLabel("/rooms/1c1b0b7e-0000-4000-8000-000000000000"))
        assertEquals(
            "/rooms/{roomId}/games/{gameId}/moves",
            routeLabel("/rooms/abc/games/def/moves"),
        )
    }

    @Test
    fun `an unknown path never becomes a label value`() {
        assertEquals("unknown", routeLabel("/whatever/someone/tries"))
        assertEquals("unknown", routeLabel("/"))
    }
}
