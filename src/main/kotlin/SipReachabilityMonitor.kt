import asterisk.AmiClient
import asterisk.AsteriskConfig
import asterisk.ReliabilityAlerter
import asterisk.SmsQueue

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
 * Guards against noise/cost: skips entirely when AMI is down (inconclusive) and requires the
 * outage to persist [DEBOUNCE_CHECKS] consecutive polls before alerting. There is no re-notify —
 * one SMS on the offline edge, one on the recovery edge, nothing in between however long it
 * lasts. Set SIP_MONITOR_ENABLED=false to disable.
 */
class SipReachabilityMonitor(
    private val db: DataBase,
    private val config: AsteriskConfig,
    private val amiClient: AmiClient,
    private val smsQueue: SmsQueue,
    private val alerter: ReliabilityAlerter,
    private val enabled: Boolean = System.getenv("SIP_MONITOR_ENABLED")?.lowercase() != "false",
) {
    private companion object {
        const val CHECK_INTERVAL_MS = 120_000L        // poll every 2 min
        const val DEBOUNCE_CHECKS = 5                 // must be down this many consecutive polls (~10 min)
    }

    /** Edge-detection per MANAGER endpoint, not per shop. See [ManagerReachabilityTracker]. */
    private val tracker = ManagerReachabilityTracker(
        downDebounceChecks = DEBOUNCE_CHECKS,
    )

    fun start() {
        if (!enabled) {
            println("[SipMonitor] disabled (SIP_MONITOR_ENABLED=false)")
            return
        }
        Thread({
            while (!amiClient.connected) Thread.sleep(5_000)
            println("[SipMonitor] started (interval=${CHECK_INTERVAL_MS / 1000}s; admin alert phone set in web admin)")
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
        // AMI down → we can't tell who's reachable. Skip entirely rather than false-alarm: an
        // inconclusive read must not authorise an alert, and the tracker is never fed a verdict
        // we don't have.
        if (!amiClient.connected) return
        val reachableAors = amiClient.pjsipReachableAors()
        val now = System.currentTimeMillis()

        // Shops that can both receive GSM calls and send an alert SMS.
        val shopsWithSim = db.getAllShops().filter { shop ->
            runCatching { !db.getShopTelephonyConfig(shop.id).modemDataDevice.isNullOrBlank() }
                .getOrDefault(false)
        }

        // Which on-duty managers cover which shops. Only on-duty managers are tracked, which is
        // what keeps the spare handsets out of this: being off duty is how the fleet declares that
        // a phone is not expected to be ringable.
        val coverage = HashMap<Int, MutableList<Shop>>()
        for (shop in shopsWithSim) {
            for (mgrId in db.getOnDutyManagerIdsForShop(shop.id)) {
                coverage.getOrPut(mgrId) { mutableListOf() } += shop
            }
        }

        // Forget managers who went off duty, so a later outage isn't held back by a stale guard.
        for (stale in tracker.tracked() - coverage.keys) tracker.forget(stale)

        for ((mgrId, shops) in coverage) {
            val reachable = reachableAors.contains(config.managerEndpointId(mgrId))
            when (val action = tracker.observe(mgrId, reachable, now)) {
                is ReachabilityAction.None -> {}

                is ReachabilityAction.Suppressed ->
                    println("[SipMonitor] SUPPRESSED offline-alert mgr=$mgrId " +
                            "reason=${action.reason} ${action.detail}")

                is ReachabilityAction.AlertOffline -> alertOffline(mgrId, shops, action.downSince)

                is ReachabilityAction.AlertOnline -> alertOnline(mgrId, action.outageMs)
            }
        }
    }

    /** "HH:mm" in the shops' local time — short enough to keep the SMS to one GSM-7 segment. */
    private fun hhmm(ms: Long): String = java.time.Instant.ofEpochMilli(ms)
        .atZone(java.time.ZoneId.of("Europe/Copenhagen"))
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

    private fun humanDuration(ms: Long): String {
        val mins = ms / 60_000
        return if (mins < 60) "${mins}m" else "${mins / 60}h ${mins % 60}m"
    }

    /**
     * One alert on the offline edge: the admin, plus a single nudge to the manager whose phone it
     * is. The nudge is the only thing that actually fixes the outage — it arrives over GSM, so it
     * reaches the handset even though the app and the tailnet are down.
     */
    private fun alertOffline(managerId: Int, shops: List<Shop>, downSince: Long) {
        val manager = db.getManagerById(managerId)
        val who = manager?.name ?: "mgr$managerId"

        // The real blast radius: shops left with no reachable on-duty manager at all. With one
        // manager on duty that is all of them, but it stays correct if a second is ever added.
        val reachable = runCatching { amiClient.pjsipReachableAors() }.getOrDefault(emptySet())
        val stranded = shops.filter { shop ->
            db.getOnDutyManagerIdsForShop(shop.id)
                .none { reachable.contains(config.managerEndpointId(it)) }
        }
        val shopList = stranded.joinToString(", ") { it.name }.ifBlank { "none" }

        println("[SipMonitor] OFFLINE mgr=$managerId ($who) since ${hhmm(downSince)} " +
                "strandedShops=$shopList — one admin alert + one manager nudge")

        // Nudge the manager, once. Plain ASCII: GSM-7 keeps it to a single segment.
        val phone = manager?.phone?.trim()
        if (!phone.isNullOrBlank()) {
            val trunk = alerter.healthyTrunkName()
            if (trunk != null) {
                smsQueue.enqueue(
                    trunk, phone,
                    "ShopManager: your phone is not connected, so shop calls cannot reach you. " +
                        "Please open the ShopManager app to reconnect.",
                )
                println("[SipMonitor]   nudge queued → $who <$phone> (from $trunk)")
            } else {
                println("[SipMonitor]   no healthy modem to send the manager nudge")
            }
        } else {
            println("[SipMonitor]   $who has no phone number on file — no nudge possible")
        }

        // shopId = null on purpose: it makes the alerter's rate-limit key global rather than
        // per-shop, which is what turned one offline phone into four admin SMS.
        alerter.record(
            "warn", "manager_offline", null,
            "$who's phone went OFFLINE at ${hhmm(downSince)}. Shops without a line: $shopList.",
            alertAdmin = true,
            dedupeKey = "manager_offline:mgr$managerId",
        )
    }

    /** One alert on the recovery edge. Admin only — no point texting a phone that just came back. */
    private fun alertOnline(managerId: Int, outageMs: Long) {
        val who = db.getManagerById(managerId)?.name ?: "mgr$managerId"
        println("[SipMonitor] ONLINE mgr=$managerId ($who) after ${humanDuration(outageMs)}")
        alerter.record(
            "info", "manager_online", null,
            "$who's phone is back ONLINE, after ${humanDuration(outageMs)} offline.",
            alertAdmin = true,
            dedupeKey = "manager_online:mgr$managerId",
        )
    }

}
