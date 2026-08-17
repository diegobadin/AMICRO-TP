// The fourth consumer group (§7.2 forbids joining an existing one): `tournament-saga` reads
// `room.lifecycle.events` and turns a room's verdict into a round's progress.
//
// Two events matter. `MatchCompleted` carries `advancingPlayers` — the room decided who advances,
// because the room is the only thing that watched the match (§3.2.2). `RoomExpired` is a room that
// never played, and it advances nobody: a round still has to close, or it waits forever.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.Properties

const val CONSUMER_GROUP = "tournament-saga"

/**
 * The event's name comes from the BODY, never from `ce-type`. The relay writes `ce-type` as a
 * reverse-DNS CloudEvents URI — `com.unoarena.room.MatchCompleted.v1` — while the catalog's name,
 * and the one the contract schema pins, is the bare `MatchCompleted` in the body. Comparing the URI
 * against the bare name skips every event while the service looks perfectly healthy: it cost P6 a
 * whole drill, and ranking read four lifecycle events and scored none of them.
 *
 * The header is still unwrapped as a fallback, so an event whose body somehow lacks `type` is
 * classified rather than silently dropped.
 */
fun eventName(headers: Map<String, String>, body: JsonObject): String {
    (body["type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
    val qualified = headers["ce-type"] ?: return ""
    val parts = qualified.split(".")
    return if (parts.size >= 2 && parts.last().startsWith("v")) parts[parts.size - 2] else ""
}

/** What the saga does with one room event, with no broker in sight so it can be tested without one. */
class SagaHandler(
    private val tournaments: Tournaments,
    private val log: (String, Map<String, Any?>) -> Unit = { _, _ -> },
) {
    fun handle(headers: Map<String, String>, body: JsonObject): String {
        val type = eventName(headers, body)
        val roomId = (body["roomId"] as? JsonPrimitive)?.contentOrNull
        if (roomId == null) {
            Metrics.eventsSkipped("no_room_id").increment()
            return "skipped"
        }

        val advancing = when (type) {
            "MatchCompleted" -> (body["advancingPlayers"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()
            // A room the clock closed before it ever played. Nobody advances, and the round closes.
            "RoomExpired" -> emptyList()
            else -> {
                Metrics.eventsSkipped("not_a_room_verdict").increment()
                return "skipped"
            }
        }

        val eventKey = headers["ce-id"] ?: "$roomId:${body["sequenceNumber"]}"
        val outcome = tournaments.recordResult(
            roomId = roomId,
            advancingPlayers = advancing,
            eventKey = eventKey,
            correlationId = headers["ce-correlationid"],
        )
        when (outcome) {
            // Every casual game in the system lands on this topic too. Not ours is not a problem.
            "not_ours" -> Metrics.eventsSkipped("not_a_tournament_room").increment()
            "duplicate" -> Metrics.eventsDeduped.increment()
            else -> {
                Metrics.roomResults.increment()
                log("room-result", mapOf("roomId" to roomId, "type" to type, "advancing" to advancing.size))
            }
        }
        return outcome
    }
}

/**
 * A single-threaded poll loop with manual commits, and a startup that retries for ever.
 *
 * The second half is the one P6 paid for: a client exhausts its own retries during a cold start —
 * Kafka is still electing while the pod is already up — and a `.catch` that only logs leaves the
 * service running `Healthy`, answering /health 200, with no consumer at all. Thirteen minutes of it
 * in P6/spectator. `tournament_consumer_starts_total` sits beside the projection counters for the
 * same reason: a result counter reading 0 cannot otherwise be told from "nobody has played yet".
 */
class SagaConsumer(
    private val brokers: String,
    private val handler: SagaHandler,
    private val log: (String, Map<String, Any?>) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var running = true

    fun start(): Thread = Thread({ run() }, CONSUMER_GROUP).apply {
        isDaemon = true
        start()
    }

    fun stop() {
        running = false
    }

    private fun run() {
        while (running) {
            try {
                consume()
            } catch (e: Exception) {
                Metrics.consumerFailures.increment()
                log("saga-consumer-error", mapOf("error" to e.toString()))
                Thread.sleep(5_000)
            }
        }
    }

    private fun consume() {
        KafkaConsumer<String, String>(properties()).use { consumer ->
            consumer.subscribe(listOf(ROOM_EVENTS_SOURCE))
            Metrics.consumerStarts.increment()
            log("saga-consumer-started", mapOf("topic" to ROOM_EVENTS_SOURCE, "group" to CONSUMER_GROUP))
            while (running) {
                val records = consumer.poll(Duration.ofSeconds(1))
                if (records.isEmpty) continue
                for (record in records) {
                    runCatching { handler.handle(headersOf(record), json.parseToJsonElement(record.value()) as JsonObject) }
                        // One unreadable message must not stop every later round from advancing.
                        .onFailure {
                            Metrics.eventsSkipped("unreadable").increment()
                            log("room-event-unreadable", mapOf("error" to it.toString()))
                        }
                }
                consumer.commitSync()
            }
        }
    }

    private fun headersOf(record: ConsumerRecord<String, String>): Map<String, String> =
        record.headers().associate { it.key() to String(it.value()) }

    private fun properties() = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
        put(ConsumerConfig.GROUP_ID_CONFIG, CONSUMER_GROUP)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        // `earliest`, like the other three groups: a tournament that started before this consumer
        // first connected still has to hear how its rooms finished.
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    }
}
