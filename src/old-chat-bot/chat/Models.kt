package chat

data class ChatMessage(
    val role: String,
    val content: String,
    val name: String? = null,
    val toolCallId: String? = null
)
