// Architecture §2.3.3: room-gameplay consumes `identity.session-events` and turns a
// SessionInvalidated into a PlayerDisconnected in every room the player is still sitting in, which
// opens the 60-second reconnection window.
//
// This is the other half of decision E1. Validating the JWT locally tells us a token was signed by
// identity; it cannot tell us the session was killed a second ago. The topic does, and the room
// reacts to it — rather than every request paying for a revocation lookup.

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.Properties
import uno.DisconnectPlayer

/** Also the dedup source key: one name for one stream (§2.3.3). */
const val SESSION_EVENTS_TOPIC = "identity.session-events"

@Serializable
data class SessionInvalidated(
    val playerId: String,
    val oldSessionId: String,
    val reason: String? = null,
)

/**
 * Separated from Kafka on purpose: what a session invalidation *means* to a room is the part worth
 * testing, and it needs no broker to test.
 */
class SessionInvalidations(
    private val rooms: Rooms,
    private val store: EventStore,
    private val log: (String, Map<String, Any?>) -> Unit = { _, _ -> },
) {
    /** Returns the rooms it disconnected the player from; 0 for a redelivery or an idle player. */
    fun handle(event: SessionInvalidated): Int {
        // D8: keyed by the invalidated session, so a redelivery cannot re-open a window that has
        // since expired — or disconnect a player who has already logged back in.
        if (!store.markConsumed(SESSION_EVENTS_TOPIC, event.oldSessionId)) {
            log("session-event-redelivered", mapOf("oldSessionId" to event.oldSessionId))
            return 0
        }
        val affected = rooms.activeRoomsOf(event.playerId)
        affected.forEach { roomId ->
            rooms.submit(roomId, DisconnectPlayer(event.playerId, event.reason ?: "session_invalidated"), null)
        }
        log(
            "session-invalidated",
            mapOf("playerId" to event.playerId, "rooms" to affected.size, "reason" to event.reason),
        )
        return affected.size
    }
}

/**
 * A single-threaded poll loop with manual commits: the offset moves only after the disconnect is
 * durable, so a crash re-delivers rather than loses. Losing one means a player stays "connected" in
 * a room they can no longer reach, and nothing would ever correct it.
 */
class SessionEventsConsumer(
    private val brokers: String,
    private val handler: SessionInvalidations,
    private val log: (String, Map<String, Any?>) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var running = true

    fun start(): Thread = Thread({ run() }, "session-events").apply {
        isDaemon = true
        start()
    }

    fun stop() {
        running = false
    }

    private fun run() {
        // The broker may not be up yet on a cold cluster. This loop is not on any request path, so
        // it retries forever instead of taking the pod down with it.
        while (running) {
            try {
                consume()
            } catch (e: Exception) {
                log("session-consumer-error", mapOf("error" to e.toString()))
                Thread.sleep(5_000)
            }
        }
    }

    private fun consume() {
        KafkaConsumer<String, String>(properties()).use { consumer ->
            consumer.subscribe(listOf(SESSION_EVENTS_TOPIC))
            log("session-consumer-started", mapOf("topic" to SESSION_EVENTS_TOPIC))
            while (running) {
                val records = consumer.poll(Duration.ofSeconds(1))
                if (records.isEmpty) continue
                for (record in records) {
                    runCatching { json.decodeFromString<SessionInvalidated>(record.value()) }
                        .onSuccess { handler.handle(it) }
                        // A payload we cannot read is dropped, not retried forever: one poisoned
                        // message must not stop every later session kill from being honoured.
                        .onFailure { log("session-event-unreadable", mapOf("error" to it.toString())) }
                }
                consumer.commitSync()
            }
        }
    }

    private fun properties() = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "room-gameplay")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
    }
}
