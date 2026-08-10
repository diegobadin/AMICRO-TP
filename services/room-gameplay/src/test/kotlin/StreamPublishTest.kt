import com.zaxxer.hikari.HikariDataSource
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
 * The publish side of E1, against the real store: what reaches the room stream has to be exactly
 * what reached the log — same events, same order, same numbering — and nothing must reach it that
 * did not commit. A fake store could not show either, since both are consequences of the
 * transaction outcome.
 */
class StreamPublishTest {

    private lateinit var dataSource: HikariDataSource
    private val published = mutableListOf<StreamEntry>()

    @BeforeTest
    fun setUp() {
        dataSource = freshDatabase()
        published.clear()
    }

    @AfterTest
    fun tearDown() = dataSource.close()

    private fun recordingRooms() = Rooms(
        EventStore(dataSource),
        config,
        stream = { _, base, events, correlationId -> published += entriesFor(base, events, correlationId) },
    )

    @Test
    fun `the stream mirrors the event log, event for event and number for number`() = testApplication {
        wire(dataSource, recordingRooms())
        val roomId = client.startedRoom()
        client.playOut(roomId, maxSteps = 12)

        val logged = dataSource.eventTypes(roomId)
        assertEquals(logged, published.map { it.fields.getValue("type") })
        assertEquals((1..logged.size).toList(), published.map { it.sequenceNumber })
    }

    @Test
    fun `a stale command publishes nothing, because nothing committed`() = testApplication {
        wire(dataSource, recordingRooms())
        val roomId = client.startedRoom()
        val before = published.size

        val view = client.gameView(roomId, ALICE)
        val stale = client.submitMove(roomId, view.currentPlayerId!!, """{"type":"draw_card"}""", ifMatch = 0)

        assertEquals(HttpStatusCode.PreconditionFailed, stale.status)
        assertEquals(before, published.size)
    }

    @Test
    fun `a replayed create publishes once, not twice`() = testApplication {
        wire(dataSource, recordingRooms())
        val key = UUID.randomUUID().toString()

        val first = client.createRoom(ALICE, key = key)
        // Creating a room commits two events — the room, and the creator taking their seat.
        val afterFirst = published.map { it.fields.getValue("type") }
        val replay = client.createRoom(ALICE, key = key)

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(roomIdOf(first.bodyAsText()), roomIdOf(replay.bodyAsText()))
        assertEquals(listOf("RoomCreated", "PlayerJoined"), afterFirst)
        assertEquals(afterFirst, published.map { it.fields.getValue("type") }, "the replay published again")
    }

    @Test
    fun `a room that completes gets its stream expired, not left behind`() = testApplication {
        wire(dataSource, recordingRooms())
        val roomId = client.startedRoom()
        val finished = client.playOut(roomId)

        assertEquals("COMPLETED", finished.status)
        // The publisher sets the TTL when RoomCompleted goes by; here we assert the trigger it keys
        // on actually arrives, since the expire itself belongs to Redis.
        assertTrue(published.any { it.fields.getValue("type") == "RoomCompleted" })
    }
}
