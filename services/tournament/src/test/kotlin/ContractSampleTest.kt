import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The producer's half of P7's async contract check, in the shape room-gameplay's has had since P5:
 * the sample is generated from the event class rather than written by hand, so a field renamed in
 * Kotlin turns this suite red before it reaches ranking or analytics.
 *
 * The two merged fields are the relay's, not the event's — `tournamentId` and `sequenceNumber`
 * identify which log the event came from, and the CloudEvents `ce-id` is built from the same pair.
 * They are merged here under the names the second relay instance is configured with
 * (`BODY_ID_FIELD=tournamentId`), which is what a consumer will actually be handed.
 *
 * Regenerate deliberately: `CONTRACT_SAMPLE_REGENERATE=1 ./gradlew test`.
 */
class ContractSampleTest {

    private val sampleFile = File("../../ci/contracts/samples/tournament-completed.json")
    private val pretty = Json { prettyPrint = true }

    private val tournamentId = "2b2b0b7e-0000-4000-8000-000000000000"
    private val sequenceNumber = 12

    private fun publishedBody(): JsonObject {
        val event = TournamentCompleted(
            champion = "alice",
            finalPlacements = listOf("alice", "bob", "carol", "dave"),
            at = Instant.parse("2026-08-17T12:00:00Z"),
        )
        // No privacy filter on the way out, unlike a room event: a tournament event has no hands,
        // no deck and no seed in it. There is nothing here a spectator may not see.
        return JsonObject(
            encodeEvent(event) + mapOf(
                "tournamentId" to JsonPrimitive(tournamentId),
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
            "the producer's TournamentCompleted no longer matches the committed contract sample. " +
                "ranking turns `finalPlacements` into a placement rating and analytics records it as " +
                "the bracket's placements, so regenerate with CONTRACT_SAMPLE_REGENERATE=1 and " +
                "update ci/contracts/tournament-completed.schema.json in the same commit.",
        )
    }

    /**
     * A tournament nobody finished still ends, and it ends with a null champion rather than a
     * missing field — the consumers' schema allows the null precisely because this can happen.
     */
    @Test
    fun `a tournament with no champion still serialises its shape`() {
        val abandoned = TournamentCompleted(champion = null, finalPlacements = emptyList(), at = Instant.EPOCH)
        val body = encodeEvent(abandoned)

        assertTrue(body.containsKey("champion"), "null is an answer; an absent field is not")
        assertEquals("TournamentCompleted", eventType(abandoned))
    }
}
