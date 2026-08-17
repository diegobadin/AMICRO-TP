import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
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
import kotlinx.serialization.json.Json
import uno.FULL_DECK
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AC-P3.4 (conditional requests and concurrency) and the privacy line of the definition of done:
 * a player's hand must never appear in another player's view. Driven over real HTTP against a real
 * store, because that is where a leak would actually happen.
 */
class MovesHttpTest {

    private lateinit var dataSource: HikariDataSource
    private val alice = ALICE
    private val bob = BOB
    private val json = testJson

    @BeforeTest
    fun setUp() {
        dataSource = freshDatabase()
    }

    @AfterTest
    fun tearDown() = dataSource.close()

    private fun ApplicationTestBuilder.wire() = wire(dataSource)

    private suspend fun HttpClient.view(roomId: String, player: String) = gameView(roomId, player)

    private suspend fun HttpClient.move(roomId: String, player: String, body: String, ifMatch: Int?) =
        submitMove(roomId, player, body, ifMatch)

    @Test
    fun `a move without If-Match is refused with 428`() = testApplication {
        wire()
        val roomId = client.startedRoom()
        val res = client.move(roomId, alice, """{"type":"draw_card"}""", ifMatch = null)
        assertEquals(428, res.status.value)
        assertTrue(res.bodyAsText().contains("if_match_required"))
    }

    @Test
    fun `a stale If-Match is refused with 412 and hands back the current state`() = testApplication {
        wire()
        val roomId = client.startedRoom()
        val current = client.view(roomId, alice).sequenceNumber

        val res = client.move(roomId, alice, """{"type":"draw_card"}""", ifMatch = current - 1)
        assertEquals(HttpStatusCode.PreconditionFailed, res.status)
        // The loser reconciles from the response body rather than having to go and fetch it.
        val returned: GameView = json.decodeFromString(res.bodyAsText())
        assertEquals(current, returned.sequenceNumber)
        assertEquals("\"$current\"", res.headers["ETag"])
    }

    @Test
    fun `a move out of turn is a 409`() = testApplication {
        wire()
        val roomId = client.startedRoom()
        val view = client.view(roomId, alice)
        val waiting = if (view.currentPlayerId == alice) bob else alice
        val res = client.move(roomId, waiting, """{"type":"draw_card"}""", ifMatch = view.sequenceNumber)
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertTrue(res.bodyAsText().contains("not_your_turn"))
    }

    @Test
    fun `a card that is not in hand is a 409`() = testApplication {
        wire()
        val roomId = client.startedRoom()
        val view = client.view(roomId, alice)
        val actor = view.currentPlayerId!!
        val hand = client.view(roomId, actor).hand
        val notHeld = (0..9).map { "R$it" }.first { it !in hand }
        val res = client.move(roomId, actor, """{"type":"play_card","card":"$notHeld"}""", ifMatch = view.sequenceNumber)
        assertEquals(HttpStatusCode.Conflict, res.status)
    }

    @Test
    fun `a wild played without a colour is a 409`() = testApplication {
        wire()
        val roomId = client.startedRoom()
        val view = client.view(roomId, alice)
        val actor = view.currentPlayerId!!
        val actorView = client.view(roomId, actor)
        val wild = actorView.hand.firstOrNull { it.startsWith("WILD") }
        if (wild == null) return@testApplication // this deal had no wild; the engine suite covers it exhaustively
        val res = client.move(roomId, actor, """{"type":"play_card","card":"$wild"}""", ifMatch = view.sequenceNumber)
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertTrue(res.bodyAsText().contains("wild_needs_color"))
    }

    @Test
    fun `an accepted move returns the new state, a new ETag and a Location`() = testApplication {
        wire()
        val roomId = client.startedRoom()
        val before = client.view(roomId, alice)
        val actor = before.currentPlayerId!!
        val res = client.move(roomId, actor, """{"type":"draw_card"}""", ifMatch = before.sequenceNumber)

        assertEquals(HttpStatusCode.Created, res.status)
        val after: GameView = json.decodeFromString(res.bodyAsText())
        assertTrue(after.sequenceNumber > before.sequenceNumber)
        assertEquals("\"${after.sequenceNumber}\"", res.headers["ETag"])
        assertNotNull(res.headers["Location"])
        // The drawn card is in the response, and only the drawer's response.
        assertEquals(client.view(roomId, actor).hand.size, after.hand.size)
    }

