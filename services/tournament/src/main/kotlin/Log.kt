// One structured line per thing worth knowing, in the shape identity and room-gameplay emit, so
// `kubectl logs` across the system reads uniformly. Built with the JSON library rather than string
// concatenation: a reason arriving from Kafka or an exception message containing a quote would
// otherwise break the line, and a log an operator cannot parse under pressure is worse than none.

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

fun logJson(level: String, fields: Map<String, Any?>) {
    val line = buildJsonObject {
        put("ts", Instant.now().toString())
        put("level", level)
        put("service", SERVICE)
        fields.forEach { (key, value) ->
            when (value) {
                null -> put(key, JsonNull)
                is Number -> put(key, value)
                is Boolean -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }.toString()
    if (level == "error") System.err.println(line) else println(line)
}

fun logInfo(vararg fields: Pair<String, Any?>) = logJson("info", fields.toMap())

fun logError(vararg fields: Pair<String, Any?>) = logJson("error", fields.toMap())

fun logJsonInfo(action: String, fields: Map<String, Any?>) = logJson("info", mapOf("action" to action) + fields)
