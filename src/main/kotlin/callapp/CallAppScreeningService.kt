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
 * - A short HTTP timeout prevents a slow API from blocking.
 * - On network/HTTP error: the **previous CallApp name is preserved** in the DB; only the
 *   error fields and retry schedule are updated.
 * - **Exponential backoff**: failed rows are retried after 2h, 4h, 8h … capped at 24h.
 * - **Circuit breaker**: if [circuitBreakerThreshold] consecutive API failures occur the
 *   breaker opens and all *batch* lookups are skipped for [circuitBreakerPauseMs] (default
 *   30 min). The breaker resets automatically when the pause expires.
 *   [screenCustomerNow] bypasses the circuit breaker so new contacts always get a best-effort
 *   immediate lookup.
 * - **Quota stop** ([CallAppQuotaExceededException] / HTTP 429): a hard halt, separate from the
 *   circuit breaker and respected by *both* entry points. A 429 is certain knowledge that no
 *   request can succeed, so retrying is pure waste — nothing is written to the DB (the cache is
 *   left intact) and all lookups are suppressed for [quotaPauseMs]. The pause is one scheduler
 *   interval, so a restored quota is discovered within ~6h at a cost of one probe request per
 *   run instead of ~130/day. In-memory, so a backend restart costs one extra probe.
 * - Unrecognised phone numbers ([InvalidPhoneNumberException]) are recorded as a benign error
 *   and never retried via the API (e.g. numbers without a standard country-code prefix).
 */
