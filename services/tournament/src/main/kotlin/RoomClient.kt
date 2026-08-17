// The customer half of the Customer–Supplier relationship with Room Gameplay (§1's context map):
// this service asks for rooms, room-gameplay creates them and owns everything that happens inside.
//
// Provisioning goes over HTTP to an endpoint that is NOT in the gateway's route table (P7 E1). The
// architecture's alternative was a `tournament.room-creation` topic; going synchronous means the
// round is only ever announced once its rooms exist, so `RoundStarted` cannot name a room that was
// never created.

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
private data class ProvisionRequest(
    val tournamentId: String,
    val roundNumber: Int,
    val roomIndex: Int,
    val players: List<String>,
    val advanceCount: Int,
)

@Serializable
data class ProvisionedRoom(val roomId: String, val gameNumber: Int = 1, val players: List<String> = emptyList())

class RoomProvisioningFailed(val status: Int, body: String) :
    RuntimeException("room-gameplay refused to provision a room: $status $body")

/**
 * The seam between deciding a round and creating its rooms. An interface because the rounds are
 * worth testing without a room-gameplay on the other end — and because "what the tournament does
 * when provisioning fails" is a test, not a hope.
 */
interface RoomProvisioner {
    fun provision(
        tournamentId: String,
        roundNumber: Int,
        roomIndex: Int,
        players: List<String>,
        advanceCount: Int,
        correlationId: String,
    ): ProvisionedRoom
}

class RoomClient(
    private val baseUrl: String,
    private val internalToken: String?,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val timeout: Duration = Duration.ofSeconds(10),
) : RoomProvisioner {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Idempotent by `tournamentId:roundNumber:roomIndex`, which room-gameplay turns into its own
     * idempotency key — so a retry after a crash, a timeout or a redelivered round asks for the same
     * room and gets the same id back rather than a second table.
     */
    override fun provision(
        tournamentId: String,
        roundNumber: Int,
        roomIndex: Int,
        players: List<String>,
        advanceCount: Int,
        correlationId: String,
    ): ProvisionedRoom {
        val body = json.encodeToString(
            ProvisionRequest(tournamentId, roundNumber, roomIndex, players, advanceCount),
        )
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/internal/rooms"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            // Both halves of the system identity, plus the shared token: one alone is a 401, which
            // is correct rather than a bug (P5's lesson, P7's token).
            .header("X-Player-Id", "system:tournament")
            .header("X-Session-Id", SESSION_ID)
            .header("X-Internal-Token", internalToken ?: "")
            .header("X-Correlation-Id", correlationId)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        // 200 is a replay: this room was already created by an earlier attempt, and the response is
        // the answer that attempt got.
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw RoomProvisioningFailed(response.statusCode(), response.body().take(500))
        }
        return json.decodeFromString(response.body())
    }

    companion object {
        /** One per process, logged at startup, so a room in room-gameplay's log names who asked for it. */
        val SESSION_ID: String = "tournament-" + java.util.UUID.randomUUID().toString().take(12)
    }
}
