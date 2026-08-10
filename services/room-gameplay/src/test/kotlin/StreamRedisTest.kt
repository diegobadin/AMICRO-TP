import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import redis.clients.jedis.RedisClient
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.UnifiedJedis
import uno.DeckRecycled
import uno.Event
import uno.PlayerJoined
import uno.RoomCompleted
import uno.RoomType

/**
 * Against a real Redis, because the one assumption the whole resume design rests on is a Redis
 * assumption: that an entry id may be dictated rather than generated, and that `{sequenceNumber}-0`
 * is therefore a stream position the gateway can hand back as `Last-Event-ID`. A fake would prove
 * that Jedis was called, which is not the question.
 */
class StreamRedisTest {

    private lateinit var redis: UnifiedJedis
    private val roomId: UUID = UUID.randomUUID()
    private val at = Instant.parse("2026-08-10T12:00:00Z")

    @BeforeTest
    fun setUp() {
        val url = System.getenv("TEST_REDIS_URL")
            ?: error(
                "TEST_REDIS_URL is not set. The entry-id contract behind AC-P4.5 cannot be proved " +
                    "against a fake, so this suite refuses to pass by skipping. Point it at a Redis, " +
                    "e.g. redis://localhost:63790",
            )
        redis = RedisClient.create(URI(url))
        redis.del(streamKey(roomId))
    }

    @AfterTest
    fun tearDown() {
        redis.del(streamKey(roomId))
        redis.close()
    }

    private fun publish(baseSequence: Int, events: List<Event>) {
        RedisRoomEvents(redis, onFailure = { throw it }).use {
            it.published(roomId, baseSequence, events, "corr-1")
        }
    }

    @Test
    fun `the entry ids are the sequence numbers, so Last-Event-ID is a stream position`() {
        publish(0, listOf(PlayerJoined("p1", 1, at), PlayerJoined("p2", 2, at)))
        publish(2, listOf(PlayerJoined("p3", 3, at)))

        val ids = redis.xrange(streamKey(roomId), StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID)
            .map { it.id.toString() }
        assertEquals(listOf("1-0", "2-0", "3-0"), ids)
    }

    @Test
    fun `a reader resumes from a sequence number and gets exactly what it missed`() {
        publish(0, listOf(PlayerJoined("p1", 1, at), PlayerJoined("p2", 2, at), PlayerJoined("p3", 3, at)))

        // Exactly what the gateway sends for `Last-Event-ID: 1` — the exclusive range `(1-0` to `+`,
        // in the raw form the protocol takes, rather than a client-side reinterpretation of it.
        val after = redis.xrange(streamKey(roomId), "(1-0", "+")
        assertEquals(listOf("2-0", "3-0"), after.map { it.id.toString() })
    }

    @Test
    fun `the seed never lands in Redis`() {
        publish(0, listOf(DeckRecycled(newDeckSize = 54, seed = 987654321L, at = at)))

        val entry = redis.xrange(streamKey(roomId), StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID).single()
        val payload = entry.fields.getValue("payload")
        assertFalse(payload.contains("987654321"), "the seed reached Redis: $payload")
        assertContains(payload, "newDeckSize")
        assertEquals("DeckRecycled", entry.fields["type"])
    }

    @Test
    fun `a Redis that is down costs the live feed, not the service`() {
        // Two properties in one, and both decide whether a cold cluster converges: constructing the
        // publisher must not require Redis to be up (or the pod crash-loops while Redis is still
        // starting), and a publish against a dead Redis must come back as a counted failure rather
        // than an exception thrown at whoever just made a legal move.
        val failures = mutableListOf<Throwable>()
        val dead = RedisClient.create(URI("redis://localhost:1"))

        RedisRoomEvents(dead, onFailure = { failures += it }).use {
            it.published(roomId, 0, listOf(PlayerJoined("p1", 1, at)), "corr-1")
        }

        assertEquals(1, failures.size, "a failed publish was swallowed instead of counted")
    }

    @Test
    fun `a completed room's stream is given a time to live`() {
        assertEquals(-2L, redis.ttl(streamKey(roomId))) // -2: the key does not exist yet
        publish(0, listOf(PlayerJoined("p1", 1, at)))
        assertEquals(-1L, redis.ttl(streamKey(roomId))) // -1: exists, no expiry while the room is live

        publish(1, listOf(RoomCompleted(RoomType.CASUAL, finalResults = listOf("p1"), at = at)))
        assertTrue(redis.ttl(streamKey(roomId)) > 0, "a finished room's stream would sit in Redis forever")
    }
}
