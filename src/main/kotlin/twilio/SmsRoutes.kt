package twilio

import DataBase
import SmsConversationSummary
import SmsMessage
import SmsUnhandledNotification
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

@Serializable
data class UnhandledCountResponse(val count: Int)

@Serializable
data class UnhandledNotificationsResponse(val notifications: List<SmsUnhandledNotification>)

@Serializable
data class MarkHandledRequest(val shopId: Int, val phone: String)

// ─── Route installer ──────────────────────────────────────────────────────────

fun Routing.smsRoutes(db: DataBase, smsService: TwilioSmsService) {

    println("[SmsRoutes] Inbound SMS webhook registered at:")
    println("  POST /api/twilio/sms/inbound   (canonical — matches Twilio console)")
    println("  POST /twilio/sms/inbound        (compatibility alias)")

    // ── Inbound SMS webhook (called by Twilio, no auth) ──────────────────────
    // Canonical URL: https://your-backend/api/twilio/sms/inbound
    // (Consistent with the existing voice routes at /api/twilio/voice/*)
    post("/api/twilio/sms/inbound") {
        handleInboundSms(call, db)
    }
    // Backward-compat alias so the old URL still works too
    post("/twilio/sms/inbound") {
        handleInboundSms(call, db)
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

        // Total unhandled-inbound count across all the caller's shops (for top-bar badge)
        get("/api/mobile/sms/unhandled-count") {
            val principal = call.principal<JWTPrincipal>()
            val refId     = principal?.payload?.getClaim("userId")?.asInt()
            val refType   = principal?.payload?.getClaim("role")?.asString()

            if (principal == null || refId == null || refType == null) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid token")
                return@get
            }

            val shopIds = getShopIdsForPrincipal(db, refType, refId)
            call.respond(UnhandledCountResponse(db.getUnhandledSmsCount(shopIds)))
        }

        // All unhandled conversations across the caller's shops (notification list)
        get("/api/mobile/sms/unhandled-notifications") {
            val principal = call.principal<JWTPrincipal>()
            val refId     = principal?.payload?.getClaim("userId")?.asInt()
            val refType   = principal?.payload?.getClaim("role")?.asString()

            if (principal == null || refId == null || refType == null) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid token")
                return@get
            }

            val shopIds = getShopIdsForPrincipal(db, refType, refId)
            call.respond(UnhandledNotificationsResponse(db.getUnhandledSmsNotifications(shopIds)))
        }

        // Mark all inbound messages in a thread as handled (read)
        post("/api/mobile/sms/thread/mark-handled") {
            val principal = call.principal<JWTPrincipal>()
            val refId     = principal?.payload?.getClaim("userId")?.asInt()
            val refType   = principal?.payload?.getClaim("role")?.asString()

            if (principal == null || refId == null || refType == null) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid token")
                return@post
            }

            val req = call.receive<MarkHandledRequest>()

            if (!isAuthorisedForShop(db, refType, refId, req.shopId)) {
                call.respond(HttpStatusCode.Forbidden, "Not authorised for this shop")
                return@post
            }

            db.markSmsConversationHandled(req.shopId, req.phone)
            call.respond(mapOf("ok" to true))
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

// ─── Inbound SMS handler (shared by both URL aliases) ────────────────────────

private suspend fun handleInboundSms(call: ApplicationCall, db: DataBase) {
    val params = call.receiveParameters()

    val from       = params["From"]       ?: ""
    val to         = params["To"]         ?: ""
    val body       = params["Body"]       ?: ""
    val messageSid = params["MessageSid"]

    println("[SmsRoutes] Inbound SMS: from=$from to=$to body=${body.take(80)}")

    // Map the Twilio `To` number back to a shop
    val shopId = db.findShopIdByTwilioNumber(to)
    if (shopId == null) {
        println("[SmsRoutes] No shop found for Twilio number '$to' — ignoring")
        call.respondText("<Response/>", ContentType.Application.Xml)
        return
    }

    println("[SmsRoutes] Matched shopId=$shopId — saving message")

    // Resolve optional customer id
    val customerId = db.getCustomerIdByPhone(from)

    // Persist
    db.insertSmsMessage(
        shopId            = shopId,
        customerId        = customerId,
        counterpartyPhone = from,
        fromPhone         = from,
        toPhone           = to,
        body              = body,
        direction         = "inbound",
        status            = "received",
        twilioMessageSid  = messageSid,
    )

    // Return empty TwiML — we don't auto-reply; manager replies manually.
    call.respondText("<Response/>", ContentType.Application.Xml)
}

// ─── Helper: check JWT caller has access to the given shopId ─────────────────

private fun isAuthorisedForShop(db: DataBase, refType: String, refId: Int, shopId: Int): Boolean {
    return when (refType) {
        "manager" -> db.isManagerOfShop(managerId = refId, shopId = shopId)
        "shop"    -> refId == shopId
        else      -> false
    }
}

// ─── Helper: all shop IDs visible to a JWT caller ────────────────────────────

private fun getShopIdsForPrincipal(db: DataBase, refType: String, refId: Int): List<Int> {
    return when (refType) {
        "manager" -> db.getShopsForManager(refId).map { it.id }
        "shop"    -> listOf(refId)
        else      -> emptyList()
    }
}
