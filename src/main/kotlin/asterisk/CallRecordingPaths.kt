package asterisk

import java.io.File

/**
 * Where call recordings live.
 *
 * Two different processes have to agree about this directory: **Asterisk writes** the files (it is
 * `MixMonitor`, running as user `asterisk`) and **the backend reads** them back to serve playback
 * to the admin. Those are the only two requirements, and they are not the same requirement — which
 * is what made the first attempt wrong.
 *
 * The obvious-looking choice, a directory under the backend's own tree, **does not work**:
 *
 * ```
 * drwxr-x--- 13 phone phone  /home/phone        <- no o+x, and asterisk is NOT in group phone
 * uid=130(asterisk) gid=137(asterisk) groups=137(asterisk),20(dialout),29(audio)
 * $ sudo -u asterisk test -x /home/phone   ->  DENIED
 * ```
 *
 * Asterisk cannot traverse `/home/phone` at all, so `MixMonitor` fails EACCES on every call.
 * Fixing the leaf directory's group and mode does not help, because the *parents* are the
 * blocker. An earlier version of this file did exactly that and reported "Directory ready" while
 * no recording could ever be written — the backend, as owner, could write there, so a
 * `canWrite()` check tested the wrong principal entirely.
 *
 * So recordings go to Asterisk's own spool instead, which both processes can already reach with
 * no permission changes at all (verified on the edge):
 *
 * ```
 * drwxr-x--- asterisk:asterisk  /var/spool/asterisk           backend traverses via group asterisk
 * drwxr-x--- asterisk:asterisk  /var/spool/asterisk/monitor   asterisk writes; backend reads
 * ```
 *
 * Mode 0750 also means the audio is not world-readable, which matters for recordings of a
 * vulnerable person: the directory itself is the access control.
 *
 * Override with CALL_RECORDING_DIR, but anything chosen must satisfy both principals — see
 * [ensureDirectory], which checks what it can and refuses loudly rather than silently not
 * recording.
 */
object CallRecordingPaths {

    /**
     * Asterisk's own monitor spool. Writable by `asterisk`, readable (and traversable) by the
     * backend through group `asterisk`, and not world-readable.
     */
    const val DEFAULT_DIRECTORY = "/var/spool/asterisk/monitor"

    val directory: String =
        System.getenv("CALL_RECORDING_DIR")?.trim()?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DIRECTORY

    /**
     * Characters that would break `MixMonitor(<path>,b)` when the path is interpolated into the
     * dialplan: a comma ends the argument, a close paren ends the application call, and `$`, `"`
     * or a backslash can be re-read by Asterisk's parser. A newline could inject a whole line.
     *
     * The generated filename is hex only, so this can only be violated by a hand-set
     * CALL_RECORDING_DIR — but a truncated path is a confusing failure and an injected argument
     * is a nasty one, so recording is refused rather than attempted with a path we cannot quote.
     */
    private val UNSAFE_IN_DIALPLAN = charArrayOf(',', ')', '(', '"', '$', '\\', '\n', '\r')

    /** True when [path] can be safely interpolated into a dialplan application argument. */
    fun isDialplanSafe(path: String): Boolean =
        path.isNotBlank() && path.none { it in UNSAFE_IN_DIALPLAN }

    /** True when the configured [directory] is safe to interpolate. */
    fun directoryIsDialplanSafe(): Boolean = isDialplanSafe(directory)

    /**
     * Checks the directory is usable, without pretending to more certainty than we have.
     *
     * What this *can* check: the path is dialplan-safe, it exists, and the backend can read it
     * (needed for playback). What it **cannot** check from inside this process is whether user
     * `asterisk` can write there — testing our own `canWrite()` tests the wrong principal, which
     * is the mistake this replaced. That is why the default is Asterisk's own spool: a directory
     * it demonstrably owns.
     *
     * Never fatal. A recording that cannot be written is a missing file, never a failed call —
     * `MixMonitor` logs its own failure in the monitor thread and the dialplan continues.
     *
     * @return true if recording should be offered at all.
     */
    fun ensureDirectory(): Boolean {
        if (!directoryIsDialplanSafe()) {
            println("[Recording] DISABLED: CALL_RECORDING_DIR contains a character that cannot be " +
                    "safely placed in a dialplan argument. path=$directory. Calls are unaffected.")
            return false
        }

        val dir = File(directory)
        if (!dir.isDirectory) {
            // Deliberately do NOT mkdir: the default lives in Asterisk's spool, which the backend
            // cannot create in, and silently inventing a directory the wrong process owns is how
            // the previous version came to report success while recording nothing.
            println("[Recording] DISABLED: $directory does not exist. Asterisk must be able to " +
                    "write it and this backend to read it; the default " +
                    "$DEFAULT_DIRECTORY satisfies both. Calls are unaffected.")
            return false
        }
        if (!dir.canRead()) {
            println("[Recording] DISABLED: $directory is not readable by the backend, so recordings " +
                    "could never be played back. Calls are unaffected.")
            return false
        }
        if (directory != DEFAULT_DIRECTORY) {
            println("[Recording] Using non-default directory $directory — verify that user " +
                    "'asterisk' can traverse and write every component of it, which this process " +
                    "cannot test on its own.")
        }
        return true
    }
}
