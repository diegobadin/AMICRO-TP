import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import uno.DeckRecycled
import uno.Event
import uno.PlayerJoined
import uno.PlayerLeft

/**
 * The realtime tier's contract, with no Redis in the way: what the gateway will read is decided
 * here, and both of the properties it depends on are properties of a pure function.
 */
class StreamsTest {

    private val at = Instant.parse("2026-08-10T12:00:00Z")

    @Test
    fun `the entry id is the sequence number, continuing from where the batch started`() {
        val events: List<Event> = listOf(PlayerJoined("p1", 1, at), PlayerJoined("p2", 2, at))
        val entries = entriesFor(baseSequence = 41, events = events, correlationId = "c1")

        assertEquals(listOf(42, 43), entries.map { it.sequenceNumber })
        assertEquals(listOf("42", "43"), entries.map { it.fields["seq"] })
    }

    @Test
    fun `a fresh room starts at one`() {
        val entries = entriesFor(0, listOf(PlayerJoined("p1", 1, at)), null)
        assertEquals(1, entries.single().sequenceNumber)
    }

    @Test
    fun `the payload is the privacy-filtered one, so the deck order never reaches a player`() {
        // DeckRecycled carries the RNG seed, and the seed is the deck: anyone holding it can
        // reconstruct every hand at the table. `publicPayload` is the same filter the outbox row
        // goes through — one filter, not two that can drift apart.
        val entries = entriesFor(7, listOf(DeckRecycled(newDeckSize = 54, seed = 987654321L, at = at)), null)
        val payload = entries.single().fields.getValue("payload")

        assertFalse(payload.contains("seed"), "the seed reached the room stream: $payload")
        assertFalse(payload.contains("987654321"))
        assertContains(payload, "newDeckSize")
    }

    @Test
    fun `each entry names its event type and carries the correlation id through`() {
        val entries = entriesFor(0, listOf(PlayerLeft("p1", 0, at)), "corr-1")
        assertEquals("PlayerLeft", entries.single().fields["type"])
        assertEquals("corr-1", entries.single().fields["correlationId"])
    }

    @Test
    fun `a command that committed nothing publishes nothing`() {
        assertEquals(emptyList(), entriesFor(9, emptyList(), "c1"))
    }

    @Test
    fun `the stream key is per room, which is what makes ordering within a room total`() {
        val roomId = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")
        assertEquals("room:11111111-1111-1111-1111-111111111111:events", streamKey(roomId))
    }
}
