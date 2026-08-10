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
    private val alice = "11111111-1111-1111-1111-111111111111"
    private val bob = "22222222-2222-2222-2222-222222222222"
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        dataSource = testPool()
        migrate(dataSource)
        dataSource.connection.use { c ->
            c.createStatement().use { it.execute("truncate room_events, outbox, rooms, idempotency_keys") }
        }
    }

    @AfterTest
    fun tearDown() = dataSource.close()

    private fun ApplicationTestBuilder.wire() {
        application { module(config, Rooms(EventStore(dataSource), config)) }
    }

    private suspend fun HttpClient.startedRoom(): String {
        val created = post("/rooms") {
            header("Authorization", "Bearer ${token(playerId = alice)}")
            contentType(ContentType.Application.Json)
            setBody("""{"maxPlayers":2}""")
        }
        val roomId = Regex(""""roomId":"([^"]+)"""").find(created.bodyAsText())!!.groupValues[1]
        post("/rooms/$roomId/players/$bob") { header("Authorization", "Bearer ${token(playerId = bob)}") }
        return roomId
    }

    private suspend fun HttpClient.view(roomId: String, player: String): GameView =
        json.decodeFromString(
            get("/rooms/$roomId/games/1") { header("Authorization", "Bearer ${token(playerId = player)}") }.bodyAsText(),
        )

    private suspend fun HttpClient.move(
        roomId: String,
        player: String,
        body: String,
        ifMatch: Int?,
    ): HttpResponse = post("/rooms/$roomId/games/1/moves") {
        header("Authorization", "Bearer ${token(playerId = player)}")
        ifMatch?.let { header("If-Match", "\"$it\"") }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

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
            header("Authorization", "Bearer ${token(playerId = alice)}")
            header("If-None-Match", "\"$seq\"")
        }
        assertEquals(HttpStatusCode.NotModified, unchanged.status)
        assertTrue(unchanged.bodyAsText().isEmpty(), "a 304 carries no body")

        client.move(roomId, client.view(roomId, alice).currentPlayerId!!, """{"type":"draw_card"}""", ifMatch = seq)

        val changed = client.get("/rooms/$roomId/games/1") {
            header("Authorization", "Bearer ${token(playerId = alice)}")
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
            header("Authorization", "Bearer ${token(playerId = bob)}")
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
            header("Authorization", "Bearer ${token(playerId = "99999999-9999-9999-9999-999999999999")}")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    /** A whole game over HTTP — AC-P3.7's shape, without the cluster or the CLI. */
    @Test
    fun `two players can play a game to a winner through the API`() = testApplication {
        wire()
        val roomId = client.startedRoom()
        var guard = 0
        var view = client.view(roomId, alice)

        while (view.status == "IN_PROGRESS" && guard++ < 2000) {
            val actor = view.currentPlayerId!!
            val mine = client.view(roomId, actor)
            val body = when {
                mine.playable.isNotEmpty() -> {
                    val card = mine.hand[mine.playable.first()]
                    val colour = if (card.startsWith("WILD")) ""","chosenColor":"RED"""" else ""
                    val uno = if (mine.hand.size == 2) ""","callingUno":true""" else ""
                    """{"type":"play_card","card":"$card"$colour$uno}"""
                }
                !mine.drewThisTurn -> """{"type":"draw_card"}"""
                else -> """{"type":"pass"}"""
            }
            val res = client.move(roomId, actor, body, ifMatch = mine.sequenceNumber)
            assertTrue(
                res.status.value in 200..299,
                "move $body by $actor was ${res.status}: ${res.bodyAsText()}",
            )
            view = client.view(roomId, alice)
        }

        assertEquals("COMPLETED", view.status, "the game should have reached a winner")
        assertTrue(view.finishingOrder.isNotEmpty())

        // The log is the authority: it has to end with the completion, and the room closes with it.
        val log = dataSource.connection.use { c ->
            c.prepareStatement("select type from room_events where room_id = ?::uuid order by sequence_number").use { s ->
                s.setString(1, roomId)
                s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }
        assertTrue(log.contains("GameCompleted"), "GameCompleted must be in the log")
        assertTrue(log.contains("RoomCompleted"), "a casual room closes with its only game")
        assertEquals("RoomCompleted", log.last())
    }
}
