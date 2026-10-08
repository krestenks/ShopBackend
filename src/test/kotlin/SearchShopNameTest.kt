import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * The manager app labels search results and calls with the shop they belong to, so the shop name
 * has to come back on message hits, conversation hits and call records.
 *
 * The shop is joined with a LEFT JOIN on purpose: a row whose shop is missing (deleted shop, old
 * data) must still be returned, just without a name. Dropping it would hide a customer's message
 * or a call from the manager, which is worse than an unlabelled row. The call query is shared by
 * the live call paths, so that case is pinned here too.
 */
class SearchShopNameTest {

    private fun freshDb(): DataBase {
        val f = Files.createTempFile("search-shop-test", ".db")
        Files.deleteIfExists(f)
        return DataBase(f.toString())
    }

    private val phone = "+4551941736"

    private fun DataBase.sms(shopId: Int, body: String) = insertSmsMessage(
        shopId = shopId, customerId = null, counterpartyPhone = phone, fromPhone = phone,
        toPhone = "+4560000000", body = body, direction = "inbound", status = "received",
    )

    @Test
    fun `message, conversation and call hits carry the shop name`() {
        val db = freshDb()
        val shopA = db.addShopForOwner(1, "Alpha Nails", null, null)
        val shopB = db.addShopForOwner(1, "Beta Spa", null, null)
        db.sms(shopA, "hello from alpha needle")
        db.sms(shopB, "hello from beta needle")
        db.createInboundCallLog(shopB, "call-1", phone, "+4560000000")

        val messages = db.searchSmsMessages(listOf(shopA, shopB), "needle")
        assertEquals(
            mapOf(shopA to "Alpha Nails", shopB to "Beta Spa"),
            messages.associate { it.shopId to it.shopName },
        )

        val threads = db.searchSmsThreads(listOf(shopA, shopB), "51941736")
        assertEquals(
            mapOf(shopA to "Alpha Nails", shopB to "Beta Spa"),
            threads.associate { it.shopId to it.shopName },
        )

        val calls = db.searchCalls(listOf(shopA, shopB), "51941736")
        assertEquals(1, calls.size)
        assertEquals("Beta Spa", calls.single().shopName)
    }

    @Test
    fun `a row whose shop is missing is still returned, without a name`() {
        val db = freshDb()
        val orphanShop = 999   // no such row in shops
        db.sms(orphanShop, "orphan needle")
        db.createInboundCallLog(orphanShop, "call-orphan", phone, "+4560000000")

        val msg = db.searchSmsMessages(listOf(orphanShop), "needle").single()
        assertNull(msg.shopName)

        assertNull(db.searchSmsThreads(listOf(orphanShop), "51941736").single().shopName)

        // The shared call select feeds the live call paths too: it must not drop the row.
        val call = db.searchCalls(listOf(orphanShop), "51941736").single()
        assertNull(call.shopName)
        assertEquals(1, db.getRecentCallsForShop(orphanShop).size)
        assertEquals(1, db.getActiveCallsForShop(orphanShop).size)
    }

    @Test
    fun `the call select used by the call paths returns the shop name and one row per call`() {
        val db = freshDb()
        val shop = db.addShopForOwner(1, "Gamma Hair", null, null)
        db.createInboundCallLog(shop, "call-a", phone, "+4560000000")
        db.createInboundCallLog(shop, "call-b", phone, "+4560000000")

        val recent = db.getRecentCallsForShop(shop)
        assertEquals(2, recent.size, "the shops join must not multiply or drop call rows")
        assertEquals(setOf("Gamma Hair"), recent.map { it.shopName }.toSet())
        assertEquals(2, db.getActiveCallsForShop(shop).size)
    }
}
