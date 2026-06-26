import kotlin.test.Test
import kotlin.test.assertEquals

class RouteTest {

    @Test
    fun `health returns 200 with service name`() {
        val (status, body) = route("GET", "/health")
        assertEquals(200, status)
        assertEquals("""{"status":"ok","service":"room-gameplay"}""", body)
    }

    @Test
    fun `sample room state returns canned stub body`() {
        val (status, body) = route("GET", "/rooms/sample/state")
        assertEquals(200, status)
        assertEquals("""{"state":"stub"}""", body)
    }

    @Test
    fun `unknown path returns 404`() {
        val (status, _) = route("GET", "/nope")
        assertEquals(404, status)
    }
}
