import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drives the real down-recovery ladder with a fake clock and recorded actions, one call per
 * 60 s watchdog check. Pins the bounds that matter: per-trunk restarts only, a resync told exactly
 * which trunks are down, never anything PBX-wide, and an admin alert that a live call elsewhere
 * cannot hold back.
 */
class DownRecoveryLadderTest {
    private val min = 60_000L

    private class Recorder : DownLadderActions {
        val log = mutableListOf<Pair<Long, String>>()
        var now = 0L
        var resyncResult: Boolean? = false
        val resyncArgs = mutableListOf<Set<Int>>()
        override fun restartTrunk(trunk: String, attempt: Int, of: Int, state: String) { log += now to "restart $trunk $attempt/$of" }
        override suspend fun resync(trunk: String, downShopIds: Set<Int>): Boolean? { log += now to "resync $trunk"; resyncArgs += downShopIds; return resyncResult }
        override fun alertDown(trunk: String, state: String) { log += now to "alert $trunk" }
    }

    private fun run(minutes: Int, rec: Recorder, ladder: DownRecoveryLadder, step: (Int) -> Unit) {
        for (m in 0 until minutes) { rec.now = m * min; step(m) }
    }

    @Test fun `a hung trunk gets two restarts and one resync per pass, 34 min apart, for hours`() {
        val rec = Recorder()
        val ladder = DownRecoveryLadder(rec, clock = { rec.now })
        run(180, rec, ladder) { ladder.onDown("shop5", "Not initialized", anyQuectelCall = false, channelsUsable = true) }

        val restarts = rec.log.filter { it.second.startsWith("restart") }
        val resyncs = rec.log.filter { it.second.startsWith("resync") }
        // restarts at 3 and 6, resync 3 min after the last restart (9); the next pass starts 30 min later
        assertEquals(listOf(9L, 45L, 81L, 117L, 153L), resyncs.map { it.first / min })
        assertEquals(2 * resyncs.size, restarts.size, "exactly two restarts per pass")
        restarts.zipWithNext().forEach { (a, b) -> assertTrue(b.first - a.first >= 3 * min, "restarts at least 3 min apart") }
        // first alert as soon as pass 1 is done, then one re-nag after 2 h
        assertEquals(listOf(9L, 129L), rec.log.filter { it.second.startsWith("alert") }.map { it.first / min })
    }

    @Test fun `a live call elsewhere skips the resync but holds back neither the alert nor the next pass`() {
        val rec = Recorder()
        val ladder = DownRecoveryLadder(rec, clock = { rec.now })
        run(60, rec, ladder) { ladder.onDown("shop5", "Not initialized", anyQuectelCall = true, channelsUsable = true) }

        assertTrue(rec.log.none { it.second.startsWith("resync") }, "no path rewrite while a call is live")
        assertEquals(9L, rec.log.first { it.second.startsWith("alert") }.first / min, "alert not delayed by the call")
        assertEquals(4, rec.log.count { it.second.startsWith("restart") }, "second pass still ran")
    }

    @Test fun `an unusable channel listing destroys nothing but still alerts`() {
        val rec = Recorder()
        val ladder = DownRecoveryLadder(rec, clock = { rec.now })
        run(30, rec, ladder) { ladder.onDown("shop5", "Not initialized", anyQuectelCall = true, channelsUsable = false) }

        assertEquals(listOf("alert shop5"), rec.log.map { it.second })
        assertEquals(3L, rec.log.single().first / min, "alerted once the debounce passed")
    }

    @Test fun `resync is told exactly which trunks are confirmed down`() {
        val rec = Recorder()
        val ladder = DownRecoveryLadder(rec, clock = { rec.now })
        run(10, rec, ladder) {
            ladder.onDown("shop5", "Not initialized", anyQuectelCall = false, channelsUsable = true)
            if (it >= 7) ladder.onDown("shop6", "Not initialized", anyQuectelCall = false, channelsUsable = true)
        }
        // at shop5's resync (minute 9) shop6 had only 2 down checks: not confirmed yet, so not movable
        assertEquals(setOf(5), rec.resyncArgs.first())
    }

    @Test fun `a second confirmed-down trunk is included in the resync`() {
        val rec = Recorder()
        val ladder = DownRecoveryLadder(rec, clock = { rec.now })
        run(10, rec, ladder) {
            ladder.onDown("shop5", "Not initialized", anyQuectelCall = false, channelsUsable = true)
            ladder.onDown("shop6", "Not initialized", anyQuectelCall = false, channelsUsable = true)
        }
        assertEquals(setOf(5, 6), rec.resyncArgs.first())
    }

    @Test fun `a resync that moved something waits one check before alerting`() {
        val rec = Recorder().apply { resyncResult = true }
        val ladder = DownRecoveryLadder(rec, clock = { rec.now })
        run(12, rec, ladder) { ladder.onDown("shop5", "Not initialized", anyQuectelCall = false, channelsUsable = true) }
        assertEquals(9L, rec.log.first { it.second.startsWith("resync") }.first / min)
        assertEquals(listOf(10L), rec.log.filter { it.second.startsWith("alert") }.map { it.first / min })
    }

    @Test fun `a resync that is unavailable or throws still ends the pass with an alert`() {
        for (result in listOf<Boolean?>(null)) {
            val rec = Recorder().apply { resyncResult = result }
            val ladder = DownRecoveryLadder(rec, clock = { rec.now })
            run(12, rec, ladder) { ladder.onDown("shop5", "Not initialized", anyQuectelCall = false, channelsUsable = true) }
            assertEquals(listOf(9L), rec.log.filter { it.second.startsWith("alert") }.map { it.first / min })
        }
        val throwing = object : DownLadderActions {
            val alerts = mutableListOf<Long>(); var now = 0L
            override fun restartTrunk(trunk: String, attempt: Int, of: Int, state: String) {}
            override suspend fun resync(trunk: String, downShopIds: Set<Int>): Boolean? = error("scan blew up")
            override fun alertDown(trunk: String, state: String) { alerts += now }
        }
        val ladder = DownRecoveryLadder(throwing, clock = { throwing.now })
        for (m in 0 until 12) { throwing.now = m * min; ladder.onDown("shop5", "Not initialized", false, true) }
        assertEquals(listOf(9L), throwing.alerts.map { it / min })
    }

    @Test fun `recovery ends the episode and the next outage starts a fresh ladder`() {
        val rec = Recorder()
        val ladder = DownRecoveryLadder(rec, clock = { rec.now })
        run(60, rec, ladder) { m ->
            if (m in 20..29) ladder.onHealthy("shop5")
            else ladder.onDown("shop5", "Not initialized", anyQuectelCall = false, channelsUsable = true)
        }
        val restarts = rec.log.filter { it.second.startsWith("restart") }.map { it.first / min to it.second }
        assertEquals(listOf(3L to "restart shop5 1/2", 6L to "restart shop5 2/2", 33L to "restart shop5 1/2", 36L to "restart shop5 2/2"), restarts)
        assertEquals(listOf(9L, 39L), rec.log.filter { it.second.startsWith("alert") }.map { it.first / min }, "each episode alerts once")
    }

    @Test fun `auto-recovery off means alert only`() {
        val rec = Recorder()
        val ladder = DownRecoveryLadder(rec, clock = { rec.now }, autoRecover = false)
        run(30, rec, ladder) { ladder.onDown("shop5", "Not initialized", anyQuectelCall = false, channelsUsable = true) }
        assertEquals(listOf("alert shop5"), rec.log.map { it.second })
    }
}
