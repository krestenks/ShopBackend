import asterisk.AmiClient

/**
 * Auto-recovers a GSM modem that has wedged in a call state.
 *
 * Symptom (observed 2026-08-08 and -12): chan_quectel fails to complete a call hangup on the
 * modem (e.g. AT+CHLD lost under AT-port contention with SMS), so its call counter never clears
 * and the device sits in "Dialing" forever — every later call then fails with "device can not
 * make call" (cause 44). Nothing clears it but a modem restart.
 *
 * Detection: a modem in a call state ([AmiClient.CALL_STATES]) with NO matching Quectel/{trunk}
 * channel in Asterisk = orphaned. A real call has both, so this never touches live calls; a short
 * debounce also rides out normal call setup/teardown. On a persistent orphan it runs
 * `quectel restart now {trunk}` — turning a multi-hour silent outage into ~1 min of self-healing.
 * Set MODEM_WATCHDOG_ENABLED=false to disable.
 */
class ModemStuckWatchdog(
    private val amiClient: AmiClient,
    private val enabled: Boolean = System.getenv("MODEM_WATCHDOG_ENABLED")?.lowercase() != "false",
) {
    private companion object {
        const val CHECK_INTERVAL_MS = 60_000L
        const val ORPHAN_CHECKS = 2                 // consecutive detections before acting (~2 min)
        const val RESTART_COOLDOWN_MS = 3 * 60_000L // don't restart the same trunk more often than this
    }

    private val orphanStreak = HashMap<String, Int>()
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

        for ((trunk, state) in states) {
            val inCall = state.trim().lowercase() in AmiClient.CALL_STATES
            if (!inCall) { orphanStreak.remove(trunk); continue }

            val hasChannel = channels.any { it.contains("Quectel/$trunk-") }
            if (hasChannel) { orphanStreak.remove(trunk); continue }   // genuine live call

            // Orphaned: modem shows a call, Asterisk has no channel for it.
            val streak = (orphanStreak[trunk] ?: 0) + 1
            orphanStreak[trunk] = streak
            if (streak < ORPHAN_CHECKS) continue

            val now = System.currentTimeMillis()
            if (now - (lastRestartAt[trunk] ?: 0L) < RESTART_COOLDOWN_MS) continue
            lastRestartAt[trunk] = now
            orphanStreak.remove(trunk)

            println("[ModemWatchdog] $trunk stuck in '$state' with no Asterisk channel — restarting modem")
            val out = amiClient.command("quectel restart now $trunk").joinToString(" ").trim()
            println("[ModemWatchdog]   restart $trunk -> ${out.ifBlank { "scheduled" }}")
        }
    }
}
