package chat

interface ConversationStore {
    fun get(conversationId: String): MutableList<ChatMessage>
    fun append(conversationId: String, message: ChatMessage)
}

class InMemoryConversationStore : ConversationStore {
    private val data = mutableMapOf<String, MutableList<ChatMessage>>()

    override fun get(conversationId: String): MutableList<ChatMessage> {
        return data.getOrPut(conversationId) { mutableListOf() }
    }

    override fun append(conversationId: String, message: ChatMessage) {
        get(conversationId).add(message)
    }
}
