package telephony

import DataBase

/** Result of an outbound SMS send. */
data class SmsSendResult(
    val success: Boolean,
    val status: Int,
    val body: String,
    /** Provider message reference (chan_quectel trunk + timestamp). */
    val providerMessageId: String? = null,
    val errorMessage: String? = null,
)

/**
 * Telephony operations the rest of the backend depends on. Implemented by
 * asterisk.AsteriskTelephonyService (the only provider); kept as an interface
 * so routes/services stay mockable in tests.
 */
interface TelephonyService {
    /** Provider tag used for logging/diagnostics. */
    val providerName: String

    /**
     * Sends an SMS to [toNumberE164] out of shop [shopId]'s GSM trunk.
     * [fromNumberE164] is display/persistence metadata — the SIM is the real sender.
     */
    suspend fun sendSms(shopId: Int, fromNumberE164: String, toNumberE164: String, body: String): SmsSendResult
}

/**
 * The number shown as sender for a shop's outbound SMS/calls: the shop's SIM
 * number. May be blank — the GSM network stamps the real sender regardless;
 * the value is only used for local persistence/display.
 */
fun resolveShopSenderNumber(db: DataBase, shopId: Int): String =
    db.getShopTelephonyConfig(shopId).phoneNumber?.trim().orEmpty()
