package asterisk

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The recording path is interpolated straight into `MixMonitor(<path>,b)` in the generated
 * dialplan. A comma would truncate the argument, a paren would end the application call, and
 * `$` or a quote could be re-read by Asterisk's parser — so a path we cannot safely quote must
 * mean "do not record", never "record to a mangled path" or "emit a broken dialplan line".
 */
class CallRecordingPathsTest {

    @Test
    fun `an ordinary absolute path is safe`() {
        assertTrue(CallRecordingPaths.isDialplanSafe("/home/phone/shopbackend/recordings"))
        assertTrue(CallRecordingPaths.isDialplanSafe("/var/spool/asterisk/monitor/shopbackend"))
    }

    @Test
    fun `the generated filename shape is safe`() {
        // 32 hex chars from a UUID with the dashes stripped, plus .wav
        assertTrue(CallRecordingPaths.isDialplanSafe("/rec/0f8c1b2a3d4e5f60718293a4b5c6d7e8.wav"))
    }

    @Test
    fun `a comma would truncate the MixMonitor argument`() {
        assertFalse(CallRecordingPaths.isDialplanSafe("/rec/with,comma"))
    }

    @Test
    fun `parens would end the application call`() {
        assertFalse(CallRecordingPaths.isDialplanSafe("/rec/with)paren"))
        assertFalse(CallRecordingPaths.isDialplanSafe("/rec/with(paren"))
    }

    @Test
    fun `dialplan variable syntax and quotes are rejected`() {
        assertFalse(CallRecordingPaths.isDialplanSafe("/rec/\${EXTEN}"))
        assertFalse(CallRecordingPaths.isDialplanSafe("/rec/with\"quote"))
        // Note the doubled backslash: a single one here is Kotlin's \b (backspace) escape, so the
        // string would contain no backslash and this assertion would pass vacuously.
        assertFalse(CallRecordingPaths.isDialplanSafe("/rec/with" + '\\' + "backslash"))
    }

    @Test
    fun `newlines are rejected, since they could inject a dialplan line`() {
        assertFalse(CallRecordingPaths.isDialplanSafe("/rec/a\nsame => n,Hangup()"))
        assertFalse(CallRecordingPaths.isDialplanSafe("/rec/a\rb"))
    }

    @Test
    fun `a blank path is not safe`() {
        assertFalse(CallRecordingPaths.isDialplanSafe(""))
        assertFalse(CallRecordingPaths.isDialplanSafe("   "))
    }
}
