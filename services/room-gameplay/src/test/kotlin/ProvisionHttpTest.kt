import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The door a tournament provisions rooms through (P7 E1/F2). The room it gets back is already
 * playing — that is the whole point of the endpoint, because a tournament that has to create, then
 * join, then start has three chances to leave a room half-built and nothing that would repair one.
 */
class ProvisionHttpTest {

    private lateinit var dataSource: HikariDataSource

    @BeforeTest
    fun setUp() {
        dataSource = freshDatabase()
    }

    @AfterTest
    fun tearDown() = dataSource.close()

    private suspend fun HttpClient.provision(
        players: List<String> = listOf(ALICE, BOB),
        tournamentId: String = "t-1",
        roundNumber: Int = 1,
        roomIndex: Int = 0,
        advanceCount: Int = 1,
        token: String? = TEST_INTERNAL_TOKEN,
    ): HttpResponse = post("/internal/rooms") {
        asTournament(token)
        contentType(ContentType.Application.Json)
        setBody(
            """{"tournamentId":"$tournamentId","roundNumber":$roundNumber,"roomIndex":$roomIndex,""" +
                """"players":${players.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},""" +
                """"advanceCount":$advanceCount}""",
        )
    }

    @Test
    fun `a provisioned room arrives seated and already dealing`() = testApplication {
        wire(dataSource)

        val res = client.provision()

        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        val roomId = roomIdOf(res.bodyAsText())
        val types = dataSource.eventTypes(roomId)
        assertEquals(
            listOf("RoomCreated", "PlayerJoined", "PlayerJoined", "GameStarted"),
            types.take(4),
            "one transaction: created, both seated, then dealt — in that order",
        )
        assertTrue(types.contains("GameStarted"), "the tournament should never have to start it: $types")
    }

    /**
     * The idempotency key is `tournamentId:roundNumber:roomIndex`, so a retried round asks for the
     * rooms it already has. Anything else and a re-emitted round doubles the bracket.
     */
    @Test
    fun `the same room of the same round twice is one room`() = testApplication {
        wire(dataSource)

        val first = client.provision()
        val second = client.provision()

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.OK, second.status, "the second call performed no creation")
        assertEquals(roomIdOf(first.bodyAsText()), roomIdOf(second.bodyAsText()))
        assertEquals(1, dataSource.roomCount(), "a retry must not leave a second room behind")
    }

    @Test
    fun `a different room index in the same round is a different room`() = testApplication {
        wire(dataSource)

        val first = client.provision(players = listOf(ALICE, BOB), roomIndex = 0)
        val second = client.provision(players = listOf(CAROL, DAVE), roomIndex = 1)

        assertTrue(roomIdOf(first.bodyAsText()) != roomIdOf(second.bodyAsText()))
        assertEquals(2, dataSource.roomCount())
    }

    /**
     * An odd number of survivors is a bye, and a bye is the tournament's decision. A room that
     * cannot play is refused loudly rather than created and left waiting for a player who is not
     * coming — which is the shape that strands a round forever.
     */
    @Test
    fun `a room that cannot play is refused rather than parked`() = testApplication {
        wire(dataSource)

        assertEquals(HttpStatusCode.BadRequest, client.provision(players = listOf(ALICE)).status)
        assertEquals(HttpStatusCode.BadRequest, client.provision(players = listOf(ALICE, ALICE)).status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.provision(advanceCount = 2).status,
            "advancing everyone in a room of two is a round that never narrows",
        )
        assertEquals(HttpStatusCode.BadRequest, client.provision(advanceCount = 0).status)
        assertEquals(0, dataSource.roomCount(), "nothing refused should have reached the log")
    }

    /**
     * Found by probing the live cluster, not by a test: `{}` answered **500**. `receiveNullable`
     * returns null for an absent body but *throws* when a required field is missing, and the throw
     * lands in the generic handler. A 5xx tells the tournament "my fault, try again", so a request
     * that can never succeed would be retried for ever.
     */
    @Test
    fun `a body this endpoint cannot read is the caller's problem, not a 500`() = testApplication {
        wire(dataSource)

        for (body in listOf("{}", """{"tournamentId":"t-1"}""", "not json", """{"players":"alice"}""")) {
            val res = client.post("/internal/rooms") {
                asTournament()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, res.status, "body: $body")
        }
        assertEquals(0, dataSource.roomCount())
    }

    @Test
    fun `the internal door needs the token, not just the prefix`() = testApplication {
        wire(dataSource)

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.provision(token = null).status,
            "being inside the cluster is not a credential",
        )
        assertEquals(HttpStatusCode.Unauthorized, client.provision(token = "not-the-token").status)
        assertEquals(0, dataSource.roomCount())
    }

    @Test
    fun `a player cannot provision a room for other people`() = testApplication {
        wire(dataSource)

        val res = client.post("/internal/rooms") {
            asPlayer(ALICE)
            header(INTERNAL_TOKEN_HEADER, TEST_INTERNAL_TOKEN)
            contentType(ContentType.Application.Json)
            setBody("""{"tournamentId":"t-1","roundNumber":1,"roomIndex":0,"players":["$ALICE","$BOB"],"advanceCount":1}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, res.status, "even holding the token, a player id is not a system id")
    }

    @Test
    fun `a player may not open a tournament room through the public door`() = testApplication {
        wire(dataSource)

        val refused = client.post("/rooms") {
            asPlayer(ALICE)
            contentType(ContentType.Application.Json)
            setBody("""{"roomType":"TOURNAMENT","maxPlayers":2}""")
        }

        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals(0, dataSource.roomCount())
    }

    /** The field was accepted and ignored since P3; honouring it must not break the value it always meant. */
    @Test
    fun `an explicit casual room is still just a room`() = testApplication {
        wire(dataSource)

        val res = client.post("/rooms") {
            asPlayer(ALICE)
            contentType(ContentType.Application.Json)
            setBody("""{"roomType":"casual","maxPlayers":2}""")
        }

        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        assertTrue(res.bodyAsText().contains("\"roomType\":\"CASUAL\""))
    }
}