class CallAppScreeningService(
    private val db: DataBase,
    private val client: CallAppLookupClient,
    /**
     * Cache lifetime for a successful hit. A CallApp name for a given number essentially never
     * changes, so this is deliberately long: at 30 days, refreshing 1370 customers cost ~1370
     * requests/month against a 1500/month plan — the refresh alone consumed the whole quota and
     * left nothing for new customers.
     */
    private val maxAgeMs: Long = 365L * 24 * 60 * 60 * 1000,
    /**
     * Cache lifetime for a genuine not-found. Shorter than [maxAgeMs] because the person may
     * since have added themselves to CallApp, but not aggressive: at 3 days each not-found cost
     * 10 requests/month, which is what turned a quota error into a self-amplifying storm.
     */
    private val notFoundMaxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val batchSize: Int = 50,
    /** How many consecutive API failures before the circuit opens. */
    private val circuitBreakerThreshold: Int = 5,
    /** How long the circuit stays open after tripping (ms). Default 30 min. */
    private val circuitBreakerPauseMs: Long = 30L * 60 * 1000,
    /** How long all lookups are suppressed after an HTTP 429. Default 6h = one scheduler cycle. */
    private val quotaPauseMs: Long = 6L * 60 * 60 * 1000,
) {
    // Circuit breaker state (in-memory; resets on backend restart).
    private val consecutiveFailures = AtomicInteger(0)
    private val circuitOpenUntil    = AtomicLong(0L)

    /** Epoch-ms until which the quota is known to be exhausted; 0 = not blocked. */
    private val quotaBlockedUntil = AtomicLong(0L)

    /** True while a known-exhausted quota is suppressing lookups. */
    fun isQuotaBlocked(now: Long = System.currentTimeMillis()): Boolean =
        now < quotaBlockedUntil.get()

    /**
     * Records a quota rejection and suppresses all lookups for [quotaPauseMs].
     * Nothing is written to the DB: the existing cache is strictly better than anything we could
     * record about a request that was never served.
     */
    private fun blockOnQuota(e: CallAppQuotaExceededException, now: Long = System.currentTimeMillis()) {
        val until = now + quotaPauseMs
        // Only log on the transition, so a blocked period doesn't spam the journal.
        if (quotaBlockedUntil.getAndSet(until) <= now) {
            println("[CallApp] QUOTA EXHAUSTED (HTTP ${e.httpStatus}) — suppressing all lookups " +
                    "for ${quotaPauseMs / 60_000} min. Cache left intact. ${e.body?.take(160)}")
        }
    }

    /** Outcome of one batch run. [requests] is what actually hit the API, i.e. what cost quota. */
    data class BatchSummary(
        val requests: Int = 0,
        val hits: Int = 0,
        val misses: Int = 0,
        val errors: Int = 0,
        val invalidPhones: Int = 0,
        val quotaBlocked: Boolean = false,
    )

    /**
     * Screens one batch of customers that are unscreened, have stale results, or whose
     * exponential-backoff retry window has passed.
     *
     * @return a [BatchSummary]; [BatchSummary.requests] is the number of requests that actually
     *         reached the API, which is what consumes quota. Skips (invalid phone numbers,
     *         circuit-breaker or quota short-circuits) cost nothing and are counted separately.
     */
    suspend fun screenPendingCustomers(): BatchSummary {
        val now = System.currentTimeMillis()

        // ── Quota stop ────────────────────────────────────────────────────────
        // Checked before the circuit breaker: a known-exhausted quota means every request would
        // be rejected, so not even a single probe is worth issuing until the pause expires.
        if (isQuotaBlocked(now)) {
            return BatchSummary(quotaBlocked = true)
        }

        // ── Circuit breaker check ─────────────────────────────────────────────
        if (now < circuitOpenUntil.get()) {
            return BatchSummary()
        }

        // ── Fetch batch ───────────────────────────────────────────────────────
        val customers = db.getCustomersNeedingCallAppScreening(
            maxAgeMs         = maxAgeMs,
            notFoundMaxAgeMs = notFoundMaxAgeMs,
            limit            = batchSize,
        )
        if (customers.isEmpty()) {
            return BatchSummary()
        }

        var requests = 0
        var hits = 0
        var misses = 0
        var errors = 0
        var invalidPhones = 0
        var quotaBlocked = false

        for (customer in customers) {
            val phone = customer.phone.trim()
            try {
                val result = client.lookup(phone)
                db.upsertCustomerCallAppScreening(customerId = customer.id, result = result)
                requests++
                if (result.found) hits++ else misses++

                // Success — reset consecutive failure counter
                consecutiveFailures.set(0)

            } catch (e: InvalidPhoneNumberException) {
                // Unrecognised phone format — store benign error; backoff won't trigger a real API call.
                // No request was issued, so this costs no quota.
                db.upsertCustomerCallAppScreeningError(
                    customerId = customer.id,
                    error      = "InvalidPhone: ${e.message}",
                )
                invalidPhones++

            } catch (e: CallAppQuotaExceededException) {
                // Quota exhausted. The request was rejected, not answered — write NOTHING, so the
                // cached name and its long TTL survive. Stop the batch outright.
                requests++
                quotaBlocked = true
                blockOnQuota(e)
                break

            } catch (e: Exception) {
                // Real API/network failure (incl. CallAppHttpException for other non-2xx codes).
                val msg = e.message?.take(300) ?: e.javaClass.simpleName
                db.upsertCustomerCallAppScreeningError(
                    customerId = customer.id,
                    error      = "Error: $msg",
                )
                requests++
                errors++

                // Update circuit breaker
                val failures = consecutiveFailures.incrementAndGet()
                if (failures >= circuitBreakerThreshold) {
                    circuitOpenUntil.set(System.currentTimeMillis() + circuitBreakerPauseMs)
                    consecutiveFailures.set(0)
                    println("[CallApp] Circuit breaker OPEN after $circuitBreakerThreshold " +
                            "consecutive failures — pausing batch lookups for " +
                            "${circuitBreakerPauseMs / 60_000} min. Last error: $msg")
                    // Stop processing the rest of this batch — API is clearly unhealthy.
                    break
                }
            }
        }

        val summary = BatchSummary(requests, hits, misses, errors, invalidPhones, quotaBlocked)
        // Log every run that touched the API. The 429 storm of 2026-10-01 ran for four days
        // without leaving a single line in the journal; silence is how it stayed invisible.
        if (requests > 0 || invalidPhones > 0) {
            val stopped = if (summary.quotaBlocked) " — STOPPED: quota exhausted" else ""
            println("[CallApp] Batch: ${summary.requests} requests (${summary.hits} hits, " +
                    "${summary.misses} not-found, ${summary.errors} errors), " +
                    "${summary.invalidPhones} unparseable skipped$stopped")
        }
        return summary
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
     * It does **not** bypass the quota stop: the breaker is a guess that the API is unhealthy and
     * worth probing past, whereas a 429 is certainty that the request cannot be served, so a
     * best-effort attempt would be waste with no upside.
     *
     * Errors are stored in the DB (with backoff) so the batch scheduler picks up retries.
     */
    suspend fun screenCustomerNow(customerId: Int, phone: String) {
        val trimmedPhone = phone.trim()
        if (trimmedPhone.isBlank()) return
        if (isQuotaBlocked()) return
        try {
            val result = client.lookup(trimmedPhone)
            db.upsertCustomerCallAppScreening(customerId = customerId, result = result)
        } catch (e: CallAppQuotaExceededException) {
            // Rejected, not answered — write nothing and stop the batch path too.
            blockOnQuota(e)
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
