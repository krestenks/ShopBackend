package asterisk

import telephony.LebaraBalance

/** What the test of one line found. [facts] are statements the backend can prove, one per line. */
data class LineTestResult(val headline: String, val facts: List<String>)

/**
 * Builds the text for the "Test line" button. chan_quectel reports a failed SMS some seconds after it
 * accepted the message, so "no failure reported" is the best result the backend can read; the phone
 * that receives the message gives the final answer.
 */
object LineTestReport {
    fun build(
        queued: Boolean,
        queueDetail: String,
        failureReport: String?,
        trunkState: String?,
        balanceKr: Double?,
        waitedSeconds: Int,
        balanceAgeHours: Long? = null,
    ): LineTestResult {
        val facts = mutableListOf<String>()
        val state = trunkState?.trim().orEmpty()
        when {
            state.isEmpty() -> facts += "Trunk state: not read."
            state.equals("free", ignoreCase = true) -> facts += "Trunk state: Free."
            else -> facts += "Trunk state: $state."
        }
        val age = balanceAgeHours?.let { " (read $it h ago)" }.orEmpty()
        facts += when {
            balanceKr == null -> "Balance: none read for this SIM."
            LebaraBalance.isBelowHardFloor(balanceKr) ->
                "Balance: ${"%.2f".format(balanceKr)} kr$age, at or below the ${LebaraBalance.HARD_FLOOR_KR.toInt()} kr renewal price, so the next renewal fails and outgoing calls stop."
            balanceKr < LebaraBalance.THRESHOLD_KR ->
                "Balance: ${"%.2f".format(balanceKr)} kr$age, below the ${LebaraBalance.THRESHOLD_KR.toInt()} kr warning level."
            else -> "Balance: ${"%.2f".format(balanceKr)} kr$age."
        }
        val headline = when {
            !queued -> "The test SMS was not queued: $queueDetail"
            failureReport != null -> "The modem reported that the test SMS failed: ${failureReport.take(160)}"
            else -> "The test SMS was queued and no failure was reported within $waitedSeconds s. Check that it arrived on the phone."
        }
        return LineTestResult(headline, facts)
    }

    fun toMessage(result: LineTestResult): String = result.headline + " " + result.facts.joinToString(" ")
}
