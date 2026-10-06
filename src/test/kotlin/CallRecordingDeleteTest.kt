import asterisk.CallRecordingReaper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for the admin's manual delete.
 *
 * Deliberately different from the age-based reaper in two ways, both tested here: it **does**
 * remove a recording marked as evidence (an admin pressing delete on one specific recording is an
 * explicit act, unlike a policy sweep — the confirmation prompt is where that gets questioned),
 * and it is audited as `delete-manual` so the chain of custody can tell "aged out" from "someone
 * removed this".
 *
 * The containment check is the one that matters most: the `path` column is written by the backend,
 * but a manual delete that trusted it blindly would turn a tampered or mis-migrated row into an
 * arbitrary file removal, running as the backend user.
 */
class CallRecordingDeleteTest {

    private fun freshDb(): DataBase {
        val f = Files.createTempFile("del-test", ".db")
        Files.deleteIfExists(f)
        return DataBase(f.toString())
    }

    private fun recordingsDir(): File = File(asterisk.CallRecordingPaths.directory)

    /** Creates a recording whose file really is inside the configured recordings directory. */
    private fun DataBase.recordingInsideDir(): Pair<Int, File>? {
        val dir = recordingsDir()
        if (!dir.isDirectory || !dir.canWrite()) return null   // not runnable on this machine
        val f = File(dir, "test-${System.nanoTime()}.wav").also { it.writeBytes("audio".toByteArray()) }
        val id = createCallRecording(
            phone = "+4551941736", shopId = 1, direction = "inbound", path = f.absolutePath,
        )
        return id to f
    }

    @Test
    fun `deleting a recording outside the recordings directory is refused`() {
        val db = freshDb()
        // A file that exists but is nowhere near the recordings directory — what a tampered or
        // mis-migrated `path` column would look like.
        val outsider = Files.createTempFile("not-a-recording", ".wav").toFile()
        outsider.writeText("important unrelated file")
        val id = db.createCallRecording(
            phone = "+4551941736", shopId = 1, direction = "inbound", path = outsider.absolutePath,
        )

        val ok = CallRecordingReaper.deleteOne(db, id, "admin")

        assertFalse(ok, "a path outside the recordings directory must be refused")
        assertTrue(outsider.exists(), "the unrelated file must still be there")
        assertNotNull(db.getCallRecording(id), "and the row must not be marked deleted")
        outsider.delete()
    }

    @Test
    fun `a traversal path is refused`() {
        val db = freshDb()
        val id = db.createCallRecording(
            phone = "+4551941736", shopId = 1, direction = "inbound",
            path = asterisk.CallRecordingPaths.directory + "/../../../etc/hostname",
        )

        val ok = CallRecordingReaper.deleteOne(db, id, "admin")

        assertFalse(ok, "canonicalisation must defeat ../ before the containment check")
        assertNotNull(db.getCallRecording(id))
    }

    @Test
    fun `deleting a missing id does nothing`() {
        val db = freshDb()
        assertFalse(CallRecordingReaper.deleteOne(db, 99999, "admin"))
    }

    @Test
    fun `a manual delete is audited separately from an aged-out one`() {
        val db = freshDb()
        val made = db.recordingInsideDir() ?: return   // skip where the dir is not writable
        val (id, file) = made

        assertTrue(CallRecordingReaper.deleteOne(db, id, "admin"))

        assertFalse(file.exists(), "the audio must actually be removed")
        assertNull(db.getCallRecording(id), "the row must be marked deleted")
        val actions = db.listCallRecordingAccess(id).map { it.action }
        assertTrue(actions.contains("delete-manual"),
            "the chain of custody must distinguish a manual delete from the reaper's 'delete'")
        assertFalse(actions.contains("delete"),
            "an aged-out delete and an admin delete are different events")
    }

    @Test
    fun `a manual delete does remove an evidence-marked recording`() {
        val db = freshDb()
        val made = db.recordingInsideDir() ?: return
        val (id, file) = made
        db.setCallRecordingPreserved(id, true, "admin")

        val ok = CallRecordingReaper.deleteOne(db, id, "admin")

        assertTrue(ok, "an explicit admin delete is not a policy sweep; preserved must not block it")
        assertFalse(file.exists())
        // ...but it is recorded that a preserved one was removed.
        val note = db.listCallRecordingAccess(id).firstOrNull { it.action == "delete-manual" }?.note
        assertTrue(note?.contains("preserved=true") == true,
            "the audit note must say the recording had been marked as evidence")
    }

    @Test
    fun `the reaper still refuses to delete a preserved recording`() {
        // Guards against the manual path's behaviour leaking into the automatic one.
        val db = freshDb()
        val made = db.recordingInsideDir() ?: return
        val (id, file) = made
        db.setCallRecordingPreserved(id, true, "admin")

        val s = CallRecordingReaper.run(db, retentionDays = 0, now = System.currentTimeMillis())

        assertTrue(file.exists(), "the automatic sweep must never remove evidence, at any age")
        assertNotNull(db.getCallRecording(id))
        assertTrue(s.deleted == 0)
        file.delete()
    }
}
