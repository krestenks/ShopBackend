package asterisk

import ShopVoiceConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Renders the voice prompts for the DTMF phone flow as WAV files that the
 * generated dialplan Playback()s by absolute path.
 *
 * TTS engine: pico2wave (validated on the phone server during the POC), then
 * downsampled to 8 kHz mono via sox when available (Asterisk's native format —
 * avoids per-call transcoding). Failures are logged, not fatal: the backend
 * runs fine on machines without pico2wave (e.g. the dev laptop); prompts are
 * simply regenerated on the next provisioning run on the real box.
 *
 * Files in [AsteriskConfig.promptsPath]:
 *   shared:    menu-invalid-goodbye, sms-sent, sms-failed, operator-unavailable
 *   per shop:  shop{id}-welcome-open, shop{id}-welcome-closed,
 *              shop{id}-menu-open, shop{id}-menu-closed
 */
class PromptGenerator(private val config: AsteriskConfig) {

    companion object {
        const val MENU_OPEN_TEXT =
            "Press 1 to receive a booking link by SMS. Press 2 to book by phone with an operator."
        const val MENU_CLOSED_TEXT =
            "The phone is currently closed, but you can perform a booking by SMS link. Press 1 to receive a link."

        private val SHARED_PROMPTS = mapOf(
            "menu-invalid-goodbye" to "We did not receive a valid selection. Goodbye.",
            "sms-sent" to "We have sent you a booking link by SMS. Goodbye.",
            "sms-failed" to "Sorry, we could not send the SMS right now. Please try again later. Goodbye.",
            "operator-unavailable" to "The operator did not answer. Please try again in a few minutes. Goodbye.",
        )
    }

    fun sharedPrompt(name: String) = "${config.promptsPath}/$name"
    fun shopPrompt(shopId: Int, name: String) = "${config.promptsPath}/shop$shopId-$name"

    /** Generates the static prompts shared by all shops. */
    fun generateSharedPrompts() {
        SHARED_PROMPTS.forEach { (name, text) -> tts(name, text) }
    }

    /** (Re)generates the per-shop prompts from the shop's voice config texts. */
    fun generateShopPrompts(shopId: Int, voice: ShopVoiceConfig) {
        tts("shop$shopId-welcome-open", voice.welcomeOpenMessage)
        tts("shop$shopId-welcome-closed", voice.welcomeClosedMessage)
        tts("shop$shopId-menu-open", MENU_OPEN_TEXT)
        tts("shop$shopId-menu-closed", MENU_CLOSED_TEXT)
    }

    /**
     * Text → {promptsPath}/{baseName}.wav (8 kHz mono when sox is available).
     * @return true when the file was (re)written.
     */
    private fun tts(baseName: String, text: String): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false
        return try {
            val dir = Paths.get(config.promptsPath)
            Files.createDirectories(dir)
            val rawWav = Files.createTempFile(dir, baseName, ".raw.wav").toFile()
            val target = File(config.promptsPath, "$baseName.wav")
            try {
                run("pico2wave", "-l", config.ttsLang, "-w", rawWav.absolutePath, body)

                // 8 kHz mono for playback without transcoding; fall back to the raw 16 kHz wav.
                val downsampled = Files.createTempFile(dir, baseName, ".8k.wav").toFile()
                val soxOk = runCatching {
                    run("sox", rawWav.absolutePath, "-r", "8000", "-c", "1", downsampled.absolutePath)
                }.isSuccess
                val source = if (soxOk) downsampled else rawWav

                makeWorldReadable(source.toPath())
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                if (!soxOk) println("[Prompts] sox unavailable — wrote $baseName.wav at 16 kHz (Asterisk will transcode)")
                Files.deleteIfExists(downsampled.toPath())
                true
            } finally {
                Files.deleteIfExists(rawWav.toPath())
            }
        } catch (e: Exception) {
            println("[Prompts] TTS failed for '$baseName' (${e.message}) — prompt file not updated")
            false
        }
    }

    private fun run(vararg cmd: String) {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw RuntimeException("${cmd[0]} timed out")
        }
        if (proc.exitValue() != 0) {
            val out = proc.inputStream.bufferedReader().readText().take(200)
            throw RuntimeException("${cmd[0]} exited ${proc.exitValue()}: $out")
        }
    }
}
