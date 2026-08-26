import asterisk.AmiClient
import asterisk.AsteriskConfig
import asterisk.ReliabilityAlerter
import kotlinx.serialization.Serializable

/**
 * The unified "is the phone system actually working?" view (Tier 3), plus a last-resort Asterisk
 * auto-restart (Tier 2, OFF by default).
 *
 * Every [INTERVAL_MS] it computes one snapshot — AMI connected?, each modem's state, and per shop
 * with an on-duty manager whether it is *call-ready* (a manager phone is Avail AND its modem is Free)
 * — and caches it. The `/health` endpoint serves the cache, so health polls (and the cron backstop)
 * never issue their own blocking AMI CLI commands (addresses the per-request `pjsip show contacts`
 * cost). This layer only observes; the modem ladder ([ModemStuckWatchdog]) and the SIP SMS alerter
 * ([SipReachabilityMonitor]) own remediation/alerts for their specific faults.
 *
 * Tier 2 — Asterisk restart: if AMI is unreachable continuously for [ASTERISK_DOWN_LIMIT_MS] (asterisk
 * itself wedged, not just a phone), and ASTERISK_AUTORESTART_ENABLED=true, run
 * `sudo systemctl restart asterisk` — heavily rate-limited (cooldown + daily cap), always alerts.
 * Disabled by default: it drops every registration/call briefly, so it ships dormant until enabled
 * after review, and needs a NOPASSWD sudoers entry for the service user.
 */
class SystemHealthMonitor(
    private val db: DataBase,
    private val config: AsteriskConfig,
    private val amiClient: AmiClient,
    private val alerter: ReliabilityAlerter,
) {
    private companion object {
        const val INTERVAL_MS = 60_000L
        const val ASTERISK_DOWN_LIMIT_MS = 10 * 60_000L   // AMI down this long → asterisk likely wedged
        const val ASTERISK_RESTART_COOLDOWN_MS = 60 * 60_000L
        const val ASTERISK_MAX_RESTARTS_PER_DAY = 3
    }

    private val asteriskAutoRestart = System.getenv("ASTERISK_AUTORESTART_ENABLED")?.lowercase() == "true"

    @Volatile private var amiDownSince: Long = 0L
    @Volatile private var lastAsteriskRestartAt: Long = 0L
    private var restartDayStamp: Long = 0L
    private var restartsToday: Int = 0

    @Volatile
    var snapshot: HealthSnapshot = HealthSnapshot(ok = false, amiConnected = false, modems = emptyList(), shops = emptyList())
        private set

    fun start() {
        Thread({
            while (!amiClient.connected) Thread.sleep(5_000)
            println("[SystemHealth] started (interval=${INTERVAL_MS / 1000}s; asterisk auto-restart=${if (asteriskAutoRestart) "ON" else "off"})")
            while (true) {
                try { runCheck() } catch (e: Exception) { println("[SystemHealth] check failed: ${e.message}") }
                Thread.sleep(INTERVAL_MS)
            }
        }, "system-health-monitor").apply { isDaemon = true }.start()
    }

    private fun runCheck() {
        val now = System.currentTimeMillis()
        val amiUp = amiClient.connected

        // Track how long AMI has been continuously down (Tier 2 trigger).
        if (amiUp) amiDownSince = 0L else if (amiDownSince == 0L) amiDownSince = now

        if (!amiUp) {
            snapshot = HealthSnapshot(ok = false, amiConnected = false, modems = emptyList(), shops = emptyList())
            maybeRestartAsterisk(now)
            return
        }

        val modemStates = amiClient.quectelDeviceStates()      // trunk -> state
        val reachable = amiClient.pjsipReachableAors()         // AoRs currently Avail

        val modems = modemStates.map { (trunk, st) -> ModemHealth(trunk, st.trim(), st.trim().equals("Free", true)) }

        val shops = db.getAllShops().mapNotNull { shop ->
            val onDuty = db.getOnDutyManagerIdsForShop(shop.id)
            if (onDuty.isEmpty()) return@mapNotNull null       // not claiming coverage → not a health concern
            val managerReachable = onDuty.any { reachable.contains(config.managerEndpointId(it)) }
            val trunk = config.trunkName(shop.id)
            val hasModem = modemStates.containsKey(trunk)
            val modemFree = modemStates[trunk]?.trim().equals("Free", ignoreCase = true)
            // Call-ready = a manager can be rung AND (if the shop has a GSM trunk) it can carry the call.
            val callReady = managerReachable && (!hasModem || modemFree)
            ShopHealth(shop.id, shop.name, managerReachable, hasModem, modemFree, callReady)
        }

        snapshot = HealthSnapshot(
            ok = shops.all { it.callReady },
            amiConnected = true,
            modems = modems,
            shops = shops,
        )
    }

    private fun maybeRestartAsterisk(now: Long) {
        if (!asteriskAutoRestart) return
        if (amiDownSince == 0L || now - amiDownSince < ASTERISK_DOWN_LIMIT_MS) return
        if (now - lastAsteriskRestartAt < ASTERISK_RESTART_COOLDOWN_MS) return
        // Daily cap.
        val day = now / (24 * 60 * 60_000L)
        if (day != restartDayStamp) { restartDayStamp = day; restartsToday = 0 }
        if (restartsToday >= ASTERISK_MAX_RESTARTS_PER_DAY) return

        lastAsteriskRestartAt = now
        restartsToday++
        val ok = runCatching {
            val p = ProcessBuilder("sudo", "systemctl", "restart", "asterisk").redirectErrorStream(true).start()
            p.waitFor()
            p.exitValue() == 0
        }.getOrElse { println("[SystemHealth] asterisk restart exec failed: ${it.message}"); false }
        println("[SystemHealth] AMI down >${ASTERISK_DOWN_LIMIT_MS / 60000}min — asterisk restart ${if (ok) "issued" else "FAILED"} (#$restartsToday today)")
        alerter.record(
            if (ok) "error" else "error", "asterisk_restart", null,
            "Asterisk was unreachable >${ASTERISK_DOWN_LIMIT_MS / 60000} min — auto-restart ${if (ok) "issued" else "FAILED"} (#$restartsToday today)",
            alertAdmin = true,
        )
    }
}

@Serializable
data class ModemHealth(val trunk: String, val state: String, val free: Boolean)

@Serializable
data class ShopHealth(
    val shopId: Int,
    val shopName: String,
    val managerReachable: Boolean,
    val hasModem: Boolean,
    val modemFree: Boolean,
    val callReady: Boolean,
)

@Serializable
data class HealthSnapshot(
    val ok: Boolean,
    val amiConnected: Boolean,
    val modems: List<ModemHealth>,
    val shops: List<ShopHealth>,
)
