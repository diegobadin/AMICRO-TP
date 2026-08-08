import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** AC-P3.1: rooms behave like resources, against the real store rather than a fake. */
class RoomsHttpTest {

    private lateinit var dataSource: HikariDataSource

    private val alice = "11111111-1111-1111-1111-111111111111"
    private val bob = "22222222-2222-2222-2222-222222222222"
    private val carol = "33333333-3333-3333-3333-333333333333"

    @BeforeTest
    fun setUp() {
        dataSource = testPool()
        migrate(dataSource)
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("truncate room_events, outbox, rooms, idempotency_keys")
            }
        }
    }

    @AfterTest
    fun tearDown() = dataSource.close()

    private fun ApplicationTestBuilder.wire() {
        application { module(config, Rooms(EventStore(dataSource), config)) }
    }

    private suspend fun HttpClient.createRoom(
        player: String,
        maxPlayers: Int = 10,
        key: String? = null,
    ): HttpResponse = post("/rooms") {
        header("Authorization", "Bearer ${token(playerId = player)}")
        key?.let { header("Idempotency-Key", it) }
        contentType(ContentType.Application.Json)
        setBody("""{"maxPlayers":$maxPlayers}""")
    }

    private fun roomIdOf(body: String) = Regex(""""roomId":"([^"]+)"""").find(body)!!.groupValues[1]

    @Test
    fun `creating a room returns 201 with a Location and an ETag`() = testApplication {
        wire()
        val res = client.createRoom(alice)
        assertEquals(HttpStatusCode.Created, res.status)
        val roomId = roomIdOf(res.bodyAsText())
        assertEquals("/rooms/$roomId", res.headers["Location"])
        assertNotNull(res.headers["ETag"])
        assertTrue(res.bodyAsText().contains(""""status":"WAITING""""))
    }

    @Test
    fun `a replayed idempotency key returns the original room instead of a second one`() = testApplication {
        wire()
        val first = client.createRoom(alice, key = "key-abc")
        val second = client.createRoom(alice, key = "key-abc")

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.OK, second.status, "a replay is not a creation")
        assertEquals(roomIdOf(first.bodyAsText()), roomIdOf(second.bodyAsText()))
        assertEquals(1, client.get("/rooms") { header("Authorization", "Bearer ${token(playerId = alice)}") }
            .bodyAsText().split("\"roomId\"").size - 1, "only one room exists")
    }

    @Test
    fun `a different player's key is a different room`() = testApplication {
        wire()
        val first = client.createRoom(alice, key = "shared-key")
        val second = client.createRoom(bob, key = "shared-key")
        assertEquals(HttpStatusCode.Created, second.status)
        assertTrue(roomIdOf(first.bodyAsText()) != roomIdOf(second.bodyAsText()))
    }

    @Test
    fun `joining twice is refused the second time`() = testApplication {
        wire()
        val roomId = roomIdOf(client.createRoom(alice, maxPlayers = 4).bodyAsText())
        val join = client.post("/rooms/$roomId/players/$bob") { header("Authorization", "Bearer ${token(playerId = bob)}") }
        assertEquals(HttpStatusCode.Created, join.status)
        val again = client.post("/rooms/$roomId/players/$bob") { header("Authorization", "Bearer ${token(playerId = bob)}") }
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertTrue(again.bodyAsText().contains("already_joined"))
    }

    @Test
    fun `a room that is already playing refuses another player`() = testApplication {
        wire()
        // minPlayers is 2, so bob's join auto-starts the game (E3).
        val roomId = roomIdOf(client.createRoom(alice, maxPlayers = 4).bodyAsText())
        client.post("/rooms/$roomId/players/$bob") { header("Authorization", "Bearer ${token(playerId = bob)}") }
        val late = client.post("/rooms/$roomId/players/$carol") { header("Authorization", "Bearer ${token(playerId = carol)}") }
        assertEquals(HttpStatusCode.Conflict, late.status)
        assertTrue(late.bodyAsText().contains("room_already_started"))
    }

    @Test
    fun `the game starts by itself once the room has enough players`() = testApplication {
        wire()
        val roomId = roomIdOf(client.createRoom(alice, maxPlayers = 4).bodyAsText())
        val joined = client.post("/rooms/$roomId/players/$bob") { header("Authorization", "Bearer ${token(playerId = bob)}") }
        assertTrue(joined.bodyAsText().contains(""""status":"IN_PROGRESS""""), joined.bodyAsText())
        assertTrue(joined.bodyAsText().contains(""""gameNumber":1"""))
    }

    @Test
    fun `leaving is idempotent`() = testApplication {
        wire()
        val roomId = roomIdOf(client.createRoom(alice, maxPlayers = 4).bodyAsText())
        client.post("/rooms/$roomId/players/$bob") { header("Authorization", "Bearer ${token(playerId = bob)}") }
        repeat(2) {
            val res = client.delete("/rooms/$roomId/players/$bob") { header("Authorization", "Bearer ${token(playerId = bob)}") }
            assertEquals(HttpStatusCode.NoContent, res.status, "attempt $it")
        }
    }

    @Test
    fun `the room list shows only rooms that can still be joined`() = testApplication {
        wire()
        val open = roomIdOf(client.createRoom(alice, maxPlayers = 4).bodyAsText())
        val started = roomIdOf(client.createRoom(carol, maxPlayers = 4).bodyAsText())
        client.post("/rooms/$started/players/$bob") { header("Authorization", "Bearer ${token(playerId = bob)}") }

        val listed = client.get("/rooms") { header("Authorization", "Bearer ${token(playerId = alice)}") }.bodyAsText()
        assertTrue(listed.contains(open), "a waiting room should be listed")
        assertTrue(!listed.contains(started), "a room already playing is not joinable")
    }

    @Test
    fun `a room that does not exist is a 404`() = testApplication {
        wire()
        val res = client.get("/rooms/44444444-4444-4444-4444-444444444444") {
            header("Authorization", "Bearer ${token(playerId = alice)}")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `a player cannot act on someone else's membership`() = testApplication {
        wire()
        val roomId = roomIdOf(client.createRoom(alice, maxPlayers = 4).bodyAsText())
        val res = client.post("/rooms/$roomId/players/$carol") { header("Authorization", "Bearer ${token(playerId = bob)}") }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun `starting a game explicitly needs enough players`() = testApplication {
        wire()
        val roomId = roomIdOf(client.createRoom(alice, maxPlayers = 4).bodyAsText())
        val tooEarly = client.post("/rooms/$roomId/games") { header("Authorization", "Bearer ${token(playerId = alice)}") }
        assertEquals(HttpStatusCode.Conflict, tooEarly.status)
        assertTrue(tooEarly.bodyAsText().contains("not_enough_players"))
    }

    @Test
    fun `the business counters move with the log`() = testApplication {
        wire()
        val before = client.get("/metrics").bodyAsText()
        val roomId = roomIdOf(client.createRoom(alice, maxPlayers = 4).bodyAsText())
        client.post("/rooms/$roomId/players/$bob") { header("Authorization", "Bearer ${token(playerId = bob)}") }
        val after = client.get("/metrics").bodyAsText()

        fun value(body: String, metric: String) =
            body.lineSequence().first { it.startsWith("$metric ") }.substringAfter(' ').toDouble()

        assertTrue(value(after, "roomgameplay_rooms_opened_total") > value(before, "roomgameplay_rooms_opened_total"))
        assertTrue(value(after, "roomgameplay_games_started_total") > value(before, "roomgameplay_games_started_total"))
    }
}

fun testPool(): HikariDataSource {
    val url = System.getenv("TEST_DATABASE_URL")
        ?: error(
            "TEST_DATABASE_URL is not set. AC-P3.1/P3.3/P3.4 cannot be proved against a fake, so " +
                "this suite refuses to pass by skipping. Point it at a Postgres, e.g. " +
                "jdbc:postgresql://localhost:55432/room_gameplay",
        )
    return HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = System.getenv("TEST_DATABASE_USER") ?: "room_gameplay"
            password = System.getenv("TEST_DATABASE_PASSWORD") ?: "test"
            maximumPoolSize = 4
        },
    )
}
