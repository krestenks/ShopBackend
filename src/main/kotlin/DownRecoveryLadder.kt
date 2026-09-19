import kotlinx.coroutines.runBlocking

/** Everything the down-recovery ladder may DO. Production talks to Asterisk; tests record the calls. */
interface DownLadderActions {
    /** `quectel restart now {trunk}` — per-trunk; reopens the port and re-inits that modem only. */
    fun restartTrunk(trunk: String, attempt: Int, of: Int, state: String)

    /** Re-scan modems and move ONLY [downShopIds]. true = a path changed, false = nothing to change,
     *  null = the stage is unavailable. */
    suspend fun resync(trunk: String, downShopIds: Set<Int>): Boolean?

    /** Admin alert that [trunk] is down (diagnosed). */
    fun alertDown(trunk: String, state: String)
}

/**
 * The recovery ladder for a modem that is neither Free nor on a call (e.g. "Not initialized" /
 * "Not connected"): GSM calls to that shop are dead. Some of those states ARE restart-recoverable
 * (2026-08-26: shop2 "Not initialized", back on a restart; 2026-09-02: shop5 recovered on a single
 * restart ~33 h after the ladder first gave up; 2026-09-17: shop5 stuck at an orphaned SMS prompt,
 * which chan_quectel's pre-init ESC now clears on any restart), so we run a bounded ladder, alert
 * with a diagnosis, then KEEP RETRYING rather than giving up. One pass:
 *
 *  1. up to [MAX_DOWN_RESTARTS] `quectel restart now {trunk}`, spaced by [RESTART_COOLDOWN_MS]
 *     (per-trunk: touches no other shop, and this trunk has no call by definition);
 *  2. one device-path resync limited to trunks confirmed down (a path change reloads chan_quectel,
 *     so it is skipped for the pass while any GSM call is live);
 *  3. pass done → admin alert, then the pass re-runs every [DOWN_RETRY_LADDER_MS] and admin is
 *     re-nagged every [RENAG_INTERVAL_MS] until the trunk is usable again.
 *
 * TRIGGER: trunk not Free and not on a call for [DOWN_CHECKS] consecutive checks.
 * EVIDENCE: WEAK (polled state), which is why every rung is per-trunk or guarded.
 * BLAST: one trunk (restart); a resync can reload chan_quectel, which restarts only trunks whose
 *        config changed — and it may only change confirmed-down trunks.
 * GUARD: no destructive rung while the channel listing is unusable; resync never while a call is live.
 * IF WRONG: a working modem is restarted (~15 s without GSM on that shop, no call up by definition).
 *
 * There is deliberately no `module reload chan_quectel.so` rung. With an unchanged config it restarts
 * no device (chan_quectel's pvt_reconfigure acts only on a config/state change), so it could not
 * help a down trunk; it only delayed the alert by 90 s and logged "reloaded" (2026-09-19 review).
 * A live call elsewhere used to hold back the pass (and so the first alert) until a quiet minute on
 * every shop; now it only skips the resync for that pass.
 */
class DownRecoveryLadder(
    private val actions: DownLadderActions,
    private val clock: () -> Long = System::currentTimeMillis,
    /** false = alert only, no restarts/resync (MODEM_DOWN_AUTORECOVER=false). */
    private val autoRecover: Boolean = true,
) {
    companion object {
        const val DOWN_CHECKS = 4                        // consecutive not-usable checks before we act (~4 min)
        const val RESTART_COOLDOWN_MS = 3 * 60_000L      // don't restart the same trunk more often than this
        const val MAX_DOWN_RESTARTS = 2                  // per-trunk restarts per pass
        const val DOWN_RETRY_LADDER_MS = 30 * 60_000L    // re-run the whole pass at most this often (never give up)
        const val RENAG_INTERVAL_MS = 2 * 60 * 60_000L   // re-alert admin about a still-down modem at most this often
    }

    private val downStreak = HashMap<String, Int>()
    private val restartAttempts = HashMap<String, Int>()
    private val lastRestartAt = HashMap<String, Long>()
    private val passDoneAt = HashMap<String, Long>()     // pass finished; re-run after DOWN_RETRY_LADDER_MS
    private val lastNagAt = HashMap<String, Long>()

    /** The trunk is Free or on a call: the episode is over, the next outage starts a fresh ladder. */
    fun onHealthy(trunk: String) {
        downStreak.remove(trunk)
        restartAttempts.remove(trunk); lastRestartAt.remove(trunk)
        passDoneAt.remove(trunk); lastNagAt.remove(trunk)
    }

    /** One observation of [trunk] neither Free nor on a call. */
    fun onDown(trunk: String, state: String, anyQuectelCall: Boolean, channelsUsable: Boolean) {
        val streak = (downStreak[trunk] ?: 0) + 1
        downStreak[trunk] = streak
        if (streak < DOWN_CHECKS) return          // debounce: ride out normal init/teardown first
        val now = clock()

        if (autoRecover && channelsUsable) {
            passDoneAt[trunk]?.let {
                if (now - it >= DOWN_RETRY_LADDER_MS) {
                    restartAttempts.remove(trunk); lastRestartAt.remove(trunk); passDoneAt.remove(trunk)
                }
            }
            if (passDoneAt[trunk] == null) {
                // Stage 1 — bounded per-trunk restarts.
                val attempts = restartAttempts[trunk] ?: 0
                if (attempts < MAX_DOWN_RESTARTS) {
                    if (now - (lastRestartAt[trunk] ?: 0L) < RESTART_COOLDOWN_MS) return
                    lastRestartAt[trunk] = now
                    restartAttempts[trunk] = attempts + 1
                    actions.restartTrunk(trunk, attempts + 1, MAX_DOWN_RESTARTS, state)
                    return
                }
                // Give the last restart the same cooldown before judging it failed (the removed reload
                // rung used to supply ~2.5 min here; without it a slow re-init got an alert at +1 min).
                if (now - (lastRestartAt[trunk] ?: 0L) < RESTART_COOLDOWN_MS) return
                // Stage 2 — the device path may be stale after a USB re-enumeration. Move ONLY
                // confirmed-down trunks: a working trunk is proof its config is right.
                if (anyQuectelCall) {
                    println("[Recovery] SUPPRESSED device resync target=$trunk reason=a GSM call is live " +
                        "(a path change reloads chan_quectel) bound=retried next pass")
                } else {
                    val downShops = downStreak.filterValues { it >= DOWN_CHECKS }.keys.mapNotNull(::shopIdOf).toSet()
                    val changed = runCatching { runBlocking { actions.resync(trunk, downShops) } }
                        .onFailure { println("[ModemWatchdog] device resync failed: ${it.message}") }
                        .getOrNull()
                    if (changed == true) {
                        println("[ModemWatchdog] resynced device paths for down shop(s) $downShops after failed restarts of $trunk")
                        passDoneAt[trunk] = now   // give the rewrite time to re-init; alert next check if still down
                        return
                    }
                }
                passDoneAt[trunk] = now
            }
        }

        // Pass done (or auto-recovery off / listing unusable): alert admin, re-nag on a slow cadence.
        val lastNag = lastNagAt[trunk]
        if (lastNag != null && now - lastNag < RENAG_INTERVAL_MS) return
        lastNagAt[trunk] = now
        actions.alertDown(trunk, state)
    }

    private fun shopIdOf(trunk: String): Int? = trunk.removePrefix("shop").toIntOrNull()
}
