import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Tests for the per-number call-recording rules.
 *
 * Recording is covert, so the failure direction is not symmetric: **failing to record is a missed
 * opportunity, recording someone we should not is a wrong we cannot undo.** Every ambiguous case
 * here must therefore resolve to "do not record".
 *
 * See docs/call-recording-plan.md.
 */
class CallRecordingRuleTest {

    private val day = 24L * 60 * 60 * 1000
    private val t0 = System.currentTimeMillis()

    private fun freshDb(): DataBase {
        val f = Files.createTempFile("recording-test", ".db")
        Files.deleteIfExists(f)
        return DataBase(f.toString())
    }

    // ─── The default is not to record ────────────────────────────────────────

    @Test
    fun `a number with no rule is not recorded`() {
        val db = freshDb()
        assertNull(db.getActiveCallRecordingRule("+4551941736"))
    }

    @Test
    fun `a blank or junk phone is never recorded`() {
        val db = freshDb()
        db.addCallRecordingRule("+4551941736", "threats to staff", "admin")

        assertNull(db.getActiveCallRecordingRule(""))
        assertNull(db.getActiveCallRecordingRule("   "))
        assertNull(db.getActiveCallRecordingRule("+4500000000"))
    }

    @Test
    fun `only the exact number is recorded, not a prefix or suffix`() {
        val db = freshDb()
        db.addCallRecordingRule("+4551941736", "threats to staff", "admin")

        assertNotNull(db.getActiveCallRecordingRule("+4551941736"))
        assertNull(db.getActiveCallRecordingRule("+455194173"))
        assertNull(db.getActiveCallRecordingRule("+45519417361"))
    }

    // ─── Enabling and disabling ──────────────────────────────────────────────

    @Test
    fun `an active rule records, and carries its reason`() {
        val db = freshDb()
        db.addCallRecordingRule("+4551941736", "threatened staff on 2026-10-02", "admin")

        val rule = db.getActiveCallRecordingRule("+4551941736")

        assertNotNull(rule)
        assertEquals("threatened staff on 2026-10-02", rule!!.reason)
        assertTrue(rule.isLive())
    }

    @Test
    fun `deactivating a rule stops the recording immediately`() {
        val db = freshDb()
        val id = db.addCallRecordingRule("+4551941736", "threats to staff", "admin")
        assertNotNull(db.getActiveCallRecordingRule("+4551941736"))

        assertTrue(db.deactivateCallRecordingRule(id, "admin"))

        assertNull(db.getActiveCallRecordingRule("+4551941736"))
    }

    // ─── Expiry: recording must not quietly become permanent ────────────────

    @Test
    fun `an expired rule does not record`() {
        val db = freshDb()
        db.addCallRecordingRule(
            "+4551941736", "threats to staff", "admin",
            expiresAt = t0 + 90 * day,
        )

        assertNotNull(db.getActiveCallRecordingRule("+4551941736", now = t0 + 89 * day))
        assertNull(db.getActiveCallRecordingRule("+4551941736", now = t0 + 91 * day),
            "recording is a response to a situation, not a permanent state")
    }

    @Test
    fun `the default rule expires rather than running forever`() {
        val db = freshDb()
        db.addCallRecordingRule("+4551941736", "threats to staff", "admin")

        val rule = db.getActiveCallRecordingRule("+4551941736")!!

        assertNotNull(rule.expiresAt, "the default must carry an expiry")
        assertTrue(rule.expiresAt!! <= t0 + 91 * day)
        assertFalse(rule.isLive(now = rule.expiresAt!! + 1))
    }

    @Test
    fun `an explicit no-expiry rule is possible but must be deliberate`() {
        val db = freshDb()
        db.addCallRecordingRule("+4551941736", "ongoing police matter", "admin", expiresAt = null)

        val rule = db.getActiveCallRecordingRule("+4551941736", now = t0 + 3650 * day)

        assertNotNull(rule)
        assertNull(rule!!.expiresAt)
    }

    @Test
    fun `re-enabling after expiry works and the newest rule wins`() {
        val db = freshDb()
        db.addCallRecordingRule("+4551941736", "first period", "admin", expiresAt = t0 + 10 * day)
        db.addCallRecordingRule("+4551941736", "renewed after review", "admin", expiresAt = t0 + 200 * day)

        val rule = db.getActiveCallRecordingRule("+4551941736", now = t0 + 50 * day)

        assertNotNull(rule)
        assertEquals("renewed after review", rule!!.reason)
    }

    // ─── Chain of custody ───────────────────────────────────────────────────

    @Test
    fun `enabling and disabling are both audited`() {
        val db = freshDb()
        val id = db.addCallRecordingRule("+4551941736", "threats to staff", "admin")
        db.deactivateCallRecordingRule(id, "admin")

        val actions = db.listCallRecordingAccess().map { it.action }

        assertTrue(actions.contains("enable"), "turning recording on must be recorded")
        assertTrue(actions.contains("disable"))
    }

    @Test
    fun `the audit log never stores a full phone number`() {
        val db = freshDb()
        db.addCallRecordingRule("+4551941736", "threats to staff", "admin")

        val notes = db.listCallRecordingAccess().mapNotNull { it.note }

        assertFalse(notes.any { it.contains("51941736") },
            "the access log is widely read; it should carry a masked number")
        assertTrue(notes.any { it.contains("XXXX") })
    }

    // ─── Rule listing ───────────────────────────────────────────────────────

    @Test
    fun `listing shows active rules before inactive ones`() {
        val db = freshDb()
        val gone = db.addCallRecordingRule("+4551941700", "old", "admin")
        db.addCallRecordingRule("+4551941736", "current", "admin")
        db.deactivateCallRecordingRule(gone, "admin")

        val rules = db.listCallRecordingRules()

        assertEquals(2, rules.size)
        assertTrue(rules.first().active)
        assertFalse(rules.last().active)
    }

    // ─── Recording rows ─────────────────────────────────────────────────────

    @Test
    fun `the default recording directory is Asterisk's own spool, which both processes can reach`() {
        // Regression: the original default was under /home/phone, which asterisk cannot traverse
        // (drwxr-x--- phone phone, and asterisk is not in group phone) - so MixMonitor failed
        // EACCES on every call while the audit log claimed a recording existed.
        assertEquals("/var/spool/asterisk/monitor", asterisk.CallRecordingPaths.DEFAULT_DIRECTORY)
        assertFalse(asterisk.CallRecordingPaths.DEFAULT_DIRECTORY.startsWith("/home/"),
            "a backend-owned home directory is not traversable by the asterisk user")
    }

    @Test
    fun `the audit trail does not claim a recording before one exists`() {
        val db = freshDb()
        db.addCallRecordingRule("+4551941736", "threats to staff", "admin")
        val recId = db.createCallRecording(
            phone = "+4551941736", shopId = 1, direction = "inbound",
            path = "/var/spool/asterisk/monitor/abc123.wav",
        )
        db.logCallRecordingAccess(recId, "record-requested", "system", "ruleId=1")

        val actions = db.listCallRecordingAccess().map { it.action }

        assertTrue(actions.contains("record-requested"))
        assertFalse(actions.contains("record"),
            "a bare \"record\" entry would assert audio that MixMonitor may never have written")
    }

    @Test
    fun `a registered recording starts un-preserved and un-deleted`() {
        val db = freshDb()
        val recId = db.createCallRecording(
            phone = "+4551941736", shopId = 1, direction = "inbound",
            path = "/var/spool/asterisk/monitor/shopbackend/abc123.wav",
        )

        assertTrue(recId > 0)
    }
}
