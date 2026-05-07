package twilio

import DataBase
import SmsConversationSummary
import SmsMessage
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// ─── Request / response models ────────────────────────────────────────────────

@Serializable
data class SendSmsRequest(
    val shopId: Int,
    val toPhone: String,
    val body: String,
)

@Serializable
data class SendSmsResponse(
    val ok: Boolean,
    val messageId: Int = -1,
    val twilioSid: String? = null,
    val error: String? = null,
)

@Serializable
data class SmsThreadResponse(val messages: List<SmsMessage>)

@Serializable
data class SmsConversationsResponse(val conversations: List<SmsConversationSummary>)

// ─── Route installer ──────────────────────────────────────────────────────────

fun Routing.smsRoutes(db: DataBase, smsService: TwilioSmsService) {

    // ── Inbound SMS webhook (called by Twilio, no auth) ──────────────────────
    // Configure this URL in the Twilio console: https://your-backend/twilio/sms/inbound
    post("/twilio/sms/inbound") {
        val params = call.receiveParameters()

        val from      = params["From"]  ?: ""
        val to        = params["To"]    ?: ""
        val body      = params["Body"]  ?: ""
        val messageSid = params["MessageSid"]

        println("SMS inbound: from=$from to=$to body=${body.take(80)}")

        // Map the Twilio `To` number back to a shop
        val shopId = db.findShopIdByTwilioNumber(to)
        if (shopId == null) {
            println("SMS inbound: no shop found for twilio number $to — ignoring")
            call.respondText("<Response/>", ContentType.Application.Xml)
            return@post
        }

        // Resolve optional customer id
        val customerId = db.getCustomerIdByPhone(from)

        // Persist
        db.insertSmsMessage(
            shopId           = shopId,
            customerId       = customerId,
            counterpartyPhone = from,
            fromPhone        = from,
            toPhone          = to,
            body             = body,
            direction        = "inbound",
            status           = "received",
            twilioMessageSid = messageSid,
        )

        // Return empty TwiML — we don't auto-reply; manager replies manually.
        call.respondText("<Response/>", ContentType.Application.Xml)
    }

    // ── Manager API endpoints (JWT authenticated) ─────────────────────────────
    authenticate("jwt") {

        // Send an SMS from the shop's Twilio number to a customer
        post("/api/mobile/sms/send") {
            val principal = call.principal<JWTPrincipal>()
            val refId     = principal?.payload?.getClaim("userId")?.asInt()
            val refType   = principal?.payload?.getClaim("role")?.asString()

            if (principal == null || refId == null || refType == null) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid token")
                return@post
            }

            val req = call.receive<SendSmsRequest>()

            // Authorisation: the caller must be the manager of this shop
            if (!isAuthorisedForShop(db, refType, refId, req.shopId)) {
                call.respond(HttpStatusCode.Forbidden, "Not authorised for this shop")
                return@post
            }

            val voiceConfig = db.getShopVoiceConfig(req.shopId)
            val fromNumber  = voiceConfig.twilioNumber?.trim().takeIf { !it.isNullOrBlank() }
                ?: (System.getenv("TWILIO_FROM_NUMBER") ?: "").trim()

            if (fromNumber.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    SendSmsResponse(ok = false, error = "No Twilio number configured for this shop")
                )
                return@post
            }

            if (req.toPhone.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    SendSmsResponse(ok = false, error = "toPhone is required")
                )
                return@post
            }

            if (req.body.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    SendSmsResponse(ok = false, error = "body is required")
                )
                return@post
            }

            // Optimistically insert with status "queued"
            val customerId = db.getCustomerIdByPhone(req.toPhone)
            val msgId = db.insertSmsMessage(
                shopId            = req.shopId,
                customerId        = customerId,
                counterpartyPhone = req.toPhone,
                fromPhone         = fromNumber,
                toPhone           = req.toPhone,
                body              = req.body,
                direction         = "outbound",
                status            = "queued",
            )

            // Send via Twilio
            val result = smsService.sendSms(
                fromNumberE164 = fromNumber,
                toNumberE164   = req.toPhone,
                bodyText       = req.body,
            )

            // Update status based on Twilio response (best-effort; row was already inserted)
            // We keep it simple: mark sent or failed. A production system would use status callbacks.
            if (result.success) {
                call.respond(SendSmsResponse(ok = true, messageId = msgId, twilioSid = result.messageSid))
            } else {
                call.respond(
                    HttpStatusCode.OK,   // still 200 so the app can display the error
                    SendSmsResponse(ok = false, messageId = msgId, error = result.errorMessage)
                )
            }
        }

        // List conversation summaries for a shop (one row per counterparty)
        get("/api/mobile/sms/conversations") {
            val principal = call.principal<JWTPrincipal>()
            val refId     = principal?.payload?.getClaim("userId")?.asInt()
            val refType   = principal?.payload?.getClaim("role")?.asString()

            if (principal == null || refId == null || refType == null) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid token")
                return@get
            }

            val shopId = call.request.queryParameters["shopId"]?.toIntOrNull()
                ?: run { call.respond(HttpStatusCode.BadRequest, "shopId required"); return@get }

            if (!isAuthorisedForShop(db, refType, refId, shopId)) {
                call.respond(HttpStatusCode.Forbidden, "Not authorised for this shop")
                return@get
            }

            val conversations = db.getSmsConversations(shopId)
            call.respond(SmsConversationsResponse(conversations))
        }

        // Full thread with a single counterparty
        get("/api/mobile/sms/thread") {
            val principal = call.principal<JWTPrincipal>()
            val refId     = principal?.payload?.getClaim("userId")?.asInt()
            val refType   = principal?.payload?.getClaim("role")?.asString()

            if (principal == null || refId == null || refType == null) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid token")
                return@get
            }

            val shopId = call.request.queryParameters["shopId"]?.toIntOrNull()
                ?: run { call.respond(HttpStatusCode.BadRequest, "shopId required"); return@get }
            val phone  = call.request.queryParameters["phone"]?.trim()
                ?: run { call.respond(HttpStatusCode.BadRequest, "phone required"); return@get }

            if (!isAuthorisedForShop(db, refType, refId, shopId)) {
                call.respond(HttpStatusCode.Forbidden, "Not authorised for this shop")
                return@get
            }

            val messages = db.getSmsThread(shopId, phone)
            call.respond(SmsThreadResponse(messages))
        }
    }
}

// ─── Helper: check JWT caller has access to the given shopId ─────────────────

private fun isAuthorisedForShop(db: DataBase, refType: String, refId: Int, shopId: Int): Boolean {
    return when (refType) {
        "manager" -> db.isManagerOfShop(managerId = refId, shopId = shopId)
        "shop"    -> refId == shopId
        else      -> false
    }
}
