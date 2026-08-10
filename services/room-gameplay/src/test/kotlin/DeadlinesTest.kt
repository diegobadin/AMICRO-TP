import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import uno.DisconnectPlayer

/**
 * AC-P3.5 and AC-P3.6 end to end. The clock is injected rather than slept through (plan D3), so
 * these run in milliseconds and still exercise the real store — which is where "expire, then
 * process" has to hold, not just in the engine.
 */
class DeadlinesTest {

    private lateinit var dataSource: HikariDataSource
    private val alice = ALICE
    private val bob = BOB
    private val json = testJson

    /** Advances only when a test says so, so a deadline can be crossed deliberately. */
    private var clock = Instant.parse("2026-08-10T12:00:00Z")

    @BeforeTest
    fun setUp() {
        dataSource = freshDatabase()
    }

    @AfterTest
    fun tearDown() = dataSource.close()

    private fun rooms() = Rooms(EventStore(dataSource), config, now = { clock }, seed = { 42L })

    private fun log(roomId: String): List<String> = dataSource.eventTypes(roomId)

    @Test
    fun `an overdue turn timer is settled before the next command is judged`() = testApplication {
        val rooms = rooms()
        application { module(config, rooms) }

        val created = client.post("/rooms") {
            asPlayer(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"maxPlayers":2}""")
        }
        val roomId = Regex(""""roomId":"([^"]+)"""").find(created.bodyAsText())!!.groupValues[1]
        client.post("/rooms/$roomId/players/$bob") { asPlayer(bob) }

        val before: GameView = json.decodeFromString(
            client.get("/rooms/$roomId/games/1") { asPlayer(alice) }.bodyAsText(),
        )
        val waiting = if (before.currentPlayerId == alice) bob else alice

        // Past the 30-second turn timer. The waiting player's move would be out of turn a moment
        // ago; the expiry runs first, so by the time it is judged the turn is theirs.
        clock = clock.plusSeconds(31)
        val res = client.post("/rooms/$roomId/games/1/moves") {
            asPlayer(waiting)
            header("If-Match", "\"${before.sequenceNumber}\"")
            contentType(ContentType.Application.Json)
            setBody("""{"type":"draw_card"}""")
        }

        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        val types = log(roomId)
        assertTrue(types.contains("TurnTimedOut"), "the timeout has to be in the log: $types")
        assertTrue(
            types.indexOf("TurnTimedOut") < types.lastIndexOf("CardDrawn"),
            "the timeout is recorded before the command that flushed it",
        )
    }

    @Test
    fun `an expired challenge window closes itself on the next command`() = testApplication {
        val rooms = rooms()
        application { module(config, rooms) }
        val roomId = UUID.randomUUID()

        // Straight to the engine-shaped path: play until someone is at one card would take a whole
        // game, so this drives the room through the same submit() the routes use.
        val created = client.post("/rooms") {
            asPlayer(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"maxPlayers":2}""")
        }
        val id = Regex(""""roomId":"([^"]+)"""").find(created.bodyAsText())!!.groupValues[1]
        client.post("/rooms/$id/players/$bob") { asPlayer(bob) }

        var view: GameView = json.decodeFromString(
            client.get("/rooms/$id/games/1") { asPlayer(alice) }.bodyAsText(),
        )
        var guard = 0
        while (view.status == "IN_PROGRESS" && guard++ < 2000) {
            val actor = view.currentPlayerId!!
            val mine: GameView = json.decodeFromString(
                client.get("/rooms/$id/games/1") { asPlayer(actor) }.bodyAsText(),
            )
            if (log(id).contains("ChallengeWindowOpened")) break
            val body = when {
                mine.playable.isNotEmpty() -> {
                    val card = mine.hand[mine.playable.first()]
                    val colour = if (card.startsWith("WILD")) ""","chosenColor":"RED"""" else ""
                    """{"type":"play_card","card":"$card"$colour}"""
                }
                !mine.drewThisTurn -> """{"type":"draw_card"}"""
                else -> """{"type":"pass"}"""
            }
            client.post("/rooms/$id/games/1/moves") {
                asPlayer(actor)
                header("If-Match", "\"${mine.sequenceNumber}\"")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            clock = clock.plusSeconds(1)
            view = json.decodeFromString(
                client.get("/rooms/$id/games/1") { asPlayer(alice) }.bodyAsText(),
            )
        }

        assertTrue(log(id).contains("ChallengeWindowOpened"), "the run never reached a one-card hand")
        // Past the five-second window; the next command has to close it first.
        clock = clock.plusSeconds(CHALLENGE_WINDOW + 1)
        val current: GameView = json.decodeFromString(
            client.get("/rooms/$id/games/1") { asPlayer(alice) }.bodyAsText(),
        )
        client.post("/rooms/$id/games/1/moves") {
            asPlayer(current.currentPlayerId!!)
            header("If-Match", "\"${current.sequenceNumber}\"")
            contentType(ContentType.Application.Json)
            setBody("""{"type":"draw_card"}""")
        }
        assertTrue(log(id).contains("ChallengeWindowClosed"), "an expired window must close itself")
    }

    /** AC-P3.6: a second login for a seated player disconnects them from the room. */
    @Test
    fun `a superseded session disconnects the player and opens the reconnection window`() = testApplication {
        val rooms = rooms()
        val store = EventStore(dataSource)
        application { module(config, rooms) }

        val created = client.post("/rooms") {
            asPlayer(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"maxPlayers":4}""")
        }
        val roomId = Regex(""""roomId":"([^"]+)"""").find(created.bodyAsText())!!.groupValues[1]
        client.post("/rooms/$roomId/players/$bob") { asPlayer(bob) }

        val invalidations = SessionInvalidations(rooms, store)
        val event = SessionInvalidated(playerId = bob, oldSessionId = "session-1", reason = "superseded")

        assertEquals(1, invalidations.handle(event), "bob is seated in one active room")
        assertTrue(log(roomId).contains("PlayerDisconnected"), "the disconnect must reach the room's log")

        val room = store.load(UUID.fromString(roomId)).state
        val connection = room.player(bob)!!.connection
        assertTrue(connection is uno.ConnectionStatus.Disconnected)
        assertEquals(clock.plusSeconds(60), connection.deadline, "the 60-second window opens")

        // D8: a redelivery of the same session event changes nothing.
        val eventsBefore = log(roomId).size
        assertEquals(0, invalidations.handle(event), "a redelivered session event is a no-op")
        assertEquals(eventsBefore, log(roomId).size)
    }

    @Test
    fun `a session kill for a player in no room is harmless`() {
        val invalidations = SessionInvalidations(rooms(), EventStore(dataSource))
        assertEquals(0, invalidations.handle(SessionInvalidated("nobody", "session-x", "superseded")))
    }

    @Test
    fun `an expired reconnection window forfeits the player on the next command`() = testApplication {
        val rooms = rooms()
        val store = EventStore(dataSource)
        application { module(config, rooms) }

        val created = client.post("/rooms") {
            asPlayer(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"maxPlayers":2}""")
        }
        val roomId = Regex(""""roomId":"([^"]+)"""").find(created.bodyAsText())!!.groupValues[1]
        client.post("/rooms/$roomId/players/$bob") { asPlayer(bob) }

        rooms.submit(UUID.fromString(roomId), DisconnectPlayer(bob, "superseded"), null)
        clock = clock.plusSeconds(61)

        val view: GameView = json.decodeFromString(
            client.get("/rooms/$roomId/games/1") { asPlayer(alice) }.bodyAsText(),
        )
        client.post("/rooms/$roomId/games/1/moves") {
            asPlayer(view.currentPlayerId!!)
            header("If-Match", "\"${view.sequenceNumber}\"")
            contentType(ContentType.Application.Json)
            setBody("""{"type":"draw_card"}""")
        }

        val types = log(roomId)
        assertTrue(types.contains("PlayerForfeited"), "the window has to expire into a forfeit: $types")
        // Two players, one gone: invariant 7 ends the game and the last one standing wins.
        assertTrue(types.contains("GameCompleted"))
        assertEquals(alice, store.load(UUID.fromString(roomId)).state.game!!.finishingOrder.first())
    }

    private companion object {
        const val CHALLENGE_WINDOW = 5L
    }
}
