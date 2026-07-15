package telephony

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// ─── Diagnostic logging (temporary; traces inbound + poll timing in the Upsun log) ──
private val SMS_LOG_TZ  = java.time.ZoneId.of("Europe/Copenhagen")
private val SMS_LOG_FMT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
/** Human-readable Copenhagen timestamp for correlating with when a message was actually sent. */
private fun smsTs(epochMs: Long = System.currentTimeMillis()): String =
    java.time.Instant.ofEpochMilli(epochMs).atZone(SMS_LOG_TZ).format(SMS_LOG_FMT)
/**
 * Writes a diagnostic line and flushes stdout immediately. Without the flush, buffered output
 * lags behind on the Upsun log, making messages show up in the app before their log line appears.
 */
private fun smsLog(msg: String) { println(msg); System.out.flush() }
/**
 * Poll-cadence logging is opt-in via env SMS_DEBUG_POLL=true, because the app's read endpoints
 * fire every few seconds per device. Turn it on for a test to see the app's actual poll gaps,
 * then unset it. Inbound-receipt logging (below) is always on and low-volume.
 */
private val SMS_DEBUG_POLL = System.getenv("SMS_DEBUG_POLL")?.equals("true", ignoreCase = true) == true

// ─── Request / response models ────────────────────────────────────────────────

@Serializable
data class SendSmsRequest(
    val shopId: Int,
    val toPhone: String,
    val body: String,
    /** When true the backend appends the shop's configured price-list footer (smsPriceListFooter) to the body. */
    val appendPriceListFooter: Boolean = false,
)

