import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.server.testing.ApplicationTestBuilder
import java.util.UUID

/**
 * These suites run against a **real** Postgres on purpose: "the events, the outbox rows and the
 * projections go in one transaction" is not a claim a fake can support, and neither is "a redelivery
 * is refused by a primary key". Without `TEST_DATABASE_URL` they fail rather than skip — a silently
 * skipped proof is worse than no proof.
 */
fun testPool(): HikariDataSource {
    val url = System.getenv("TEST_DATABASE_URL")
        ?: error(
            "TEST_DATABASE_URL is required — this suite proves nothing without a database, e.g. " +
                "jdbc:postgresql://localhost:55432/tournament",
        )
    return HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = System.getenv("TEST_DATABASE_USER") ?: "tournament"
            password = System.getenv("TEST_DATABASE_PASSWORD") ?: "test"
            maximumPoolSize = 4
        },
    )
}

fun freshDatabase(): HikariDataSource = testPool().also { dataSource ->
    migrate(dataSource)
    dataSource.connection.use { connection ->
        connection.createStatement().use {
            it.execute("truncate tournament_events, outbox, tournaments, round_rooms, consumed_events, idempotency_keys")
        }
    }
}

val ALICE = "11111111-1111-1111-1111-111111111111"
val BOB = "22222222-2222-2222-2222-222222222222"
val CAROL = "33333333-3333-3333-3333-333333333333"
val DAVE = "44444444-4444-4444-4444-444444444444"

val testConfig: Config = Config.fromEnv(
    mapOf("TOURNAMENT_MIN_PLAYERS" to "4", "TOURNAMENT_ROOM_SIZE" to "2", "TOURNAMENT_ADVANCE_COUNT" to "1"),
)

/** What the gateway puts on a request once it has validated the token. */
fun HttpRequestBuilder.asPlayer(playerId: String = ALICE, sessionId: String = "session-$playerId") {
    header(PLAYER_HEADER, playerId)
    header(SESSION_HEADER, sessionId)
}

/**
 * Room-gameplay, without room-gameplay. Hands out predictable room ids and remembers what it was
 * asked for, so a test can assert the tournament asked for the right rooms — and can make
 * provisioning fail on demand, because "what happens when the rooms cannot be created" is the
 * question a round hangs on.
 */
class FakeRooms(private val failFrom: Int = Int.MAX_VALUE) : RoomProvisioner {
    data class Request(val roundNumber: Int, val roomIndex: Int, val players: List<String>, val advanceCount: Int)

    val requests = mutableListOf<Request>()
    private val issued = mutableMapOf<String, ProvisionedRoom>()

    override fun provision(
        tournamentId: String,
        roundNumber: Int,
        roomIndex: Int,
        players: List<String>,
        advanceCount: Int,
        correlationId: String,
    ): ProvisionedRoom {
        requests += Request(roundNumber, roomIndex, players, advanceCount)
        if (requests.size > failFrom) throw RoomProvisioningFailed(503, "no")
        // Keyed the way room-gameplay keys it, so asking twice for the same room of the same round
        // returns the same id — which is the property the whole retry story rests on.
        return issued.getOrPut("$tournamentId:$roundNumber:$roomIndex") {
            ProvisionedRoom(UUID.randomUUID().toString(), 1, players)
        }
    }
}

fun ApplicationTestBuilder.wire(tournaments: Tournaments) {
    application { module(tournaments) }
}
