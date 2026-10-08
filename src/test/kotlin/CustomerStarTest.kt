import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * The follow-up star on a customer.
 *
 * Two things matter most. The star has to reach every list the app filters on (conversations,
 * calls, search), keyed by phone because rows often have no customer id. And a starred customer
 * must be exempt from customer retention: a manager flagged this person for follow-up, so the
 * sweep deleting their profile 30 days later would silently throw that flag away.
 */
class CustomerStarTest {

    private val day = 24L * 60 * 60 * 1000
    private val t0 = System.currentTimeMillis()
    private val phone = "+4551941736"

    private fun freshDb(): DataBase {
        val f = Files.createTempFile("star-test", ".db")
        Files.deleteIfExists(f)
        return DataBase(f.toString())
    }

    private fun DataBase.sms(shopId: Int, from: String = phone, body: String = "hello needle") = insertSmsMessage(
        shopId = shopId, customerId = null, counterpartyPhone = from, fromPhone = from,
        toPhone = "+4560000000", body = body, direction = "inbound", status = "received",
    )

    @Test
    fun `starring a number with no customer row creates one and stars it`() {
        val db = freshDb()
        val shop = db.addShopForOwner(1, "Alpha", null, null)
        db.sms(shop)
        assertNull(db.getCustomerIdByPhone(phone))
        assertFalse(db.isCustomerStarredByPhone(phone), "an unknown number is simply not starred")
        assertNull(db.getCustomerIdByPhone(phone), "reading the state must not create a customer")

        val id = db.setCustomerStarredByPhone(phone, true)
        assertTrue(db.isCustomerStarredByPhone(phone))

        assertEquals(id, db.getCustomerIdByPhone(phone))
        assertTrue(db.getCustomerById(id)!!.starred)
    }

    @Test
    fun `un-starring clears it and a new customer starts un-starred`() {
        val db = freshDb()
        val id = db.setCustomerStarredByPhone(phone, true)
        db.setCustomerStarredByPhone(phone, false)
        assertFalse(db.getCustomerById(id)!!.starred)
        assertFalse(db.getCustomerById(db.insertNewCustomer("+4551000009"))!!.starred)
    }

    @Test
    fun `the star shows on conversation, search and call rows by phone`() {
        val db = freshDb()
        val shop = db.addShopForOwner(1, "Alpha", null, null)
        val other = "+4552222222"
        db.sms(shop)
        db.sms(shop, from = other, body = "other needle")
        db.createInboundCallLog(shop, "call-1", phone, "+4560000000")
        db.createInboundCallLog(shop, "call-2", other, "+4560000000")

        db.setCustomerStarredByPhone(phone, true)

        val convos = db.getAllSmsConversationsAcrossShops(listOf(shop)).associate { it.counterpartyPhone to it.starred }
        assertEquals(mapOf(phone to true, other to false), convos)

        val unhandled = db.getUnhandledSmsNotifications(listOf(shop)).associate { it.counterpartyPhone to it.starred }
        assertEquals(mapOf(phone to true, other to false), unhandled)

        val msgs = db.searchSmsMessages(listOf(shop), "needle").associate { it.counterpartyPhone to it.starred }
        assertEquals(mapOf(phone to true, other to false), msgs)

        val threads = db.searchSmsThreads(listOf(shop), "5194").associate { it.counterpartyPhone to it.starred }
        assertEquals(mapOf(phone to true), threads)

        val calls = db.getRecentCallsForShop(shop).associate { it.fromPhone to it.starred }
        assertEquals(mapOf(phone to true, other to false), calls)
        assertTrue(db.searchCalls(listOf(shop), "51941736").single().starred)
    }

    @Test
    fun `a shop may only star numbers it has had contact with`() {
        val db = freshDb()
        val a = db.addShopForOwner(1, "Alpha", null, null)
        val b = db.addShopForOwner(1, "Beta", null, null)
        db.sms(a)
        db.createInboundCallLog(b, "call-b", "+4553333333", "+4560000000")

        assertTrue(db.shopHasContactWith(a, phone))
        assertFalse(db.shopHasContactWith(b, phone), "contact is per shop")
        assertTrue(db.shopHasContactWith(b, "+4553333333"), "a call counts as contact")
        assertFalse(db.shopHasContactWith(a, "+4599999999"))
    }

    // ─── Retention ───────────────────────────────────────────────────────────

    private fun DataBase.retentionReady(): DataBase {
        if (getAllShops().isEmpty()) addShopForOwner(1, "RetentionTest", null, null)
        for (shop in getAllShops()) {
            upsertShopVoiceConfig(getShopVoiceConfig(shop.id).copy(customerRetentionDays = 730))
        }
        return this
    }

    @Test
    fun `a starred customer is never flagged or deleted by retention`() {
        val db = freshDb().retentionReady()
        val starred = db.insertNewCustomer("+4551000001").also { db.touchCustomerActivity(it, t0 - 900 * day) }
        val plain = db.insertNewCustomer("+4551000002").also { db.touchCustomerActivity(it, t0 - 900 * day) }
        db.setCustomerStarredByPhone("+4551000001", true)

        val first = db.runCustomerRetention(now = t0)
        // Well past the grace period: the plain one goes, the starred one must still be there.
        val second = db.runCustomerRetention(now = t0 + 400 * day)

        assertEquals(1, first.flagged, "only the un-starred customer is flagged")
        assertEquals(1, second.deleted)
        assertNotNull(db.getCustomerById(starred), "the starred customer survives")
        assertNull(db.getCustomerById(plain), "an un-starred inactive customer is still removed")
    }

    @Test
    fun `starring a customer already flagged for deletion withdraws the flag`() {
        val db = freshDb().retentionReady()
        val id = db.insertNewCustomer("+4551000003").also { db.touchCustomerActivity(it, t0 - 900 * day) }
        assertEquals(1, db.runCustomerRetention(now = t0).flagged)
        assertTrue(db.getCustomersFlaggedForDeletion().any { it.id == id })

        db.setCustomerStarredByPhone("+4551000003", true)
        val s = db.runCustomerRetention(now = t0 + 40 * day)

        assertEquals(0, s.deleted, "the grace period had elapsed, but the star protects")
        assertEquals(1, s.unflagged)
        assertTrue(db.getCustomersFlaggedForDeletion().none { it.id == id })
        assertNotNull(db.getCustomerById(id))
    }
}
