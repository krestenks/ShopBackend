/**
 * The decision of *when* to say a manager's phone went offline or came back — separated from
 * Asterisk, SMS and the clock so it can be tested without any of them.
 *
 * Edge-triggered by design. The previous alerting was level-triggered and keyed on the shop: one
 * unreachable handset made all four shops independently "unconnected", each texting every manager
 * on it plus the admin, every 30 minutes. The measured cost of that was **204 SMS for one 18-hour
 * outage** — about 70% of all SMS the backend sent in that window — charged to the shops' prepaid
 * balance. See docs/offline-alert-rework-plan.md.
 *
 * Here, one phone going offline produces exactly one alert, and coming back produces exactly one
 * more, however long it lasts in between.
 */

/** What the caller should do about a manager, after one observation. */
sealed interface ReachabilityAction {
    /** Nothing to say. */
    data object None : ReachabilityAction

    /** The phone has been unreachable long enough to be real. [downSince] is the first down poll. */
    data class AlertOffline(val downSince: Long) : ReachabilityAction

    /** It is back. [outageMs] is measured from the first down poll, not from when we alerted. */
    data class AlertOnline(val downSince: Long, val outageMs: Long) : ReachabilityAction

    /**
     * An offline alert was due but withheld. Carries the reason and how far the suppression is
     * from its bound, because a guard that suppresses silently is indistinguishable from one that
     * never fires (docs/recovery-code-rules.md §4).
     */
    data class Suppressed(val reason: String, val detail: String) : ReachabilityAction
}

class ManagerReachabilityTracker(
    /**
     * Consecutive unreachable polls before an offline alert. At the caller's 2-minute poll this is
     * ~10 minutes.
     *
     * Calibrated, not guessed: every gap that ever healed by itself was 33-106 s
     * (`reachable again after Ns` across the whole journal), so 10 minutes sits 5.7x above the
     * longest. That is why none of those gaps ever alerted, and why the value is kept.
     */
    private val downDebounceChecks: Int = 5,
    /**
     * Consecutive reachable polls before the recovery alert. Without this, a phone flapping around
     * the threshold would alternate offline/online pairs. ~4 minutes at a 2-minute poll.
     */
    private val upDebounceChecks: Int = 2,
    /**
     * After a recovery, withhold a new offline alert for this long. Caps the worst case for a phone
     * that cycles more slowly than the debounces can absorb.
     *
     * A time limit rather than a strike count, so the suppression is inherently bounded and can
     * never become permanent.
     */
    private val flapGuardMs: Long = 30 * 60_000L,
) {

    private class State {
        var downStreak = 0
        var upStreak = 0
        /** First poll of the current unreachable run; 0 when reachable. */
        var firstDownAt = 0L
        /** When the offline alert was sent; 0 means we have not alerted about this outage. */
        var alertedAt = 0L
        /** First reachable poll of the current recovery run, i.e. when it actually came back. */
        var firstUpAt = 0L
        /** When we last reported a recovery, for the flap guard. */
        var lastRecoveryAt = 0L
        /** So a suppressed episode is reported once rather than every poll. */
        var suppressionReported = false
    }

    private val states = HashMap<Int, State>()

    /** True while we are in the alerted-offline state for [managerId]. */
    fun isAlertedOffline(managerId: Int): Boolean = (states[managerId]?.alertedAt ?: 0L) != 0L

    /**
     * Feed one observation.
     *
     * @param reachable true, false, or **null for "could not determine"** — e.g. AMI was down.
     *   Null never advances a streak and never produces an alert: absence of evidence is not
     *   evidence of absence (docs/recovery-code-rules.md §1-2), and here the consequence of
     *   getting it wrong is a false alarm plus an SMS charge.
     */
    fun observe(managerId: Int, reachable: Boolean?, now: Long): ReachabilityAction {
        if (reachable == null) return ReachabilityAction.None
        val s = states.getOrPut(managerId) { State() }

        if (reachable) {
            s.downStreak = 0
            s.suppressionReported = false
            if (s.alertedAt == 0L) {
                // Was never reported offline, so there is nothing to recover from.
                s.firstDownAt = 0L
                s.upStreak = 0
                return ReachabilityAction.None
            }
            if (s.upStreak == 0) s.firstUpAt = now
            s.upStreak++
            if (s.upStreak < upDebounceChecks) return ReachabilityAction.None

            val downSince = s.firstDownAt
            // Measured to the FIRST reachable poll, not to this confirming one: the phone was
            // already back then, and the up-debounce is our caution, not part of the outage. Keeps
            // the reported duration independent of the debounce setting.
            val outage = (s.firstUpAt - downSince).coerceAtLeast(0L)
            s.alertedAt = 0L
            s.firstDownAt = 0L
            s.firstUpAt = 0L
            s.upStreak = 0
            s.lastRecoveryAt = now
            return ReachabilityAction.AlertOnline(downSince, outage)
        }

        // Unreachable.
        s.upStreak = 0
        s.firstUpAt = 0L
        if (s.alertedAt != 0L) return ReachabilityAction.None   // already reported; stay quiet

        if (s.firstDownAt == 0L) s.firstDownAt = now
        s.downStreak++
        if (s.downStreak < downDebounceChecks) return ReachabilityAction.None

        val sinceRecovery = if (s.lastRecoveryAt == 0L) Long.MAX_VALUE else now - s.lastRecoveryAt
        if (sinceRecovery < flapGuardMs) {
            if (s.suppressionReported) return ReachabilityAction.None
            s.suppressionReported = true
            return ReachabilityAction.Suppressed(
                reason = "flap-guard",
                detail = "sinceLastRecovery=${sinceRecovery / 60_000}m bound=${flapGuardMs / 60_000}m",
            )
        }

        s.alertedAt = now
        s.suppressionReported = false
        return ReachabilityAction.AlertOffline(s.firstDownAt)
    }

    /**
     * Drop all state for a manager — used when they go off duty, so a later outage alerts promptly
     * instead of being held back by a stale flap guard.
     */
    fun forget(managerId: Int) {
        states.remove(managerId)
    }

    /** Managers currently being tracked, so the caller can forget the ones no longer on duty. */
    fun tracked(): Set<Int> = states.keys.toSet()
}
