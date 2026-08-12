package asterisk

import DataBase
import telephony.SmsSendResult
import telephony.TelephonyService

/**
 * TelephonyService backed by the self-hosted Asterisk/Quectel stack.
 *
 * SMS goes out over the shop's own GSM trunk (SIM), so the
 * fromNumber parameter is ignored — the sending identity IS the SIM.
 */
class AsteriskTelephonyService(
    private val amiClient: AmiClient,
    private val config: AsteriskConfig,
    private val db: DataBase,
    private val smsQueue: SmsQueue,
) : TelephonyService {

    override val providerName = "asterisk"

    override suspend fun sendSms(shopId: Int, fromNumberE164: String, toNumberE164: String, body: String, gateOnCall: Boolean): SmsSendResult {
        if (toNumberE164.isBlank()) return SmsSendResult(false, 400, "Missing To number")
        if (!amiClient.connected) {
            return SmsSendResult(false, 503, "Asterisk AMI not connected", errorMessage = "Asterisk AMI not connected")
        }
        val telephony = db.getShopTelephonyConfig(shopId)
        if (telephony.modemDataDevice.isNullOrBlank()) {
            return SmsSendResult(
                false, 400, "Shop $shopId has no GSM modem configured",
                errorMessage = "Shop $shopId has no GSM modem configured",
            )
        }
        val trunk = config.trunkName(shopId)
        // Through the queue: serialized per modem + held until the line is idle. gateOnCall=false
        // (booking-link, sent during the call) maps to immediate so it doesn't wait/deadlock.
        val result = smsQueue.enqueue(trunk, toNumberE164, body, immediate = !gateOnCall).await()
        return SmsSendResult(
            success = result.success,
            status = if (result.success) 200 else 500,
            body = result.detail,
            // chan_quectel has no globally unique message id; trunk+timestamp aids log correlation.
            providerMessageId = if (result.success) "quectel:$trunk:${System.currentTimeMillis()}" else null,
            errorMessage = if (result.success) null else result.detail,
        )
    }
}
