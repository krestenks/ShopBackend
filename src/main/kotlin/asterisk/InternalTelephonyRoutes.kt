package asterisk

import DataBase
import VoiceCallState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import twilio.persistInboundSms

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

        // Tenant-wide blacklist: tell the dialplan to reject before ringing the app.
        val ownerId = db.getOwnerIdForShop(shopId)
        val blacklisted = from.isNotBlank() && if (ownerId != null) {
            db.isPhoneBlacklistedByOwner(ownerId, from)
        } else {
            db.isPhoneBlacklisted(shopId, from)
        }
        if (blacklisted) {
            println("[Asterisk/call-inbound] blacklisted caller=$from shop=$shopId")
            call.respondText("reject")
            return@post
        }

        val toPhone = db.getShopTelephonyConfig(shopId).phoneNumber ?: ""
        val callId = db.createInboundCallLog(shopId, uniqueId, from, toPhone)
        db.updateCallState(callId, VoiceCallState.INCOMING_CALL, "asterisk uniqueid=$uniqueId")

        // Auto-create a "New" customer row so the app can navigate to a profile,
        // mirroring the Twilio welcome handler.
        val existingCustomerId = if (from.isNotBlank()) db.getCustomerIdByPhone(from) else null
        val customerId = existingCustomerId ?: from.takeIf { it.isNotBlank() }?.let {
            runCatching { db.insertNewCustomer(it) }.getOrNull()
        }
        val status = existingCustomerId?.let { db.getCustomerById(it)?.status?.trim() }
        val isKnown = existingCustomerId != null && !status.isNullOrBlank() && !status.equals("New", ignoreCase = true)
        db.updateCallCustomer(callId, customerId, if (isKnown) "known" else "unknown")

        // TODO(FCM): push-wake the manager app here before Asterisk Dial()s the SIP endpoint.
        println("[Asterisk/call-inbound] callId=$callId shop=$shopId from=$from uniqueid=$uniqueId known=$isKnown")
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
