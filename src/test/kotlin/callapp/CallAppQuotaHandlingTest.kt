package callapp

import Customer
import DataBase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for the 2026-10-04 quota incident.
 *
 * A quota rejection (HTTP 429) used to be returned as an ordinary `found = false` result. The
 * caller then filed it through the *success* path, which clears `error`, resets `failure_count`
 * and overwrites the cached name with NULL. That single mis-classification:
 *   - erased 397 cached CallApp names,
 *   - bypassed the exponential backoff (rows with `error IS NULL` take the not-found TTL branch),
 *   - made the circuit breaker unreachable (a 429 reset `consecutiveFailures` to 0),
 *   - and moved the whole customer base from the long hit TTL onto the short not-found TTL,
 *     multiplying the request rate ~10x into a self-amplifying storm (~130 wasted requests/day).
 *
 * The invariant these tests protect: **a response that was rejected rather than answered must
 * never be written to the cache.**
 */
class CallAppQuotaHandlingTest {

    private fun customer(id: Int, phone: String = "+4551941736") =
        Customer(id = id, phone = phone, name = "C$id", status = "", payment = 0, language = 0)

    /** A client that throws whatever it is given, recording how many requests it served. */
    private class ThrowingClient(private val error: () -> Throwable) : CallAppLookupClient {
        var calls = 0
        override suspend fun lookup(phoneNumber: String): CallAppLookupResult {
            calls++
            throw error()
        }
    }

    private class StaticClient(private val result: CallAppLookupResult) : CallAppLookupClient {
        var calls = 0
        override suspend fun lookup(phoneNumber: String): CallAppLookupResult {
            calls++
            return result
        }
    }

    private fun quota() = CallAppQuotaExceededException(
        429,
        """{"message":"You have exceeded the MONTHLY quota for Requests on your current plan, PRO."}""",
    )

    // ─── The core invariant ──────────────────────────────────────────────────

    @Test
    fun `a 429 writes nothing to the cache`() = runTest {
        val db = mockk<DataBase>(relaxed = true)
        every { db.getCustomersNeedingCallAppScreening(any(), any(), any()) } returns
            listOf(customer(1), customer(2), customer(3))
        val client = ThrowingClient { quota() }

        val summary = CallAppScreeningService(db = db, client = client).screenPendingCustomers()

        // Neither the success path (which would NULL the name) nor the error path runs.
        verify(exactly = 0) { db.upsertCustomerCallAppScreening(any(), any(), any()) }
        verify(exactly = 0) { db.upsertCustomerCallAppScreeningError(any(), any(), any()) }
        assertTrue(summary.quotaBlocked, "batch should report the quota stop")
        assertEquals(0, summary.misses, "a rejected request is not a not-found")
    }

    @Test
    fun `a 429 stops the batch after one request instead of burning the whole batch`() = runTest {
        val db = mockk<DataBase>(relaxed = true)
        every { db.getCustomersNeedingCallAppScreening(any(), any(), any()) } returns
            (1..50).map { customer(it) }
        val client = ThrowingClient { quota() }

        val summary = CallAppScreeningService(db = db, client = client).screenPendingCustomers()

        assertEquals(1, client.calls, "must not keep asking once the quota is known to be gone")
        assertEquals(1, summary.requests)
    }

    @Test
    fun `once quota-blocked, later batches issue no requests at all`() = runTest {
        val db = mockk<DataBase>(relaxed = true)
        every { db.getCustomersNeedingCallAppScreening(any(), any(), any()) } returns
            listOf(customer(1))
        val client = ThrowingClient { quota() }
        val svc = CallAppScreeningService(db = db, client = client)

        svc.screenPendingCustomers()
        assertTrue(svc.isQuotaBlocked())
        val second = svc.screenPendingCustomers()

        assertEquals(1, client.calls, "the pause must suppress the probe entirely")
        assertEquals(0, second.requests)
        assertTrue(second.quotaBlocked)
    }

    @Test
    fun `the quota pause expires so a restored quota is picked up`() = runTest {
        val db = mockk<DataBase>(relaxed = true)
        every { db.getCustomersNeedingCallAppScreening(any(), any(), any()) } returns
            listOf(customer(1))
        val client = ThrowingClient { quota() }
        // A pause of 0 ms means the block is already expired by the next check.
        val svc = CallAppScreeningService(db = db, client = client, quotaPauseMs = 0)

        svc.screenPendingCustomers()
        assertFalse(svc.isQuotaBlocked(), "a zero-length pause must not block forever")
        svc.screenPendingCustomers()

        assertEquals(2, client.calls, "lookups must resume once the pause elapses")
    }

