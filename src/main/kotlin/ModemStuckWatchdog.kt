import asterisk.RecoveryAudit
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
 * ([DownRecoveryLadder]: per-trunk restarts → device resync); if that doesn't clear it, admin is alerted with a diagnosis
 * (modem/voice-init failure — restart-recoverable — vs a genuine SIM/signal fault) and the ladder
 * is RETRIED every DOWN_RETRY_LADDER_MS — it never gives up permanently, since a modem stuck
 * "Not initialized" is often restart-recoverable a while later (observed 2026-09-02: shop5 sat down
 * ~33 h after the ladder gave up, yet a single restart cleared it) — with a re-nag to admin every
 * RENAG_INTERVAL_MS while it stays down. Set MODEM_WATCHDOG_ENABLED=false to disable.
 */
class ModemStuckWatchdog(
    private val amiClient: AmiClient,
    private val alerter: ReliabilityAlerter,
    /** Re-scan modems by IMSI and move ONLY the given confirmed-down shops when restarts can't
     *  recover them — fixes a stale device path after a USB re-enumeration (the 2026-09-05 outage)
     *  without ever rewriting a working trunk (2026-09-12). Returns true if a path actually changed.
     *  Null disables the stage. */
    private val resyncDevices: (suspend (downShopIds: Set<Int>) -> Boolean)? = null,
    private val enabled: Boolean = System.getenv("MODEM_WATCHDOG_ENABLED")?.lowercase() != "false",
) {
    private companion object {
        const val CHECK_INTERVAL_MS = 60_000L
        const val ORPHAN_CHECKS = 2                 // consecutive orphan detections before restart (~2 min)
        const val RESTART_COOLDOWN_MS = 3 * 60_000L // don't restart the same trunk more often than this
    }

    /** Kill switch for the down-modem AUTO-RECOVERY ladder (restart→resync). The orphan-in-call
     *  restart and the down alerting stay on regardless. Set MODEM_DOWN_AUTORECOVER=false to disable. */
    private val downAutoRecover = System.getenv("MODEM_DOWN_AUTORECOVER")?.lowercase() != "false"

    private val orphanStreak = HashMap<String, Int>()
    private val lastRestartAt = HashMap<String, Long>()
    private val downLadder = DownRecoveryLadder(AsteriskLadderActions(), autoRecover = downAutoRecover)

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
        // commandOrNull, not command(): the latter hands back an error string on a failed query,
        // which contains no "Quectel/" and therefore reads as "nothing is on a call" — turning an
        // AMI hiccup into a restart of every busy modem (audit finding 6). No listing, no decisions.
        // We need the channel listing to tell an orphaned modem from a busy one. When we cannot get
        // a usable one, suspend the DESTRUCTIVE branches only — detection, streak accounting and the
        // admin alert must keep running, or a genuinely dead modem goes silent for as long as the
        // AMI query is broken (and if the AMI user lacks the command class, that is forever).
        // Weak evidence may still justify alerting; it just may not justify destroying anything.
        val channels = amiClient.commandOrNull("core show channels")
        val channelsUsable = channels != null && channels.any { AmiClient.ACTIVE_CALLS_RE.containsMatchIn(it) }
        if (!channelsUsable) {
            println("[ModemWatchdog] channel listing unavailable/incomplete — " +
                "alerting still active, automatic restarts/resync suspended this pass")
        }
        // A device resync rewrites + reloads chan_quectel, which restarts every trunk whose config
        // changed and would drop a call on it, so the ladder never resyncs while any Quectel channel is up.
        val anyQuectelCall = !channelsUsable || channels!!.any { it.contains("Quectel/") }

        for ((trunk, rawState) in states) {
            val state = rawState.trim()
            when {
                state.lowercase() in AmiClient.CALL_STATES -> {
                    downLadder.onHealthy(trunk)
                    if (!channelsUsable) { orphanStreak.remove(trunk); continue }
                    if (channels!!.any { it.contains("Quectel/$trunk-") }) { orphanStreak.remove(trunk); continue }
                    handleOrphan(trunk, state)
                }
                state.equals("Free", ignoreCase = true) -> {
                    orphanStreak.remove(trunk); downLadder.onHealthy(trunk)
                }
                else -> {  // not Free, not in a call → modem down / unusable
                    orphanStreak.remove(trunk)
                    downLadder.onDown(trunk, state, anyQuectelCall, channelsUsable)
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

        // WEAK: "no matching channel in a listing we may not have received" — the guard reads the
        // same fail-open channel list as everything else (audit finding 6), so announce it.
        RecoveryAudit.fired(
            action = "quectel restart now",
            target = trunk,
            evidence = "state='$state' with no Quectel/$trunk channel for $ORPHAN_CHECKS checks",
            strength = RecoveryAudit.Evidence.WEAK,
            blast = RecoveryAudit.Blast.TRUNK,
            ami = amiClient, alerter = alerter, shopId = shopIdOf(trunk),
        )

        val out = amiClient.command("quectel restart now $trunk").joinToString(" ").trim()
        println("[ModemWatchdog] restart $trunk -> ${out.ifBlank { "scheduled" }}")
        alerter.record(
            "warn", "modem_restart", shopIdOf(trunk),
            "Modem '$trunk' was stuck in '$state' with no active call — auto-restarted (calls were failing)",
            alertAdmin = true,
        )
    }

    /** What the down ladder does in production: AMI + audit + reliability events. */
    private inner class AsteriskLadderActions : DownLadderActions {
        override fun restartTrunk(trunk: String, attempt: Int, of: Int, state: String) {
            RecoveryAudit.fired(
                action = "quectel restart now (down-recover $attempt/$of)",
                target = trunk,
                evidence = "state='$state' not Free and no call for ${DownRecoveryLadder.DOWN_CHECKS} checks",
                strength = RecoveryAudit.Evidence.WEAK,
                blast = RecoveryAudit.Blast.TRUNK,
                ami = amiClient, alerter = alerter, shopId = shopIdOf(trunk),
            )
            val out = amiClient.command("quectel restart now $trunk").joinToString(" ").trim()
            println("[ModemWatchdog] down-recover restart $trunk (attempt $attempt/$of) -> ${out.ifBlank { "scheduled" }}")
            alerter.record(
                "warn", "modem_restart", shopIdOf(trunk),
                "Modem '$trunk' was down (state '$state') — auto-restarted (attempt $attempt/$of)",
                alertAdmin = false,   // don't SMS on each recovery attempt; only the pass-done alert + re-nags
            )
        }

        override suspend fun resync(trunk: String, downShopIds: Set<Int>): Boolean? {
            val resync = resyncDevices ?: return null
            val changed = resync(downShopIds)
            if (changed) {
                alerter.record(
                    "warn", "modem_resync", shopIdOf(trunk),
                    "Modem '$trunk' was down after restarts — re-scanned modems and moved the down trunk(s) " +
                        "$downShopIds to their current device paths (stale path, likely a USB re-enumeration)",
                    alertAdmin = false,
                )
            }
            return changed
        }

        override fun alertDown(trunk: String, state: String) {
            alerter.record("error", "modem_down", shopIdOf(trunk), diagnoseDown(trunk, state), alertAdmin = true)
        }
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
