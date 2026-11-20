package asterisk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * An SMS body reaches chan_quectel through the `MessageSend` AMI action, carried base64-encoded.
 *
 * Two things have to hold. The body must arrive byte for byte: AMI headers are line-based, so a
 * raw newline would end one, and the CLI command this route replaced could not carry a newline
 * either, because Asterisk's CLI parser removes a backslash and passes the next character
 * through literally, turning an escaped newline into the letter "n".
 *
 * And it must arrive whole. Asterisk refuses a header line longer than about 1024 characters,
 * which base64 reaches at around 750 bytes of body — shorter than a shop's price list. The
 * encoded body is therefore split between `Base64Body` and the `bodycont1` variable, and the
 * driver appends the second to the first. These tests reassemble it the way the driver does.
 */
class SmsBodyTransportTest {

    /** The body as the driver rebuilds it: decode the head, decode the continuation, join. */
    private fun decodedBody(action: QuectelMessageSendAction): String {
        val decoder = Base64.getDecoder()
        val head = decoder.decode(action.getBase64Body())
        val cont = action.getVariables()["bodycont1"]?.let { decoder.decode(it) } ?: ByteArray(0)
        return String(head + cont, Charsets.UTF_8)
    }

    private fun actionFor(body: String) =
        QuectelMessageSendAction.of("shop1", "+4500000000", body)
            ?: error("the action was refused")

    /** The price list that exposed the header limit in production: 844 bytes of UTF-8. */
    private val sunnyPriceList =
        "☀️🌞 SUNNY🌞☀️\nThai Massage & Full service Menu \n\n" +
            "🪷 Thai Oil Massage \n⏰ 30 Min — 💰 300 \n⏰ 60 Min — 💰 500\n" +
            "━━━━━━━━━━━━━━\n🩱 Special Massage (B2B • HJ) \n" +
            "⏰ 30 Min — 💰 600 \n⏰ 45 Min — 💰 700 \n⏰ 60 Min — 💰 800\n" +
            "━━━━━━━━━━━━━━\n🪩 Premium Massage (B2B • BJ Without ) \n" +
            "⏰ 30 Min— 💰 700 \n⏰ 45 Min — 💰 800 \n⏰ 60 Min — 💰 1,000\n" +
            "━━━━━━━━━━━━━━\n❤️‍🔥 Sex full service \n" +
            "⏰ 15 Min — 💰 600 \n⏰ 30 Min— 💰 800 \n⏰ 45 Min— 💰 1,000 \n⏰ 60 Min — 💰 1,200\n\n" +
            "━━━━━━━━━━━━━━\n🛑No Anal  \n🛑CIM +100\n🛑Sex  without  +500\n" +
            "🛑 come inside +200\n🛑No Rimming \n\n\n" +
            "🌹✨ Clean • Private • Friendly Service ✨🌹\n\n" +
            "😊\"Vær venligst høflig over for mig.\" 😊"

    // ── The body arrives as written ──────────────────────────────────────────

    @Test
    fun `a multi-line price list survives the transport unchanged`() {
        val body = "💙Thai & oil Massage relax\n30 min 300\n60 min 500\n\n🩱Massage ( B2B , HJ)\n30 min 600"
        assertEquals(body, decodedBody(actionFor(body)))
    }

    @Test
    fun `a newline stays a newline and never becomes the letter n`() {
        val decoded = decodedBody(actionFor("line one\nline two"))
        assertEquals(listOf("line one", "line two"), decoded.lines())
        assertFalse(decoded.contains("line onen"), "the newline was replaced by its escape")
    }

    @Test
    fun `an emoji body keeps every code point`() {
        val body = "🔵 one\n🩱 two"
        assertEquals(body, decodedBody(actionFor(body)))
    }

    @Test
    fun `backslashes and quotes reach the modem as written`() {
        val body = """a\nb "quoted" c\\d"""
        assertEquals(body, decodedBody(actionFor(body)))
    }

    @Test
    fun `carriage returns are normalised away`() {
        assertEquals("one\ntwo", decodedBody(actionFor("one\r\ntwo")))
    }

    // ── The body arrives whole ───────────────────────────────────────────────

    @Test
    fun `the price list that broke production survives whole`() {
        assertTrue(
            sunnyPriceList.toByteArray(Charsets.UTF_8).size > 756,
            "this fixture must exceed what one header carries or it tests nothing",
        )
        val action = actionFor(sunnyPriceList)
        assertTrue(action.getVariables().containsKey("bodycont1"), "a long body needs the continuation")
        assertEquals(sunnyPriceList, decodedBody(action))
    }

    @Test
    fun `the head is a whole number of base64 groups so each part decodes alone`() {
        assertEquals(0, actionFor(sunnyPriceList).getBase64Body().length % 4)
    }

    @Test
    fun `every header line stays inside the limit Asterisk accepts`() {
        val action = actionFor(sunnyPriceList)
        assertTrue(
            "Base64Body: ".length + action.getBase64Body().length < 1024,
            "Base64Body would make the header line too long",
        )
        // asterisk-java joins the whole variable map onto one line.
        val variableLine = "Variable: " + action.getVariables().entries.joinToString(",") { "${it.key}=${it.value}" }
        assertTrue(variableLine.length < 1024, "the variable line would be too long: ${variableLine.length}")
    }

    @Test
    fun `a short body needs no continuation`() {
        assertTrue(actionFor("short").getVariables().isEmpty())
    }

    @Test
    fun `no header value carries a raw newline`() {
        val action = actionFor(sunnyPriceList)
        val headers = listOf(action.getAction(), action.getDestination(), action.getTo(), action.getBase64Body()) +
            action.getVariables().values
        for (value in headers) {
            assertFalse(value.contains("\n"), "a header value would end the AMI frame")
            assertFalse(value.contains("\r"), "a header value would end the AMI frame")
        }
    }

    @Test
    fun `a body beyond what the two headers carry is refused, not truncated`() {
        val huge = "x".repeat(QuectelMessageSendAction.MAX_BODY_BYTES + 1)
        assertNull(
            QuectelMessageSendAction.of("shop1", "+4500000000", huge),
            "a body that does not fit must be refused",
        )
    }

    @Test
    fun `a body at the limit is still accepted`() {
        val atLimit = "x".repeat(QuectelMessageSendAction.MAX_BODY_BYTES)
        assertEquals(atLimit, decodedBody(actionFor(atLimit)))
    }

    @Test
    fun `the action addresses the trunk through the mobile technology`() {
        val action = QuectelMessageSendAction.of("shop7", "+4511223344", "hi")!!
        assertEquals("MessageSend", action.getAction())
        assertEquals("mobile:shop7", action.getDestination())
        assertEquals("+4511223344", action.getTo())
    }
}
