package uno

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant

/**
 * A card travels as its §5.F notation and an instant as ISO-8601, so a row in `room_events` is
 * readable with `psql` alone. The audit path exists to be read by a person under time pressure.
 */
object CardSerializer : KSerializer<Card> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("uno.Card", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Card) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Card {
        val text = decoder.decodeString()
        return Card.parse(text) ?: throw IllegalArgumentException("not a card: $text")
    }
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/**
 * `type` is the discriminator and it is the catalog's event name (D4), so the column in
 * `room_events` and the Kafka payload P5 publishes carry the same identifier the design docs use.
 */
val EventJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = false
}

fun encodeEvent(event: Event): JsonObject = EventJson.encodeToJsonElement(event) as JsonObject

fun decodeEvent(payload: JsonObject): Event = EventJson.decodeFromJsonElement(Event.serializer(), payload)

fun eventType(event: Event): String = encodeEvent(event).let { (it["type"] as kotlinx.serialization.json.JsonPrimitive).content }
