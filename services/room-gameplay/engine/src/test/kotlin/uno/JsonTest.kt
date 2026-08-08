package uno

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The log is the authority, so an event that does not survive a round trip through the store is a
 * silent corruption of the only record there is. This checks the real logs the property suite
 * generates rather than a handful of constructed events.
 */
class JsonTest {

    @Test
    fun `every event in a generated game round-trips through its stored payload`() {
        runBlocking {
            checkAll(Arb.long()) { seed ->
                val table = Table(listOf("a", "b", "c"), seed).playOut()
                for (event in table.log) {
                    assertEquals(event, decodeEvent(encodeEvent(event)), "round-trip failed for $event")
                }
            }
        }
    }

    @Test
    fun `a log rebuilt from stored payloads replays to the same aggregate`() {
        runBlocking {
            checkAll(Arb.long()) { seed ->
                val table = Table(listOf("a", "b"), seed).playOut()
                val stored = table.log.map { decodeEvent(encodeEvent(it)) }
                assertEquals(table.state, replay(stored, "room-1"), "seed $seed diverged through storage")
            }
        }
    }

    @Test
    fun `the discriminator is the catalog's event name`() {
        val played = CardPlayed("a", card("R5"), card("R5"), 3, null, "b", T0)
        assertEquals("CardPlayed", eventType(played))
        assertEquals(JsonPrimitive("R5"), encodeEvent(played)["card"])
    }

    @Test
    fun `cards and instants are stored readably`() {
        val json = encodeEvent(GameStarted(1, listOf("a", "b"), card("BSKIP"), Color.BLUE, 42L, 30, T0)).toString()
        assertTrue(json.contains(""""initialDiscardCard":"BSKIP""""), json)
        assertTrue(json.contains(""""at":"2026-08-08T12:00:00Z""""), json)
    }
}
