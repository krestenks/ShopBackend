import asterisk.AmiClient
import asterisk.ReliabilityAlerter

/**
 * Auto-recovers a GSM modem wedged in a call state, and flags modems that go down.
 *
 * Stuck (observed 2026-08-08 and -12): chan_quectel fails to complete a call hangup on the modem
 * (e.g. AT+CHLD lost under AT-port contention with SMS), so its call counter never clears and the
 * device sits in "Dialing" forever — every later call then fails with "device can not make call"
 * (cause 44). Detection: modem in a call state ([AmiClient.CALL_STATES]) with NO matching
 * Quectel/{trunk} channel in Asterisk = orphaned. A real call has both, so live calls are never
 * touched; a short debounce rides out normal setup/teardown. On a persistent orphan it runs
 * `quectel restart now {trunk}` and records a [ReliabilityAlerter] event (admin SMS + admin log).
 *
 * Down: a modem that is neither Free nor on a call (e.g. "Not connected") for several minutes means
 * GSM calls to that shop are dead — recorded + admin-alerted once per outage (not restarted, since a
 * restart won't fix a SIM/signal fault). Set MODEM_WATCHDOG_ENABLED=false to disable.
 */
class ModemStuckWatchdog(
    private val amiClient: AmiClient,
    private val alerter: ReliabilityAlerter,
    private val enabled: Boolean = System.getenv("MODEM_WATCHDOG_ENABLED")?.lowercase() != "false",
) {
    private companion object {
        const val CHECK_INTERVAL_MS = 60_000L
        const val ORPHAN_CHECKS = 2                 // consecutive orphan detections before restart (~2 min)
        const val DOWN_CHECKS = 4                   // consecutive not-usable checks before "down" (~4 min)
        const val RESTART_COOLDOWN_MS = 3 * 60_000L // don't restart the same trunk more often than this
    }

    private val orphanStreak = HashMap<String, Int>()
    private val downStreak = HashMap<String, Int>()
    private val downAlerted = HashSet<String>()
    private val lastRestartAt = HashMap<String, Long>()

    fun start() {
        if (!enabled) {
            println("[ModemWatchdog] disabled (MODEM_WATCHDOG_ENABLED=false)")
            return
        }
        Thread({
            while (!amiClient.connected) Thread.sleep(5_000)
            println("[ModemWatchdog] started (interval=${CHECK_INTERVAL_MS / 1000}s)")
            while (true) {
                try {
                    runCheck()
                } catch (e: Exception) {
                    println("[ModemWatchdog] check failed: ${e.message}")
                }
                Thread.sleep(CHECK_INTERVAL_MS)
            }
        }, "modem-stuck-watchdog").apply { isDaemon = true }.start()
    }

    private fun runCheck() {
        if (!amiClient.connected) return
        val states = amiClient.quectelDeviceStates()
        if (states.isEmpty()) return
        val channels = amiClient.command("core show channels")

        for ((trunk, rawState) in states) {
            val state = rawState.trim()
            when {
                state.lowercase() in AmiClient.CALL_STATES -> {
                    downStreak.remove(trunk); downAlerted.remove(trunk)
                    if (channels.any { it.contains("Quectel/$trunk-") }) { orphanStreak.remove(trunk); continue }
                    handleOrphan(trunk, state)
                }
                state.equals("Free", ignoreCase = true) -> {
                    orphanStreak.remove(trunk); downStreak.remove(trunk); downAlerted.remove(trunk)
                }
                else -> {  // not Free, not in a call → modem down / unusable
                    orphanStreak.remove(trunk)
                    handleDown(trunk, state)
                }
            }
        }
    }

    private fun handleOrphan(trunk: String, state: String) {
        val streak = (orphanStreak[trunk] ?: 0) + 1
        orphanStreak[trunk] = streak
        if (streak < ORPHAN_CHECKS) return
        val now = System.currentTimeMillis()
        if (now - (lastRestartAt[trunk] ?: 0L) < RESTART_COOLDOWN_MS) return
        lastRestartAt[trunk] = now
        orphanStreak.remove(trunk)

        val out = amiClient.command("quectel restart now $trunk").joinToString(" ").trim()
        println("[ModemWatchdog] restart $trunk -> ${out.ifBlank { "scheduled" }}")
        alerter.record(
            "warn", "modem_restart", shopIdOf(trunk),
            "Modem '$trunk' was stuck in '$state' with no active call — auto-restarted (calls were failing)",
            alertAdmin = true,
        )
    }

    private fun handleDown(trunk: String, state: String) {
        val streak = (downStreak[trunk] ?: 0) + 1
        downStreak[trunk] = streak
        if (streak < DOWN_CHECKS || trunk in downAlerted) return
        downAlerted.add(trunk)
        alerter.record(
            "error", "modem_down", shopIdOf(trunk),
            "Modem '$trunk' is not usable (state '$state') — GSM calls to this shop are down",
            alertAdmin = true,
        )
    }

    private fun shopIdOf(trunk: String): Int? = trunk.removePrefix("shop").toIntOrNull()
}
