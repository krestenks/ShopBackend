import asterisk.AmiClient
import asterisk.AsteriskConfig

/**
 * Watches whether each shop can actually receive calls and — because the system has no
 * push-wake — texts the managers on their normal phone numbers when it can't. This turns a
 * *silent* loss (a manager's app/SIP quietly dropped) into a loud, actionable alert.
 *
 * "Unconnected" = a shop has at least one ON-DUTY manager (someone declared they're taking
 * calls) but NONE of those managers' SIP endpoints are reachable on Asterisk (all Unavailable).
 * That is exactly the dangerous case: a customer calling now would hit "line busy" even though
 * someone is supposed to answer. Off-duty managers de-register on purpose, so they're excluded;
 * a shop with nobody on duty is never alerted (no coverage is expected then).
 *
 * The alert SMS is sent FROM the shop's own SIM to every manager associated with the shop who
 * has a phone number on file — so it reaches their real phone even though the app is down.
 *
 * Guards against noise/cost: skips entirely when AMI is down (inconclusive), requires the
 * outage to persist [DEBOUNCE_CHECKS] consecutive polls before the first alert, and re-alerts
 * at most once per [RENOTIFY_COOLDOWN_MS] while a shop stays down. Set SIP_MONITOR_ENABLED=false
 * to disable.
 */
class SipReachabilityMonitor(
    private val db: DataBase,
    private val config: AsteriskConfig,
    private val amiClient: AmiClient,
    private val enabled: Boolean = System.getenv("SIP_MONITOR_ENABLED")?.lowercase() != "false",
) {
    private companion object {
        const val CHECK_INTERVAL_MS = 120_000L        // poll every 2 min
        const val DEBOUNCE_CHECKS = 2                 // must be down this many consecutive polls (~4 min)
        const val RENOTIFY_COOLDOWN_MS = 30 * 60_000L // while still down, re-alert at most this often
    }

    private val downStreak = HashMap<Int, Int>()      // shopId → consecutive down polls
    private val lastAlertAt = HashMap<Int, Long>()    // shopId → last alert (epoch ms)

    fun start() {
        if (!enabled) {
            println("[SipMonitor] disabled (SIP_MONITOR_ENABLED=false)")
            return
        }
        Thread({
            while (!amiClient.connected) Thread.sleep(5_000)
            println("[SipMonitor] started (interval=${CHECK_INTERVAL_MS / 1000}s)")
            while (true) {
                try {
                    runCheck()
                } catch (e: Exception) {
                    println("[SipMonitor] check failed: ${e.message}")
                }
                Thread.sleep(CHECK_INTERVAL_MS)
            }
        }, "sip-reachability-monitor").apply { isDaemon = true }.start()
    }

    private fun runCheck() {
        // AMI down → we can't tell who's reachable; skip rather than false-alarm every shop.
        if (!amiClient.connected) return
        val reachableAors = amiClient.pjsipReachableAors()

        for (shop in db.getAllShops()) {
            // Only shops with a SIM can (a) receive GSM calls and (b) send the alert SMS.
            val hasSim = runCatching { !db.getShopTelephonyConfig(shop.id).modemDataDevice.isNullOrBlank() }
                .getOrDefault(false)
            if (!hasSim) { clear(shop.id); continue }

            val onDuty = db.getOnDutyManagerIdsForShop(shop.id)
            if (onDuty.isEmpty()) { clear(shop.id); continue }   // nobody claims coverage → not alertable

            val anyReachable = onDuty.any { reachableAors.contains(config.managerEndpointId(it)) }
            if (anyReachable) { clear(shop.id); continue }        // at least one on-duty manager is up

            // Unconnected: on-duty managers exist, none reachable. Debounce, then alert with cooldown.
            val streak = (downStreak[shop.id] ?: 0) + 1
            downStreak[shop.id] = streak
            if (streak < DEBOUNCE_CHECKS) continue

            val now = System.currentTimeMillis()
            if (now - (lastAlertAt[shop.id] ?: 0L) < RENOTIFY_COOLDOWN_MS) continue
            lastAlertAt[shop.id] = now
            alert(shop)
        }
    }

    /** Shop recovered (or isn't alertable) — forget its state so the next outage alerts promptly. */
    private fun clear(shopId: Int) {
        downStreak.remove(shopId)
        lastAlertAt.remove(shopId)
    }

    private fun alert(shop: Shop) {
        val recipients = db.getManagerIdsForShop(shop.id)
            .mapNotNull { db.getManagerById(it) }
            .filter { !it.phone.isNullOrBlank() }
            .distinctBy { it.phone!!.trim() }
        if (recipients.isEmpty()) {
            println("[SipMonitor] ${shop.name} unconnected, but no associated manager has a phone number on file")
            return
        }
        // Plain ASCII only — GSM-7 keeps it to one SMS segment and avoids modem UCS-2 quirks.
        val msg = "ALERT: shop '${shop.name}' has no connected phone line right now - an on-duty " +
            "manager is not reachable. Please open the ShopManager app on your phone to reconnect."
        val trunk = config.trunkName(shop.id)
        println("[SipMonitor] ${shop.name} UNCONNECTED — texting ${recipients.size} manager(s) from $trunk")
        for (m in recipients) {
            val res = amiClient.sendSms(trunk, m.phone!!.trim(), msg)
            println("[SipMonitor]   → ${m.name} <${m.phone}>: ${if (res.success) "sent" else "FAILED (${res.detail})"}")
        }
    }
}
