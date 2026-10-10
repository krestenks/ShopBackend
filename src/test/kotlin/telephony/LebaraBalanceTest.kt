package telephony

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the Lebara balance parser and the low-balance threshold.
 *
 * Why this matters more than it looks. The cash balance funds both the 30-day package renewal and
 * pay-as-you-go SMS. A shop below the renewal price is not dead right away — the failure lands at
 * **renewal time**, when the package cannot renew and **outgoing calls die**. SMS keeps working
 * throughout, billed from the same balance, so it carries on draining the money the renewal needs.
 * The shop therefore looks fine (texts going out, incoming calls ringing) while being unable to
 * dial out, and the alarm is the only thing that surfaces it.
 *
 * The threshold has to sit *above* the floor. It previously did not — the default was 99 kr
 * against a real floor of 100 — so a shop at 99.50 was already failing and had never been warned.
 * Found in practice: shop1 at 106.92 kr with no alert raised.
 */
class LebaraBalanceTest {

    // ─── The real replies, copied from shop_telephony_config on the edge ─────

    @Test
    fun `parses the English reply format`() {
        val text = "Your balance is 106.92 kr. You have 61440.04 MB left in your data package. " +
            "Data usage may be delayed. Regards, Lebara"
        assertEquals(106.92, LebaraBalance.parseKr(text))
    }

    @Test
    fun `parses the bilingual Danish reply and takes the balance, not the data figure`() {
        val text = "Din saldo er 196.27 kr. Du har 61440.04MB tilbage af din pakke. " +
            "Your balance is 196.27 kr. You have 61440.04MB left in you package"
        // 61440.04 appears in the same message; anchoring on the currency word is what avoids it.
        assertEquals(196.27, LebaraBalance.parseKr(text))
    }

    @Test
    fun `the MB figure is never mistaken for money`() {
        // A deliberately hostile shape: data first, balance second.
        val text = "Du har 61440.04MB tilbage. Din saldo er 42.50 kr."
        assertEquals(42.50, LebaraBalance.parseKr(text))
    }

    // ─── Format tolerance ────────────────────────────────────────────────────

    @Test
    fun `accepts comma decimals, DKK, and varied spacing`() {
        assertEquals(42.50, LebaraBalance.parseKr("Din saldo er 42,50 kr."))
        assertEquals(42.50, LebaraBalance.parseKr("Your balance is 42.50 DKK"))
        assertEquals(8.0, LebaraBalance.parseKr("Du har 8,00 kr tilbage"))
        assertEquals(130.0, LebaraBalance.parseKr("balance: 130kr"))
    }

    @Test
    fun `an unparseable message never raises an alarm`() {
        // Fail safe: a reply we cannot read must not be reported as low, or every carrier wording
        // change becomes a false alarm on every shop.
        for (text in listOf(null, "", "   ", "Welcome to Lebara!", "Your package expires 30 Nov")) {
            assertNull(LebaraBalance.parseKr(text), "should not parse: '$text'")
            assertFalse(LebaraBalance.isLow(text), "unparseable must not be 'low': '$text'")
        }
    }

    // ─── The floor and the threshold ─────────────────────────────────────────

    @Test
    fun `the hard floor is 100 kr`() {
        // Established 2026-10-10. A fact about the carrier, not a tuneable.
        assertEquals(100.0, LebaraBalance.HARD_FLOOR_KR)
    }

    @Test
    fun `the warning threshold sits above the hard floor`() {
        assertTrue(
            LebaraBalance.THRESHOLD_KR > LebaraBalance.HARD_FLOOR_KR,
            "an alarm at or below the floor fires after the shop has already gone silent - " +
                "this is the bug that let shop1 reach 106.92 kr unreported",
        )
    }

    @Test
    fun `the margin is a deliberate operator choice, not an accident`() {
        // 120 kr, set by Kresten for the volume these SIMs carry: 20 kr above the renewal price.
        // The hard invariant is the assertion above (threshold > floor); this one exists so a
        // casual edit toward the floor has to be deliberate.
        val margin = LebaraBalance.THRESHOLD_KR - LebaraBalance.HARD_FLOOR_KR
        assertTrue(margin >= 20.0, "margin of $margin kr is tighter than was agreed")
    }

    @Test
    fun `shop1's real balance would now be flagged`() {
        val text = "Your balance is 106.92 kr. You have 61440.04 MB left in your data package."
        assertTrue(LebaraBalance.isLow(text),
            "106.92 kr is 6.92 kr from going silent; the old 99 kr threshold said nothing")
        assertFalse(LebaraBalance.isBelowHardFloor(LebaraBalance.parseKr(text)),
            "but it has not actually failed yet - the distinction is the point")
    }

    @Test
    fun `the healthy shops are not flagged`() {
        for (kr in listOf("196.27", "197.08", "178.44")) {
            assertFalse(LebaraBalance.isLow("Din saldo er $kr kr."), "$kr should be healthy")
        }
    }

    @Test
    fun `isLow is strictly below the threshold`() {
        val t = LebaraBalance.THRESHOLD_KR
        assertTrue(LebaraBalance.isLow("balance ${t - 0.01} kr"))
        assertFalse(LebaraBalance.isLow("balance $t kr"))
        assertFalse(LebaraBalance.isLow("balance ${t + 0.01} kr"))
    }

    @Test
    fun `isBelowHardFloor includes the floor itself`() {
        assertTrue(LebaraBalance.isBelowHardFloor(100.0), "at the floor, outbound is already gone")
        assertTrue(LebaraBalance.isBelowHardFloor(99.99))
        assertFalse(LebaraBalance.isBelowHardFloor(100.01))
        assertFalse(LebaraBalance.isBelowHardFloor(null), "unknown is not 'failing'")
    }
}
