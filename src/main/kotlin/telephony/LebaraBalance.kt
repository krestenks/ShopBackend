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
     * The cash needed to renew the package: **100 kr exactly** (Kresten, 2026-10-10).
     *
     * The mechanism, corrected — an earlier version of this comment had it wrong:
     *
     * - The cash balance funds **both** the 30-day package renewal **and** pay-as-you-go SMS.
     * - A shop at 99.50 kr is **not** dead. Nothing fails immediately.
     * - The failure happens at **renewal time**: if cash cannot cover the renewal, the package
     *   does not renew and **outgoing calls die**.
     * - **SMS keeps working either way**, billed per message against the cash balance — so it
     *   carries on draining the very balance the renewal depends on.
     *
     * Two consequences worth holding on to. The symptom is *outgoing calls*, not SMS, so a shop
     * can look healthy (texts going out fine, incoming calls ringing) while being unable to dial.
     * And because SMS still bills, any machine-sent SMS — including our own alerts — eats the
     * renewal money.
     *
     * This is a fact about the carrier, not a tuneable. [THRESHOLD_KR] is the tuneable.
     */
    const val HARD_FLOOR_KR: Double = 100.0

    /**
     * Warn the manager to top up when the kr cash balance drops below this. Env-overridable via
     * LOW_BALANCE_THRESHOLD_KR.
     *
     * Must stay **above** [HARD_FLOOR_KR]: an alarm at the floor fires only once the renewal has
     * already failed. The previous default was 99 — at or below the floor — which is why shop1 sat
     * at 106.92 kr with nothing raised.
     *
     * **120 kr**, chosen by Kresten on 2026-10-10 as reasonable for the message volume these SIMs
     * carry. That is 20 kr of margin above the floor; at the busiest shop's ~45 outbound SMS/day
     * it buys on the order of a day or two to top up, which is the trade-off he accepted.
     *
     * Note what this threshold does *not* model: the real question is whether the balance will
     * still be ≥ [HARD_FLOOR_KR] on renewal day, and SMS keeps draining it until then. A level
     * check approximates that; see docs for the runway-based alternative, which was offered and
     * not built.
     *
     * Lebara's own low-balance SMS only fires under 25 kr — far too late to be useful. The MB/data
     * figure in the balance reply is NOT a health signal; data is unused and never moves.
     */
    val THRESHOLD_KR: Double =
        System.getenv("LOW_BALANCE_THRESHOLD_KR")?.trim()?.toDoubleOrNull() ?: 120.0

    /**
     * True when [kr] is at or below the renewal price — i.e. the next renewal would fail and
     * outgoing calls would stop. Not "already dead": SMS still works at this level.
     */
    fun isBelowHardFloor(kr: Double?): Boolean = kr != null && kr <= HARD_FLOOR_KR

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
