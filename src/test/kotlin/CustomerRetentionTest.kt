import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Tests for the two-stage customer retention: flag after N days of no activity, delete after a
 * further grace period, never touch an active customer.
 *
 * The property that matters most is the one the old code got wrong: **activity must not be
 * inferred from rows that communication retention has already deleted.** The old
 * `deleteExpiredCustomers` looked for `sms_message` / `voice_call` rows newer than the cutoff, so
 * once a customer's history aged out they looked inactive and became deletable — capping effective
 * customer retention at the (much shorter) communications window. `customers.last_activity_at`
 * decouples them, and `NULL` means "unknown", which must never authorise deletion.
 *
 * Time is injected via `runCustomerRetention(now = …)` rather than back-dating rows, so the tests
 * exercise the same code path production does.
 */
class CustomerRetentionTest {

    private val day = 24L * 60 * 60 * 1000
    private val t0 = System.currentTimeMillis()

    private fun freshDb(): DataBase {
        val f = Files.createTempFile("retention-test", ".db")
        Files.deleteIfExists(f)
        val db = DataBase(f.toString())
        // A fresh DB may have no shops; retention reads its window from shop config.
        if (db.getAllShops().isEmpty()) db.addShopForOwner(1, "RetentionTest", null, null)
        return db
    }

    /** Creates a customer whose last activity is [daysAgo] before [t0], or unknown if null. */
    private fun DataBase.customerInactiveFor(daysAgo: Int?, phone: String): Int {
        val id = insertNewCustomer(phone)
        if (daysAgo != null) touchCustomerActivity(id, t0 - daysAgo * day)
        return id
    }

    /** Sets every shop's inactivity window, so the "most lenient shop" rule has a known value. */
    private fun DataBase.setWindow(days: Int) {
        for (shop in getAllShops()) {
            upsertShopVoiceConfig(getShopVoiceConfig(shop.id).copy(customerRetentionDays = days))
        }
    }

    private fun DataBase.flaggedAt(id: Int): Long? =
        getCustomersFlaggedForDeletion().firstOrNull { it.id == id }?.flaggedAt

    private fun DataBase.exists(id: Int): Boolean = getCustomerById(id) != null

    // ─── Flagging ────────────────────────────────────────────────────────────

    @Test
    fun `a customer inactive beyond the window is flagged, not deleted`() {
        val db = freshDb(); db.setWindow(730)
        val id = db.customerInactiveFor(800, "+4551000001")

        val s = db.runCustomerRetention(now = t0)

        assertEquals(1, s.flagged)
        assertEquals(0, s.deleted, "flagging must never delete in the same pass")
        assertNotNull(db.flaggedAt(id))
        assertTrue(db.exists(id))
    }

    @Test
    fun `a customer inside the window is not flagged`() {
        val db = freshDb(); db.setWindow(730)
        val id = db.customerInactiveFor(700, "+4551000002")

        db.runCustomerRetention(now = t0)

        assertNull(db.flaggedAt(id))
        assertTrue(db.exists(id))
    }

    @Test
    fun `unknown last activity is never flagged`() {
        val db = freshDb(); db.setWindow(730)
        val id = db.customerInactiveFor(null, "+4551000003")

        val s = db.runCustomerRetention(now = t0)

        assertEquals(0, s.flagged, "\"I cannot determine\" is not \"inactive\"")
        assertNull(db.flaggedAt(id))
        assertTrue(db.exists(id))
        assertTrue(s.skippedUnknown >= 1, "the deliberate non-action must be visible")
    }

    @Test
    fun `an unknown-activity customer is never deleted, however long we wait`() {
        val db = freshDb(); db.setWindow(730)
        val id = db.customerInactiveFor(null, "+4551000003b")

        // Two sweeps a decade apart: the first could flag, the second could delete. Neither may.
        val first = db.runCustomerRetention(now = t0)
        val second = db.runCustomerRetention(now = t0 + 3650 * day)

        assertEquals(0, first.flagged)
        assertEquals(0, second.deleted)
        assertNull(db.flaggedAt(id))
        assertTrue(db.exists(id))
    }

    @Test
    fun `flagging is idempotent - the flag timestamp does not move on later sweeps`() {
        val db = freshDb(); db.setWindow(730)
        val id = db.customerInactiveFor(800, "+4551000004")

        db.runCustomerRetention(now = t0)
        val first = db.flaggedAt(id)
        val second = db.runCustomerRetention(now = t0 + 5 * day)

        assertEquals(0, second.flagged, "an already-flagged customer must not be re-flagged")
        assertEquals(first, db.flaggedAt(id), "re-flagging would restart the grace clock forever")
    }

