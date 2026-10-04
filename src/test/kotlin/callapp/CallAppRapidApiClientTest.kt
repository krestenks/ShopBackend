package callapp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * End-to-end tests for [CallAppRapidApiClient] against a throwaway loopback HTTP server, so the
 * real CIO client, status handling and UTF-8 decoding are all exercised.
 *
 * The line under test is the non-2xx branch: it used to *return* a `found = false` result, which
 * the caller then cached as a genuine not-found. See [CallAppQuotaHandlingTest] for why that was
 * so destructive.
 */
class CallAppRapidApiClientTest {

    private lateinit var server: HttpServer
    private var status = 200
    private var body = "{}"

    @BeforeEach
    fun start() {
        // Port 0 = let the OS pick a free one; bound to loopback only.
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api/v1/search") { ex: HttpExchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun client() = CallAppRapidApiClient(
        CallAppRapidApiConfig(
            apiKey  = "test-key-not-a-real-credential",
            host    = "localhost",
            baseUrl = "http://${InetAddress.getLoopbackAddress().hostAddress}:${server.address.port}",
        )
    )

    // ─── The regression ─────────────────────────────────────────────────────

    @Test
    fun `HTTP 429 throws a quota exception rather than returning a not-found`() = runTest {
        status = 429
        body = """{"message":"You have exceeded the MONTHLY quota for Requests on your current plan, PRO."}"""

        val e = assertThrows<CallAppQuotaExceededException> { client().lookup("+4551941736") }

        assertEquals(429, e.httpStatus)
        assertTrue(e.body!!.contains("MONTHLY quota"), "the raw body is kept for the log line")
    }

    @Test
    fun `other non-2xx statuses throw a plain HTTP exception`() = runTest {
        status = 503
        body = """{"message":"upstream down"}"""

        val e = assertThrows<CallAppHttpException> { client().lookup("+4551941736") }

        assertEquals(503, e.httpStatus)
    }

    @Test
    fun `a 401 is an HTTP error, not a quota error — a bad key must not silence lookups`() = runTest {
        status = 401
        body = """{"message":"Invalid API key"}"""

        assertThrows<CallAppHttpException> { client().lookup("+4551941736") }
    }

    // ─── Normal answers still parse ─────────────────────────────────────────

    @Test
    fun `a hit parses name and priority`() = runTest {
        status = 200
        body = """{"status":true,"message":"Success","timestamp":1735000000,"data":{"name":"Søren Kjærgård","priority":3}}"""

        val r = client().lookup("+4551941736")

        assertTrue(r.found)
        assertEquals("Søren Kjærgård", r.name, "non-ASCII must survive as UTF-8")
        assertEquals(3, r.priority)
    }

    @Test
    fun `a miss where data is a string is a clean not-found, not an error`() = runTest {
        status = 200
        body = """{"status":false,"message":"Invalid phone number or data not found.","data":"Invalid phone number or data not found."}"""

        val r = client().lookup("+4551941736")

        assertFalse(r.found)
        assertNull(r.name)
    }

    @Test
    fun `an unparseable number never reaches the network`() = runTest {
        status = 429  // would throw if a request were issued
        assertThrows<InvalidPhoneNumberException> { client().lookup("12") }
    }
}
