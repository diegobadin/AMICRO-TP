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
import redis.clients.jedis.RedisClient
import java.net.URI
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

// One request line each, carrying the correlationId the client sent (or one we minted) — the
// observability seam the README documents.
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
        Metrics.commandDuration(route, status).record(elapsed, TimeUnit.NANOSECONDS)
        logInfo(
            "method" to call.request.httpMethod.value,
            "route" to route,
            "status" to status,
            "durationMs" to elapsed / 1_000_000,
            "correlationId" to call.attributes[CorrelationId],
        )
    }
}

fun Application.module(config: Config, rooms: Rooms) {
    install(ContentNegotiation) { json() }
    install(Observability)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logError("action" to "unhandled", "correlationId" to call.correlationId(), "error" to cause.toString())
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal_error"))
        }
    }
    installAuth()

    routing {
        get("/health") { call.respond(Health("ok", SERVICE)) }
        get("/metrics") { call.respondText(Metrics.registry.scrape(), ContentType.Text.Plain) }
        roomRoutes(rooms)
        internalRoutes(rooms)
    }
}

/**
 * Idempotency keys are retained for 24 hours (persistence-layer §1.4) and then swept, so the table
 * does not grow for the lifetime of the deployment. Once on startup and daily after that: a replay
 * arriving a day late is a bug, not a retry, and should get a fresh room rather than a stale answer.
 */
private fun scheduleIdempotencySweep(dataSource: DataSource) {
    val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "idempotency-sweep").apply { isDaemon = true }
    }
    scheduler.scheduleWithFixedDelay({
        runCatching { sweepIdempotencyKeys(dataSource) }
            .onSuccess { if (it > 0) logInfo("action" to "idempotency-swept", "rows" to it) }
            .onFailure { logError("action" to "idempotency-sweep-failed", "error" to it.toString()) }
    }, 0, 24, TimeUnit.HOURS)
}

fun main() {
    val config = Config.fromEnv()
    val dataSource = pool(config)
    // Migrate before listening. If Postgres is not there the process exits and the kubelet retries:
    // a pod that never becomes ready is a far better failure than one answering against no schema.
    try {
        migrate(dataSource)
    } catch (e: Exception) {
        logError("action" to "migrate", "error" to e.toString())
        exitProcess(1)
    }

    val store = EventStore(dataSource)
    val stream = RedisRoomEvents(
        redis = RedisClient.create(URI(config.redisUrl)),
        onFailure = { e ->
            Metrics.streamPublishFailures.increment()
            logError("action" to "stream-publish-failed", "error" to e.toString())
        },
    )
    val rooms = Rooms(store, config, stream = stream)
    scheduleIdempotencySweep(dataSource)

    // A superseded session has to disconnect the player from any room they are sitting in (E1).
    SessionEventsConsumer(
        brokers = config.kafkaBrokers,
        handler = SessionInvalidations(rooms, store, ::logJsonInfo),
        log = ::logJsonInfo,
    ).start()

    logInfo("msg" to "listening", "port" to config.port)
    embeddedServer(Netty, port = config.port) { module(config, rooms) }.start(wait = true)
}

private fun logJsonInfo(action: String, fields: Map<String, Any?>) =
    logJson("info", mapOf("action" to action) + fields)
