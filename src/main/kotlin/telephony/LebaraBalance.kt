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
    /** Warn the manager to top up below this (kr). Env-overridable via LOW_BALANCE_THRESHOLD_KR. */
    val THRESHOLD_KR: Double =
        System.getenv("LOW_BALANCE_THRESHOLD_KR")?.trim()?.toDoubleOrNull() ?: 25.0

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
