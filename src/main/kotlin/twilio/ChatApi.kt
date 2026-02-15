package twilio

import DataBase
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * API endpoint for testing chatbot without Twilio
 */
fun Route.chatApiRoutes(db: DataBase, chatbotService: TwilioChatbotService) {

    post("/api/chat/send") {
        val params = call.receiveParameters()
        val phone = params["phone"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing phone")
        val message = params["message"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing message")
        val shopId = params["shop_id"]?.toIntOrNull() ?: 1

        println("[ChatTest] From $phone (shop $shopId): $message")

        val response = chatbotService.processMessage(phone, message, shopId)

        call.respond(ChatResponse(response))
    }
}

@Serializable
data class ChatResponse(val response: String)
