package llm

import chat.ChatMessage
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

class LmStudioBackend(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String? = null
) : ModelBackend {

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            })
        }

        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 180_000   // 3 minutes
            socketTimeoutMillis = 180_000
        }
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ModelToolSpec>
    ): ModelReply {

        val openAiMessages = messages.map { it.toOpenAi() }

        val openAiTools = if (tools.isEmpty()) {
            null
        } else {
            tools.map { spec ->
                OpenAiTool(
                    type = "function",
                    function = OpenAiFunction(
                        name = spec.name,
                        description = spec.description,
                        parameters = Json.decodeFromString(spec.jsonSchema)
                    )
                )
            }
        }

        val req = OpenAiChatRequest(
            model = model,
            messages = openAiMessages,
            tools = openAiTools
        )

        val response: OpenAiChatResponse = http.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            if (!apiKey.isNullOrBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            setBody(req)
        }.body()

        val msg = response.choices.first().message

        val toolCalls = msg.toolCalls?.map {
            ToolCall(
                id = it.id,
                name = it.function.name,
                argumentsJson = it.function.arguments
            )
        } ?: emptyList()

        val fallback = if (toolCalls.isEmpty()) parseToolCallFromContent(msg.content) else emptyList()

        val effectiveToolCalls = if (toolCalls.isNotEmpty()) toolCalls else fallback

        return ModelReply(
            assistantText = if (effectiveToolCalls.isNotEmpty()) null else msg.content,
            toolCalls = effectiveToolCalls
        )
    }
}

private fun parseToolCallFromContent(content: String?): List<ToolCall> {
    if (content.isNullOrBlank()) return emptyList()

    val cleaned = content.trim()
    val jsonPart = if (cleaned.contains("</tool_call>")) {
        cleaned.substringBefore("</tool_call>").trim()
    } else {
        cleaned
    }

    if (!jsonPart.startsWith("{")) return emptyList()
    if (!jsonPart.contains("\"name\"")) return emptyList()
    if (!jsonPart.contains("\"arguments\"")) return emptyList()

    return try {
        val obj = Json.parseToJsonElement(jsonPart).jsonObject
        val name = obj["name"]?.jsonPrimitive?.content ?: return emptyList()
        val args = obj["arguments"]?.toString() ?: "{}"

        listOf(
            ToolCall(
                id = "fallback-${System.currentTimeMillis()}",
                name = name,
                argumentsJson = args
            )
        )
    } catch (_: Exception) {
        emptyList()
    }
}


private fun ChatMessage.toOpenAi(): OpenAiMessage {
    return when (role) {
        "system" -> OpenAiMessage(role = "system", content = content)
        "user" -> OpenAiMessage(role = "user", content = content)
        "assistant" -> OpenAiMessage(role = "assistant", content = content)
        "tool" -> OpenAiMessage(role = "tool", content = content, toolCallId = toolCallId, name = name)
        else -> OpenAiMessage(role = "user", content = content)
    }
}
