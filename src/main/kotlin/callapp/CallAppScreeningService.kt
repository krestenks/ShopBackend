package callapp

import DataBase
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Screens customers against the CallApp RapidAPI, caching results in the DB.
 *
 * ## Two screening modes
 *
 * ### Background batch ([screenPendingCustomers])
 * - Runs on a scheduler (every 6 hours).
 * - Processes all customers that are unscreened, have stale results, or whose
 *   exponential-backoff retry window has passed.
 *
 * ### On-contact immediate lookup ([screenCustomerNow])
 * - Called from inbound call/SMS handlers the moment a new (or previously unscreened)
 *   customer is first seen on an inbound call or SMS.
 * - Runs in a background coroutine — the inbound handler response is never delayed.
 * - Ensures the CallApp name is available for the *next* interaction, not 6 hours later.
 *
 * ## Failure robustness
 * - A short HTTP timeout (default 1.2s) prevents a slow API from blocking.
 * - On network/HTTP error: the **previous CallApp name is preserved** in the DB; only the
 *   error fields and retry schedule are updated.
 * - **Exponential backoff**: failed rows are retried after 2h, 4h, 8h … capped at 24h.
 * - **Circuit breaker**: if [circuitBreakerThreshold] consecutive API failures occur the
 *   breaker opens and all *batch* lookups are skipped for [circuitBreakerPauseMs] (default
 *   30 min). The breaker resets automatically when the pause expires.
 *   [screenCustomerNow] bypasses the circuit breaker so new contacts always get a best-effort
 *   immediate lookup.
 * - Unrecognised phone numbers ([InvalidPhoneNumberException]) are recorded as a benign error
 *   and never retried via the API (e.g. numbers without a standard country-code prefix).
 */
class CallAppScreeningService(
    private val db: DataBase,
    private val client: CallAppLookupClient,
    private val maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,   // 30 days cache for found=1 results
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
            return 0
        }

        // ── Fetch batch ───────────────────────────────────────────────────────
        val customers = db.getCustomersNeedingCallAppScreening(
            maxAgeMs = maxAgeMs,
            limit    = batchSize,
        )
        if (customers.isEmpty()) {
            return 0
        }

        var queried = 0
        var skipped = 0
        for (customer in customers) {
            val phone = customer.phone.trim()
            try {
                val result = client.lookup(phone)
                db.upsertCustomerCallAppScreening(customerId = customer.id, result = result)
                queried++

                // Success — reset consecutive failure counter
                consecutiveFailures.set(0)

            } catch (e: InvalidPhoneNumberException) {
                // Unrecognised phone format — store benign error; backoff won't trigger a real API call.
                db.upsertCustomerCallAppScreeningError(
                    customerId = customer.id,
                    error      = "InvalidPhone: ${e.message}",
                )
                skipped++

            } catch (e: Exception) {
                // Real API/network failure
                val msg = e.message?.take(300) ?: e.javaClass.simpleName
                db.upsertCustomerCallAppScreeningError(
                    customerId = customer.id,
                    error      = "Error: $msg",
                )
                queried++

                // Update circuit breaker
                val failures = consecutiveFailures.incrementAndGet()
                if (failures >= circuitBreakerThreshold) {
                    val openUntilNew = System.currentTimeMillis() + circuitBreakerPauseMs
                    circuitOpenUntil.set(openUntilNew)
                    consecutiveFailures.set(0)
                    // Stop processing the rest of this batch — API is clearly unhealthy.
                    break
                }
            }
        }

        return queried
    }

    /**
     * Performs an immediate CallApp lookup for a single customer.
     *
     * Intended to be called (inside a background coroutine) from inbound call/SMS handlers
     * the first time a customer contacts the shop, so their CallApp name is available for
     * the manager within seconds rather than waiting up to 6 hours for the batch scheduler.
     *
     * Unlike [screenPendingCustomers], this method **bypasses the circuit breaker** so a
     * new-contact lookup always gets a best-effort attempt regardless of recent batch failures.
     *
     * Errors are stored in the DB (with backoff) so the batch scheduler picks up retries.
     */
    suspend fun screenCustomerNow(customerId: Int, phone: String) {
        val trimmedPhone = phone.trim()
        if (trimmedPhone.isBlank()) return
        try {
            val result = client.lookup(trimmedPhone)
            db.upsertCustomerCallAppScreening(customerId = customerId, result = result)
        } catch (e: InvalidPhoneNumberException) {
            // Unrecognised format — store benign error so the batch scheduler won't retry it.
            db.upsertCustomerCallAppScreeningError(
                customerId = customerId,
                error      = "InvalidPhone: ${e.message}",
            )
        } catch (e: Exception) {
            val msg = e.message?.take(300) ?: e.javaClass.simpleName
            db.upsertCustomerCallAppScreeningError(
                customerId = customerId,
                error      = "Error: $msg",
            )
        }
    }
}
