// The Public Event Publisher of Architecture §2.2: before an event is written to the outbox, the
// data no one outside this service may see is stripped from it. Deck order and hand contents never
// enter an event payload in the first place (that is a property of the engine's design), so what
// is left to remove is the RNG seed — and the spectator privacy boundary P6 depends on this being
// true from the start rather than retrofitted.

import kotlinx.serialization.json.JsonObject
import uno.DeckRecycled
import uno.Event
import uno.GameCompleted
import uno.PrivateEvent
import uno.RoomCompleted
import uno.RoomCreated
import uno.encodeEvent

const val PUBLIC_TOPIC = "room.public.events"
const val LIFECYCLE_TOPIC = "room.lifecycle.events"

/** §2.3.2: lifecycle events are what Tournament and Ranking consume; the rest feed Spectator. */
fun topicFor(event: Event): String = when (event) {
    is RoomCreated, is GameCompleted, is RoomCompleted -> LIFECYCLE_TOPIC
    else -> PUBLIC_TOPIC
}

private val PRIVATE_FIELDS = setOf("seed")

fun publicPayload(event: Event): JsonObject {
    val full = encodeEvent(event)
    if (event !is PrivateEvent) return full
    return JsonObject(full.filterKeys { it !in PRIVATE_FIELDS })
}

/**
 * `DeckRecycled` and `GameStarted` are the only events that carry a seed, and both are marked
 * private. A new private event that forgets the marker would leak, so the marker is what the test
 * asserts against — not a hand-maintained list of event names.
 */
fun leaksPrivateData(event: Event): Boolean =
    publicPayload(event).keys.any { it in PRIVATE_FIELDS }

data class OutboxRow(
    val sequenceNumber: Int,
    val topic: String,
    val eventType: String,
    val payload: JsonObject,
)
