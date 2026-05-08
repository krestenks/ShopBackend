package callapp

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ParseDanishPhoneTest {

    // ─── Valid inputs ────────────────────────────────────────────────────────

    @Test fun `plus prefix E164`() =
        assertEquals(Pair("45", "51941736"), parseDanishPhone("+4551941736"))

    @Test fun `plus prefix with spaces`() =
        assertEquals(Pair("45", "51941736"), parseDanishPhone("+45 51 94 17 36"))

    @Test fun `00 prefix`() =
        assertEquals(Pair("45", "51941736"), parseDanishPhone("004551941736"))

    @Test fun `bare 10-digit with country code`() =
        assertEquals(Pair("45", "51941736"), parseDanishPhone("4551941736"))

    @Test fun `hyphen-separated`() =
        assertEquals(Pair("45", "51941736"), parseDanishPhone("+45-51-94-17-36"))

    @Test fun `leading and trailing whitespace`() =
        assertEquals(Pair("45", "51941736"), parseDanishPhone("  +4551941736  "))

    // ─── Invalid inputs ──────────────────────────────────────────────────────

    @Test fun `8-digit number without country code throws`() {
        assertFailsWith<InvalidPhoneNumberException> { parseDanishPhone("51941736") }
    }

    @Test fun `wrong country code throws`() {
        assertFailsWith<InvalidPhoneNumberException> { parseDanishPhone("+4651941736") }
    }

    @Test fun `too short throws`() {
        assertFailsWith<InvalidPhoneNumberException> { parseDanishPhone("+45519417") }
    }

    @Test fun `too long throws`() {
        assertFailsWith<InvalidPhoneNumberException> { parseDanishPhone("+455194173600") }
    }

    @Test fun `blank string throws`() {
        assertFailsWith<InvalidPhoneNumberException> { parseDanishPhone("") }
    }
}
