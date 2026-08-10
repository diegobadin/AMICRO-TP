// The realtime tier's source (decision E1): every committed event is appended to a per-room Redis
// stream, which the gateway tails and fans out as SSE. Nothing else reads it — the outbox is still
// P5's, and the event log in Postgres is still the archive.
//
// Two properties this file exists to hold:
//
//   The publish happens AFTER the commit, never inside the transaction. Inside, a Redis outage
//   would roll back a legal move — the exact inversion of log-before-broadcast.
//
//   The payload is `publicPayload(event)`, the same privacy filter the outbox row goes through.
//   `GameStarted` and `DeckRecycled` carry the RNG seed; publishing the raw encoding would hand the
//   deck order to every player at the table.

import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.UnifiedJedis
import redis.clients.jedis.params.XAddParams
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import uno.Event
import uno.eventType

fun streamKey(roomId: UUID): String = "room:$roomId:events"

/** A P3 game closed at 359 events, so this is a whole game plus slack. */
const val STREAM_MAXLEN = 2000L

/**
 * Refreshed on every publish, so a stream outlives the room it belongs to by this much and no more.
 * Keyed to the *last write* rather than to `RoomCompleted`, because a room that is abandoned never
 * completes — and a rule that only reclaims tidy endings reclaims nothing on a busy cluster.
 * `room_events` is the archive; this is a window onto the last few minutes of it.
 */
const val STREAM_TTL_SECONDS = 6 * 3600L

private const val QUEUE_DEPTH = 1024

fun interface RoomEvents {
    /** Called once per committed batch, with the sequence number the batch started from. */
    fun published(roomId: UUID, baseSequence: Int, events: List<Event>, correlationId: String?)
}

val NoRoomEvents = RoomEvents { _, _, _, _ -> }

/** One stream entry. `sequenceNumber` becomes the entry id, which is what makes resume work. */
data class StreamEntry(val sequenceNumber: Int, val fields: Map<String, String>)

/**
 * What a committed batch looks like on the wire, with no Redis in sight — so the two properties
 * that matter (the id is the sequence number, the payload is privacy-filtered) are provable by a
 * plain unit test rather than by reading the publish loop.
 */
fun entriesFor(baseSequence: Int, events: List<Event>, correlationId: String?): List<StreamEntry> =
    events.mapIndexed { index, event ->
        val sequenceNumber = baseSequence + index + 1
        StreamEntry(
            sequenceNumber,
            mapOf(
                "type" to eventType(event),
                "seq" to sequenceNumber.toString(),
                "payload" to publicPayload(event).toString(),
                "correlationId" to (correlationId ?: ""),
            ),
        )
    }

/**
 * Publishing is asynchronous on a single thread: one thread keeps the per-room order the gateway
 * depends on, and taking Redis off the response path means an outage costs the live feed rather
 * than a second per move. A full queue drops the batch and counts it — the events are already
 * durable, and a client repairs its view from the stream heartbeat or the resync read.
 */
class RedisRoomEvents(
    private val redis: UnifiedJedis,
    private val onFailure: (Throwable) -> Unit,
) : RoomEvents, AutoCloseable {

    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_DEPTH),
        { runnable -> Thread(runnable, "room-stream").apply { isDaemon = true } },
        // Dropping is the right answer when Redis cannot keep up — the events are durable and the
        // client repairs itself — but a silent drop is not. The handler is how it gets counted.
        { _, _ -> onFailure(IllegalStateException("stream publish queue full, batch dropped")) },
    )

    override fun published(roomId: UUID, baseSequence: Int, events: List<Event>, correlationId: String?) {
        worker.execute { publish(roomId, baseSequence, events, correlationId) }
    }

    private fun publish(roomId: UUID, baseSequence: Int, events: List<Event>, correlationId: String?) {
        val key = streamKey(roomId)
        try {
            for (entry in entriesFor(baseSequence, events, correlationId)) {
                redis.xadd(
                    key,
                    // The entry id IS the sequence number, so `Last-Event-ID` is a stream position
                    // and needs no lookup table (Architecture §1.4). Room sequence numbers are
                    // strictly increasing — that is P3's primary key — which is exactly what XADD
                    // requires of an explicit id.
                    XAddParams.xAddParams()
                        .id(StreamEntryID(entry.sequenceNumber.toLong(), 0L))
                        .maxLen(STREAM_MAXLEN)
                        .approximateTrimming(),
                    entry.fields,
                )
            }
            redis.expire(key, STREAM_TTL_SECONDS)
        } catch (e: Throwable) {
            onFailure(e)
        }
    }

    override fun close() {
        worker.shutdown()
        worker.awaitTermination(5, TimeUnit.SECONDS)
    }
}
