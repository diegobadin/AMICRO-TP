import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    // Even the auth checks go through the real wiring: a 401 that is really a 404 because a route
    // was never registered is exactly the kind of pass this suite must not hand out.
    private val dataSource = freshDatabase()
    private fun ApplicationTestBuilder.wire() = wire(dataSource)

    @Test
    fun `health is open and names the service`() = testApplication {
        wire()
        val res = client.get("/health")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("""{"status":"ok","service":"room-gameplay"}""", res.bodyAsText())
    }

    /**
     * The exact exposed names, not just "some metrics came back". P8 builds dashboards on these and
     * a rename is a silent break — and the Prometheus client does rewrite names it dislikes, which
     * is how roomgameplay_rooms_created_total quietly became roomgameplay_rooms_total.
     */
    @Test
    fun `metrics exposes the business counters under the names P8 will chart`() = testApplication {
        wire()
        // The business counters are registered eagerly; the request timer carries route/status tags
        // and so only exists once something has been served. Serve something first, then scrape.
        client.get("/health")
        val body = client.get("/metrics").bodyAsText()
        listOf(
            "roomgameplay_rooms_opened_total",
            "roomgameplay_games_started_total",
            "roomgameplay_games_completed_total",
            "roomgameplay_command_duration_seconds",
        ).forEach { assertTrue(body.contains(it), "missing $it") }
    }

    @Test
    fun `a room read without a token is rejected`() = testApplication {
        wire()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/rooms").status)
    }

    @Test
    fun `a token signed by another cluster's secret is rejected`() = testApplication {
        wire()
        val res = client.get("/rooms") { header("Authorization", "Bearer ${token(secret = "someone-else")}") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `an expired token is rejected`() = testApplication {
        wire()
        val expired = token(expiresAt = Date(System.currentTimeMillis() - 1_000))
        val res = client.get("/rooms") { header("Authorization", "Bearer $expired") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `a valid identity token is accepted`() = testApplication {
        wire()
        val res = client.get("/rooms") { header("Authorization", "Bearer ${token()}") }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `the correlation id the client sent comes back`() = testApplication {
        wire()
        val res = client.get("/health") { header("X-Correlation-Id", "abc-123") }
        assertEquals("abc-123", res.headers["X-Correlation-Id"])
    }
}

class RouteLabelTest {

    @Test
    fun `ids collapse into their template so the metric label stays bounded`() {
        assertEquals("/rooms/{roomId}", routeLabel("/rooms/1c1b0b7e-0000-4000-8000-000000000000"))
        assertEquals(
            "/rooms/{roomId}/games/{gameNumber}/moves",
            routeLabel("/rooms/abc/games/def/moves"),
        )
    }

    @Test
    fun `an unknown path never becomes a label value`() {
        assertEquals("unknown", routeLabel("/whatever/someone/tries"))
        assertEquals("unknown", routeLabel("/"))
    }
}
