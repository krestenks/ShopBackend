import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the bound on the most expensive recovery rung: a PBX-wide chan_quectel reload. On 2026-09-17
 * one hung modem made the ladder reload the whole PBX four times in 2.5 h; the budget must allow one
 * per down-episode, give it back when the trunk recovers, and re-arm (never lock out forever).
 */
class ReloadBudgetTest {
    private val hour = 60 * 60_000L
    private fun budget() = ReloadBudget(rearmAfterMs = 6 * hour)

    @Test fun `a trunk that never reloaded may reload`() {
        assertTrue(budget().available("shop5", now = 0))
    }

    @Test fun `one reload per episode - later ladder passes are refused`() {
        val b = budget()
        b.spend("shop5", now = 0)
        for (pass in 1..4) assertFalse(b.available("shop5", now = pass * 34 * 60_000L), "pass $pass")
    }

    @Test fun `the budget is per trunk`() {
        val b = budget()
        b.spend("shop5", now = 0)
        assertTrue(b.available("shop1", now = 1))
    }

    @Test fun `recovery gives the next episode a fresh budget`() {
        val b = budget()
        b.spend("shop5", now = 0)
        b.reset("shop5")
        assertTrue(b.available("shop5", now = 1))
    }

    @Test fun `bounded - re-arms after the window, not before`() {
        val b = budget()
        b.spend("shop5", now = 0)
        assertFalse(b.available("shop5", now = 6 * hour - 1))
        assertTrue(b.available("shop5", now = 6 * hour))
        assertEquals(60_000L, b.msUntilRearm("shop5", now = 6 * hour - 60_000L))
    }
}
