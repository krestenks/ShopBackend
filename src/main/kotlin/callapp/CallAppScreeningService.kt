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
 * - Called from Twilio webhook handlers the moment a new (or previously unscreened)
 *   customer is first seen on an inbound call or SMS.
 * - Runs in a background coroutine — the Twilio webhook response is never delayed.
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
        val runAt = java.time.Instant.now()
        println("[CallAppScreening] Batch started at $runAt")

        // ── Circuit breaker check ─────────────────────────────────────────────
        val now = System.currentTimeMillis()
        val openUntil = circuitOpenUntil.get()
        if (now < openUntil) {
            val waitSec = (openUntil - now) / 1000
            println("[CallAppScreening] Circuit OPEN — skipping batch, resumes in ${waitSec}s")
            return 0
        }

        // ── Fetch batch ───────────────────────────────────────────────────────
        val customers = db.getCustomersNeedingCallAppScreening(
            maxAgeMs = maxAgeMs,
            limit    = batchSize,
        )
        if (customers.isEmpty()) {
            println("[CallAppScreening] No pending customers — all results are fresh or in backoff. Done.")
            return 0
        }

        println("[CallAppScreening] Screening ${customers.size} customer(s) (batchSize=$batchSize, maxAgeMs=$maxAgeMs)…")

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

                println(
                    "[CallAppScreening] ✓ customerId=${customer.id} phone=$phone " +
                    "found=${result.found} name=${result.name ?: "(none)"} priority=${result.priority}"
                )

            } catch (e: InvalidPhoneNumberException) {
                // Unrecognised phone format — store benign error; backoff won't trigger a real API call.
                db.upsertCustomerCallAppScreeningError(
                    customerId = customer.id,
                    error      = "InvalidPhone: ${e.message}",
                )
                skipped++
                println("[CallAppScreening] ⚠ customerId=${customer.id} phone=$phone — unrecognised number format, skipped")

            } catch (e: Exception) {
                // Real API/network failure
                val msg = e.message?.take(300) ?: e.javaClass.simpleName
                db.upsertCustomerCallAppScreeningError(
                    customerId = customer.id,
                    error      = "Error: $msg",
                )
                println("[CallAppScreening] ✗ customerId=${customer.id} phone=$phone — ${e.javaClass.simpleName}: $msg")
                queried++

                // Update circuit breaker
                val failures = consecutiveFailures.incrementAndGet()
                if (failures >= circuitBreakerThreshold) {
                    val openUntilNew = System.currentTimeMillis() + circuitBreakerPauseMs
                    circuitOpenUntil.set(openUntilNew)
                    consecutiveFailures.set(0)
                    println(
                        "[CallAppScreening] Circuit breaker TRIPPED after $failures consecutive failures. " +
                        "Pausing all lookups for ${circuitBreakerPauseMs / 60_000} min. Remaining batch abandoned."
                    )
                    // Stop processing the rest of this batch — API is clearly unhealthy.
                    break
                }
            }
        }

        println("[CallAppScreening] Done — API queries: $queried, skipped (bad format): $skipped, total candidates: ${customers.size}")
        return queried
    }

    /**
     * Performs an immediate CallApp lookup for a single customer.
     *
     * Intended to be called (inside a background coroutine) from Twilio webhook handlers
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
            println(
                "[CallAppScreening] Immediate ✓ customerId=$customerId phone=$trimmedPhone " +
                "found=${result.found} name=${result.name ?: "(none)"}"
            )
        } catch (e: InvalidPhoneNumberException) {
            // Unrecognised format — store benign error so the batch scheduler won't retry it.
            db.upsertCustomerCallAppScreeningError(
                customerId = customerId,
                error      = "InvalidPhone: ${e.message}",
            )
            println("[CallAppScreening] Immediate ⚠ customerId=$customerId phone=$trimmedPhone — unrecognised format, skipped")
        } catch (e: Exception) {
            val msg = e.message?.take(300) ?: e.javaClass.simpleName
            db.upsertCustomerCallAppScreeningError(
                customerId = customerId,
                error      = "Error: $msg",
            )
            println("[CallAppScreening] Immediate ✗ customerId=$customerId phone=$trimmedPhone — ${e.javaClass.simpleName}: $msg")
        }
    }
}
