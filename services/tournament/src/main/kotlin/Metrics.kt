// Instrumented from its first real phase, per the program rule — P8 consolidates dashboards, it
// does not retrofit counters.
//
// Every gauge-shaped question here is asked with a counter instead, because a gauge that was never
// `Set` reads 0 and 0 is usually the healthy value: `timerworker_due_rooms 0` cost P5 real diagnosis
// time. `consumer_starts_total` is the one that matters most — a `room_results_total` of 0 cannot
// otherwise be told from "nobody has finished a match yet".

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.concurrent.TimeUnit

object Metrics {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also {
        ClassLoaderMetrics().bindTo(it)
        JvmMemoryMetrics().bindTo(it)
        JvmGcMetrics().bindTo(it)
        JvmThreadMetrics().bindTo(it)
        ProcessorMetrics().bindTo(it)
    }

    // "opened" rather than "created": OpenMetrics reserves the `_created` suffix, and the Prometheus
    // client silently rewrites a name that uses it — room-gameplay lost `rooms_created_total` to this.
    val tournamentsCreated: Counter = Counter.builder("tournament.tournaments.opened")
        .description("Tournaments opened for registration").register(registry)

    val registrations: Counter = Counter.builder("tournament.registrations")
        .description("Players registered").register(registry)

    val tournamentsStarted: Counter = Counter.builder("tournament.tournaments.started")
        .description("Tournaments that crossed the registration threshold").register(registry)

    val tournamentsCompleted: Counter = Counter.builder("tournament.tournaments.completed")
        .description("Tournaments played to a champion").register(registry)

    val roundsStarted: Counter = Counter.builder("tournament.rounds.started")
        .description("Rounds whose rooms were provisioned and announced").register(registry)

    val roundsCompleted: Counter = Counter.builder("tournament.rounds.completed")
        .description("Rounds where every room reported").register(registry)

    val roomsProvisioned: Counter = Counter.builder("tournament.rooms.provisioned")
        .description("Rooms asked of room-gameplay").register(registry)

    val roomResults: Counter = Counter.builder("tournament.room.results")
        .description("Room verdicts recorded against a round").register(registry)

    val eventsDeduped: Counter = Counter.builder("tournament.events.deduped")
        .description("Redelivered room events recognised and dropped").register(registry)

    // The counter that turns "this consumer is doing nothing" into a diagnosable statement.
    val consumerStarts: Counter = Counter.builder("tournament.consumer.starts")
        .description("Times the saga consumer entered its poll loop").register(registry)

    val consumerFailures: Counter = Counter.builder("tournament.consumer.failures")
        .description("Times the saga consumer fell out of its poll loop and backed off").register(registry)

    // Commands that lost every race for a sequence number. A registration rush makes this move;
    // it moving a lot means the retry budget is too small for the contention.
    val contended: Counter = Counter.builder("tournament.commands.contended")
        .description("Commands that exhausted their optimistic-concurrency attempts").register(registry)

    val reconcileSweeps: Counter = Counter.builder("tournament.reconcile.sweeps")
        .description("Reconciler passes completed").register(registry)

    val reconcileFailures: Counter = Counter.builder("tournament.reconcile.failures")
        .description("Reconciler passes that could not advance a tournament").register(registry)

    fun eventsSkipped(reason: String): Counter = Counter.builder("tournament.events.skipped")
        .description("Room events the saga had no use for").tag("reason", reason).register(registry)

    fun requestDuration(route: String, status: Int): Timer = Timer.builder("tournament.http.request.duration")
        .tag("route", route).tag("status", status.toString()).register(registry)

    fun record(timer: Timer, nanos: Long) = timer.record(nanos, TimeUnit.NANOSECONDS)
}
