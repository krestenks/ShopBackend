package llm

import chat.ChatMessage

data class ModelReply(
    val assistantText: String? = null,
    val toolCalls: List<ToolCall> = emptyList()
)

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

interface ModelBackend {
    suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ModelToolSpec> = emptyList()
    ): ModelReply
}

data class ModelToolSpec(
    val name: String,
    val description: String,
    val jsonSchema: String
)
