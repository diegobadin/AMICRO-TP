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

    // P7: rooms a tournament asked for, as opposed to rooms a player opened. Both increment
    // `rooms.opened`; this one says which door they came through.
    val provisionedRooms: Counter = Counter.builder("roomgameplay.rooms.provisioned")
        .description("Tournament rooms created through the internal route").register(registry)

    val gamesCompleted: Counter = Counter.builder("roomgameplay.games.completed")
        .description("Games played to a winner").register(registry)

    // Best-effort publication to the realtime tier: the events are already durable when this fires,
    // so a failure costs a live frame, not a move. Silent would be the problem, hence the counter.
    val streamPublishFailures: Counter = Counter.builder("roomgameplay.stream.publish.failures")
        .description("Committed events that did not reach the room stream").register(registry)

    // P5: a room the clock closed rather than a game, and a seat given up for not being sat in.
    // Both say the same thing from different ends — nobody came back — and both used to be
    // impossible to count because nothing evaluated a deadline unless a player was still there.
    val roomsExpired: Counter = Counter.builder("roomgameplay.rooms.expired")
        .description("Waiting rooms closed by the clock").register(registry)

    val idleForfeits: Counter = Counter.builder("roomgameplay.players.idle.forfeits")
        .description("Seats given up after consecutive turn timeouts").register(registry)

    fun timerTick(result: String): Counter = Counter.builder("roomgameplay.timer.ticks")
        .description("Ticks from the timer worker, by what they found")
        .tags("result", result).register(registry)

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
    "/internal/rooms/{roomId}/tick",
).map { it.trim('/').split('/') }

fun routeLabel(path: String): String {
    val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
    val template = TEMPLATES.firstOrNull { t ->
        t.size == segments.size && t.zip(segments).all { (a, b) -> a.startsWith("{") || a == b }
    } ?: return "unknown"
    return "/" + template.joinToString("/")
}
