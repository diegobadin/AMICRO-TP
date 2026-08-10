// Instrumented from its first real phase, per the program rule. The business counters come first:
// moves and completed games are two of the three P8 needs, and they are incremented at the edge so
// the engine stays a pure function with no registry in it.

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

object Metrics {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also {
        ClassLoaderMetrics().bindTo(it)
        JvmMemoryMetrics().bindTo(it)
        JvmGcMetrics().bindTo(it)
        JvmThreadMetrics().bindTo(it)
        ProcessorMetrics().bindTo(it)
    }

    // "opened", not "created": OpenMetrics reserves the `_created` suffix for the creation-timestamp
    // series, so the Prometheus client rewrites roomgameplay_rooms_created_total into the ambiguous
    // roomgameplay_rooms_total. A deviation from plan D7's name, forced by the exposition format.
    val roomsCreated: Counter = Counter.builder("roomgameplay.rooms.opened")
        .description("Rooms created").register(registry)

    val gamesStarted: Counter = Counter.builder("roomgameplay.games.started")
        .description("Games started").register(registry)

    val gamesCompleted: Counter = Counter.builder("roomgameplay.games.completed")
        .description("Games played to a winner").register(registry)

    fun move(type: String, result: String): Counter = Counter.builder("roomgameplay.moves")
        .description("Moves submitted, by command type and outcome")
        .tags("type", type, "result", result).register(registry)

    fun rejection(reason: String): Counter = Counter.builder("roomgameplay.engine.rejections")
        .description("Commands the engine refused, by reason")
        .tags("reason", reason).register(registry)

    fun commandDuration(route: String, status: Int): Timer = Timer.builder("roomgameplay.command.duration")
        .description("Request latency by route and status")
        .tags("route", route, "status", status.toString()).register(registry)
}

// An unbounded `route` label lets anyone hitting random URLs grow the metric cardinality without
// limit, so only paths matching a known template become a label value.
private val TEMPLATES = listOf(
    "/health",
    "/metrics",
    "/rooms",
    "/rooms/{roomId}",
    "/rooms/{roomId}/players/{playerId}",
    "/rooms/{roomId}/games",
    "/rooms/{roomId}/games/{gameNumber}",
    "/rooms/{roomId}/games/{gameNumber}/moves",
).map { it.trim('/').split('/') }

fun routeLabel(path: String): String {
    val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
    val template = TEMPLATES.firstOrNull { t ->
        t.size == segments.size && t.zip(segments).all { (a, b) -> a.startsWith("{") || a == b }
    } ?: return "unknown"
    return "/" + template.joinToString("/")
}
