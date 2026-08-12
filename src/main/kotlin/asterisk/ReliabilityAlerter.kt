package asterisk

import DataBase

/**
 * Central sink for reliability/telephony events. Records each to the DB log (shown in the web
 * admin) and, for call-blocking events, texts the admin number (configured in the web admin).
 *
 * The admin SMS goes out from a currently-healthy modem — the affected shop's SIM if it's up,
 * else any "Free" trunk — through [SmsQueue], and is rate-limited per event kind so a flapping
 * fault can't spam. This is how the operator finds out about problems that would otherwise be
 * silent, and the log is how they spot patterns to harden.
 */
class ReliabilityAlerter(
    private val db: DataBase,
    private val config: AsteriskConfig,
    private val amiClient: AmiClient,
    private val smsQueue: SmsQueue,
) {
    private companion object {
        const val ADMIN_SMS_COOLDOWN_MS = 15 * 60_000L
    }

    private val lastAdminAlertAt = HashMap<String, Long>()

    /**
     * Log an event; when [alertAdmin] and an admin number is set, also SMS it (rate-limited per
     * category+shop). [shopId] is the affected shop — used for the log and the preferred sender SIM.
     */
    @Synchronized
    fun record(severity: String, category: String, shopId: Int?, message: String, alertAdmin: Boolean) {
        runCatching { db.recordReliabilityEvent(severity, category, shopId, message) }
            .onFailure { println("[Reliability] log write failed: ${it.message}") }
        println("[Reliability] $severity/$category${shopId?.let { " shop$it" } ?: ""}: $message")
        if (!alertAdmin) return

        val admin = db.getSipAlertAdminPhone() ?: return
        val key = "$category:${shopId ?: 0}"
        val now = System.currentTimeMillis()
        if (now - (lastAdminAlertAt[key] ?: 0L) < ADMIN_SMS_COOLDOWN_MS) return

        val trunk = pickHealthyTrunk(shopId) ?: run {
            println("[Reliability] no healthy modem available to send admin alert ($category)")
            return
        }
        lastAdminAlertAt[key] = now
        smsQueue.enqueue(trunk, admin, "ShopManager alert: $message")
        println("[Reliability] admin alert queued → $admin (from $trunk)")
    }

    /** A trunk whose modem is currently Free — the affected shop's if possible, else any. */
    private fun pickHealthyTrunk(preferShopId: Int?): String? {
        val free = amiClient.quectelDeviceStates()
            .filterValues { it.trim().equals("Free", ignoreCase = true) }.keys
        if (preferShopId != null) {
            val pref = config.trunkName(preferShopId)
            if (pref in free) return pref
        }
        return free.firstOrNull()
    }
}
