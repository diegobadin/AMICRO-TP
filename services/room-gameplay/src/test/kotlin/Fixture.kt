import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Everything the HTTP suites share. These tests run against a **real** Postgres on purpose — a fake
 * cannot prove a rollback left no rows, or that two writers cannot take one sequence number — so
 * the fixture is a real pool and the guard against running them blind lives in one place.
 */
val config: Config = Config.fromEnv(mapOf("IDENTITY_JWT_SECRET" to "test-secret"))

val ALICE = "11111111-1111-1111-1111-111111111111"
val BOB = "22222222-2222-2222-2222-222222222222"
val CAROL = "33333333-3333-3333-3333-333333333333"

val testJson = Json { ignoreUnknownKeys = true }

/**
 * What the gateway puts on a request once it has validated the token. The session id is derived
 * from the player so a test can tell two callers apart; nothing downstream reads it beyond
 * requiring that it is there.
 */
fun HttpRequestBuilder.asPlayer(playerId: String = ALICE, sessionId: String = "session-$playerId") {
    header(PLAYER_HEADER, playerId)
    header(SESSION_HEADER, sessionId)
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

/** A migrated, empty database. Truncating beats per-test ids: a leftover row is a silent lie. */
fun freshDatabase(): HikariDataSource = testPool().also { dataSource ->
    migrate(dataSource)
    dataSource.connection.use { connection ->
        connection.createStatement().use {
            it.execute("truncate room_events, outbox, rooms, idempotency_keys, consumed_events")
        }
    }
}

fun HikariDataSource.eventTypes(roomId: String): List<String> =
    connection.use { connection ->
        connection.prepareStatement(
            "select type from room_events where room_id = ?::uuid order by sequence_number",
        ).use { statement ->
            statement.setString(1, roomId)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

fun HikariDataSource.countIn(table: String, roomId: UUID): Int =
    connection.use { connection ->
        connection.prepareStatement("select count(*) from $table where room_id = ?").use { statement ->
            statement.setObject(1, roomId)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

fun ApplicationTestBuilder.wire(dataSource: HikariDataSource, rooms: Rooms? = null) {
    application { module(config, rooms ?: Rooms(EventStore(dataSource), config)) }
}

// ---- the calls every suite makes, in the words the API uses

suspend fun HttpClient.createRoom(player: String, maxPlayers: Int = 10, key: String? = null): HttpResponse =
    post("/rooms") {
        asPlayer(player)
        key?.let { header("Idempotency-Key", it) }
        contentType(ContentType.Application.Json)
        setBody("""{"maxPlayers":$maxPlayers}""")
    }

suspend fun HttpClient.joinRoom(roomId: String, player: String): HttpResponse =
    post("/rooms/$roomId/players/$player") { asPlayer(player) }

suspend fun HttpClient.gameView(roomId: String, player: String): GameView =
    testJson.decodeFromString(
        get("/rooms/$roomId/games/1") { asPlayer(player) }.bodyAsText(),
    )

suspend fun HttpClient.submitMove(roomId: String, player: String, body: String, ifMatch: Int?): HttpResponse =
    post("/rooms/$roomId/games/1/moves") {
        asPlayer(player)
        ifMatch?.let { header("If-Match", "\"$it\"") }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

fun roomIdOf(body: String): String = Regex(""""roomId":"([^"]+)"""").find(body)!!.groupValues[1]

/** Two players seated, which is `ROOM_MIN_PLAYERS`, so the game has auto-started (E3). */
suspend fun HttpClient.startedRoom(maxPlayers: Int = 2): String {
    val roomId = roomIdOf(createRoom(ALICE, maxPlayers).bodyAsText())
    joinRoom(roomId, BOB)
    return roomId
}

/**
 * Plays whatever is legal until the game ends or `until` says stop — the same loop the CLI runs,
 * so a suite can get to a late-game position without scripting one.
 */
suspend fun HttpClient.playOut(roomId: String, maxSteps: Int = 2000, until: (GameView) -> Boolean = { false }): GameView {
    var view = gameView(roomId, ALICE)
    var steps = 0
    while (view.status == "IN_PROGRESS" && steps++ < maxSteps && !until(view)) {
        val actor = view.currentPlayerId!!
        val mine = gameView(roomId, actor)
        val body = when {
            mine.playable.isNotEmpty() -> {
                val card = mine.hand[mine.playable.first()]
                val colour = if (card.startsWith("WILD")) ""","chosenColor":"RED"""" else ""
                """{"type":"play_card","card":"$card"$colour}"""
            }
            !mine.drewThisTurn -> """{"type":"draw_card"}"""
            else -> """{"type":"pass"}"""
        }
        submitMove(roomId, actor, body, mine.sequenceNumber)
        view = gameView(roomId, ALICE)
    }
    return view
}
