package asterisk

import DataBase
import VoiceCallOutcome
import VoiceCallState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import telephony.persistInboundSms

/**
 * Endpoints the generated Asterisk dialplan CURLs into (localhost only in practice).
 * Authenticated by the ASTERISK_INTERNAL_SECRET shared secret, passed as a form field.
 *
 *   POST /api/internal/telephony/sms/inbound   secret, shopId, from, body
 *   POST /api/internal/telephony/call/inbound  secret, shopId, from, uniqueid
 *   POST /api/internal/telephony/provision     secret [, shopId] — re-provision Asterisk
 *       (curl-able stopgap until the admin web UI gets a provision button in Phase 4)
 */
fun Routing.internalTelephonyRoutes(
    db: DataBase,
    config: AsteriskConfig,
    provisioner: AsteriskProvisioner,
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
        // shows the attempt) and a routing decision: respond "reject" and the
        // dialplan hangs up with cause 21 before ringing the app.
        val toPhone = db.getShopTelephonyConfig(shopId).phoneNumber ?: ""
        val callId = db.createInboundCallLog(shopId, uniqueId, from, toPhone)
        db.updateCallState(callId, VoiceCallState.INCOMING_CALL, "asterisk uniqueid=$uniqueId")

        // Auto-create a "New" customer row so the app can navigate to a profile.
        val existingCustomerId = if (from.isNotBlank()) db.getCustomerIdByPhone(from) else null
        val customerId = existingCustomerId ?: from.takeIf { it.isNotBlank() }?.let {
            runCatching { db.insertNewCustomer(it) }.getOrNull()
        }
        val status = existingCustomerId?.let { db.getCustomerById(it)?.status?.trim() }
        val isKnown = existingCustomerId != null && !status.isNullOrBlank() && !status.equals("New", ignoreCase = true)
        db.updateCallCustomer(callId, customerId, if (isKnown) "known" else "unknown")

        // 1. Tenant-wide blacklist.
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

        // 2. Effective open/closed (manager override wins over the schedule).
        val voice = db.getShopVoiceConfig(shopId)
        val isOpen = when (voice.phoneOverride?.trim()?.lowercase()) {
            "open" -> true
            "closed" -> false
            else -> db.isShopOpenByScheduleNow(shopId)
        }
        if (!isOpen) {
            db.updateCallState(callId, VoiceCallState.CLOSED_MESSAGE, "shop closed")
            db.terminateCall(callId, VoiceCallOutcome.CLOSED_HOURS)
            println("[Asterisk/call-inbound] REJECT shop closed shop=$shopId caller=$from")
            call.respondText("reject")
            return@post
        }

        // 3. Temporary operator closure (manager toggle in the app).
        if (voice.temporaryOperatorClosed) {
            db.updateCallState(callId, VoiceCallState.TEMPORARY_CLOSED_MESSAGE, "temporary operator closure")
            db.terminateCall(callId, VoiceCallOutcome.TEMP_OPERATOR_CLOSED)
            println("[Asterisk/call-inbound] REJECT temp-closed shop=$shopId caller=$from")
            call.respondText("reject")
            return@post
        }

        // TODO(FCM): push-wake the manager app here before Asterisk Dial()s the SIP endpoint.
        println("[Asterisk/call-inbound] RING callId=$callId shop=$shopId from=$from uniqueid=$uniqueId known=$isKnown")
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
