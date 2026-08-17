import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The surface the gateway proxies. Everything here needs a session, because a tournament is
 * something a player joins — and because the gateway overwriting these two headers is the control
 * the whole trust boundary rests on.
 */
class HttpTest {

    private lateinit var dataSource: HikariDataSource
    private lateinit var rooms: FakeRooms

    @BeforeTest
    fun setUp() {
        dataSource = freshDatabase()
        rooms = FakeRooms()
    }

    @AfterTest
    fun tearDown() = dataSource.close()

    private fun tournaments() = Tournaments(Store(dataSource), rooms, testConfig)

    private fun idOf(body: String) = Regex(""""tournamentId":"([^"]+)"""").find(body)!!.groupValues[1]

    @Test
    fun `every route needs the gateway's headers`() = testApplication {
        wire(tournaments())

        assertEquals(HttpStatusCode.Unauthorized, client.get("/tournaments").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/tournaments").status)
        // Half an identity is no identity — the P4 lesson, kept alive one service further out.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/tournaments") { header(PLAYER_HEADER, ALICE) }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/tournaments") { header(SESSION_HEADER, "s") }.status,
        )
    }

    @Test
    fun `a system identity is refused on a player route`() = testApplication {
        wire(tournaments())
        val res = client.get("/tournaments") {
            header(PLAYER_HEADER, "${SYSTEM_PREFIX}timer-worker")
            header(SESSION_HEADER, "worker-1")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status, "the fence swings both ways")
    }

    @Test
    fun `open, register, and watch the threshold start it`() = testApplication {
        wire(tournaments())

        val created = client.post("/tournaments") { asPlayer(ALICE) }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val id = idOf(created.bodyAsText())

        listOf(ALICE, BOB, CAROL).forEach { player ->
            assertEquals(HttpStatusCode.Created, client.post("/tournaments/$id/register") { asPlayer(player) }.status)
        }
        assertTrue(client.get("/tournaments/$id") { asPlayer(ALICE) }.bodyAsText().contains("\"status\":\"REGISTRATION\""))

        client.post("/tournaments/$id/register") { asPlayer(DAVE) }
        val started = client.get("/tournaments/$id") { asPlayer(ALICE) }.bodyAsText()
        assertTrue(started.contains("\"status\":\"IN_PROGRESS\""), started)
    }

    @Test
    fun `a player asks where they are, and is told their room`() = testApplication {
        wire(tournaments())
        val id = idOf(client.post("/tournaments") { asPlayer(ALICE) }.bodyAsText())
        listOf(ALICE, BOB, CAROL, DAVE).forEach { client.post("/tournaments/$id/register") { asPlayer(it) } }

        val placement = client.get("/tournaments/$id/players/$ALICE") { asPlayer(ALICE) }.bodyAsText()

        assertTrue(placement.contains("\"roundNumber\":1"), placement)
        assertTrue(placement.contains("\"roomId\""), placement)
        assertTrue(placement.contains("\"eliminated\":false"), placement)
    }

    @Test
    fun `unregistering is idempotent, like leaving a room`() = testApplication {
        wire(tournaments())
        val id = idOf(client.post("/tournaments") { asPlayer(ALICE) }.bodyAsText())
        client.post("/tournaments/$id/register") { asPlayer(ALICE) }

        assertEquals(HttpStatusCode.NoContent, client.delete("/tournaments/$id/register") { asPlayer(ALICE) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/tournaments/$id/register") { asPlayer(ALICE) }.status)
    }

    @Test
    fun `a tournament that does not exist is a 404, not a 500`() = testApplication {
        wire(tournaments())
        val missing = UUID.randomUUID()

        assertEquals(HttpStatusCode.NotFound, client.get("/tournaments/$missing") { asPlayer(ALICE) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/tournaments/not-a-uuid") { asPlayer(ALICE) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/tournaments/$missing/register") { asPlayer(ALICE) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/tournaments/$missing/rounds/1") { asPlayer(ALICE) }.status)
    }

    @Test
    fun `the route label does not grow a series per tournament id`() {
        assertEquals("/tournaments", routeLabel("/tournaments"))
        assertEquals("/tournaments/:id", routeLabel("/tournaments/${UUID.randomUUID()}"))
        assertEquals("/tournaments/:id/register", routeLabel("/tournaments/${UUID.randomUUID()}/register"))
        assertEquals("/tournaments/:id/rounds/:n", routeLabel("/tournaments/${UUID.randomUUID()}/rounds/2"))
        assertEquals("/health", routeLabel("/health"))
    }
}
