import asterisk.CallRecordingReaper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for the recording reaper.
 *
 * The invariant that matters: **recordings are evidence, so the reaper must never destroy one to
 * reclaim disk space.** Low disk stops new recordings; deletion is age-based only, and a recording
 * the admin marked `preserved` is never deleted at any age. Getting this backwards would mean the
 * housekeeping code destroying the exact thing the feature exists to produce — the failure mode
 * docs/recovery-code-rules.md was written about.
 */
class CallRecordingReaperTest {

    private val day = 24L * 60 * 60 * 1000
    private val t0 = System.currentTimeMillis()

    private fun freshDb(): DataBase {
        val f = Files.createTempFile("reaper-test", ".db")
        Files.deleteIfExists(f)
        return DataBase(f.toString())
    }

    private fun tempDir(): File =
        Files.createTempDirectory("reaper-rec").toFile().also { it.deleteOnExit() }

    /** Creates a recording row whose file exists, started [daysAgo] before now. */
    private fun DataBase.recordingWithFile(dir: File, daysAgo: Int, bytes: ByteArray = "audio".toByteArray()): Int {
        val f = File(dir, "rec-$daysAgo-${System.nanoTime()}.wav")
        f.writeBytes(bytes)
        return createCallRecording(
            phone = "+4551941736", shopId = 1, direction = "inbound",
            path = f.absolutePath, startedAt = t0 - daysAgo * day,
        )
    }

    private fun DataBase.recordingWithoutFile(dir: File, daysAgo: Int): Int =
        createCallRecording(
            phone = "+4551941736", shopId = 1, direction = "inbound",
            path = File(dir, "missing-${System.nanoTime()}.wav").absolutePath,
            startedAt = t0 - daysAgo * day,
        )

    // ─── Deletion is age-based, and only age-based ───────────────────────────

    @Test
    fun `a recording past the window is deleted and its file removed`() {
        val db = freshDb(); val dir = tempDir()
        val id = db.recordingWithFile(dir, daysAgo = 400)
        val path = db.getCallRecording(id)!!.path

        val s = CallRecordingReaper.run(db, retentionDays = 365, now = t0)

        assertEquals(1, s.deleted)
        assertFalse(File(path).exists(), "the audio file must actually be removed")
    }

    @Test
    fun `a recording inside the window is left alone`() {
        val db = freshDb(); val dir = tempDir()
        val id = db.recordingWithFile(dir, daysAgo = 300)

        val s = CallRecordingReaper.run(db, retentionDays = 365, now = t0)

        assertEquals(0, s.deleted)
        assertTrue(File(db.getCallRecording(id)!!.path).exists())
    }

    @Test
    fun `a preserved recording is never deleted, at any age`() {
        val db = freshDb(); val dir = tempDir()
        val id = db.recordingWithFile(dir, daysAgo = 5000)
        assertTrue(db.setCallRecordingPreserved(id, true, "admin"))
        val path = db.getCallRecording(id)!!.path

        val s = CallRecordingReaper.run(db, retentionDays = 365, now = t0)

        assertEquals(0, s.deleted, "evidence marked preserved must survive any retention window")
        assertEquals(1, s.preservedKept, "and the deliberate keep must be visible")
        assertTrue(File(path).exists())
    }

    @Test
    fun `the row survives deletion so the record that it existed outlives the audio`() {
        val db = freshDb(); val dir = tempDir()
        val id = db.recordingWithFile(dir, daysAgo = 400)

        CallRecordingReaper.run(db, retentionDays = 365, now = t0)

        // getCallRecording filters deleted rows, but the access log still names it.
        assertNull(db.getCallRecording(id))
        val actions = db.listCallRecordingAccess(id).map { it.action }
        assertTrue(actions.contains("delete"), "deletion must be in the chain of custody")
    }

    @Test
    fun `preserving and unpreserving are both audited`() {
        val db = freshDb(); val dir = tempDir()
        val id = db.recordingWithFile(dir, daysAgo = 1)
        db.setCallRecordingPreserved(id, true, "admin")
        db.setCallRecordingPreserved(id, false, "admin")

        val actions = db.listCallRecordingAccess(id).map { it.action }

        assertTrue(actions.contains("preserve"))
        assertTrue(actions.contains("unpreserve"))
    }

    // ─── Finalising: size and hash ───────────────────────────────────────────

