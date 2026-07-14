package telephony

import DataBase
import twilio.TwilioSmsService

/**
 * Provider-agnostic result of an outbound SMS send.
 * Mirrors TwilioSmsService.SmsResult so existing call sites can switch providers
 * without changing their control flow.
 */
data class SmsSendResult(
    val success: Boolean,
    val status: Int,
    val body: String,
    /** Provider message id: Twilio Message SID, or a chan_quectel reference on Asterisk. */
    val providerMessageId: String? = null,
    val errorMessage: String? = null,
)

/**
 * Abstraction over the telephony provider (Twilio today, self-hosted Asterisk tomorrow).
 *
 * Only the operations that must work identically on both providers live here.
 * Twilio-specific voice flows (TwiML webhooks, whisper bridging) stay in the twilio
 * package; the Asterisk equivalents are driven by the generated dialplan + AMI events.
 */
interface TelephonyService {
    /** "twilio" or "asterisk" — used for logging/diagnostics. */
    val providerName: String

    /**
     * Sends an SMS to [toNumberE164].
     *
     * [shopId] selects the sending identity on Asterisk (the shop's GSM trunk);
     * [fromNumberE164] selects it on Twilio. Callers should pass both.
     */
    suspend fun sendSms(shopId: Int, fromNumberE164: String, toNumberE164: String, body: String): SmsSendResult
}

/**
 * The number shown as sender for a shop's outbound SMS/calls: the shop's SIM number
 * on Asterisk, the shop's Twilio number (or global fallback) on Twilio. May be blank
 * on Asterisk — the GSM network stamps the real sender regardless; the value is only
 * used for local persistence/display.
 */
fun resolveShopSenderNumber(db: DataBase, telephony: TelephonyService, shopId: Int): String {
    return if (telephony.providerName == "asterisk") {
        db.getShopTelephonyConfig(shopId).phoneNumber?.trim().orEmpty()
    } else {
        db.getShopVoiceConfig(shopId).twilioNumber?.trim()?.takeIf { it.isNotBlank() }
            ?: (System.getenv("TWILIO_FROM_NUMBER") ?: "").trim()
    }
}

/** Twilio-backed implementation delegating to the existing REST client. */
class TwilioTelephonyService(private val smsService: TwilioSmsService) : TelephonyService {
    override val providerName = "twilio"

    override suspend fun sendSms(shopId: Int, fromNumberE164: String, toNumberE164: String, body: String): SmsSendResult {
        val r = smsService.sendSms(fromNumberE164, toNumberE164, body)
        return SmsSendResult(
            success = r.success,
            status = r.status,
            body = r.body,
            providerMessageId = r.messageSid,
            errorMessage = r.errorMessage,
        )
    }
}
