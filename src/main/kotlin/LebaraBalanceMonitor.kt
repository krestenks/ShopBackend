import asterisk.AmiClient
import kotlinx.coroutines.runBlocking
import telephony.LebaraTopup
import telephony.TelephonyService
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Once a day, asks each Lebara shop's SIM for its prepaid balance (texts "balance" to 5010 via
 * [LebaraTopup.requestBalance]). The reply arrives asynchronously from 5010 and is stored raw in
 * `shop_telephony_config.balance` (see the inbound-SMS handler), where the low-balance check and the
 * manager-app alert read it. This monitor only triggers the refresh so the stored balance stays
 * current; it does not itself alert.
 *
 * Runs at ~[checkHour] local, once per calendar day. Set LEBARA_BALANCE_MONITOR_ENABLED=false to
 * disable; LEBARA_BALANCE_CHECK_HOUR to move the time; LOW_BALANCE_THRESHOLD_KR for the threshold
 * (read by [telephony.LebaraBalance]).
 */
class LebaraBalanceMonitor(
    private val db: DataBase,
    private val telephony: TelephonyService,
    private val amiClient: AmiClient,
    private val enabled: Boolean = System.getenv("LEBARA_BALANCE_MONITOR_ENABLED")?.lowercase() != "false",
    private val checkHour: Int = System.getenv("LEBARA_BALANCE_CHECK_HOUR")?.trim()?.toIntOrNull() ?: 9,
) {
    private companion object { const val TICK_MS = 15 * 60_000L }

    // Persisted so a restart doesn't re-send the daily balance SMS; survives process bounces.
    private val stateFile = File("data/lebara_balance_last_run")

    fun start() {
        if (!enabled) { println("[LebaraBalance] disabled (LEBARA_BALANCE_MONITOR_ENABLED=false)"); return }
        Thread({
            while (!amiClient.connected) Thread.sleep(5_000)   // don't fire before the modem/AMI is up
            println("[LebaraBalance] started (daily at ~${checkHour}:00 local)")
            while (true) {
                try { maybeRunDaily() } catch (e: Exception) { println("[LebaraBalance] tick failed: ${e.message}") }
                Thread.sleep(TICK_MS)
            }
        }, "lebara-balance-monitor").apply { isDaemon = true }.start()
    }

    private fun lastRunDay(): LocalDate? =
        runCatching { LocalDate.parse(stateFile.readText().trim()) }.getOrNull()

    private fun markRun(day: LocalDate) {
        runCatching { stateFile.parentFile?.mkdirs(); stateFile.writeText(day.toString()) }
            .onFailure { println("[LebaraBalance] could not persist last-run: ${it.message}") }
    }

    private fun maybeRunDaily() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        if (lastRunDay() == today) return             // already ran today (survives restarts)
        if (now.hour < checkHour) return              // wait until the target hour
        markRun(today)
        requestAllBalances()
    }

    private fun requestAllBalances() {
        var sent = 0
        for (shop in db.getAllShops()) {
            val cfg = runCatching { db.getShopTelephonyConfig(shop.id) }.getOrNull() ?: continue
            if (!cfg.carrier.equals(LebaraTopup.CARRIER, ignoreCase = true)) continue
            if (cfg.modemDataDevice.isNullOrBlank()) continue
            val res = runCatching { runBlocking { LebaraTopup.requestBalance(telephony, shop.id) } }.getOrNull()
            if (res?.success == true) sent++
            else println("[LebaraBalance] balance request failed for shop ${shop.id} (${res?.body ?: "no modem?"})")
            Thread.sleep(3000)   // spread requests over the shared modem/AMI
        }
        println("[LebaraBalance] daily balance requests sent to $sent Lebara shop(s)")
    }
}
