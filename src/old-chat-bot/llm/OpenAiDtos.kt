package llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val temperature: Double? = 0.2,
    @SerialName("tool_choice") val toolChoice: String? = "auto",
    val tools: List<OpenAiTool>? = null
)

@Serializable
data class OpenAiTool(
    val type: String,
    val function: OpenAiFunction
)

@Serializable
data class OpenAiFunction(
    val name: String,
    val description: String,
    val parameters: kotlinx.serialization.json.JsonObject
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

@Serializable
data class OpenAiToolCall(
    val id: String,
    val type: String,
    val function: OpenAiToolCallFunction
)

@Serializable
data class OpenAiToolCallFunction(
    val name: String,
    val arguments: String
)

@Serializable
data class OpenAiChatResponse(
    val choices: List<OpenAiChoice>
)

@Serializable
data class OpenAiChoice(
    val message: OpenAiMessage
)
