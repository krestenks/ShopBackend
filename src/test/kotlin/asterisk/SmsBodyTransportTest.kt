package asterisk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * An SMS body reaches chan_quectel through the `MessageSend` AMI action, carried base64-encoded.
 *
 * The encoding is what keeps the body intact. AMI headers are line-based, so a raw newline in a
 * header value would end it; and the CLI command this route replaced could not carry one either,
 * because Asterisk's CLI parser removes a backslash and passes the next character through
 * literally, which turns an escaped newline into the letter "n".
 *
 * These tests hold the body to arriving byte for byte.
 */
class SmsBodyTransportTest {

    private fun decodedBody(action: QuectelMessageSendAction): String =
        String(Base64.getDecoder().decode(action.getBase64Body()), Charsets.UTF_8)

    @Test
    fun `a multi-line price list survives the transport unchanged`() {
        val body = "💙Thai & oil Massage relax\n30 min 300\n60 min 500\n\n🩱Massage ( B2B , HJ)\n30 min 600"
        val action = QuectelMessageSendAction("shop9", "+4551941736", body)
        assertEquals(body, decodedBody(action))
    }

    @Test
    fun `a newline stays a newline and never becomes the letter n`() {
        val action = QuectelMessageSendAction("shop1", "+4500000000", "line one\nline two")
        val decoded = decodedBody(action)
        assertEquals(listOf("line one", "line two"), decoded.lines())
        assertFalse(decoded.contains("line onen"), "the newline was replaced by its escape")
    }

    @Test
    fun `an emoji body keeps every code point`() {
        val body = "🔵 one\n🩱 two"
        val action = QuectelMessageSendAction("shop1", "+4500000000", body)
        assertEquals(body, decodedBody(action))
    }

    @Test
    fun `backslashes and quotes reach the modem as written`() {
        val body = """a\nb "quoted" c\\d"""
        val action = QuectelMessageSendAction("shop1", "+4500000000", body)
        assertEquals(body, decodedBody(action))
    }

    @Test
    fun `no header value carries a raw newline`() {
        val action = QuectelMessageSendAction("shop1", "+4500000000", "one\ntwo\nthree")
        val headers = listOf(action.getAction(), action.getDestination(), action.getTo(), action.getBase64Body())
        for (value in headers) {
            assertFalse(value.contains("\n"), "a header value would end the AMI frame: $value")
            assertFalse(value.contains("\r"), "a header value would end the AMI frame: $value")
        }
    }

    @Test
    fun `carriage returns are normalised away`() {
        val action = QuectelMessageSendAction("shop1", "+4500000000", "one\r\ntwo")
        assertEquals("one\ntwo", decodedBody(action))
    }

    @Test
    fun `the action addresses the trunk through the mobile technology`() {
        val action = QuectelMessageSendAction("shop7", "+4511223344", "hi")
        assertEquals("MessageSend", action.getAction())
        assertEquals("mobile:shop7", action.getDestination())
        assertEquals("+4511223344", action.getTo())
    }

    @Test
    fun `an empty body encodes to an empty payload rather than failing`() {
        val action = QuectelMessageSendAction("shop1", "+4500000000", "")
        assertTrue(action.getBase64Body().isEmpty())
        assertEquals("", decodedBody(action))
    }
}
