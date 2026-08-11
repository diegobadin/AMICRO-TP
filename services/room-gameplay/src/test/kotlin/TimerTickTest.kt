import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seam P5's timer worker pokes: a cached deadline on the projection, and one internal route
 * that asks the aggregate to look at its clock. Everything here runs against a real Postgres —
 * "the column is written in the same transaction as the events" is not a claim a fake can support.
 */
class TimerTickTest {

    private lateinit var dataSource: HikariDataSource
    private val alice = ALICE
    private val bob = BOB

    /** Advances only when a test says so, so a deadline can be crossed deliberately. */
    private var clock = Instant.parse("2026-08-11T12:00:00Z")

    @BeforeTest
    fun setUp() {
        dataSource = freshDatabase()
    }

    @AfterTest
    fun tearDown() = dataSource.close()

    private fun rooms() = Rooms(EventStore(dataSource), config, now = { clock }, seed = { 42L })

    @Test
    fun `a tick settles an overdue turn with no player command involved`() = testApplication {
        wire(dataSource, rooms())
        val roomId = client.startedRoom()
        val before = dataSource.eventTypes(roomId).size

        clock = clock.plusSeconds(31)
        val res = client.post("/internal/rooms/$roomId/tick") { asTimerWorker() }

        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val types = dataSource.eventTypes(roomId)
        assertTrue(types.contains("TurnTimedOut"), "nobody sent a command; the clock did this: $types")
        assertTrue(types.size > before)
    }

    @Test
    fun `a tick with nothing due writes nothing`() = testApplication {
        wire(dataSource, rooms())
        val roomId = client.startedRoom()
        val before = dataSource.eventTypes(roomId)

        val res = client.post("/internal/rooms/$roomId/tick") { asTimerWorker() }

        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("\"events\":0"), res.bodyAsText())
        assertEquals(before, dataSource.eventTypes(roomId), "an early tick is a no-op, not a rewrite")
    }

    /**
     * The P4 lesson, kept alive one layer down: `X-Player-Id` alone is not an identity here, and a
     * probe that sets only one header sees a 401 that is correct rather than a bug.
     */
    @Test
    fun `half the worker's identity is no identity`() = testApplication {
        wire(dataSource, rooms())
        val roomId = client.startedRoom()

        assertEquals(HttpStatusCode.Unauthorized, client.post("/internal/rooms/$roomId/tick").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/internal/rooms/$roomId/tick") { header(PLAYER_HEADER, "${SYSTEM_PREFIX}timer-worker") }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/internal/rooms/$roomId/tick") { header(SESSION_HEADER, "worker-1") }.status,
        )
    }

    @Test
    fun `a player cannot tick, and the worker cannot play`() = testApplication {
        wire(dataSource, rooms())
        val roomId = client.startedRoom()

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/internal/rooms/$roomId/tick") { asPlayer(alice) }.status,
            "a real player carrying real headers is still not the timer worker",
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/rooms") { asTimerWorker() }.status,
            "and the fence swings the other way",
        )
    }

    @Test
    fun `a tick for a room that does not exist is a 404`() = testApplication {
        wire(dataSource, rooms())
        val res = client.post("/internal/rooms/${UUID.randomUUID()}/tick") { asTimerWorker() }
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/internal/rooms/not-a-uuid/tick") { asTimerWorker() }.status)
    }

    @Test
    fun `the projection carries the deadline the room is waiting on`() = testApplication {
        wire(dataSource, rooms())

        val roomId = roomIdOf(client.createRoom(alice, maxPlayers = 2).bodyAsText())
        assertEquals(
            clock.plusSeconds(config.waitingRoomExpirySeconds),
            dataSource.nextDeadlineOf(roomId),
            "a waiting room is waiting on its own expiry",
        )

        client.joinRoom(roomId, bob)
        assertEquals(
            clock.plusSeconds(config.turnTimeoutSeconds),
            dataSource.nextDeadlineOf(roomId),
            "once the game starts it is the turn timer",
        )
    }

    @Test
    fun `a finished room is waiting on nothing`() = testApplication {
        wire(dataSource, rooms())
        val roomId = client.startedRoom()
        assertNotNull(dataSource.nextDeadlineOf(roomId))

        // Two players, one walks out: invariant 7 ends the game and closes the casual room.
        client.delete("/rooms/$roomId/players/$bob") { asPlayer(bob) }

        assertTrue(dataSource.eventTypes(roomId).contains("RoomCompleted"))
        assertNull(dataSource.nextDeadlineOf(roomId), "nothing should ever tick a closed room again")
    }
}