    @Test
    fun `a settled file gets its size and a sha256`() {
        val db = freshDb(); val dir = tempDir()
        val id = db.recordingWithFile(dir, daysAgo = 1, bytes = "hello".toByteArray())

        val s = CallRecordingReaper.run(db, retentionDays = 365, now = t0)

        assertEquals(1, s.finalised)
        val rec = db.getCallRecording(id)!!
        assertEquals(5L, rec.sizeBytes)
        // sha256("hello")
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", rec.sha256)
    }

    @Test
    fun `a recording still in progress is not finalised yet`() {
        val db = freshDb(); val dir = tempDir()
        // Started 10 seconds ago — MixMonitor may still be writing.
        val f = File(dir, "live.wav"); f.writeBytes("partial".toByteArray())
        val id = db.createCallRecording(
            phone = "+4551941736", shopId = 1, direction = "inbound",
            path = f.absolutePath, startedAt = t0 - 10_000,
        )

        val s = CallRecordingReaper.run(db, retentionDays = 365, now = t0)

        assertEquals(0, s.finalised, "hashing a file still being written would store a wrong hash")
        assertNull(db.getCallRecording(id)!!.sizeBytes)
    }

    @Test
    fun `an unanswered call is recorded as no-audio rather than retried forever`() {
        val db = freshDb(); val dir = tempDir()
        val id = db.recordingWithoutFile(dir, daysAgo = 1)

        val first = CallRecordingReaper.run(db, retentionDays = 365, now = t0)
        val second = CallRecordingReaper.run(db, retentionDays = 365, now = t0)

        assertEquals(1, first.noAudio)
        assertEquals(0, second.noAudio, "size 0 must stop it being re-examined every hour")
        assertEquals(0L, db.getCallRecording(id)!!.sizeBytes)
        assertTrue(db.listCallRecordingAccess(id).any { it.action == "no-audio" })
    }

    @Test
    fun `a confirmed recording is marked as such in the chain of custody`() {
        val db = freshDb(); val dir = tempDir()
        val id = db.recordingWithFile(dir, daysAgo = 1)

        CallRecordingReaper.run(db, retentionDays = 365, now = t0)

        val actions = db.listCallRecordingAccess(id).map { it.action }
        assertTrue(actions.contains("record-confirmed"),
            "confirmation must be file-backed, separate from the record-requested entry")
    }

    // ─── The space guard never deletes ───────────────────────────────────────

    @Test
    fun `the space guard allows recording on a normal filesystem`() {
        val dir = tempDir()
        assertTrue(CallRecordingReaper.hasSpaceToRecord(dir.absolutePath))
    }

    @Test
    fun `an unknown free space fails open rather than silently disabling recording`() {
        // usableSpace answers 0 for a path that does not exist.
        assertTrue(CallRecordingReaper.hasSpaceToRecord("/no/such/path/anywhere"))
    }

    @Test
    fun `the reaper never deletes anything because of disk pressure`() {
        val db = freshDb(); val dir = tempDir()
        // Recent, unpreserved recordings — exactly what a space-based reaper would eat first.
        val ids = (1..3).map { db.recordingWithFile(dir, daysAgo = it) }

        val s = CallRecordingReaper.run(db, retentionDays = 365, now = t0)

        assertEquals(0, s.deleted, "deletion must be age-based only; space pressure stops NEW " +
            "recordings instead of destroying evidence")
        for (id in ids) assertNotNull(db.getCallRecording(id))
    }

    @Test
    fun `the retention default is generous enough to outlast an evidence-gathering period`() {
        // A 90-day recording rule must not outlive the recordings it produced.
        assertTrue(CallRecordingReaper.RECORDING_RETENTION_DAYS >= 365,
            "recordings must survive long past the 90-day rule window and any police handover")
    }

    @Test
    fun `an undeletable file leaves the row for the next sweep`() {
        val db = freshDb(); val dir = tempDir()
        // A directory at the recording's path cannot be deleted by File.delete() when non-empty.
        val stubborn = File(dir, "stubborn.wav").also { it.mkdirs(); File(it, "child").writeText("x") }
        val id = db.createCallRecording(
            phone = "+4551941736", shopId = 1, direction = "inbound",
            path = stubborn.absolutePath, startedAt = t0 - 400 * day,
        )

        val s = CallRecordingReaper.run(db, retentionDays = 365, now = t0)

        assertEquals(1, s.deleteFailed)
        assertEquals(0, s.deleted)
        assertNotNull(db.getCallRecording(id),
            "losing track of an undeletable file is worse than retrying in an hour")
    }
}
