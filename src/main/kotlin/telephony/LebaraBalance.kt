package telephony

/**
 * Parses the prepaid balance out of a Lebara balance-reply SMS (stored raw in
 * `shop_telephony_config.balance`) and decides whether a shop is running low.
 *
 * Lebara replies vary in wording/locale ("Din saldo er 42,50 kr.", "Your balance is 42.50 DKK",
 * "Du har 8,00 kr tilbage"), so the parser is deliberately tolerant: prefer an amount anchored to a
 * currency word (kr/dkk), else fall back to the first decimal number in the text. Both comma and dot
 * decimals are accepted. Returns null when nothing money-shaped is found (unparseable → never alerts).
 */
object LebaraBalance {
    /**
     * Warn the manager to top up when the kr cash balance drops below this. Env-overridable via
     * LOW_BALANCE_THRESHOLD_KR.
     *
     * Default 99 kr: the Lebara DK offline packages these SIMs run on (GETALL = 60 GB + 10 hrs,
     * 99 kr/30 days) auto-renew by deducting from the kr CASH balance, and the shop buys 99 kr
     * top-ups. Below 99 kr the balance can't cover the next renewal → the package fails to renew and
     * outbound SMS + calls stop silently (incoming calls keep working, so it's easy to miss). So the
     * alarm = "you don't have enough for the next renewal, add a top-up." Lebara's own low-balance
     * SMS only fires under 25 kr — far too late. The MB/data figure in the balance reply is NOT a
     * health signal (data is unused, it never moves).
     */
    val THRESHOLD_KR: Double =
        System.getenv("LOW_BALANCE_THRESHOLD_KR")?.trim()?.toDoubleOrNull() ?: 99.0

    // Amount immediately followed by a currency word — the most reliable signal.
    private val ANCHORED = Regex("""(\d+(?:[.,]\d{1,2})?)\s*(?:kr|dkk)\b""", RegexOption.IGNORE_CASE)
    // Fallback: the first decimal number anywhere (avoids grabbing bare integers like dates/expiry).
    private val DECIMAL = Regex("""\d+[.,]\d{1,2}""")

    /** Balance in kroner parsed from [text], or null if none could be read. */
    fun parseKr(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val raw = (ANCHORED.find(text)?.groupValues?.get(1) ?: DECIMAL.find(text)?.value) ?: return null
        return raw.replace(',', '.').toDoubleOrNull()
    }

    /** True when [text] parses to a balance strictly below [THRESHOLD_KR]. Unparseable → false. */
    fun isLow(text: String?): Boolean = parseKr(text)?.let { it < THRESHOLD_KR } ?: false
}