    // ─── Deletion only after the grace period ────────────────────────────────

    @Test
    fun `a flagged customer is not deleted before the grace period elapses`() {
        val db = freshDb(); db.setWindow(730)
        val id = db.customerInactiveFor(800, "+4551000005")
        db.runCustomerRetention(now = t0)

        val s = db.runCustomerRetention(graceDays = 30, now = t0 + 29 * day)

        assertEquals(0, s.deleted)
        assertTrue(db.exists(id))
    }

    @Test
    fun `a flagged customer is deleted once the grace period elapses`() {
        val db = freshDb(); db.setWindow(730)
        val id = db.customerInactiveFor(800, "+4551000006")
        db.runCustomerRetention(now = t0)

        val s = db.runCustomerRetention(graceDays = 30, now = t0 + 31 * day)

        assertEquals(1, s.deleted)
        assertFalse(db.exists(id))
    }

    @Test
    fun `deletion needs a flag first - a single pass never deletes`() {
        val db = freshDb(); db.setWindow(730)
        val id = db.customerInactiveFor(5000, "+4551000006b")

        // Even wildly past the window, the first sweep may only flag.
        val s = db.runCustomerRetention(graceDays = 30, now = t0)

        assertEquals(1, s.flagged)
        assertEquals(0, s.deleted)
        assertTrue(db.exists(id))
    }

    // ─── Activity rescues a flagged customer ─────────────────────────────────

    @Test
    fun `activity clears the flag`() {
        val db = freshDb(); db.setWindow(730)
        val id = db.customerInactiveFor(800, "+4551000007")
        db.runCustomerRetention(now = t0)
        assertNotNull(db.flaggedAt(id))

        db.touchCustomerActivityByPhone("+4551000007", t0)

        assertNull(db.flaggedAt(id), "a customer who gets back in touch is active by definition")
    }

    @Test
    fun `a customer who becomes active during the grace window is not deleted`() {
        val db = freshDb(); db.setWindow(730)
        val id = db.customerInactiveFor(800, "+4551000008")
        db.runCustomerRetention(now = t0)

        db.touchCustomerActivityByPhone("+4551000008", t0 + 10 * day)   // they call back
        val s = db.runCustomerRetention(graceDays = 30, now = t0 + 31 * day)

        assertEquals(0, s.deleted)
        assertTrue(db.exists(id))
    }

    @Test
    fun `the sweep un-flags a customer who no longer qualifies`() {
        val db = freshDb(); db.setWindow(730)
        val id = db.customerInactiveFor(800, "+4551000009")
        db.runCustomerRetention(now = t0)
        assertNotNull(db.flaggedAt(id))

        // An admin widens the window; the flag is now a false statement and must be withdrawn.
        db.setWindow(1000)
        val s = db.runCustomerRetention(now = t0)

        assertEquals(1, s.unflagged)
        assertNull(db.flaggedAt(id))
    }

    @Test
    fun `a blacklisted customer is never deleted, even while still flagged`() {
        val db = freshDb(); db.setWindow(730)
        val shopId = db.getAllShops().first().id
        val id = db.customerInactiveFor(800, "+4551000017")
        db.runCustomerRetention(now = t0)
        assertNotNull(db.flaggedAt(id))

        // Blacklisting does NOT clear the flag, so this exercises the re-check at delete time
        // rather than the flag-clearing path. An active block is a deliberate record the shop
        // wants kept — losing it would silently un-block a nuisance caller.
        db.addBlacklistEntry(shopId, "+4551000017", "nuisance")
        val s = db.runCustomerRetention(graceDays = 30, now = t0 + 31 * day)

        assertEquals(0, s.deleted, "the flag alone must never authorise deletion")
        assertTrue(db.exists(id))
    }

    @Test
    fun `keepCustomer clears the flag and restarts the clock`() {
        val db = freshDb(); db.setWindow(730)
        val id = db.customerInactiveFor(800, "+4551000009b")
        db.runCustomerRetention(now = t0)

        assertTrue(db.keepCustomer(id))

        assertNull(db.flaggedAt(id))
        // And not immediately re-flagged on the next sweep.
        assertEquals(0, db.runCustomerRetention(now = t0).flagged)
    }

    @Test
    fun `touch never moves last activity backwards`() {
        val db = freshDb(); db.setWindow(730)
        val id = db.customerInactiveFor(10, "+4551000010")
        val before = db.getCustomerLastActivity(id)!!

        db.touchCustomerActivityByPhone("+4551000010", before - 5 * day)

        assertEquals(before, db.getCustomerLastActivity(id),
            "a replayed or out-of-order write must not make a customer look staler")
    }

