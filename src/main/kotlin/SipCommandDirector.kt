import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side half of the phone's SIP self-healing.
 *
 * The backend learns a phone has gone deaf long before the phone does: Asterisk's qualify
 * verdict is authoritative and [SipReachabilityMonitor] polls it every two minutes, whereas
 * the phone can only guess from its own registration state — which demonstrably lies. On
 * 2026-08-28 the shop phone's own notification read "Shop phone online (2)" while Asterisk
 * had no reachable contact for either of that manager's shops, and two shops took no calls
 * for over an hour.
 *
 * This class turns that asymmetry into an action. It tracks how long each AOR has been
 * unreachable across sip-health polls and, once the phone's own recovery ladder has plainly
 * failed, returns a command telling it to escalate. It also records what the phone believes
 * about itself so the *disagreement* — the real signature of this failure — is visible in
 * the log rather than having to be reconstructed from a bugreport.
 *
 * Deliberately advisory: the phone rate-limits and may ignore commands. A bug here must not
 * be able to drive a fleet of phones into a restart loop.
 */
object SipCommandDirector {

    /** Commands understood by the app. Anything else must be ignored by the client. */
    const val CMD_REFRESH = "refresh"
    const val CMD_REBUILD = "rebuild"
    const val CMD_RESTART = "restart"

    /**
     * Only intervene well after the phone's own ladder should have fixed it. The app runs its
     * watchdog every 60s and reaches its Core-rebuild rung at roughly 3-5 minutes, so anything
     * below this is just fighting the client.
     */
    private const val REBUILD_AFTER_MS = 5 * 60_000L
    private const val RESTART_AFTER_MS = 12 * 60_000L

    /** Never repeat a command to the same AOR faster than this, whatever the poll rate. */
    private const val COMMAND_COOLDOWN_MS = 5 * 60_000L

    /** What a phone last told us about itself. */
    @Serializable
    data class Report(
        /** The app's own verdict: HEALTHY / SUSPECT / DEAF. */
        val state: String? = null,
        /** ms since iterate() last completed; -1 = never. */
        val iterateStalledMs: Long? = null,
        /** Monotonic iterate counter, so two reports give a rate. */
        val iterateCount: Long? = null,
        /** Seconds since the last registration state change of any kind; -1 = never. */
        val regEventAgeS: Long? = null,
        /** Seconds since a registration last reached Ok; -1 = never. */
        val regOkAgeS: Long? = null,
        /** How many lines the app believes are registered. */
        val registered: Int? = null,
        /** Which recovery rung the app last fired. */
        val rung: Int? = null,
        /** The app's consecutive-unreachable counter. */
        val unreachableStreak: Int? = null,
        val appVersion: String? = null,
        // ── Radio/signal telemetry (added 2026-09; nullable — older apps omit them) ──
        /** LTE/NR reference signal power (dBm), e.g. -95. Null if unavailable. */
        val rsrp: Int? = null,
        /** LTE/NR reference signal quality (dB), e.g. -11. Null if unavailable. */
        val rsrq: Int? = null,
        /** Coarse signal bars 0..4 from SignalStrength.level. */
        val signalLevel: Int? = null,
        /** Overall signal strength (dBm) from SignalStrength. */
        val signalDbm: Int? = null,
        /** Active transport at report time: "cellular" | "wifi" | "other" | "none". */
        val transport: String? = null,
        // ── Battery telemetry (added 2026-09; nullable — older apps omit them) ──
        /** Battery charge 0..100 (%). Null if unavailable. A phone that dies overnight goes dark. */
        val batteryPct: Int? = null,
        /** True while charging/full. Null if unavailable. */
        val batteryCharging: Boolean? = null,
    )

    private class State {
        var firstUnreachableAt: Long? = null
        var lastCommand: String? = null
        var lastCommandAt: Long = 0
        var lastReport: Report? = null
        var lastReportAt: Long = 0
    }

    private val states = ConcurrentHashMap<String, State>()

    private fun stateFor(aor: String) = states.computeIfAbsent(aor) { State() }

    /**
     * Record the current qualify verdict for [aor] and decide whether to ask the phone to
     * escalate. Called on every sip-health poll.
     *
     * @return a command string, or null to leave the phone's own ladder alone.
     */
    fun onVerdict(aor: String, reachable: Boolean, now: Long = System.currentTimeMillis()): String? {
        val st = stateFor(aor)
        synchronized(st) {
            if (reachable) {
                if (st.firstUnreachableAt != null) {
                    val downMs = now - st.firstUnreachableAt!!
                    println("[SipCommand] $aor reachable again after ${downMs / 1000}s")
                }
                st.firstUnreachableAt = null
                st.lastCommand = null
                return null
            }

            val since = st.firstUnreachableAt ?: now.also { st.firstUnreachableAt = it }
            val downMs = now - since

            val wanted = when {
                downMs >= RESTART_AFTER_MS -> CMD_RESTART
                downMs >= REBUILD_AFTER_MS -> CMD_REBUILD
                else -> null
            } ?: return null

            // Cooldown, and never re-send the identical command back to back.
            if (now - st.lastCommandAt < COMMAND_COOLDOWN_MS) return null

            st.lastCommand = wanted
            st.lastCommandAt = now
            println("[SipCommand] $aor unreachable ${downMs / 1000}s -> sending '$wanted'" +
                (st.lastReport?.let { " (phone last reported state=${it.state} registered=${it.registered})" } ?: ""))
            return wanted
        }
    }

    /**
     * Store what the phone believes about itself, and shout if it disagrees with Asterisk.
     *
     * The disagreement is the diagnosis: a phone insisting it is registered while qualify says
     * Unavailable is exactly the 2026-08-28 failure, and it is invisible from either side alone.
     */
    fun onReport(aor: String, report: Report, reachable: Boolean, now: Long = System.currentTimeMillis()) {
        val st = stateFor(aor)
        synchronized(st) {
            st.lastReport = report
            st.lastReportAt = now
        }
        val appThinksFine = (report.state == null || report.state.equals("HEALTHY", ignoreCase = true)) &&
            (report.registered ?: 0) > 0
        if (!reachable && appThinksFine) {
            val downS = st.firstUnreachableAt?.let { (now - it) / 1000 } ?: 0
            println("[SipCommand] DISAGREEMENT $aor: Asterisk says UNREACHABLE (${downS}s) but phone " +
                "reports state=${report.state} registered=${report.registered} " +
                "iterateStalled=${report.iterateStalledMs}ms regOkAge=${report.regOkAgeS}s " +
                "rung=${report.rung} streak=${report.unreachableStreak} v=${report.appVersion}")
        }
    }

    /** Last known phone-reported state for an AOR, for the web admin / debugging. */
    fun lastReport(aor: String): Report? = states[aor]?.lastReport

    /** How long this AOR has been continuously unreachable, in ms, or null if it is fine. */
    fun unreachableForMs(aor: String, now: Long = System.currentTimeMillis()): Long? =
        states[aor]?.firstUnreachableAt?.let { now - it }
}
