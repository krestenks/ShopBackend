package telephony

/**
 * Lebara prepaid top-up by SMS. Per Lebara: text 5010 with "Topup <8-digit code> <6-digit code>".
 * The SMS goes out the shop's OWN SIM (the one being topped up) — see [TelephonyService.sendSms],
 * where the sending identity is the SIM, so we only need the shop id.
 */
object LebaraTopup {
    const val CARRIER = "lebara"
    const val SHORTCODE = "5010"
    private val CODE1 = Regex("^\\d{8}$")   // first voucher code — 8 digits
    private val CODE2 = Regex("^\\d{6}$")   // second voucher code — 6 digits

    data class Validation(val ok: Boolean, val error: String? = null, val code1: String = "", val code2: String = "")

    /** Strips non-digits, then checks the 8 + 6 digit shape. */
    fun validate(code1: String?, code2: String?): Validation {
        val c1 = code1?.filter { it.isDigit() }.orEmpty()
        val c2 = code2?.filter { it.isDigit() }.orEmpty()
        if (!CODE1.matches(c1)) return Validation(false, "First code must be 8 digits")
        if (!CODE2.matches(c2)) return Validation(false, "Second code must be 6 digits")
        return Validation(true, code1 = c1, code2 = c2)
    }

    fun messageBody(code1: String, code2: String) = "Topup $code1 $code2"

    /** Validates the codes, then sends the top-up SMS out shop [shopId]'s SIM to 5010. */
    suspend fun send(telephony: TelephonyService, shopId: Int, code1: String?, code2: String?): SmsSendResult {
        val v = validate(code1, code2)
        if (!v.ok) return SmsSendResult(false, 400, v.error ?: "Invalid codes", errorMessage = v.error)
        return telephony.sendSms(shopId, "", SHORTCODE, messageBody(v.code1, v.code2))
    }

    /** Requests the prepaid balance: texts "balance" to 5010 from shop [shopId]'s SIM. The reply
     *  arrives asynchronously as an inbound SMS from 5010 — see [isCarrierReply]. */
    suspend fun requestBalance(telephony: TelephonyService, shopId: Int): SmsSendResult =
        telephony.sendSms(shopId, "", SHORTCODE, "balance")

    /** True when an inbound SMS came from the Lebara short code (5010) — i.e. a balance/top-up reply. */
    fun isCarrierReply(from: String?): Boolean =
        from?.filter { it.isDigit() } == SHORTCODE
}
