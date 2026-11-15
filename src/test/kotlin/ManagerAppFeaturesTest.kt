import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Therapist page, quick messages, customer timeline and the owner scoping of customers.
 * Owner 1 has shops Aor (1 therapist) and Duo (2 therapists); owner 2 has one shop.
 */
class ManagerAppFeaturesTest {

    private class Env(val file: Path, val db: DataBase) {
        val raw: Connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        fun exec(sql: String) = raw.createStatement().use { it.executeUpdate(sql) }
        fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): T =
            raw.createStatement().use { s -> val rs = s.executeQuery(sql); rs.next(); read(rs) }
    }

    private fun env(): Env {
        val f = Files.createTempFile("features-test", ".db")
        Files.deleteIfExists(f)
        return Env(f, DataBase(f.toString()))
    }

    private fun DataBase.employee(name: String, phone: String?, shopId: Int): Int {
        addEmployee(name, phone)
        val id = getAllEmployees().maxOf { it.id }
        assignEmployeeToShop(id, shopId, exclusive = true)
        return id
    }

    private class World(
        val e: Env, val aor: Int, val duo: Int, val other: Int,
        val aorTherapist: Int, val duoOne: Int, val duoTwo: Int, val otherTherapist: Int,
    ) { val db get() = e.db }

    private fun world(): World {
        val e = env()
        val db = e.db
        assertEquals(2, db.addOwner("Second owner", "second"), "owner 1 is the default owner")
        val aor = db.addShopForOwner(1, "Aor", null, null)
        val duo = db.addShopForOwner(1, "Duo", null, null)
        val other = db.addShopForOwner(2, "OtherTenant", null, null)
        e.exec("INSERT INTO shop_telephony_config (shop_id, phone_number) VALUES ($aor, '+4550000001')")
        e.exec("INSERT INTO shop_telephony_config (shop_id, phone_number) VALUES ($duo, '+4550000002')")
        val a = db.employee("Old Aor", "+4560000001", aor)
        val d1 = db.employee("Mai", null, duo)
        val d2 = db.employee("Lek", "  ", duo)
        val o = db.employee("Zed", null, other)
        return World(e, aor, duo, other, a, d1, d2, o)
    }

    // ── Therapists ────────────────────────────────────────────────────────────

    @Test
    fun `therapists list shows the number mode, the line and the mobile, for the owner only`() {
        val w = world()
        val list = w.db.getTherapistsForOwner(1)

        assertEquals(listOf("Old Aor", "Mai", "Lek"), list.map { it.name })
        val aor = list.first { it.id == w.aorTherapist }
        assertEquals("dedicated", aor.numberMode)
        assertEquals("+4550000001", aor.lineNumber)
        assertEquals("+4560000001", aor.mobile)
        val mai = list.first { it.id == w.duoOne }
        assertEquals("shared", mai.numberMode)
        assertEquals("+4550000002", mai.lineNumber)
        assertNull(mai.mobile)
        assertNull(list.first { it.id == w.duoTwo }.mobile, "a blank mobile is no mobile")
        assertFalse(list.any { it.id == w.otherTherapist }, "another owner's therapist is not listed")
        assertNull(w.db.getTherapistForOwner(w.otherTherapist, 1))
    }

    @Test
    fun `renaming the only therapist of a shop renames the shop too`() {
        val w = world()

        val r = w.db.renameTherapist(w.aorTherapist, 1, "  Aor Noi  ")!!

        assertEquals("Aor Noi", r.therapist.name)
        assertEquals("Aor Noi", r.therapist.shopName)
        assertTrue(r.employeeRenamed)
        assertTrue(r.shopRenamed)
        assertEquals("Aor Noi", w.e.query("SELECT name FROM shops WHERE id = ${w.aor}") { it.getString(1) })
    }

    @Test
    fun `renaming to the name the shop already has reports no shop change`() {
        val w = world()
        val r = w.db.renameTherapist(w.aorTherapist, 1, "Aor")!!
        assertTrue(r.employeeRenamed)
        assertFalse(r.shopRenamed, "the shop already had this name")
        assertEquals("Aor", r.therapist.shopName)
    }

    @Test
    fun `renaming a therapist in a shared shop leaves the shop name alone`() {
        val w = world()

        val r = w.db.renameTherapist(w.duoOne, 1, "Mai Mai")!!

        assertEquals("Mai Mai", r.therapist.name)
        assertEquals("Duo", r.therapist.shopName)
        assertTrue(r.employeeRenamed)
        assertFalse(r.shopRenamed)
        assertEquals("Duo", w.e.query("SELECT name FROM shops WHERE id = ${w.duo}") { it.getString(1) })
    }

    @Test
    fun `a rename that changes nothing reports nothing changed`() {
        val w = world()
        val r = w.db.renameTherapist(w.duoOne, 1, "Mai")!!
        assertFalse(r.employeeRenamed)
        assertFalse(r.shopRenamed)
    }

    @Test
    fun `a bad name is rejected and nothing changes`() {
        val w = world()
        assertThrows(IllegalArgumentException::class.java) { w.db.renameTherapist(w.aorTherapist, 1, "   ") }
        assertThrows(IllegalArgumentException::class.java) { w.db.renameTherapist(w.aorTherapist, 1, "x".repeat(THERAPIST_NAME_MAX_LENGTH + 1)) }
        assertEquals("Old Aor", w.db.getTherapistForOwner(w.aorTherapist, 1)!!.name)
        assertEquals("Aor", w.e.query("SELECT name FROM shops WHERE id = ${w.aor}") { it.getString(1) })
    }

    @Test
    fun `another owner cannot rename or move a therapist`() {
        val w = world()
        assertNull(w.db.renameTherapist(w.aorTherapist, 2, "Hijack"))
        assertNull(w.db.moveTherapist(w.aorTherapist, 2, w.other))
        assertEquals("Old Aor", w.db.getTherapistForOwner(w.aorTherapist, 1)!!.name)
        assertEquals(w.aor, w.db.getTherapistForOwner(w.aorTherapist, 1)!!.shopId)
    }

    @Test
    fun `moving to another shop of the owner changes the shop and the number mode`() {
        val w = world()

        val moved = w.db.moveTherapist(w.aorTherapist, 1, w.duo)!!

        assertEquals(w.duo, moved.shopId)
        assertEquals("shared", moved.numberMode)
        assertEquals("+4550000002", moved.lineNumber)
        assertEquals(1, w.e.query("SELECT COUNT(*) FROM employee_shop WHERE employee_id = ${w.aorTherapist}") { it.getInt(1) })
        // The two shops that were shared are now one and three: the remaining one is not "dedicated" by accident.
        assertEquals(3, w.db.getTherapistsForOwner(1).count { it.shopId == w.duo })
    }

    @Test
    fun `moving to a shop of another owner is refused and nothing changes`() {
        val w = world()
        assertNull(w.db.moveTherapist(w.aorTherapist, 1, w.other))
        assertEquals(w.aor, w.db.getTherapistForOwner(w.aorTherapist, 1)!!.shopId)
    }

    @Test
    fun `after a move the therapist left alone in a shop makes it dedicated`() {
        val w = world()
        w.db.moveTherapist(w.duoTwo, 1, w.aor)
        assertEquals("dedicated", w.db.getTherapistForOwner(w.duoOne, 1)!!.numberMode)
        val r = w.db.renameTherapist(w.duoOne, 1, "Mai Solo")!!
        assertTrue(r.shopRenamed, "the shop is now the therapist's own")
        assertEquals("Mai Solo", r.therapist.shopName)
    }

    // ── Quick messages ────────────────────────────────────────────────────────

    @Test
    fun `quick messages keep their order and are private to the manager`() {
        val db = env().db
        val a = db.addQuickMessage(10, 1, "Hello")
        val b = db.addQuickMessage(10, 1, "Running 10 minutes late 🙏")
        db.addQuickMessage(11, 1, "Not yours")

        assertEquals(listOf("Hello", "Running 10 minutes late 🙏"), db.getQuickMessages(10).map { it.text })
        assertEquals(listOf("Not yours"), db.getQuickMessages(11).map { it.text })
        assertFalse(db.updateQuickMessage(a.id, 11, "Changed by someone else"))
        assertFalse(db.deleteQuickMessage(b.id, 11))
        assertEquals("Hello", db.getQuickMessages(10)[0].text)
        assertEquals(2, db.getQuickMessages(10).size)
    }

    @Test
    fun `a manager can edit and delete their own messages`() {
        val db = env().db
        val a = db.addQuickMessage(10, 1, "Hello")
        assertTrue(db.updateQuickMessage(a.id, 10, "  Hi there  "))
        assertEquals("Hi there", db.getQuickMessages(10).single().text)
        assertTrue(db.deleteQuickMessage(a.id, 10))
        assertTrue(db.getQuickMessages(10).isEmpty())
    }

    @Test
    fun `the list is limited in size and each text in length`() {
        val db = env().db
        repeat(QUICK_MESSAGE_MAX_COUNT) { db.addQuickMessage(10, 1, "m$it") }
        assertThrows(IllegalArgumentException::class.java) { db.addQuickMessage(10, 1, "one too many") }
        db.addQuickMessage(11, 1, "x".repeat(QUICK_MESSAGE_MAX_LENGTH))
        assertThrows(IllegalArgumentException::class.java) { db.addQuickMessage(11, 1, "x".repeat(QUICK_MESSAGE_MAX_LENGTH + 1)) }
        assertThrows(IllegalArgumentException::class.java) { db.addQuickMessage(11, 1, "  ") }
        // An emoji counts as one character, not two.
        val db2 = env().db
        db2.addQuickMessage(12, 1, "🙏".repeat(QUICK_MESSAGE_MAX_LENGTH))
        assertThrows(IllegalArgumentException::class.java) { db2.addQuickMessage(12, 1, "🙏".repeat(QUICK_MESSAGE_MAX_LENGTH + 1)) }
    }

    @Test
    fun `reordering needs exactly the manager's own messages`() {
        val db = env().db
        val a = db.addQuickMessage(10, 1, "A")
        val b = db.addQuickMessage(10, 1, "B")
        val c = db.addQuickMessage(10, 1, "C")
        val foreign = db.addQuickMessage(11, 1, "Foreign")

        assertTrue(db.reorderQuickMessages(10, listOf(c.id, a.id, b.id)))
        assertEquals(listOf("C", "A", "B"), db.getQuickMessages(10).map { it.text })

        assertFalse(db.reorderQuickMessages(10, listOf(a.id, b.id)), "a missing id")
        assertFalse(db.reorderQuickMessages(10, listOf(a.id, b.id, c.id, foreign.id)), "someone else's id")
        assertFalse(db.reorderQuickMessages(10, listOf(a.id, a.id, b.id)), "a repeated id")
        assertEquals(listOf("C", "A", "B"), db.getQuickMessages(10).map { it.text })
        assertEquals(listOf("Foreign"), db.getQuickMessages(11).map { it.text })
    }

    // ── Customers per owner ───────────────────────────────────────────────────

    @Test
    fun `the same number is a separate customer for each owner`() {
        val w = world()
        val c1 = w.db.ensureCustomerByPhone("+4551000200", w.aor)
        val c2 = w.db.ensureCustomerByPhone("+4551000200", w.other)

        assertTrue(c1 != c2)
        assertEquals(1, w.db.getOwnerIdForCustomer(c1))
        assertEquals(2, w.db.getOwnerIdForCustomer(c2))
        assertEquals(c1, w.db.ensureCustomerByPhone("+4551000200", w.duo), "same owner, same customer")
        assertEquals(c2, w.db.ensureCustomerByPhone("+4551000200", w.other))
    }

    @Test
    fun `a customer without an owner is found, not duplicated, by the owner's shop`() {
        val w = world()
        val orphan = w.db.insertNewCustomer("+4551000201")
        assertNull(w.db.getOwnerIdForCustomer(orphan))
        assertEquals(orphan, w.db.ensureCustomerByPhone("+4551000201", w.aor))
    }

    @Test
    fun `startup gives an ownerless customer the owner of the shop it dealt with`() {
        val w = world()
        val viaSms = w.db.insertNewCustomer("+4551000210")
        val viaCall = w.db.insertNewCustomer("+4551000211")
        val noContact = w.db.insertNewCustomer("+4551000212")
        w.e.exec("INSERT INTO sms_message (shop_id, customer_id, counterparty_phone, from_phone, to_phone, body, direction, status, created_at) " +
            "VALUES (${w.other}, $viaSms, '+4551000210', '+4551000210', '+4550000009', 'hi', 'inbound', 'received', 1000)")
        w.e.exec("INSERT INTO voice_call (shop_id, provider_call_id, from_phone, to_phone, customer_id, started_at) " +
            "VALUES (${w.duo}, 'c1', '+4551000211', '+4550000002', $viaCall, 1000)")

        val reopened = DataBase(w.e.file.toString())

        assertEquals(2, reopened.getOwnerIdForCustomer(viaSms))
        assertEquals(1, reopened.getOwnerIdForCustomer(viaCall))
        assertNull(reopened.getOwnerIdForCustomer(noContact), "two owners: no guess")
    }

    @Test
    fun `with a single owner startup gives it the ownerless customers`() {
        val e = env()
        e.db.addShopForOwner(1, "Only", null, null)
        e.exec("DELETE FROM owners WHERE id <> 1")
        val orphan = e.db.insertNewCustomer("+4551000220")

        val reopened = DataBase(e.file.toString())

        assertEquals(1, reopened.getOwnerIdForCustomer(orphan))
    }

    // ── Timeline ──────────────────────────────────────────────────────────────

    private fun World.sms(shop: Int, customer: Int?, phone: String, at: Long, direction: String, body: String) =
        e.exec("INSERT INTO sms_message (shop_id, customer_id, counterparty_phone, from_phone, to_phone, body, direction, status, created_at) " +
            "VALUES ($shop, ${customer ?: "NULL"}, '$phone', '$phone', '+4550000009', '$body', '$direction', 'sent', $at)")

    private fun World.call(shop: Int, customer: Int?, from: String, to: String, start: Long, end: Long?, outcome: String) =
        e.exec("INSERT INTO voice_call (shop_id, provider_call_id, from_phone, to_phone, customer_id, outcome, started_at, ended_at) " +
            "VALUES ($shop, 'p$start$shop', '$from', '$to', ${customer ?: "NULL"}, '$outcome', $start, ${end ?: "NULL"})")

    private val phone = "+4551000300"

    private fun timelineWorld(): Pair<World, Int> {
        val w = world()
        val c = w.db.ensureCustomerByPhone(phone, w.aor)
        w.sms(w.aor, c, phone, 1000, "inbound", "hello")
        w.sms(w.duo, null, phone, 3000, "outbound", "reply")           // no customer id: matched by number
        w.call(w.aor, c, phone, "+4550000001", 2000, 62_000, "ANSWERED")   // customer called in
        w.call(w.duo, null, "+4550000002", phone, 4000, null, "IN_PROGRESS") // we called out, no end yet
        w.sms(w.other, null, phone, 5000, "inbound", "other tenant")       // other owner: never shown
        return w to c
    }

    @Test
    fun `timeline merges calls and sms across shops, newest first, for the owner only`() {
        val (w, c) = timelineWorld()

        val t = w.db.getCustomerTimeline(c, 1, null, null, 50)!!

        assertEquals(listOf(4000L, 3000L, 2000L, 1000L), t.entries.map { it.at })
        assertEquals(listOf("call", "sms", "call", "sms"), t.entries.map { it.type })
        assertNull(t.nextBefore)
        val inboundCall = t.entries[2]
        assertEquals("inbound", inboundCall.direction)
        assertEquals(60L, inboundCall.durationSeconds)
        assertEquals("ANSWERED", inboundCall.outcome)
        val outboundCall = t.entries[0]
        assertEquals("outbound", outboundCall.direction)
        assertNull(outboundCall.durationSeconds, "a call without an end has no duration")
        assertEquals("reply", t.entries[1].body)
        assertEquals("outbound", t.entries[1].direction)
        assertEquals("Duo", t.entries[1].shopName)
    }

    @Test
    fun `timeline can be limited to one shop`() {
        val (w, c) = timelineWorld()
        val t = w.db.getCustomerTimeline(c, 1, w.aor, null, 50)!!
        assertEquals(listOf(2000L, 1000L), t.entries.map { it.at })
        assertTrue(t.entries.all { it.shopId == w.aor })
    }

    @Test
    fun `timeline pages with before and nextBefore without gaps or repeats`() {
        val (w, c) = timelineWorld()

        val first = w.db.getCustomerTimeline(c, 1, null, null, 3)!!
        assertEquals(listOf(4000L, 3000L, 2000L), first.entries.map { it.at })
        assertEquals(2000L, first.nextBefore)

        val second = w.db.getCustomerTimeline(c, 1, null, first.nextBefore, 3)!!
        assertEquals(listOf(1000L), second.entries.map { it.at })
        assertNull(second.nextBefore)
    }

    @Test
    fun `timeline refuses another owner's customer, an unknown customer and another owner's shop`() {
        val (w, c) = timelineWorld()
        assertNull(w.db.getCustomerTimeline(c, 2, null, null, 50), "customer of owner 1 asked by owner 2")
        assertNull(w.db.getCustomerTimeline(999_999, 1, null, null, 50))
        assertNull(w.db.getCustomerTimeline(c, 1, w.other, null, 50), "a shop of another owner")
        assertNotNull(w.db.getCustomerTimeline(c, 1, w.duo, null, 50))
    }
}