    @Test
    fun `on-contact lookup is suppressed while quota-blocked`() = runTest {
        val db = mockk<DataBase>(relaxed = true)
        every { db.getCustomersNeedingCallAppScreening(any(), any(), any()) } returns
            listOf(customer(1))
        val client = ThrowingClient { quota() }
        val svc = CallAppScreeningService(db = db, client = client)

        svc.screenPendingCustomers()          // trips the quota stop
        svc.screenCustomerNow(99, "+4551941736")

        assertEquals(1, client.calls, "a 429 is certainty, not worth a best-effort probe")
        verify(exactly = 0) { db.upsertCustomerCallAppScreening(any(), any(), any()) }
    }

    @Test
    fun `on-contact lookup trips the quota stop for the batch path too`() = runTest {
        val db = mockk<DataBase>(relaxed = true)
        val client = ThrowingClient { quota() }
        val svc = CallAppScreeningService(db = db, client = client)

        svc.screenCustomerNow(99, "+4551941736")

        assertTrue(svc.isQuotaBlocked())
        verify(exactly = 0) { db.upsertCustomerCallAppScreeningError(any(), any(), any()) }
    }

    // ─── Other failures keep the old, correct behaviour ──────────────────────

    @Test
    fun `a non-429 HTTP error takes the error path, preserving the cached name`() = runTest {
        val db = mockk<DataBase>(relaxed = true)
        every { db.getCustomersNeedingCallAppScreening(any(), any(), any()) } returns
            listOf(customer(1))
        val client = ThrowingClient { CallAppHttpException(503, "Service Unavailable", "{}") }

        val summary = CallAppScreeningService(db = db, client = client).screenPendingCustomers()

        // upsertCustomerCallAppScreeningError deliberately does not overwrite name/found.
        verify(exactly = 1) { db.upsertCustomerCallAppScreeningError(1, any(), any()) }
        verify(exactly = 0) { db.upsertCustomerCallAppScreening(any(), any(), any()) }
        assertEquals(1, summary.errors)
        assertFalse(summary.quotaBlocked)
    }

    @Test
    fun `five consecutive failures open the circuit breaker`() = runTest {
        val db = mockk<DataBase>(relaxed = true)
        every { db.getCustomersNeedingCallAppScreening(any(), any(), any()) } returns
            (1..20).map { customer(it) }
        val client = ThrowingClient { CallAppHttpException(500, "Internal Server Error", null) }

        val summary = CallAppScreeningService(db = db, client = client).screenPendingCustomers()

        assertEquals(5, client.calls, "breaker should stop the batch at the threshold")
        assertEquals(5, summary.errors)
    }

    @Test
    fun `a genuine not-found is still cached`() = runTest {
        val db = mockk<DataBase>(relaxed = true)
        every { db.getCustomersNeedingCallAppScreening(any(), any(), any()) } returns
            listOf(customer(1))
        val client = StaticClient(
            CallAppLookupResult(
                found = false, name = null, priority = null, status = true,
                message = "Invalid phone number or data not found.", timestamp = null, rawJson = "{}",
            )
        )

        val summary = CallAppScreeningService(db = db, client = client).screenPendingCustomers()

        verify(exactly = 1) { db.upsertCustomerCallAppScreening(1, any(), any()) }
        assertEquals(1, summary.misses)
        assertEquals(0, summary.errors)
    }

    @Test
    fun `an unparseable number costs no request`() = runTest {
        val db = mockk<DataBase>(relaxed = true)
        every { db.getCustomersNeedingCallAppScreening(any(), any(), any()) } returns
            listOf(customer(1, phone = "not-a-number"))
        val client = ThrowingClient { InvalidPhoneNumberException("bad") }

        val summary = CallAppScreeningService(db = db, client = client).screenPendingCustomers()

        assertEquals(0, summary.requests, "quota accounting must not count skips as requests")
        assertEquals(1, summary.invalidPhones)
    }

    // ─── Quota budget: the TTLs the service asks the DB for ──────────────────

    @Test
    fun `hits are cached for a year and not-founds for a month`() = runTest {
        val db = mockk<DataBase>(relaxed = true)
        every { db.getCustomersNeedingCallAppScreening(any(), any(), any()) } returns emptyList()

        CallAppScreeningService(db = db, client = StaticClient(mockk())).screenPendingCustomers()

        val day = 24L * 60 * 60 * 1000
        verify { db.getCustomersNeedingCallAppScreening(365L * day, 30L * day, any()) }
    }
}
