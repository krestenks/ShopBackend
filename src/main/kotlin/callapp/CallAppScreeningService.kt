package callapp

import DataBase
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Screens customers against the CallApp RapidAPI, caching results in the DB.
 *
 * ## Failure robustness
 * - Lookups run in a **background scheduler** — never on the hot path of a call or SMS.
 * - A short HTTP timeout (default 1.2s) prevents a slow API from blocking the thread.
 * - On network/HTTP error: the **previous CallApp name is preserved** in the DB; only the
 *   error fields and retry schedule are updated.
 * - **Exponential backoff**: failed rows are retried after 2h, 4h, 8h … capped at 24h.
 * - **Circuit breaker**: if [circuitBreakerThreshold] consecutive API failures occur the
 *   breaker opens and all lookups are skipped for [circuitBreakerPauseMs] (default 30 min).
 *   The breaker resets automatically when the pause expires.
 * - Non-Danish phone numbers ([InvalidPhoneNumberException]) are recorded as a benign error
 *   and never retried via the API.
 */
class CallAppScreeningService(
    private val db: DataBase,
    private val client: CallAppLookupClient,
    private val maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,   // 30 days cache for successes
    private val batchSize: Int = 50,
    /** How many consecutive API failures before the circuit opens. */
    private val circuitBreakerThreshold: Int = 5,
    /** How long the circuit stays open after tripping (ms). Default 30 min. */
    private val circuitBreakerPauseMs: Long = 30L * 60 * 1000,
) {
    // Circuit breaker state (in-memory; resets on backend restart).
    private val consecutiveFailures = AtomicInteger(0)
    private val circuitOpenUntil    = AtomicLong(0L)

    /**
     * Screens one batch of customers that are unscreened, have stale results, or whose
     * exponential-backoff retry window has passed.
     *
     * @return the number of customers actually queried against the API (not counting
     *         [InvalidPhoneNumberException] skips or circuit-breaker short-circuits).
     */
    suspend fun screenPendingCustomers(): Int {
        // ── Circuit breaker check ─────────────────────────────────────────────
        val now = System.currentTimeMillis()
        val openUntil = circuitOpenUntil.get()
        if (now < openUntil) {
            val waitSec = (openUntil - now) / 1000
            println("[CallAppScreening] Circuit open — pausing lookups for ${waitSec}s")
            return 0
        }

        // ── Fetch batch ───────────────────────────────────────────────────────
        val customers = db.getCustomersNeedingCallAppScreening(
            maxAgeMs = maxAgeMs,
            limit    = batchSize,
        )
        if (customers.isEmpty()) return 0

        println("[CallAppScreening] Screening ${customers.size} customer(s)…")

        var queried = 0
        for (customer in customers) {
            val phone = customer.phone.trim()
            try {
                val result = client.lookup(phone)
                db.upsertCustomerCallAppScreening(customerId = customer.id, result = result)
                queried++

                // Success — reset consecutive failure counter
                consecutiveFailures.set(0)

                println(
                    "[CallAppScreening] customerId=${customer.id} phone=$phone " +
                    "found=${result.found} name=${result.name} priority=${result.priority}"
                )

            } catch (e: InvalidPhoneNumberException) {
                // Not a Danish number — store benign error; backoff won't trigger a real API call.
                db.upsertCustomerCallAppScreeningError(
                    customerId = customer.id,
                    error      = "InvalidPhone: ${e.message}",
                )
                // No API call was made; don't update the failure counter.

            } catch (e: Exception) {
                // Real API/network failure
                val msg = e.message?.take(300) ?: e.javaClass.simpleName
                db.upsertCustomerCallAppScreeningError(
                    customerId = customer.id,
                    error      = "Error: $msg",
                )
                println("[CallAppScreening] ERROR customerId=${customer.id} phone=$phone — $msg")
                queried++

                // Update circuit breaker
                val failures = consecutiveFailures.incrementAndGet()
                if (failures >= circuitBreakerThreshold) {
                    val openUntilNew = System.currentTimeMillis() + circuitBreakerPauseMs
                    circuitOpenUntil.set(openUntilNew)
                    consecutiveFailures.set(0)
                    println(
                        "[CallAppScreening] Circuit breaker tripped after $failures failures. " +
                        "Pausing for ${circuitBreakerPauseMs / 60_000} min."
                    )
                    // Stop processing the rest of this batch — API is clearly unhealthy.
                    break
                }
            }
        }

        println("[CallAppScreening] Done — API queries: $queried / ${customers.size}")
        return queried
    }
}
