package asterisk

import DataBase
import java.io.File
import java.security.MessageDigest

/**
 * Housekeeping for call recordings: finalise finished files, delete old ones, and keep the disk
 * from filling.
 *
 * ## The one design decision that matters
 *
 * Recordings are ~1 MB/minute of WAV on the **same filesystem as the SQLite database**. On
 * 2026-09-07 this box had a total SMS and DB outage because a single unwatched file filled that
 * disk (SQLITE_FULL on every write). So an unbounded writer with no reaper is an availability
 * risk to the phone system, not just untidiness.
 *
 * The tempting response is "when the disk gets low, delete the oldest recordings". **This does
 * not do that, deliberately.** These recordings are evidence being gathered for a handover to
 * police or social services; deleting them to reclaim space would destroy the thing the feature
 * exists to produce, in order to protect a disk that has 174 GB free. That is precisely the
 * failure mode this codebase keeps relearning — recovery code destroying the working state it
 * was meant to protect (see docs/recovery-paths-audit-2026-09-13.md).
 *
 * So the two concerns are kept strictly separate:
 *
 *  - **Low disk → stop recording.** [hasSpaceToRecord] is consulted before each new recording.
 *    Refusing to start a new one protects both the disk and the existing evidence, and it is
 *    reversible: free some space and recording resumes by itself.
 *  - **Deletion is only ever age-based**, generous by default, and never touches a recording the
 *    admin marked `preserved`.
 *
 * ```
 * TRIGGER:  hourly, from the cleanup scheduler. Deletes recordings whose started_at is older
 *           than RECORDING_RETENTION_DAYS (365) and which are not marked preserved.
 * EVIDENCE: STRONG. An explicit started_at timestamp written when the recording was created,
 *           compared against a configured age. Nothing is inferred from a missing file, a failed
 *           read or absent metadata.
 * BLAST:    one .wav file per firing, plus setting deleted_at on its row. The row itself is kept
 *           so the record that a recording existed, and who accessed it, outlives the audio.
 *           Cannot touch calls, trunks, config or the database schema.
 * GUARD:    preserved = 1 is excluded in SQL, not in Kotlin, so no code path can forget it; the
 *           default age is a year; and a file that cannot be deleted leaves the row untouched so
 *           the next sweep retries rather than losing track of it.
 * IF WRONG: a recording older than a year that the admin had not marked as evidence is gone. The
 *           access log and the row survive, so it is visible that it existed. No effect on any
 *           call.
 * ```
 */
object CallRecordingReaper {

    /** Age after which an unpreserved recording is deleted. Deliberately generous. */
    const val RECORDING_RETENTION_DAYS = 365

    /**
     * Stop starting new recordings when the filesystem has less than this free.
     *
     * Chosen to be far above anything SQLite needs to keep working, because the point is to
     * protect the database and the phone system, not to use every last byte for audio. At
     * ~1 MB/minute, 5 GB is still some 80 hours of conversation in hand.
     */
    const val MIN_FREE_BYTES_TO_RECORD = 5L * 1024 * 1024 * 1024

    /** A file is only finalised once it has had time to be closed by MixMonitor. */
    private const val SETTLE_MS = 5L * 60 * 1000

    /**
     * A RIFF/WAV header is exactly 44 bytes, so a file of that size contains **no audio samples**.
     *
     * This is the normal result of a call that was never answered: MixMonitor's `b` option only
     * writes while the channel is bridged, but the file is opened (and the header written) as soon
     * as the application runs. Treating "file exists and is non-empty" as "we have audio" showed
     * such a recording as "0 kB" with a working player that plays silence — which reads as a
     * broken recording rather than an unanswered call. Observed for real on two calls from the
     * number this feature was built for.
     */
    const val WAV_HEADER_BYTES = 44L

    /** True when the file holds actual audio rather than just a header (or nothing). */
    fun hasAudio(f: File): Boolean = f.isFile && f.length() > WAV_HEADER_BYTES

    data class Summary(
        val finalised: Int = 0,
        val noAudio: Int = 0,
        val deleted: Int = 0,
        val freedBytes: Long = 0,
        val preservedKept: Int = 0,
        val deleteFailed: Int = 0,
    ) {
        fun isInteresting() = finalised + noAudio + deleted + deleteFailed > 0
    }

    /**
     * Whether there is room to start another recording.
     *
     * Returns true when the free space cannot be determined: `usableSpace` answers 0 for a path
     * that does not exist or cannot be queried, and treating "I could not tell" as "stop
     * recording" would silently disable the feature on any odd filesystem. The risk is bounded —
     * MixMonitor's own write failure is the backstop — and this is the one place where failing
     * open is the lesser evil. It says so in the log either way.
     */
    fun hasSpaceToRecord(dir: String = CallRecordingPaths.directory): Boolean {
        val usable = runCatching { File(dir).usableSpace }.getOrDefault(0L)
        if (usable <= 0L) {
            println("[Recording] Free space on $dir could not be determined — allowing recording; " +
                    "MixMonitor's own write failure is the backstop.")
            return true
        }
        if (usable < MIN_FREE_BYTES_TO_RECORD) {
            println("[Recording] REFUSING new recordings: only ${usable / (1024 * 1024)} MB free on " +
                    "$dir, below the ${MIN_FREE_BYTES_TO_RECORD / (1024 * 1024 * 1024)} GB floor. " +
                    "Existing recordings are NOT deleted to make room — they are evidence. Free " +
                    "space and recording resumes automatically. Calls are unaffected.")
            return false
        }
        return true
    }

