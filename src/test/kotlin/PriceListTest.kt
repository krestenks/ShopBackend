import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** The price list is free text with emojis, saved per therapist and listed per shop. */
class PriceListTest {

    private class Setup(val db: DataBase, val shopA: Int, val shopB: Int, val mai: Int, val bo: Int, val zed: Int)

    private fun setup(): Setup {
        val f = Files.createTempFile("pricelist-test", ".db")
        Files.deleteIfExists(f)
        val db = DataBase(f.toString())
        val shopA = db.addShopForOwner(1, "ShopA", null, null)
        val shopB = db.addShopForOwner(1, "ShopB", null, null)
        fun therapist(name: String, shop: Int): Int {
            db.addEmployee(name, null)
            val id = db.getAllEmployees().maxOf { it.id }
            db.assignEmployeeToShop(id, shop, exclusive = true)
            return id
        }
        val mai = therapist("Mai", shopA)
        val bo = therapist("bo", shopA)
        val zed = therapist("Zed", shopB)
        return Setup(db, shopA, shopB, mai, bo, zed)
    }

    private val sample = "💆 Thai massage 60 min – 500 kr\n✨ Oil massage 90 min – 700 kr\n🙏 Thank you"

    @Test
    fun `a therapist with no saved list has an empty text`() {
        val s = setup()
        val lists = s.db.getPriceListsForShop(s.shopB)
        assertEquals(1, lists.size)
        assertEquals("Zed", lists[0].name)
        assertEquals("", lists[0].priceList)
    }

    @Test
    fun `text with emojis and line breaks is saved and read back unchanged`() {
        val s = setup()
        assertEquals(sample, s.db.setTherapistPriceList(s.mai, sample))
        assertEquals(sample, s.db.getPriceListsForShop(s.shopA).first { it.therapistId == s.mai }.priceList)
    }

    @Test
    fun `line breaks are stored as newline and the ends are trimmed`() {
        val s = setup()
        assertEquals("a\nb\nc", s.db.setTherapistPriceList(s.mai, "  a\r\nb\rc \n\n"))
    }

    @Test
    fun `a blank text clears the list, and a new save replaces the old one`() {
        val s = setup()
        s.db.setTherapistPriceList(s.mai, "old")
        s.db.setTherapistPriceList(s.mai, "new")
        assertEquals("new", s.db.getPriceListsForShop(s.shopA).first { it.therapistId == s.mai }.priceList)
        assertEquals("", s.db.setTherapistPriceList(s.mai, "  \n "))
        assertEquals("", s.db.getPriceListsForShop(s.shopA).first { it.therapistId == s.mai }.priceList)
    }

    @Test
    fun `the length limit counts an emoji as two and a refused save keeps the old text`() {
        val s = setup()
        val limit = PRICE_LIST_MAX_LENGTH
        s.db.setTherapistPriceList(s.mai, "x".repeat(limit))
        assertThrows(IllegalArgumentException::class.java) { s.db.setTherapistPriceList(s.mai, "x".repeat(limit + 1)) }
        assertEquals("x".repeat(limit), s.db.getPriceListsForShop(s.shopA).first { it.therapistId == s.mai }.priceList)
        s.db.setTherapistPriceList(s.mai, "💆".repeat(limit / 2))
        assertThrows(IllegalArgumentException::class.java) { s.db.setTherapistPriceList(s.mai, "💆".repeat(limit / 2 + 1)) }
    }

    @Test
    fun `each therapist has their own list, listed by shop in name order`() {
        val s = setup()
        s.db.setTherapistPriceList(s.mai, "Mai list")
        s.db.setTherapistPriceList(s.bo, "Bo list")
        s.db.setTherapistPriceList(s.zed, "Zed list")
        val shopA = s.db.getPriceListsForShop(s.shopA)
        assertEquals(listOf("bo", "Mai"), shopA.map { it.name }, "name order ignores case")
        assertEquals(listOf("Bo list", "Mai list"), shopA.map { it.priceList })
        assertEquals(listOf("Zed list"), s.db.getPriceListsForShop(s.shopB).map { it.priceList })
    }

    @Test
    fun `the list belongs to the therapist, so a move to another shop takes it along`() {
        val s = setup()
        s.db.setTherapistPriceList(s.mai, "Mai list")
        s.db.moveTherapist(s.mai, 1, s.shopB)
        assertTrue(s.db.getPriceListsForShop(s.shopA).none { it.therapistId == s.mai })
        assertEquals("Mai list", s.db.getPriceListsForShop(s.shopB).first { it.therapistId == s.mai }.priceList)
    }
}