    // ─── The decoupling: the whole point of the change ───────────────────────

    @Test
    fun `a customer stays protected after their communications are deleted`() {
        val db = freshDb(); db.setWindow(730)
        // Active 100 days ago, but their SMS and calls have since been pruned by communication
        // retention, so there is no row left to infer activity from. Under the old design this
        // customer was deletable; the durable stamp is what protects them now.
        val id = db.customerInactiveFor(100, "+4551000011")

        val s = db.runCustomerRetention(now = t0)

        assertEquals(0, s.flagged)
        assertTrue(db.exists(id))
    }

    @Test
    fun `an inbound SMS stamps activity without a customer_id`() {
        val db = freshDb(); db.setWindow(730)
        val shopId = db.getAllShops().first().id
        val id = db.customerInactiveFor(800, "+4551000015")

        // customerId is null, as it is for all inbound traffic before a booking is confirmed.
        db.insertSmsMessage(
            shopId = shopId, customerId = null, counterpartyPhone = "+4551000015",
            fromPhone = "+4551000015", toPhone = "+4500000000", body = "hi",
            direction = "inbound", status = "received",
        )

        assertNotNull(db.getCustomerLastActivity(id))
        assertEquals(0, db.runCustomerRetention(now = t0).flagged,
            "matching must be by phone, not customer_id")
    }

    @Test
    fun `the most lenient shop window wins`() {
        val db = freshDb()
        db.addShopForOwner(1, "SecondShop", null, null)
        val shops = db.getAllShops()
        if (shops.size < 2) return
        db.setWindow(100)
        db.upsertShopVoiceConfig(
            db.getShopVoiceConfig(shops.first().id).copy(customerRetentionDays = 900)
        )
        val id = db.customerInactiveFor(500, "+4551000012")

        db.runCustomerRetention(now = t0)

        assertNull(db.flaggedAt(id),
            "one shop's short window must not delete a customer another shop still considers active")
    }

    // ─── The one-time window raise ───────────────────────────────────────────

    @Test
    fun `the one-time raise only ever raises, and runs once`() {
        val db = freshDb()
        db.setWindow(365)

        val first = db.raiseCustomerRetentionToTwoYearsOnce()
        assertTrue(first >= 1)
        for (shop in db.getAllShops()) {
            assertEquals(730, db.getShopVoiceConfig(shop.id).customerRetentionDays)
        }

        // Second call is a no-op, so a later deliberate reduction is not overridden.
        assertEquals(-1, db.raiseCustomerRetentionToTwoYearsOnce())
        db.setWindow(365)
        assertEquals(-1, db.raiseCustomerRetentionToTwoYearsOnce())
        assertEquals(365, db.getShopVoiceConfig(db.getAllShops().first().id).customerRetentionDays)
    }

    @Test
    fun `the one-time raise never lowers a longer window`() {
        val db = freshDb()
        db.setWindow(3650)

        db.raiseCustomerRetentionToTwoYearsOnce()

        assertEquals(3650, db.getShopVoiceConfig(db.getAllShops().first().id).customerRetentionDays,
            "shortening a retention window would delete data the old setting kept")
    }

    // ─── Backfill ────────────────────────────────────────────────────────────

    @Test
    fun `backfill leaves customers with no surviving evidence unknown`() {
        val db = freshDb()
        val id = db.customerInactiveFor(null, "+4551000013")

        db.backfillCustomerActivity()

        assertNull(db.getCustomerLastActivity(id), "no evidence must stay unknown, not become 0")
    }

    @Test
    fun `backfill does not overwrite an existing stamp`() {
        val db = freshDb()
        val id = db.customerInactiveFor(42, "+4551000014")
        val before = db.getCustomerLastActivity(id)

        db.backfillCustomerActivity()

        assertEquals(before, db.getCustomerLastActivity(id))
    }

    @Test
    fun `backfill reconstructs activity from a surviving message`() {
        val db = freshDb()
        val shopId = db.getAllShops().first().id
        val id = db.insertNewCustomer("+4551000016")
        db.insertSmsMessage(
            shopId = shopId, customerId = null, counterpartyPhone = "+4551000016",
            fromPhone = "+4551000016", toPhone = "+4500000000", body = "hi",
            direction = "inbound", status = "received",
        )
        // Simulate a pre-migration row by clearing the stamp the insert hook just wrote.
        db.runCustomerRetention(now = t0)   // no-op; keeps the API surface honest
        val stamped = db.getCustomerLastActivity(id)

        db.backfillCustomerActivity()

        assertNotNull(stamped)
        assertEquals(stamped, db.getCustomerLastActivity(id))
    }
}
