package twilio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatbotToolParsingTest {

    private fun findJsonObjectIndices(content: String): List<Pair<Int, Int>> {
        val results = mutableListOf<Pair<Int, Int>>()
        var i = 0
        
        while (i < content.length) {
            if (content[i] == '{') {
                val end = findMatchingBrace(content, i)
                if (end > i) {
                    results.add(Pair(i, end + 1))
                    i = end + 1
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        
        return results
    }

    private fun findMatchingBrace(s: String, openIndex: Int): Int {
        var depth = 0
        var inString = false
        var i = openIndex
        
        while (i < s.length) {
            val c = s[i]
            
            if (c == '"' && (i == 0 || s[i - 1] != '\\')) {
                inString = !inString
            } else if (!inString) {
                if (c == '{') depth++
                if (c == '}') {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    @Test
    fun testRealLlmResponse() {
        val response = """
{
  "id": "chatcmpl-ds43o0rwqzlj2ydct3uh6f",
  "object": "chat.completion",
  "model": "essentialai/rnj-1",
  "choices": [
    {
      "message": {
        "content": "{\"tool\":\"get_therapist_details\",\"arguments\":{\"therapistId\": \"3\"}}\n"
      }
    }
  ]
}
        """.trimIndent()
        
        val json = Json.parseToJsonElement(response).jsonObject
        val choices = json["choices"]?.jsonArray
        val message = choices?.get(0)?.jsonObject?.get("message")?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.content
        
        Assertions.assertNotNull(content)
        
        // Unescape
        val unescaped = content!!.replace("\\\"", "\"").replace("\\n", "\n").trim()
        Assertions.assertTrue(unescaped.contains("get_therapist_details"))
        
        val indices = findJsonObjectIndices(unescaped)
        Assertions.assertTrue(indices.isNotEmpty())
        
        val jsonStr = unescaped.substring(indices[0].first, indices[0].second)
        val parsed = Json.parseToJsonElement(jsonStr).jsonObject
        
        Assertions.assertEquals("get_therapist_details", parsed["tool"]?.jsonPrimitive?.content)
    }

    @Test
    fun testSimpleToolCall() {
        val content = """{"tool":"list_therapists","arguments":{}}"""
        val indices = findJsonObjectIndices(content)
        Assertions.assertEquals(1, indices.size)
    }

    @Test
    fun testFindNestedJson() {
        val content = """{"tool":"book_multi","arguments":{"appointments":[{"a":"b"}]}}"""
        val indices = findJsonObjectIndices(content)
        Assertions.assertTrue(indices.size >= 1)
    }

    @Test
    fun testFindMultipleToolCalls() {
        val content = """{"tool":"a","arguments":{}}{"tool":"b","arguments":{}}"""
        val indices = findJsonObjectIndices(content)
        Assertions.assertEquals(2, indices.size)
    }

    @Test
    fun testFindToolCallInText() {
        val content = """Hello, I should call {"tool":"test","arguments":{"x":1}} and then respond"""
        val indices = findJsonObjectIndices(content)
        Assertions.assertEquals(1, indices.size)
    }

    @Test
    fun testParseWithArrays() {
        val content = """{"tool":"book_multi","arguments":{"appointments":[{"therapistId":"1"}]}}"""
        val indices = findJsonObjectIndices(content)
        Assertions.assertTrue(indices.size >= 1)
    }

    @Test
    fun testDoubleBraceErrorCase() {
        val content = """{{"therapistId":"2"}}"""
        val indices = findJsonObjectIndices(content)
        Assertions.assertTrue(indices.isNotEmpty())
    }

    @Test
    fun testHandleEscapedQuotes() {
        val content = """{\"tool\":\"test\",\"arguments\":{\"x\":1}}"""
        val unescaped = content.replace("\\\"", "\"")
        val indices = findJsonObjectIndices(unescaped)
        Assertions.assertEquals(1, indices.size)
    }

    @Test
    fun testParseUnescapedContent() {
        val content = """{\"tool\":\"get_therapist_details\",\"arguments\":{\"therapistId\": \"3\"}}"""
        val unescaped = content.replace("\\\"", "\"")
        val indices = findJsonObjectIndices(unescaped)
        Assertions.assertTrue(indices.isNotEmpty())
    }

    @Test
    fun testConcatenatedToolCalls() {
        // Multiple tool calls concatenated like the LLM returns:
        // {"tool":"get_therapist_details","arguments":{"therapistId":"2"}}{"tool":"get_therapist_details","arguments":{"therapistId":"3"}}
        val content = """{\"tool\":\"get_therapist_details\",\"arguments\":{\"therapistId\":\"2\"}}{\"tool\":\"get_therapist_details\",\"arguments\":{\"therapistId\":\"3\"}}"""
        val unescaped = content.replace("\\\"", "\"")
        
        val indices = findJsonObjectIndices(unescaped)
        Assertions.assertEquals(2, indices.size)
        
        // Parse first tool call
        val firstJson = Json.parseToJsonElement(unescaped.substring(indices[0].first, indices[0].second)).jsonObject
        Assertions.assertEquals("get_therapist_details", firstJson["tool"]?.jsonPrimitive?.content)
        
        // Parse second tool call
        val secondJson = Json.parseToJsonElement(unescaped.substring(indices[1].first, indices[1].second)).jsonObject
        Assertions.assertEquals("get_therapist_details", secondJson["tool"]?.jsonPrimitive?.content)
    }
}
