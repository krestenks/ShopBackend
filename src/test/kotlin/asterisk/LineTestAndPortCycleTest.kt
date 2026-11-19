package asterisk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LineTestAndPortCycleTest {
    // ---- power-cycle guard ----

    @Test fun `AMI that did not answer refuses`() {
        assertFalse(PortCycleGuard.decide(TrunkLookup.Unknown).allowed)
    }

    @Test fun `a modem that no trunk holds may be cycled`() {
        assertTrue(PortCycleGuard.decide(TrunkLookup.None).allowed)
    }

    @Test fun `a Free trunk may be cycled and a down trunk may be cycled`() {
        assertTrue(PortCycleGuard.decide(TrunkLookup.Held("shop9", "Free")).allowed)
        assertTrue(PortCycleGuard.decide(TrunkLookup.Held("shop8", "Not connected Start scheduled")).allowed)
        assertTrue(PortCycleGuard.decide(TrunkLookup.Held("shop9", "Not initialized")).allowed)
    }

    @Test fun `a trunk with a call or an unknown state refuses`() {
        for (state in listOf("Ring", "Dialing", "Active", "Incoming", "Held", "Initializing", "Stopped", "", null, "anything new")) {
            assertFalse(PortCycleGuard.decide(TrunkLookup.Held("shop6", state)).allowed, "state=$state")
        }
    }

    // ---- line test text ----

    @Test fun `a failed SMS is reported with the balance fact`() {
        val r = LineTestReport.build(true, "queued", "Error sending message", "Free", 87.81, 45)
        assertTrue(r.headline.contains("failed"))
        assertTrue(r.facts.any { it.contains("87.81 kr") && it.contains("renewal price") })
    }

    @Test fun `no failure within the wait is not claimed as delivered`() {
        val r = LineTestReport.build(true, "queued", null, "Free", 150.0, 45)
        assertTrue(r.headline.contains("no failure was reported"))
        assertFalse(r.headline.contains("delivered"))
        assertEquals("Balance: 150.00 kr.", r.facts[1])
    }

    @Test fun `a message that was not queued says so and unknown facts are stated as not read`() {
        val r = LineTestReport.build(false, "Shop 8 has no GSM modem configured", null, null, null, 0)
        assertTrue(r.headline.startsWith("The test SMS was not queued"))
        assertEquals(listOf("Trunk state: not read.", "Balance: none read for this SIM."), r.facts)
    }
}
