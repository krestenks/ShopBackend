import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager

/**
 * A customer created automatically from a call or message gets the owner of the shop that had
 * the contact, so it is visible to that owner's managers without waiting for a restart.
 */
class CustomerOwnerOnInsertTest {

    private fun freshDbFile() = Files.createTempFile("owner-on-insert", ".db").also { Files.deleteIfExists(it) }

    private fun ownerOf(dbFile: java.nio.file.Path, customerId: Int): Int? =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { c ->
            c.prepareStatement("SELECT owner_id FROM customers WHERE id = ?").use { s ->
                s.setInt(1, customerId)
                val rs = s.executeQuery()
                check(rs.next())
                rs.getInt(1).takeIf { !rs.wasNull() }
            }
        }

    @Test
    fun `a new customer gets the owner of the shop`() {
        val file = freshDbFile()
        val db = DataBase(file.toString())
        val shopOfOwner2 = db.addShopForOwner(2, "OtherTenant", null, null)

        val id = db.ensureCustomerByPhone("+4551000100", shopOfOwner2)

        assertEquals(2, ownerOf(file, id))
    }

    @Test
    fun `the stub insert gets the owner of the shop`() {
        val file = freshDbFile()
        val db = DataBase(file.toString())
        val shop = db.addShopForOwner(1, "Alpha", null, null)

        val id = db.insertNewCustomer("+4551000101", shop)

        assertEquals(1, ownerOf(file, id))
    }

    @Test
    fun `starring an unknown number creates the customer with the shop owner`() {
        val file = freshDbFile()
        val db = DataBase(file.toString())
        val shop = db.addShopForOwner(2, "OtherTenant", null, null)

        val id = db.setCustomerStarredByPhone("+4551000102", true, shop)

        assertEquals(2, ownerOf(file, id))
    }

    @Test
    fun `without a shop the owner stays empty`() {
        val file = freshDbFile()
        val db = DataBase(file.toString())

        val id = db.insertNewCustomer("+4551000103")

        assertNull(ownerOf(file, id))
    }

    @Test
    fun `an existing customer keeps its owner, and another owner gets its own customer`() {
        val file = freshDbFile()
        val db = DataBase(file.toString())
        val first = db.addShopForOwner(1, "Alpha", null, null)
        val sameOwner = db.addShopForOwner(1, "Beta", null, null)
        val other = db.addShopForOwner(2, "OtherTenant", null, null)

        val id = db.ensureCustomerByPhone("+4551000104", first)

        assertEquals(id, db.ensureCustomerByPhone("+4551000104", sameOwner))
        assertEquals(1, ownerOf(file, id))
        val otherId = db.ensureCustomerByPhone("+4551000104", other)
        assertEquals(2, ownerOf(file, otherId))
        assertEquals(1, ownerOf(file, id), "the first owner's customer is untouched")
    }
}
