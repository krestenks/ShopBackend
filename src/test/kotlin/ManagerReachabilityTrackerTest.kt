import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the offline/online edge detection.
 *
 * The thing being prevented is measured, not hypothetical: the level-triggered per-shop alerting
 * this replaces sent **204 SMS for one 18-hour outage**, about 70% of all SMS the backend sent in
 * that window, charged to the shops' prepaid balance — which had pushed shop1 below the credit
 * floor where outbound SMS silently stops working. See docs/offline-alert-rework-plan.md.
 *
 * So the properties that matter are: one alert per edge no matter how long the outage, nothing at
 * all for the short gaps that heal themselves, and never an alert from an inconclusive reading.
 */
class ManagerReachabilityTrackerTest {

    private val mgr = 2
    private val poll = 120_000L          // the monitor polls every 2 minutes
    private val t0 = 1_760_000_000_000L  // a fixed instant; the tracker only uses differences

    /** Feeds [n] polls of [reachable], returning every action produced. */
    private fun ManagerReachabilityTracker.feed(
        n: Int,
        reachable: Boolean?,
        from: Long,
    ): List<ReachabilityAction> = (0 until n).map { observe(mgr, reachable, from + it * poll) }

    private fun List<ReachabilityAction>.alerts() =
        filter { it !is ReachabilityAction.None }

    // ─── Short gaps must stay silent ─────────────────────────────────────────

    @Test
    fun `every gap that ever healed itself produces no alert`() {
        // Durations taken from the journal: `reachable again after Ns` across its whole history.
        for (gapSeconds in listOf(33, 40, 50, 60, 106)) {
            val t = ManagerReachabilityTracker()
            var now = t0
            // Down for the duration of the gap, at the real poll rate.
            val downPolls = (gapSeconds * 1000L / poll).toInt() + 1
            val down = t.feed(downPolls, false, now)
            now += downPolls * poll
            val up = t.feed(3, true, now)

            assertTrue((down + up).alerts().isEmpty(),
                "a ${gapSeconds}s gap must not alert; the debounce exists for exactly these")
        }
    }

    @Test
    fun `a gap just under the debounce stays silent`() {
        val t = ManagerReachabilityTracker(downDebounceChecks = 5)
        assertTrue(t.feed(4, false, t0).alerts().isEmpty())
        assertFalse(t.isAlertedOffline(mgr))
    }

    // ─── One alert per edge ──────────────────────────────────────────────────

    @Test
    fun `the debounce boundary is the fifth consecutive down poll`() {
        val t = ManagerReachabilityTracker(downDebounceChecks = 5)
        val actions = t.feed(5, false, t0)

        assertEquals(4, actions.take(4).count { it is ReachabilityAction.None })
        assertInstanceOf(ReachabilityAction.AlertOffline::class.java, actions[4])
        assertTrue(t.isAlertedOffline(mgr))
    }

    @Test
    fun `an 18-hour outage produces exactly one offline and one online alert`() {
        val t = ManagerReachabilityTracker()
        var now = t0
        val downPolls = (18 * 60 * 60 * 1000L / poll).toInt()   // 540 polls
        val down = t.feed(downPolls, false, now)
        now += downPolls * poll
        val up = t.feed(5, true, now)

        val offline = down.alerts()
        assertEquals(1, offline.size, "this is the case that used to send 204 SMS")
        assertInstanceOf(ReachabilityAction.AlertOffline::class.java, offline.single())

        val online = up.alerts()
        assertEquals(1, online.size)
        val rec = online.single() as ReachabilityAction.AlertOnline
        // Duration is measured from the FIRST down poll, not from when we got round to alerting.
        assertEquals(18 * 60L, rec.outageMs / 60_000, "reported outage should be ~18h")
    }

    @Test
    fun `staying down sends nothing further, however many polls elapse`() {
        val t = ManagerReachabilityTracker()
        t.feed(5, false, t0)                              // alerts
        val rest = t.feed(200, false, t0 + 5 * poll)      // ~6.5 more hours

        assertTrue(rest.alerts().isEmpty(), "no re-notify: that was the 30-minute repeat")
    }

    @Test
    fun `recovery needs the up debounce, so one good poll is not enough`() {
        val t = ManagerReachabilityTracker(upDebounceChecks = 2)
        t.feed(5, false, t0)
        var now = t0 + 5 * poll

        val first = t.observe(mgr, true, now)
        assertInstanceOf(ReachabilityAction.None::class.java, first)
        val second = t.observe(mgr, true, now + poll)
        assertInstanceOf(ReachabilityAction.AlertOnline::class.java, second)
    }

