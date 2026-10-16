package asterisk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for detecting calls that bypassed the blacklist and opening hours because the backend did
 * not answer the dialplan's routing request.
 *
 * This is the only evidence such a call leaves. The `voice_call` row is written by the endpoint
 * that failed, so the call appears in no database query at all — which is why a reported leak on
 * 2026-10-16 could be neither confirmed nor refuted. The parser therefore has to be right about
 * real log text, and must not re-announce old lines every time the backend restarts.
 */
class FailOpenLogWatchTest {

    private val now = 1_792_163_435_000L          // matches uniqueid 1792163435.x
    private val lookback = 30 * 60_000L

    /** The line the generated dialplan actually emits (no commas — Log() would split on them). */
    private fun line(shop: Int, caller: String, id: String, verdict: String = "") =
        "[Oct 16 17:10:35] WARNING[12345][C-0000abcd] Ext. s: FAILOPEN shop=$shop " +
            "verdict=[$verdict] caller=$caller id=$id - backend unreachable so the blacklist " +
            "and opening hours were BYPASSED for this call"

    @Test
    fun `a real fail-open line is parsed into its parts`() {
        val e = parse(line(1, "+4527822182", "1792163435.23897"), now, lookback).single()

        assertEquals(1, e.shopId)
        assertEquals("+4527822182", e.caller)
        assertEquals("1792163435.23897", e.uniqueId)
        assertEquals("", e.verdict, "an empty verdict is the normal fail-open case")
    }

    @Test
    fun `a garbage verdict is captured, not just an empty one`() {
        // A 403 body or an HTML error page would also fail to match any GotoIf.
        val e = parse(line(2, "+4511112222", "1792163435.1", "<html>502 Bad"), now, lookback).single()
        assertEquals("<html>502 Bad", e.verdict)
    }

    @Test
    fun `ordinary log traffic produces nothing`() {
        val noise = """
            [Oct 16 17:10:35] NOTICE[123] chan_quectel.c: shop1 Call ended
            [Oct 16 17:10:36] VERBOSE[124] pbx.c: Executing Dial
            [Oct 16 17:11:00] WARNING[125] func_curl.c: Failed to connect to 127.0.0.1 port 8080
        """.trimIndent()
        assertTrue(parse(noise, now, lookback).isEmpty(),
            "only the dialplan's own marker counts; a curl warning alone is not a leaked call")
    }

    // ─── The property that stops an alert storm ─────────────────────────────

    @Test
    fun `lines older than the lookback are ignored`() {
        // Same shape, but a uniqueid from two hours ago.
        val old = ((now / 1000) - 7200).toString() + ".5"
        assertTrue(parse(line(1, "+4527822182", old), now, lookback).isEmpty(),
            "a restart re-reads the whole tail; without this bound every old line alerts again")
    }

    @Test
    fun `a line right at the edge of the window is still reported`() {
        val edge = ((now - lookback) / 1000 + 5).toString() + ".9"
        assertEquals(1, parse(line(1, "+4500000000", edge), now, lookback).size)
    }

    // ─── Shapes that occur because we read the tail of a live file ──────────

    @Test
    fun `a truncated first line does not break the scan or invent an event`() {
        // Reading the last N bytes can start mid-line.
        val text = "rdict=[] caller=+4511111111 id=1792163435.1 - backend unreach\n" +
            line(5, "+4522223333", "1792163435.2")
        val events = parse(text, now, lookback)

        assertEquals(1, events.size, "the half line has no 'FAILOPEN shop=' so it must be skipped")
        assertEquals(5, events.single().shopId)
    }

    @Test
    fun `a line with an unparsable uniqueid is skipped rather than guessed at`() {
        val bad = "[Oct 16 17:10:35] WARNING[1] Ext. s: FAILOPEN shop=1 verdict=[] " +
            "caller=+4527822182 id=notanid - backend unreachable"
        assertTrue(parse(bad, now, lookback).isEmpty(),
            "without a timestamp we cannot tell if it is current; silence beats a false alarm")
    }

    @Test
    fun `several leaks in one window are all reported, one per call`() {
        val text = listOf(
            line(1, "+4511111111", "1792163435.1"),
            line(2, "+4522222222", "1792163436.2"),
            line(6, "+4533333333", "1792163437.3"),
        ).joinToString("\n")

        val events = parse(text, now + 5_000, lookback)
        assertEquals(3, events.size)
        assertEquals(listOf(1, 2, 6), events.map { it.shopId })
        assertEquals(3, events.map { it.uniqueId }.toSet().size, "each call distinct")
    }

    @Test
    fun `empty input is handled`() {
        assertTrue(parse("", now, lookback).isEmpty())
        assertTrue(parse("\n\n", now, lookback).isEmpty())
    }

    // ─── The generated dialplan must actually emit what this parses ─────────

    @Test
    fun `the dialplan line the generator writes is one this parser understands`() {
        // Guards against the two halves drifting apart: a change to the Log() text that this
        // parser no longer matches would silently restore the old invisible behaviour.
        val generated = " same => n,ExecIf(\$[\"\${VERDICT}\" != \"ring\"]?" +
            "Log(WARNING,FAILOPEN shop=1 verdict=[\${VERDICT:0:40}] caller=\${CALLERID(num)} " +
            "id=\${UNIQUEID} - backend unreachable so the blacklist and opening hours were " +
            "BYPASSED for this call))"

        // Substitute what Asterisk would, then parse it.
        val rendered = generated
            .replace("\${VERDICT:0:40}", "")
            .replace("\${CALLERID(num)}", "+4527822182")
            .replace("\${UNIQUEID}", "1792163435.23897")

        val e = parse(rendered, now, lookback).single()
        assertEquals(1, e.shopId)
        assertEquals("+4527822182", e.caller)
        assertEquals("1792163435.23897", e.uniqueId)
    }

    @Test
    fun `the Log message contains no comma, which Log would read as another argument`() {
        val msg = "FAILOPEN shop=1 verdict=[] caller=+4527822182 id=1792163435.1 - backend " +
            "unreachable so the blacklist and opening hours were BYPASSED for this call"
        assertFalse(msg.contains(","),
            "Log(level,message) splits on commas; a comma here would truncate the evidence")
    }
}
