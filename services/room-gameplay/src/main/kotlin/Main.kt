import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant

const val SERVICE = "room-gameplay"
const val DEFAULT_PORT = 8081

/**
 * Pure routing function — maps an HTTP (method, path) to a (statusCode, jsonBody) pair.
 * Kept side-effect-free so it can be unit-tested without a running server.
 */
fun route(method: String, path: String): Pair<Int, String> = when {
    method == "GET" && path == "/health" ->
        200 to """{"status":"ok","service":"$SERVICE"}"""
    method == "GET" && path == "/rooms/sample/state" ->
        200 to """{"state":"stub"}"""
    else ->
        404 to """{"error":"not_found"}"""
}

private fun jsonEscape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

private fun logRequest(method: String, path: String, status: Int) {
    val line = """{"ts":"${Instant.now()}","level":"info","service":"$SERVICE",""" +
        """"method":"${jsonEscape(method)}","path":"${jsonEscape(path)}","status":$status}"""
    println(line)
}

private fun handle(exchange: HttpExchange) {
    val method = exchange.requestMethod
    val path = exchange.requestURI.path
    val (status, body) = route(method, path)
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
    logRequest(method, path, status)
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/") { exchange -> exchange.use { handle(it) } }
    server.executor = null
    server.start()
    println("""{"ts":"${Instant.now()}","level":"info","service":"$SERVICE","msg":"listening","port":$port}""")
}
