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
 * Down: a modem that is neither Free nor on a call (e.g. "Not initialized", "Not connected") for
 * several minutes means GSM calls to that shop are dead. Auto-recovery runs a bounded ladder
 * (restarts → chan_quectel reload); if that doesn't clear it, admin is alerted with a diagnosis
 * (modem/voice-init failure — restart-recoverable — vs a genuine SIM/signal fault) and the ladder
 * is RETRIED every DOWN_RETRY_LADDER_MS — it never gives up permanently, since a modem stuck
 * "Not initialized" is often restart-recoverable a while later (observed 2026-09-02: shop5 sat down
 * ~33 h after the ladder gave up, yet a single restart cleared it) — with a re-nag to admin every
 * RENAG_INTERVAL_MS while it stays down. Set MODEM_WATCHDOG_ENABLED=false to disable.
 */
class ModemStuckWatchdog(
    private val amiClient: AmiClient,
    private val alerter: ReliabilityAlerter,
    private val enabled: Boolean = System.getenv("MODEM_WATCHDOG_ENABLED")?.lowercase() != "false",
) {
    private companion object {
        const val CHECK_INTERVAL_MS = 60_000L
        const val ORPHAN_CHECKS = 2                 // consecutive orphan detections before restart (~2 min)
        const val DOWN_CHECKS = 4                   // consecutive not-usable checks before we act (~4 min)
        const val RESTART_COOLDOWN_MS = 3 * 60_000L // don't restart the same trunk more often than this
        const val MAX_DOWN_RESTARTS = 2             // per-trunk `quectel restart` attempts before escalating
        const val CHAN_RELOAD_COOLDOWN_MS = 30 * 60_000L // global chan_quectel reload, at most this often
        const val RELOAD_GRACE_MS = 90_000L         // let a chan_quectel reload finish re-init before giving up
        const val DOWN_RETRY_LADDER_MS = 30 * 60_000L    // after the ladder is exhausted, re-run the whole ladder at most this often (never give up permanently)
        const val RENAG_INTERVAL_MS = 2 * 60 * 60_000L   // re-alert admin about a still-down modem at most this often
    }

    /** Kill switch for the down-modem AUTO-RECOVERY ladder (restart→reload). The orphan-in-call
     *  restart and the down alerting stay on regardless. Set MODEM_DOWN_AUTORECOVER=false to disable. */
    private val downAutoRecover = System.getenv("MODEM_DOWN_AUTORECOVER")?.lowercase() != "false"

    private val orphanStreak = HashMap<String, Int>()
    private val downStreak = HashMap<String, Int>()
    private val lastRestartAt = HashMap<String, Long>()
    // Down-recovery ladder state.
    private val downRestartAttempts = HashMap<String, Int>()
    private val lastDownRestartAt = HashMap<String, Long>()
    @Volatile private var lastChanReloadAt = 0L
    // Set when a ladder pass (restarts + reload) has exhausted for a trunk; the pass is retried
    // after DOWN_RETRY_LADDER_MS rather than giving up forever. lastNagAt gates the periodic re-alert.
    private val ladderExhaustedAt = HashMap<String, Long>()
    private val lastNagAt = HashMap<String, Long>()

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
        // A chan_quectel reload re-inits ALL trunks and would drop any live GSM call, so the
        // down-recovery ladder must never reload while any Quectel channel is up.
        val anyQuectelCall = channels.any { it.contains("Quectel/") }

        for ((trunk, rawState) in states) {
            val state = rawState.trim()
            when {
                state.lowercase() in AmiClient.CALL_STATES -> {
                    clearDownState(trunk)
                    if (channels.any { it.contains("Quectel/$trunk-") }) { orphanStreak.remove(trunk); continue }
                    handleOrphan(trunk, state)
                }
                state.equals("Free", ignoreCase = true) -> {
                    orphanStreak.remove(trunk); clearDownState(trunk)
                }
                else -> {  // not Free, not in a call → modem down / unusable
                    orphanStreak.remove(trunk)
                    handleDown(trunk, state, anyQuectelCall)
                }
            }
        }
    }

    /** Reset all down/recovery bookkeeping for a trunk that is healthy (Free) or on a call. */
    private fun clearDownState(trunk: String) {
        downStreak.remove(trunk)
        downRestartAttempts.remove(trunk); lastDownRestartAt.remove(trunk)
        ladderExhaustedAt.remove(trunk); lastNagAt.remove(trunk)
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

    /**
     * A modem that is neither Free nor on a call (e.g. "Not initialized" / "Not connected") means GSM
     * calls to that shop are dead. Some of those states ARE restart-recoverable (observed 2026-08-26:
     * shop2 sat "Not initialized" and a `quectel restart` + chan_quectel reload brought it back; and
     * 2026-09-02: shop5 recovered on a single restart ~33 h after the ladder first gave up), so we run
     * a bounded recovery ladder, alert with a diagnosis, and then KEEP RETRYING rather than giving up:
     *   1. up to [MAX_DOWN_RESTARTS] `quectel restart now {trunk}`, spaced by [RESTART_COOLDOWN_MS];
     *   2. still down → one `module reload chan_quectel.so` per [CHAN_RELOAD_COOLDOWN_MS] (skipped
     *      while any GSM call is live, since a reload drops all trunks);
     *   3. still down after that → record `modem_down` + admin SMS (diagnosed: init-failure vs
     *      SIM/signal), then re-run the whole ladder every [DOWN_RETRY_LADDER_MS] and re-nag admin
     *      every [RENAG_INTERVAL_MS] until the modem is usable again.
     */
    private fun handleDown(trunk: String, state: String, anyQuectelCall: Boolean) {
        val streak = (downStreak[trunk] ?: 0) + 1
        downStreak[trunk] = streak
        if (streak < DOWN_CHECKS) return          // debounce: ride out normal init/teardown first
        val now = System.currentTimeMillis()

        if (downAutoRecover) {
            // If a previous ladder pass gave up, retry the WHOLE ladder after a cooldown instead of
            // giving up forever — the modem may have become restart-recoverable in the meantime.
            ladderExhaustedAt[trunk]?.let {
                if (now - it >= DOWN_RETRY_LADDER_MS) {
                    downRestartAttempts.remove(trunk); lastDownRestartAt.remove(trunk); ladderExhaustedAt.remove(trunk)
                }
            }

            if (ladderExhaustedAt[trunk] == null) {
                // Stage 1 — bounded per-trunk restarts.
                val attempts = downRestartAttempts[trunk] ?: 0
                if (attempts < MAX_DOWN_RESTARTS) {
                    if (now - (lastDownRestartAt[trunk] ?: 0L) < RESTART_COOLDOWN_MS) return
                    lastDownRestartAt[trunk] = now
                    downRestartAttempts[trunk] = attempts + 1
                    val out = amiClient.command("quectel restart now $trunk").joinToString(" ").trim()
                    println("[ModemWatchdog] down-recover restart $trunk (attempt ${attempts + 1}/$MAX_DOWN_RESTARTS) -> ${out.ifBlank { "scheduled" }}")
                    alerter.record(
                        "warn", "modem_restart", shopIdOf(trunk),
                        "Modem '$trunk' was down (state '$state') — auto-restarted (attempt ${attempts + 1}/$MAX_DOWN_RESTARTS)",
                        alertAdmin = false,   // don't SMS on each recovery attempt; only the give-up + re-nags alert
                    )
                    return
                }
                // Stage 2 — restarts didn't help: one global chan_quectel reload (never during a live call).
                if (!anyQuectelCall && now - lastChanReloadAt >= CHAN_RELOAD_COOLDOWN_MS) {
                    lastChanReloadAt = now
                    runCatching { amiClient.reloadChanQuectel() }
                        .onFailure { println("[ModemWatchdog] chan_quectel reload failed: ${it.message}") }
                    println("[ModemWatchdog] reloaded chan_quectel after $MAX_DOWN_RESTARTS failed restarts of $trunk")
                    alerter.record(
                        "warn", "chan_reload", shopIdOf(trunk),
                        "Modem '$trunk' still down after $MAX_DOWN_RESTARTS restarts — reloaded chan_quectel",
                        alertAdmin = false,
                    )
                    return
                }
                if (anyQuectelCall) return   // defer the reload until no call is live; re-evaluate next tick
                // Reload fired but the modem hasn't come back yet — give it time to re-init before alerting.
                if (now - lastChanReloadAt < RELOAD_GRACE_MS) return
                ladderExhaustedAt[trunk] = now   // pass done; retried after DOWN_RETRY_LADDER_MS (never permanent)
            }
        }

        // Ladder exhausted (or auto-recovery disabled): alert admin, then re-nag on a slow cadence
        // while it stays down. Never permanently silent — the ladder above keeps retrying in parallel.
        val lastNag = lastNagAt[trunk]
        if (lastNag != null && now - lastNag < RENAG_INTERVAL_MS) return
        lastNagAt[trunk] = now
        alerter.record("error", "modem_down", shopIdOf(trunk), diagnoseDown(trunk, state), alertAdmin = true)
    }

    /**
     * Actionable modem-down message. Reads the detailed device state to distinguish a modem/voice
     * INIT failure (radio registered + Voice != Yes → a restart usually fixes it) from a genuine
     * SIM/signal fault (not registered → a restart won't help), instead of always blaming SIM/signal.
     */
    private fun diagnoseDown(trunk: String, fallbackState: String): String {
        val d = amiClient.quectelDeviceState(trunk)
        val fullState = d["State"]?.takeIf { it.isNotBlank() } ?: fallbackState
        val registered = d["GSM Registration Status"]?.contains("Registered", ignoreCase = true) == true
        val voiceUp = d["Voice"].equals("Yes", ignoreCase = true)
        val rssi = d["RSSI"]?.takeIf { it.isNotBlank() } ?: "?"
        val cause = when {
            registered && !voiceUp ->
                "radio is fine (registered, RSSI $rssi) but voice/modem init failed — usually clears on a restart; auto-recovery keeps retrying"
            !registered ->
                "not registered to the network (RSSI $rssi) — likely a SIM or signal fault a restart can't fix"
            else ->
                "modem not usable (RSSI $rssi)"
        }
        return "Modem '$trunk' down (state '$fullState'): $cause. GSM calls to this shop are affected."
    }

    private fun shopIdOf(trunk: String): Int? = trunk.removePrefix("shop").toIntOrNull()
}
