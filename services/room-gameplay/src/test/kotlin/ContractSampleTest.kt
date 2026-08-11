import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import uno.GameCompleted
import uno.GameStarted
import uno.RoomType

/**
 * The producer's half of the async contract check (P5 E3). Until now `ci/contracts/validate.py`
 * validated a sample written by hand in Python, which had drifted from the real event in two ways
 * at once — it required a `gameId` and an `eventId` that exist nowhere in this system, while the
 * engine emits `gameNumber`. A check whose sample no producer emits is a seam pretending to be a
 * contract.
 *
 * So the sample is generated here instead, from the event class and the same
 * `publicPayload` filter the outbox row goes through, plus the two fields the relay merges in. The
 * values are illustrative; the **shape** comes from the producer, which is what makes a rename or a
 * dropped field turn this suite red before it reaches a consumer.
 *
 * Regenerate deliberately: `CONTRACT_SAMPLE_REGENERATE=1 ./gradlew test`.
 */
class ContractSampleTest {

    private val sampleFile = File("../../ci/contracts/samples/game-completed.json")
    private val pretty = Json { prettyPrint = true }

    private val roomId = "1c1b0b7e-0000-4000-8000-000000000000"
    private val sequenceNumber = 42

    private fun publishedBody(): JsonObject {
        val event = GameCompleted(
            roomType = RoomType.CASUAL,
            gameNumber = 1,
            finishingOrder = listOf("alice", "bob"),
            cardPointTotals = mapOf("alice" to 0, "bob" to 17),
            isAbandoned = false,
            completedAt = Instant.parse("2026-08-11T12:00:00Z"),
            at = Instant.parse("2026-08-11T12:00:00Z"),
        )
        // What the relay puts on the wire: the filtered payload, with the aggregate's identity
        // merged in. The room id and sequence number are not event fields — they identify which
        // log the event came from, and the CloudEvents `ce-id` header is built from the same pair.
        return JsonObject(
            publicPayload(event) + mapOf(
                "roomId" to JsonPrimitive(roomId),
                "sequenceNumber" to JsonPrimitive(sequenceNumber),
            ),
        )
    }

    @Test
    fun `the committed contract sample is the one the producer would publish`() {
        val rendered = pretty.encodeToString(JsonObject.serializer(), publishedBody())

        if (System.getenv("CONTRACT_SAMPLE_REGENERATE") == "1") {
            sampleFile.parentFile.mkdirs()
            sampleFile.writeText(rendered + "\n")
        }

        assertTrue(sampleFile.exists(), "the CI contract check validates ${sampleFile.path}; it is missing")
        assertEquals(
            rendered.trim(),
            sampleFile.readText().trim(),
            "the producer's GameCompleted no longer matches the committed contract sample. If the " +
                "change is intended, regenerate with CONTRACT_SAMPLE_REGENERATE=1 and update " +
                "ci/contracts/game-completed.schema.json in the same commit — a consumer reads it.",
        )
    }

    /**
     * The sample is what a consumer will be handed, so it is also the last place a leak could hide.
     * `GameStarted` is the event that carries the seed; if the filter ever stopped removing it, a
     * generated sample would carry it straight into the contract.
     */
    @Test
    fun `no private field survives into the published shape`() {
        val started = GameStarted(
            gameNumber = 1,
            playerOrder = listOf("alice", "bob"),
            initialDiscardCard = uno.Card.parse("R5")!!,
            initialColor = uno.Color.RED,
            seed = 987654321L,
            turnTimeoutSeconds = 30,
            at = Instant.parse("2026-08-11T12:00:00Z"),
        )
        assertFalse(leaksPrivateData(started), "the seed is the deck order")
        assertFalse(publishedBody().containsKey("seed"))
    }
}
