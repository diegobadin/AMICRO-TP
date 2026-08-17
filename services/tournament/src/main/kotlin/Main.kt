import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.system.exitProcess

@Serializable
data class ErrorBody(val error: String)

@Serializable
private data class Health(val status: String, val service: String)

private val StartedAt = AttributeKey<Long>("startedAt")
private val CorrelationId = AttributeKey<String>("correlationId")

fun ApplicationCall.correlationId(): String = attributes[CorrelationId]

/**
 * Bounded route labels, because the raw path would let anyone grow the metric cardinality by
 * inventing tournament ids.
 */
fun routeLabel(path: String): String {
    val parts = path.trim('/').split('/')
    return when {
        parts.firstOrNull() != "tournaments" -> "/" + (parts.firstOrNull() ?: "")
        parts.size == 1 -> "/tournaments"
        parts.size == 2 -> "/tournaments/:id"
        else -> "/tournaments/:id/" + parts.drop(2).joinToString("/") { if (it.toIntOrNull() != null) ":n" else it }
            .replace(Regex("/[0-9a-fA-F-]{8,}"), "/:id")
    }
}

// A probe is not an event: the kubelet's /health lines arrive every few seconds forever, and in P6
// they drowned the single line that explained a whole failure.
private val Observability = createApplicationPlugin("Observability") {
    onCall { call ->
        call.attributes.put(StartedAt, System.nanoTime())
        val id = call.request.headers["X-Correlation-Id"] ?: UUID.randomUUID().toString()
        call.attributes.put(CorrelationId, id)
        call.response.headers.append("X-Correlation-Id", id)
    }
    on(ResponseSent) { call ->
        val elapsed = System.nanoTime() - call.attributes[StartedAt]
        val status = call.response.status()?.value ?: 0
        val route = routeLabel(call.request.path())
        Metrics.record(Metrics.requestDuration(route, status), elapsed)
        if (route != "/health" && route != "/metrics") {
            logInfo(
                "method" to call.request.httpMethod.value,
                "route" to route,
                "status" to status,
                "durationMs" to elapsed / 1_000_000,
                "correlationId" to call.attributes[CorrelationId],
            )
        }
    }
}

fun Application.module(tournaments: Tournaments) {
    install(ContentNegotiation) { json() }
    install(Observability)
    install(StatusPages) {
        // A body we cannot read is the caller's problem, not a 500 telling them to retry for ever.
        exception<BadRequestException> { call, cause ->
            logError("action" to "malformed", "correlationId" to call.correlationId(), "error" to cause.toString())
            call.respond(HttpStatusCode.BadRequest, ErrorBody("malformed_request"))
        }
        exception<Throwable> { call, cause ->
            logError("action" to "unhandled", "correlationId" to call.correlationId(), "error" to cause.toString())
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal_error"))
        }
    }
    installAuth()

    routing {
        get("/health") { call.respond(Health("ok", SERVICE)) }
        get("/metrics") { call.respondText(Metrics.registry.scrape(), ContentType.Text.Plain) }
        tournamentRoutes(tournaments)
    }
}

fun pool(config: Config): DataSource = HikariDataSource(
    HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        username = config.databaseUser
        config.databasePassword?.let { password = it }
        maximumPoolSize = 8
    },
)

/**
 * The sweep that finishes what a crash interrupted. Every step it calls is idempotent, so a pass
 * with nothing to do writes nothing — and it is the reason a round whose last result arrived during
 * a restart does not sit there forever.
 */
private fun scheduleReconciler(tournaments: Tournaments, intervalMs: Long) {
    val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "reconciler").apply { isDaemon = true }
    }
    scheduler.scheduleWithFixedDelay({
        runCatching { tournaments.reconcile() }
            .onSuccess { if (it > 0) logInfo("action" to "reconciled", "advanced" to it) }
            .onFailure { logError("action" to "reconcile-sweep-failed", "error" to it.toString()) }
    }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
}

fun main() {
    val config = Config.fromEnv()
    val dataSource = pool(config)
    // This service owns its schema, so a migration it cannot perform is fatal: a pod that never
    // becomes ready is a far better failure than one answering against no tables. Five or six
    // restarts while Postgres comes up on a cold cluster are expected, not a defect (delta §11.12).
    try {
        migrate(dataSource)
    } catch (e: Exception) {
        logError("action" to "migrate", "error" to e.toString())
        exitProcess(1)
    }

    val store = Store(dataSource)
    val rooms = RoomClient(config.roomGameplayUrl, config.internalToken)
    val tournaments = Tournaments(store, rooms, config)

    SagaConsumer(
        brokers = config.kafkaBrokers,
        handler = SagaHandler(tournaments, ::logJsonInfo),
        log = ::logJsonInfo,
    ).start()

    scheduleReconciler(tournaments, config.reconcileIntervalMs)

    logInfo(
        "msg" to "listening",
        "port" to config.port,
        "minPlayers" to config.minPlayers,
        "roomSize" to config.roomSize,
        "sessionId" to RoomClient.SESSION_ID,
    )
    embeddedServer(Netty, port = config.port) { module(tournaments) }.start(wait = true)
}