    @Test
    fun `a recovery that was never reported offline says nothing`() {
        val t = ManagerReachabilityTracker()
        // Two down polls (below the debounce), then back up.
        t.feed(2, false, t0)
        val up = t.feed(5, true, t0 + 2 * poll)

        assertTrue(up.alerts().isEmpty(), "nothing was reported, so there is nothing to recover from")
    }

    // ─── Inconclusive readings ───────────────────────────────────────────────

    @Test
    fun `a null reading never alerts and never advances the streak`() {
        val t = ManagerReachabilityTracker(downDebounceChecks = 5)

        // 50 polls where AMI could not tell us anything.
        assertTrue(t.feed(50, null, t0).alerts().isEmpty(),
            "absence of evidence is not evidence of absence - and here it would cost an SMS")
        assertFalse(t.isAlertedOffline(mgr))

        // And it must not have advanced the down streak: 4 real down polls still stay silent.
        assertTrue(t.feed(4, false, t0 + 50 * poll).alerts().isEmpty())
    }

    @Test
    fun `an inconclusive spell in the middle of an outage does not re-alert`() {
        val t = ManagerReachabilityTracker()
        t.feed(5, false, t0)                                   // one offline alert
        val middle = t.feed(10, null, t0 + 5 * poll)           // AMI drops out
        val after = t.feed(10, false, t0 + 15 * poll)          // still down

        assertTrue((middle + after).alerts().isEmpty())
        assertTrue(t.isAlertedOffline(mgr))
    }

    // ─── The flap guard, and its bound ───────────────────────────────────────

    @Test
    fun `a flapping phone is capped, and the suppression is reported once`() {
        val t = ManagerReachabilityTracker(
            downDebounceChecks = 2, upDebounceChecks = 1, flapGuardMs = 30 * 60_000L,
        )
        var now = t0
        // First cycle: alerts offline, then online.
        t.feed(2, false, now); now += 2 * poll
        t.observe(mgr, true, now); now += poll

        // Second outage, 10 minutes later — inside the guard.
        val second = t.feed(6, false, now)

        val suppressed = second.filterIsInstance<ReachabilityAction.Suppressed>()
        assertEquals(1, suppressed.size, "report the suppression once, not every poll")
        assertEquals("flap-guard", suppressed.single().reason)
        assertTrue(suppressed.single().detail.contains("bound="),
            "a suppressing guard must say how far it is from its bound")
        assertTrue(second.none { it is ReachabilityAction.AlertOffline })
    }

    @Test
    fun `the flap guard is bounded - it cannot suppress forever`() {
        val t = ManagerReachabilityTracker(
            downDebounceChecks = 2, upDebounceChecks = 1, flapGuardMs = 30 * 60_000L,
        )
        var now = t0
        t.feed(2, false, now); now += 2 * poll
        t.observe(mgr, true, now)

        // An outage starting well past the guard must alert normally.
        now += 31 * 60_000L
        val later = t.feed(2, false, now)

        assertTrue(later.any { it is ReachabilityAction.AlertOffline },
            "past the bound, stop believing the guard - a real outage must get through")
    }

    // ─── Independence and lifecycle ──────────────────────────────────────────

    @Test
    fun `managers are tracked independently`() {
        val t = ManagerReachabilityTracker(downDebounceChecks = 2)
        t.observe(1, false, t0)
        t.observe(1, false, t0 + poll)          // mgr1 alerts
        val other = t.observe(9, false, t0 + poll)

        assertTrue(t.isAlertedOffline(1))
        assertFalse(t.isAlertedOffline(9), "one manager's outage must not alert for another")
        assertInstanceOf(ReachabilityAction.None::class.java, other)
    }

    @Test
    fun `going off duty clears the state, so a later outage alerts promptly`() {
        val t = ManagerReachabilityTracker(downDebounceChecks = 2, flapGuardMs = 30 * 60_000L)
        var now = t0
        t.feed(2, false, now); now += 2 * poll
        t.observe(mgr, true, now)               // recovery sets the flap guard

        t.forget(mgr)
        assertFalse(t.tracked().contains(mgr))

        // Immediately down again — with the state gone, the guard must not hold it back.
        val after = t.feed(2, false, now + poll)
        assertTrue(after.any { it is ReachabilityAction.AlertOffline })
    }
}