    @Test
    fun `polling with If-None-Match answers 304 while nothing has happened`() = testApplication {
        wire()
        val roomId = client.startedRoom()
        val seq = client.view(roomId, alice).sequenceNumber

        val unchanged = client.get("/rooms/$roomId/games/1") {
            asPlayer(alice)
            header("If-None-Match", "\"$seq\"")
        }
        assertEquals(HttpStatusCode.NotModified, unchanged.status)
        assertTrue(unchanged.bodyAsText().isEmpty(), "a 304 carries no body")

        client.move(roomId, client.view(roomId, alice).currentPlayerId!!, """{"type":"draw_card"}""", ifMatch = seq)

        val changed = client.get("/rooms/$roomId/games/1") {
            asPlayer(alice)
            header("If-None-Match", "\"$seq\"")
        }
        assertEquals(HttpStatusCode.OK, changed.status)
    }

    @Test
    fun `a player's hand never appears in the other player's view`() = testApplication {
        wire()
        val roomId = client.startedRoom()
        val aliceHand = client.view(roomId, alice).hand
        val bobResponse = client.get("/rooms/$roomId/games/1") {
            asPlayer(bob)
        }.bodyAsText()
        val bobView: GameView = json.decodeFromString(bobResponse)

        // Not 7: the first-card rule may already have made someone draw. The count has to match
        // what alice actually holds — that is what is public — while the cards themselves do not.
        assertEquals(aliceHand.size, bobView.opponents.single().cardCount, "counts are public, cards are not")

        // Comparing the two hands directly would prove nothing: most cards exist twice, so an
        // overlap is ordinary. What must hold is that the response carries no card at all beyond
        // bob's own hand and the public discard top — checked on the raw JSON, because a leak would
        // be a field the view class does not even model.
        val ownCards = Regex(""""hand":\[[^]]*]""").find(bobResponse)!!.value
        val elsewhere = bobResponse.replace(ownCards, "").replace(""""discardTop":"${bobView.discardTop}"""", "")
        FULL_DECK.distinct().forEach { card ->
            assertTrue(
                !Regex(""""$card"""").containsMatchIn(elsewhere),
                "a card ($card) appears in bob's view outside his own hand: $elsewhere",
            )
        }
    }

    @Test
    fun `a stranger cannot read the game at all`() = testApplication {
        wire()
        val roomId = client.startedRoom()
        val res = client.get("/rooms/$roomId/games/1") {
            asPlayer("99999999-9999-9999-9999-999999999999")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    /**
     * The same defect P7 found on the internal route, checked on the route that has had it since
     * P3: `?: malformed_move` only ever caught an *absent* body, because a move missing its `type`
     * throws in deserialization instead of arriving as null. It answered 500.
     */
    @Test
    fun `a move body that will not parse is a 400, not a 500`() = testApplication {
        wire()
        val roomId = client.startedRoom()
        val seq = client.gameView(roomId, alice).sequenceNumber

        for (body in listOf("{}", "not json", """{"type":42}""")) {
            val res = client.submitMove(roomId, alice, body, seq)
            assertEquals(HttpStatusCode.BadRequest, res.status, "body: $body")
        }
    }

    /** A whole game over HTTP — AC-P3.7's shape, without the cluster or the CLI. */
    @Test
    fun `two players can play a game to a winner through the API`() = testApplication {
        wire()
        val roomId = client.startedRoom()
        val view = client.playOut(roomId)

        assertEquals("COMPLETED", view.status, "the game should have reached a winner")
        assertTrue(view.finishingOrder.isNotEmpty())

        // The log is the authority: it has to end with the completion, and the room closes with it.
        val log = dataSource.eventTypes(roomId)
        assertTrue(log.contains("GameCompleted"), "GameCompleted must be in the log")
        assertTrue(log.contains("RoomCompleted"), "a casual room closes with its only game")
        assertEquals("RoomCompleted", log.last())
    }
}