@Serializable
data class SendSmsResponse(
    val ok: Boolean,
    val messageId: Int = -1,
    /** Provider message reference (chan_quectel trunk + timestamp). */
    val providerSid: String? = null,
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

fun Routing.smsRoutes(
    db: DataBase,
    smsService: TelephonyService,
    callAppScreening: callapp.CallAppScreeningService? = null,
) {

    // ── Manager API endpoints (JWT authenticated) ─────────────────────────────
    authenticate("jwt") {

        // Send an SMS from the shop's number (GSM SIM) to a customer
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
            // The SIM is the actual sender; this value is only stored for display.
            val fromNumber  = resolveShopSenderNumber(db, req.shopId)

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

            // Optionally append the shop-configured price-list footer
            val effectiveBody = if (req.appendPriceListFooter) {
                val footer = voiceConfig.smsPriceListFooter?.trim()?.takeIf { it.isNotBlank() }
                if (footer != null) "${req.body}\n\n$footer" else req.body
            } else {
                req.body
            }

            // Optimistically insert with status "queued". A guarded insert collapses accidental
            // double-taps (identical shop+phone+body within a few seconds) into one row; when a
            // recent duplicate is detected we skip the send entirely and echo the existing id.
            val customerId = db.getCustomerIdByPhone(req.toPhone)
            val insert = db.insertOutboundSmsIfNotDuplicate(
                shopId            = req.shopId,
                customerId        = customerId,
                counterpartyPhone = req.toPhone,
                fromPhone         = fromNumber,
                toPhone           = req.toPhone,
                body              = effectiveBody,
                status            = "queued",
            )
            val msgId = insert.id

            if (!insert.isNew) {
                // Duplicate send suppressed — the original request already sent (or is sending) this text.
                smsLog("[SMS] Suppressed duplicate outbound to ${req.toPhone} for shop ${req.shopId} (msgId=$msgId)")
                call.respond(SendSmsResponse(ok = true, messageId = msgId))
                return@post
            }

            // Send via the shop's GSM trunk (Asterisk/chan_quectel)
            val result = smsService.sendSms(
                shopId         = req.shopId,
                fromNumberE164 = fromNumber,
                toNumberE164   = req.toPhone,
                body           = effectiveBody,
            )

            // Persist the outcome so the row reflects reality (provider id for delivery/cost
            // tracking, or the error) instead of being stuck forever at "queued".
            if (result.success) {
                db.updateSmsStatus(msgId, status = "sent", providerMessageSid = result.providerMessageId, errorMessage = null)
                call.respond(SendSmsResponse(ok = true, messageId = msgId, providerSid = result.providerMessageId))
            } else {
                db.updateSmsStatus(msgId, status = "failed", providerMessageSid = null, errorMessage = result.errorMessage)
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

        // ALL conversations across the caller's shops (unread count may be 0)
        get("/api/mobile/sms/all-conversations") {
            val principal = call.principal<JWTPrincipal>()
            val refId     = principal?.payload?.getClaim("userId")?.asInt()
            val refType   = principal?.payload?.getClaim("role")?.asString()

            if (principal == null || refId == null || refType == null) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid token")
                return@get
            }

            val shopIds = getShopIdsForPrincipal(db, refType, refId)
            val allConvos = db.getAllSmsConversationsAcrossShops(shopIds)
            if (SMS_DEBUG_POLL) {
                val unread = allConvos.sumOf { it.unreadCount }
                smsLog("[SMS-POLL] ${smsTs()} all-conversations caller=$refType:$refId shops=$shopIds convos=${allConvos.size} unread=$unread")
            }
            call.respond(UnhandledNotificationsResponse(allConvos))
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
            if (SMS_DEBUG_POLL) {
                smsLog("[SMS-POLL] ${smsTs()} thread caller=$refType:$refId shop=$shopId phone=$phone msgs=${messages.size}")
            }
            call.respond(SmsThreadResponse(messages))
        }
    }
}

/** Outcome of persisting an inbound SMS. */
enum class InboundSmsPersistResult { STORED, SUPPRESSED_DUPLICATE, BLACKLISTED }

/**
 * Inbound-SMS persistence: blacklist check, customer auto-create, CallApp
 * screening trigger, and deduplicated insert. Called by the Asterisk dialplan's
 * internal endpoint.
 */
fun persistInboundSms(
    db: DataBase,
    shopId: Int,
    fromPhone: String,
    toPhone: String,
    body: String,
    providerMessageSid: String?,
    callAppScreening: callapp.CallAppScreeningService? = null,
    elapsedSinceNs: Long = System.nanoTime(),
): InboundSmsPersistResult {
    // Blacklist check (tenant-wide) — silently drop messages from blocked senders
    val smsOwnerId = db.getOwnerIdForShop(shopId)
    val isSmsBlacklisted = if (smsOwnerId != null) {
        db.isPhoneBlacklistedByOwner(smsOwnerId, fromPhone)
    } else {
        db.isPhoneBlacklisted(shopId, fromPhone)
    }
    if (fromPhone.isNotBlank() && isSmsBlacklisted) {
        smsLog("[SMS-IN] DROP blacklisted From=$fromPhone shop=$shopId Sid=$providerMessageSid")
        return InboundSmsPersistResult.BLACKLISTED
    }

    // Auto-create a customer record if none exists yet — mirrors the behaviour of inbound
    // voice calls so that every new SMS sender immediately gets a profile (status "New").
    val customerId: Int? = if (fromPhone.isNotBlank()) {
        val id = db.ensureCustomerByPhone(fromPhone)
        // Retroactively link any prior SMS messages that arrived before the record existed
        db.linkSmsMessagesToCustomer(shopId, fromPhone, id)
        id
    } else {
        null
    }

    // Trigger an immediate background CallApp lookup for customers that have never been
    // screened. Fires and forgets — does not delay the webhook response at all.
    if (customerId != null && fromPhone.isNotBlank() && callAppScreening != null &&
        db.getCustomerCallAppScreening(customerId) == null) {
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            callAppScreening.screenCustomerNow(customerId, fromPhone)
        }
    }

    // Persist with duplicate suppression. Webhook retries (same provider sid) and
    // upstream/carrier double-delivery (same body from the same number within a few
    // seconds under a different sid) both collapse to a single stored row.
    val insertedId = db.insertInboundSmsDeduped(
        shopId            = shopId,
        customerId        = customerId,
        counterpartyPhone = fromPhone,
        fromPhone         = fromPhone,
        toPhone           = toPhone,
        body              = body,
        status            = "received",
        providerMessageSid  = providerMessageSid,
    )
    val elapsedMs = (System.nanoTime() - elapsedSinceNs) / 1_000_000
    return if (insertedId == null) {
        smsLog("[SMS-IN] SUPPRESSED-DUP From=$fromPhone shop=$shopId Sid=$providerMessageSid handledIn=${elapsedMs}ms")
        InboundSmsPersistResult.SUPPRESSED_DUPLICATE
    } else {
        smsLog("[SMS-IN] STORED id=$insertedId From=$fromPhone shop=$shopId Sid=$providerMessageSid handledIn=${elapsedMs}ms")
        InboundSmsPersistResult.STORED
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

// ─── Helper: all shop IDs visible to a JWT caller ────────────────────────────

private fun getShopIdsForPrincipal(db: DataBase, refType: String, refId: Int): List<Int> {
    return when (refType) {
        "manager" -> db.getShopsForManager(refId).map { it.id }
        "shop"    -> listOf(refId)
        else      -> emptyList()
    }
}
