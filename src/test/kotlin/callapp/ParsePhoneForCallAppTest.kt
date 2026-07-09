package callapp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows

/**
 * Tests for [parsePhoneForCallApp] — the international E.164 parser used by CallApp screening.
 * It splits any "+CC…" number into (countryCode, nationalNumber) using longest-match-first
 * against the ITU-T calling-code list, and throws [InvalidPhoneNumberException] on garbage.
 */
class ParsePhoneForCallAppTest {

    // ─── Valid inputs ────────────────────────────────────────────────────────

    @Test fun `plus prefix E164`() =
        assertEquals("45" to "51941736", parsePhoneForCallApp("+4551941736"))

    @Test fun `plus prefix with spaces`() =
        assertEquals("45" to "51941736", parsePhoneForCallApp("+45 51 94 17 36"))

    @Test fun `00 prefix`() =
        assertEquals("45" to "51941736", parsePhoneForCallApp("004551941736"))

    @Test fun `bare digits with country code`() =
        assertEquals("45" to "51941736", parsePhoneForCallApp("4551941736"))

    @Test fun `hyphen-separated`() =
        assertEquals("45" to "51941736", parsePhoneForCallApp("+45-51-94-17-36"))

    @Test fun `leading and trailing whitespace`() =
        assertEquals("45" to "51941736", parsePhoneForCallApp("  +4551941736  "))

    // Longest-match-first correctly picks multi-digit codes over shorter prefixes.
    @Test fun `two-digit country code Thailand`() =
        assertEquals("66" to "812345678", parsePhoneForCallApp("+66812345678"))

    @Test fun `three-digit country code Saudi Arabia`() =
        assertEquals("966" to "512345678", parsePhoneForCallApp("+966512345678"))

    // ─── Invalid inputs ──────────────────────────────────────────────────────

    @Test fun `blank string throws`() {
        assertThrows<InvalidPhoneNumberException> { parsePhoneForCallApp("") }
    }

    @Test fun `whitespace-only throws`() {
        assertThrows<InvalidPhoneNumberException> { parsePhoneForCallApp("   ") }
    }

    @Test fun `non-digit characters throw`() {
        assertThrows<InvalidPhoneNumberException> { parsePhoneForCallApp("+45-hello") }
    }

    @Test fun `too short throws`() {
        assertThrows<InvalidPhoneNumberException> { parsePhoneForCallApp("12345") }
    }
}
