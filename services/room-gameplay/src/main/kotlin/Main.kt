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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class ErrorBody(val error: String)

@Serializable
private data class Health(val status: String, val service: String)

private val StartedAt = AttributeKey<Long>("startedAt")
private val CorrelationId = AttributeKey<String>("correlationId")

fun ApplicationCall.correlationId(): String = attributes[CorrelationId]

// One structured JSON line per request, carrying the correlationId the client sent (or one we
// minted) — the observability seam the README documents, and the same shape identity emits so a
// `kubectl logs` across both services reads uniformly.
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
        println(
            """{"ts":"${Instant.now()}","level":"info","service":"$SERVICE",""" +
                """"method":"${call.request.httpMethod.value}","route":"$route","status":$status,""" +
                """"durationMs":${elapsed / 1_000_000},"correlationId":"${call.attributes[CorrelationId]}"}""",
        )
    }
}

fun Application.module(config: Config) {
    install(ContentNegotiation) { json() }
    install(Observability)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            System.err.println(
                """{"ts":"${Instant.now()}","level":"error","service":"$SERVICE",""" +
                    """"correlationId":"${call.correlationId()}","error":"${cause.toString().replace('"', '\'')}"}""",
            )
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal_error"))
        }
    }
    installAuth(config)

    routing {
        get("/health") { call.respond(Health("ok", SERVICE)) }
        get("/metrics") {
            call.respondText(Metrics.registry.scrape(), ContentType.Text.Plain)
        }
        roomRoutes()
    }
}

fun main() {
    val config = Config.fromEnv()
    println(
        """{"ts":"${Instant.now()}","level":"info","service":"$SERVICE","msg":"listening","port":${config.port}}""",
    )
    embeddedServer(Netty, port = config.port) { module(config) }.start(wait = true)
}