    /**
     * One housekeeping pass. Safe to call on a schedule; does nothing when there is nothing to do.
     */
    fun run(
        db: DataBase,
        retentionDays: Int = RECORDING_RETENTION_DAYS,
        now: Long = System.currentTimeMillis(),
    ): Summary {
        var finalised = 0
        var noAudio = 0
        var deleted = 0
        var freed = 0L
        var failed = 0

        // ── Finalise: record size and hash once MixMonitor has closed the file ───────
        // The hash is what makes a handed-over recording hard to dispute later, and it has to be
        // taken before anything else touches the file.
        for (rec in db.listUnfinalisedCallRecordings()) {
            if (now - rec.startedAt < SETTLE_MS) continue   // may still be recording
            val f = File(rec.path)
            if (hasAudio(f)) {
                val hash = runCatching { sha256(f) }.getOrNull()
                db.finaliseCallRecording(rec.id, f.length(), hash, null)
                db.logCallRecordingAccess(rec.id, "record-confirmed", "system",
                    "bytes=${f.length()} sha256=${hash?.take(16) ?: "unavailable"}")
                finalised++
            } else {
                // No audio: with MixMonitor's `b` option a call that was never answered writes
                // no samples — either no file at all, or a bare 44-byte header. Recorded as
                // size 0 either way, so it stops being re-examined every hour and the chain of
                // custody says plainly that no audio exists.
                val why = if (f.isFile) "header only (${f.length()} bytes), no audio samples"
                          else "no file at the expected path"
                db.finaliseCallRecording(rec.id, 0L, null, null)
                db.logCallRecordingAccess(rec.id, "no-audio", "system",
                    "$why — the call was most likely never answered")
                noAudio++
            }
        }

        // ── Delete: age only, never for space, never if preserved ────────────────────
        val cutoff = now - retentionDays * 24L * 60 * 60 * 1000
        val preservedKept = db.countPreservedPastCutoff(cutoff)
        for (rec in db.listDeletableCallRecordings(cutoff)) {
            val f = File(rec.path)
            val size = if (f.isFile) f.length() else 0L
            val goneOrDeleted = !f.exists() || runCatching { f.delete() }.getOrDefault(false)
            if (goneOrDeleted) {
                db.markCallRecordingDeleted(rec.id, now)
                db.logCallRecordingAccess(rec.id, "delete", "system",
                    "age>${retentionDays}d bytes=$size")
                // Every destructive action announces itself, with the evidence it acted on.
                println("[Recording] DELETED id=${rec.id} phone=${util.maskPhone(rec.phone)} " +
                        "evidence=startedAt=${java.time.Instant.ofEpochMilli(rec.startedAt)} " +
                        "age>${retentionDays}d preserved=false bytes=$size")
                deleted++
                freed += size
            } else {
                // Leave the row alone so the next sweep retries; losing track of an undeletable
                // file is worse than trying again in an hour.
                println("[Recording] Could not delete ${rec.path} (id=${rec.id}) — leaving the row " +
                        "so the next sweep retries.")
                failed++
            }
        }

        if (preservedKept > 0) {
            println("[Recording] $preservedKept recording(s) past the ${retentionDays}d window are " +
                    "marked preserved and were deliberately kept.")
        }
        return Summary(finalised, noAudio, deleted, freed, preservedKept, failed)
    }

    /**
     * Deletes one recording on explicit admin instruction.
     *
     * Distinct from the age-based sweep in two ways. It ignores `preserved`, because an admin
     * pressing delete on a specific recording is a deliberate act, not a policy sweep — the
     * confirmation prompt is where that gets questioned, not here. And it is audited as
     * `delete-manual` with the admin's name, so the chain of custody distinguishes "aged out" from
     * "someone removed this".
     *
     * The resolved file must sit inside the recordings directory. The `path` column is written by
     * the backend itself, but confining the delete means a tampered or mis-migrated row can never
     * turn this into an arbitrary file removal — the same containment the playback route uses.
     *
     * The row is kept with `deleted_at` set, so the record that the recording existed, and who
     * had listened to it, outlives the audio.
     *
     * @return true if the row was marked deleted.
     */
    fun deleteOne(db: DataBase, id: Int, actor: String): Boolean {
        val rec = db.getCallRecording(id) ?: return false
        val root = File(CallRecordingPaths.directory).canonicalFile
        val file = runCatching { File(rec.path).canonicalFile }.getOrNull()

        // Containment is checked WITHOUT regard to whether the file happens to exist. An earlier
        // version only refused when it existed, so a path outside the directory that was merely
        // absent still had its row marked deleted and reported success — treating an untrustworthy
        // row as if it had been handled. If the path is not ours, the row is not to be touched.
        val inside = file != null && file.path.startsWith(root.path + File.separator)
        if (!inside) {
            println("[Recording] REFUSED manual delete of id=$id: ${file?.path ?: rec.path} is " +
                    "outside ${root.path} — the row is left alone for inspection")
            return false
        }
        if (file!!.exists() && !runCatching { file.delete() }.getOrDefault(false)) {
            println("[Recording] Manual delete of id=$id failed to remove ${file.path}")
            return false
        }

        db.markCallRecordingDeleted(id)
        db.logCallRecordingAccess(id, "delete-manual", actor,
            "phone=${util.maskPhone(rec.phone)} preserved=${rec.preserved} " +
                "bytes=${rec.sizeBytes ?: 0}")
        println("[Recording] DELETED (manual) id=$id by $actor " +
                "phone=${util.maskPhone(rec.phone)} preserved=${rec.preserved}")
        return true
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
