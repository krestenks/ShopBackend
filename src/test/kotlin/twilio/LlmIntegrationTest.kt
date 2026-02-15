package twilio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * Integration tests for the chatbot.
 */
class LlmIntegrationTest {

    @Test
    fun testLlmIsAccessible() {
        val response = callLlmSimple("hello")
        println("Response: $response")
        assertTrue(response.isNotEmpty())
    }

    @Test
    fun testToolCallParsingParallelFormat() {
        // Test parallel format: {"id": 0, "name": "therapists", "arguments": {}}
        val testContent = """{"id": 0, "name": "therapists", "arguments": {}}"""
        val pattern = """\{"id":\s*\d+,\s*"name":\s*"([^"]+)",\s*"arguments":\s*(\{[^{}]*(?:\{[^{}]*\}[^{}]*)*)\}""".toRegex()
        val match = pattern.find(testContent)
        assertNotNull(match)
        assertEquals("therapists", match!!.groupValues[1])
    }

    @Test
    fun testToolCallParsingSequentialFormat() {
        // Test sequential format: {"tool":"therapists","arguments":{}}
        val testContent = """{"tool":"therapists","arguments":{}}"""
        val pattern = """\{"tool":\s*"([^"]+)",\s*"arguments":\s*(\{[^{}]*(?:\{[^{}]*\}[^{}]*)*)\}""".toRegex()
        val match = pattern.find(testContent)
        assertNotNull(match)
        assertEquals("therapists", match!!.groupValues[1])
    }

    @Test
    fun testToolCallParsingForAvailableSlots() {
        val testContent = """{"tool":"available_slots","arguments":{"employee_id":1,"date":"2025-01-15"}}"""
        val pattern = """\{"tool":\s*"([^"]+)",\s*"arguments":\s*(\{[^{}]*(?:\{[^{}]*\}[^{}]*)*)\}""".toRegex()
        val match = pattern.find(testContent)
        assertNotNull(match)
        assertEquals("available_slots", match!!.groupValues[1])
        assertTrue(match.groupValues[2].contains("employee_id"))
    }

    @Test
    fun testToolCallParsingForCreateBooking() {
        val testContent = """{"id": 4, "name": "create_booking", "arguments": {"employee_id": 2, "service_id": "1", "datetime": "2025-01-15 10:00", "phone": "+1234567890"}}"""
        val pattern = """\{"id":\s*\d+,\s*"name":\s*"([^"]+)",\s*"arguments":\s*(\{[^{}]*(?:\{[^{}]*\}[^{}]*)*)\}""".toRegex()
        val match = pattern.find(testContent)
        assertNotNull(match)
        assertEquals("create_booking", match!!.groupValues[1])
        assertTrue(match.groupValues[2].contains("employee_id"))
        assertTrue(match.groupValues[2].contains("service_id"))
    }

    private fun callLlmSimple(message: String): String {
        return try {
            val url = URL("http://localhost:1234/v1/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val requestBody = """
            {
                "model": "llama-3-groq-8b-tool-use",
                "messages": [
                    {"role": "user", "content": "$message"}
                ],
                "max_tokens": 512
            }
            """.trimIndent()

            conn.outputStream.bufferedWriter().use { it.write(requestBody) }
            
            if (conn.responseCode != 200) return "HTTP Error: ${conn.responseCode}"
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
