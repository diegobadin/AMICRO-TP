// The same wire discipline room-gameplay's engine uses (`uno/Json.kt`): `type` is the discriminator
// and it carries the catalog's event name, so the column in `tournament_events`, the outbox payload
// and the Kafka body all say `TournamentCompleted` — which is what a consumer classifies on, since
// `ce-type` is a reverse-DNS URI and comparing against it silently skips everything (P6's drill).
//
// Duplicated from the engine rather than shared: kaniko builds each service from its own directory
// (P5's handoff decided this trade for the Go workers, P6 for the CQRS pair, and P7 keeps it).

@file:UseSerializers(InstantSerializer::class)

import kotlinx.serialization.KSerializer
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

val EventJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = false
}

fun encodeEvent(event: Event): JsonObject = EventJson.encodeToJsonElement(event) as JsonObject

fun decodeEvent(payload: JsonObject): Event = EventJson.decodeFromJsonElement(Event.serializer(), payload)

fun eventType(event: Event): String = (encodeEvent(event)["type"] as JsonPrimitive).content
