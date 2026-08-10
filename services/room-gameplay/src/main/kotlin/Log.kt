// One structured line per thing worth knowing, in the same shape identity emits, so `kubectl logs`
// across both services reads uniformly.
//
// Built with the JSON library rather than string-concatenated: a reason string arriving from Kafka
// or an exception message containing a quote would otherwise break the line, and a log an operator
// cannot parse under pressure is worse than no log. Errors go to stderr so stdout stays a clean
// stream of request lines.

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
