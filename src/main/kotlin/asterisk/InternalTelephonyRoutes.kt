package asterisk

import DataBase
import VoiceCallOutcome
import VoiceCallState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.withTimeoutOrNull
import telephony.TelephonyService
import telephony.persistInboundSms
import telephony.resolveShopSenderNumber

/**
 * Endpoints the generated Asterisk dialplan CURLs into (localhost only in practice).
 * Authenticated by the ASTERISK_INTERNAL_SECRET shared secret, passed as a form field.
 *
 *   POST /api/internal/telephony/sms/inbound   secret, shopId, from, body
 *   POST /api/internal/telephony/call/inbound  secret, shopId, from, uniqueid
 *       → routing verdict: reject | ring | menu_open | menu_closed | menu_temp
 *   POST /api/internal/telephony/booking-link  secret, shopId, from, uniqueid → ok | fail
 *   POST /api/internal/telephony/call/event    secret, uniqueid, event (operator | menu_timeout)
 *   POST /api/internal/telephony/provision     secret [, shopId] — re-provision Asterisk
 *       (curl-able stopgap until the admin web UI gets a provision button)
 */
fun Routing.internalTelephonyRoutes(
    db: DataBase,
    config: AsteriskConfig,
    provisioner: AsteriskProvisioner,
    telephonyService: TelephonyService,
    callAppScreening: callapp.CallAppScreeningService? = null,
) {

    suspend fun ApplicationCall.authorizedParams(): Parameters? {
        val params = runCatching { receiveParameters() }.getOrDefault(Parameters.Empty)
        val secret = params["secret"] ?: request.queryParameters["secret"]
        if (config.internalSecret.isBlank() || secret != config.internalSecret) {
            respond(HttpStatusCode.Forbidden, "invalid secret")
            return null
        }
        return params
    }

    post("/api/internal/telephony/sms/inbound") {
        val params = call.authorizedParams() ?: return@post
        val shopId = params["shopId"]?.toIntOrNull()
            ?: run { call.respond(HttpStatusCode.BadRequest, "shopId required"); return@post }
        val from = params["from"]?.trim().orEmpty()
        val body = params["body"] ?: ""

        val toPhone = db.getShopTelephonyConfig(shopId).phoneNumber ?: ""
        val result = persistInboundSms(
            db = db,
            shopId = shopId,
            fromPhone = from,
            toPhone = toPhone,
            body = body,
            providerMessageSid = null,
            callAppScreening = callAppScreening,
        )
        call.respondText(result.name.lowercase())
    }

    post("/api/internal/telephony/call/inbound") {
        val params = call.authorizedParams() ?: return@post
        val shopId = params["shopId"]?.toIntOrNull()
            ?: run { call.respond(HttpStatusCode.BadRequest, "shopId required"); return@post }
        val from = params["from"]?.trim().orEmpty()
        val uniqueId = params["uniqueid"]?.trim()?.takeIf { it.isNotBlank() }
            ?: "ast-${System.currentTimeMillis()}"

        // Every inbound call gets a log row (also the rejected ones, so the app
        // shows the attempt) and a routing verdict for the dialplan.
        val toPhone = db.getShopTelephonyConfig(shopId).phoneNumber ?: ""
        val callId = db.createInboundCallLog(shopId, uniqueId, from, toPhone)
        db.updateCallState(callId, VoiceCallState.INCOMING_CALL, "asterisk uniqueid=$uniqueId")

        // Identify the customer; auto-create a "New" row so the app can open a profile.
        // "Known" (whitelisted) = existing row whose status is set and not "New".
        val existingCustomerId = if (from.isNotBlank()) db.getCustomerIdByPhone(from) else null
        val customerId = existingCustomerId ?: from.takeIf { it.isNotBlank() }?.let {
            runCatching { db.insertNewCustomer(it) }.getOrNull()
        }
        val status = existingCustomerId?.let { db.getCustomerById(it)?.status?.trim() }
        val isKnown = existingCustomerId != null && !status.isNullOrBlank() && !status.equals("New", ignoreCase = true)
        db.updateCallCustomer(callId, customerId, if (isKnown) "known" else "unknown")

        // 1. Tenant-wide blacklist → reject for everyone.
        val ownerId = db.getOwnerIdForShop(shopId)
        val blacklisted = from.isNotBlank() && if (ownerId != null) {
            db.isPhoneBlacklistedByOwner(ownerId, from)
        } else {
            db.isPhoneBlacklisted(shopId, from)
        }
        if (blacklisted) {
            db.updateCallState(callId, VoiceCallState.REJECTED_BLACKLISTED, "caller=$from")
            db.terminateCall(callId, VoiceCallOutcome.BLACKLIST_REJECTED)
            println("[Asterisk/call-inbound] REJECT blacklisted caller=$from shop=$shopId")
            call.respondText("reject")
            return@post
        }

        // CallApp name lookup — done SYNCHRONOUSLY (bounded) so the name is on the
        // record for the FIRST call, not just subsequent ones. Only for callers we've
        // never screened; the dialplan waits ~2 s before ringing, so this fits. The
        // client has its own short timeout; the extra bound guards against a hang.
        if (customerId != null && from.isNotBlank() && callAppScreening != null &&
            db.getCustomerCallAppScreening(customerId) == null) {
            withTimeoutOrNull(2500) { callAppScreening.screenCustomerNow(customerId, from) }
        }

        // 2. Effective open/closed (manager override wins over the schedule).
        val voice = db.getShopVoiceConfig(shopId)
        val isOpen = when (voice.phoneOverride?.trim()?.lowercase()) {
            "open" -> true
            "closed" -> false
            else -> db.isShopOpenByScheduleNow(shopId)
        }
        val tempClosed = voice.temporaryOperatorClosed

        // 3. Known customers always get the DTMF menu (SMS booking works even
        //    when closed); the menu variant decides whether option 2 exists.
        if (isKnown) {
            db.updateCallState(callId, VoiceCallState.KNOWN_CUSTOMER_MENU)
            val verdict = when {
                !isOpen -> "menu_closed"
                tempClosed -> "menu_temp"
                else -> "menu_open"
            }
            println("[Asterisk/call-inbound] $verdict callId=$callId shop=$shopId from=$from")
            call.respondText(verdict)
            return@post
        }

        // 4. Unknown callers: silent reject when unavailable, plain ringing when open.
        if (!isOpen) {
            db.updateCallState(callId, VoiceCallState.CLOSED_MESSAGE, "shop closed")
            db.terminateCall(callId, VoiceCallOutcome.CLOSED_HOURS)
            println("[Asterisk/call-inbound] REJECT shop closed shop=$shopId caller=$from")
            call.respondText("reject")
            return@post
        }
        if (tempClosed) {
            db.updateCallState(callId, VoiceCallState.TEMPORARY_CLOSED_MESSAGE, "temporary operator closure")
            db.terminateCall(callId, VoiceCallOutcome.TEMP_OPERATOR_CLOSED)
            println("[Asterisk/call-inbound] REJECT temp-closed shop=$shopId caller=$from")
            call.respondText("reject")
            return@post
        }

        db.updateCallState(callId, VoiceCallState.UNKNOWN_CUSTOMER_ROUTE)
        // TODO(FCM): push-wake the manager app here before Asterisk Dial()s the SIP endpoint.
        println("[Asterisk/call-inbound] RING callId=$callId shop=$shopId from=$from uniqueid=$uniqueId")
        call.respondText("ring")
    }

    // Digit 1 in the menu: create a booking token and text the link via the shop's SIM.
    post("/api/internal/telephony/booking-link") {
        val params = call.authorizedParams() ?: return@post
        val shopId = params["shopId"]?.toIntOrNull()
            ?: run { call.respond(HttpStatusCode.BadRequest, "shopId required"); return@post }
        val from = params["from"]?.trim().orEmpty()
        val uniqueId = params["uniqueid"]?.trim().orEmpty()
        val record = uniqueId.takeIf { it.isNotBlank() }?.let { db.getCallByProviderCallId(it) }

        if (from.isBlank()) {
            record?.let { db.terminateCall(it.id, VoiceCallOutcome.SYSTEM_ERROR) }
            call.respondText("fail")
            return@post
        }

        record?.let { db.updateCallState(it.id, VoiceCallState.KNOWN_CUSTOMER_SMS_BOOKING) }

        val customerId = db.ensureCustomerByPhone(from)
        val token = db.generateBookingToken(customerId, shopId, from)
        val base = (System.getenv("PUBLIC_BASE_URL") ?: System.getenv("PUBLIC_BOOKING_URL") ?: config.backendBaseUrl).trimEnd('/')
        val body = "Booking link: $base/api/book?token=$token"

        val fromNumber = resolveShopSenderNumber(db, shopId)
        val result = telephonyService.sendSms(shopId, fromNumber, from, body)

        // Persist the outbound SMS so it shows in the message thread.
        runCatching {
            db.insertSmsMessage(
                shopId = shopId,
                customerId = customerId,
                counterpartyPhone = from,
                fromPhone = fromNumber,
                toPhone = from,
                body = body,
                direction = "outbound",
                status = if (result.success) "sent" else "failed",
            )
        }

        if (result.success) {
            record?.let { db.terminateCall(it.id, VoiceCallOutcome.SMS_SENT) }
            println("[Asterisk/booking-link] SENT shop=$shopId to=$from")
            call.respondText("ok")
        } else {
            record?.let { db.terminateCall(it.id, VoiceCallOutcome.SYSTEM_ERROR) }
            println("[Asterisk/booking-link] FAILED shop=$shopId to=$from: ${result.errorMessage}")
            call.respondText("fail")
        }
    }

    // Menu progress events from the dialplan (call log bookkeeping).
    post("/api/internal/telephony/call/event") {
        val params = call.authorizedParams() ?: return@post
        val uniqueId = params["uniqueid"]?.trim().orEmpty()
        val event = params["event"]?.trim().orEmpty()
        val record = uniqueId.takeIf { it.isNotBlank() }?.let { db.getCallByProviderCallId(it) }
        if (record == null) {
            call.respondText("unknown-call")
            return@post
        }
        when (event) {
            "operator" -> db.updateCallState(record.id, VoiceCallState.KNOWN_CUSTOMER_OPERATOR_ROUTE)
            "menu_timeout" -> if (record.isActive) db.terminateCall(record.id, VoiceCallOutcome.INVALID_MENU_MAX_RETRIES)
            else -> println("[Asterisk/call-event] ignoring unknown event '$event' for $uniqueId")
        }
        call.respondText("ok")
    }

    post("/api/internal/telephony/provision") {
        val params = call.authorizedParams() ?: return@post
        val shopId = params["shopId"]?.toIntOrNull()
        try {
            if (shopId != null) {
                val cfg = provisioner.provisionShop(shopId)
                call.respondText("provisioned shop $shopId (endpoint=${config.endpointId(shopId)}, sip password set=${!cfg.sipPassword.isNullOrBlank()})")
            } else {
                provisioner.provisionAllConfigured()
                call.respondText("provisioned all configured shops")
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, "provisioning failed: ${e.message}")
        }
    }
}
