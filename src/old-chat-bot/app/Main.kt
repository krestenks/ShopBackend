package app

import chat.ChatOrchestrator
import chat.ConversationContext
import chat.InMemoryConversationStore
import domain.BookingService
import llm.LmStudioBackend
import tools.BookAppointmentTool
import tools.TimeSlotsTool
import domain.ServiceDirectoryService
import tools.GetTherapistAvailabilityTool
import tools.GetTherapistDetailsTool
import tools.ListTherapistsTool
import tools.ToolRegistry
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val baseUrl = System.getenv("LM_BASE_URL") ?: "http://172.30.128.1:1234/v1"
    //val modelName = System.getenv("LM_MODEL") ?: "essentialai/rnj-1"
    val modelName = System.getenv("LM_MODEL") ?: "qwen2.5-7b-instruct-uncensored"
    val apiKey = System.getenv("LM_API_KEY")

    val backend = LmStudioBackend(baseUrl = baseUrl, model = modelName, apiKey = apiKey)

    val bookingService = BookingService()
    val serviceDirectory = ServiceDirectoryService()

    val registry = ToolRegistry(listOf(
        TimeSlotsTool(bookingService),
        BookAppointmentTool(bookingService),
        ListTherapistsTool(serviceDirectory),
        GetTherapistDetailsTool(serviceDirectory),
        GetTherapistAvailabilityTool(serviceDirectory)
    ))

    val store = InMemoryConversationStore()
    val orchestrator = ChatOrchestrator(backend, store, registry)

    val ctx = ConversationContext(
        conversationId = "cph-centrum:local-console",
        shopId = "cph-centrum"
    )

    println("Shop Chatbot started. Type 'exit' to quit.")
    while (true) {
        print("> ")
        val input = readln().trim()
        if (input.equals("exit", ignoreCase = true)) break

        val response = orchestrator.handleUserMessage(ctx, input)
        println(response)
    }
}
