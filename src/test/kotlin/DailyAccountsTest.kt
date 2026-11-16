import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.YearMonth

/** Daily accounts per therapist: storage, totals, validation and owner scoping. Amounts are øre. */
class DailyAccountsTest {

    private val november = YearMonth.of(2026, 11)

    private class Setup(val db: DataBase, val file: java.nio.file.Path, val shopA: Int, val shopB: Int, val therapist: Int, val otherOwnerTherapist: Int) {
        fun accountRowCount(employeeId: Int): Int =
            java.sql.DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { c ->
                c.createStatement().use { st ->
                    val rs = st.executeQuery("SELECT COUNT(*) FROM daily_accounts WHERE employee_id = $employeeId")
                    rs.next(); rs.getInt(1)
                }
            }
    }

    private fun setup(): Setup {
        val f = Files.createTempFile("accounts-test", ".db")
        Files.deleteIfExists(f)
        val db = DataBase(f.toString())
        db.addOwner("Second owner", "second")
        val shopA = db.addShopForOwner(1, "ShopA", null, null)
        val shopB = db.addShopForOwner(1, "ShopB", null, null)
        val otherShop = db.addShopForOwner(2, "OtherTenant", null, null)
        db.addEmployee("Mai", null)
        val mai = db.getAllEmployees().maxOf { it.id }
        db.assignEmployeeToShop(mai, shopA, exclusive = true)
        db.addEmployee("Zed", null)
        val zed = db.getAllEmployees().maxOf { it.id }
        db.assignEmployeeToShop(zed, otherShop, exclusive = true)
        return Setup(db, f, shopA, shopB, mai, zed)
    }

    private fun day(date: String, cash: Long = 0, mobilepay: Long = 0, revolut: Long = 0, food: Long = 0) =
        AccountDayDto(date, cash, mobilepay, revolut, food)

    @Test
    fun `a month lists every date, with zero for days that were not entered`() {
        val s = setup()
        val m = s.db.getAccountsMonth(s.therapist, november)
        assertEquals("2026-11", m.month)
        assertEquals(30, m.days.size)
        assertEquals("2026-11-01", m.days.first().day)
        assertEquals("2026-11-30", m.days.last().day)
        assertTrue(m.days.all { it.total == 0L })
        assertEquals(0L, m.totals.total)
        assertEquals("2026-11", m.totals.day)
        assertEquals(31, s.db.getAccountsMonth(s.therapist, YearMonth.of(2026, 12)).days.size)
        assertEquals(29, s.db.getAccountsMonth(s.therapist, YearMonth.of(2028, 2)).days.size, "leap year")
    }

    @Test
    fun `day total is received minus food and the month row sums each column`() {
        val s = setup()
        val saved = s.db.saveAccountsMonth(
            s.therapist, november,
            listOf(
                day("2026-11-03", cash = 50000, mobilepay = 120000, revolut = 30000, food = 4500),
                day("2026-11-04", cash = 10000, food = 12000),
            ),
        )
        val d3 = saved.days.first { it.day == "2026-11-03" }
        assertEquals(50000 + 120000 + 30000 - 4500L, d3.total)
        assertEquals(10000 - 12000L, saved.days.first { it.day == "2026-11-04" }.total, "a day can be negative")
        assertEquals(60000L, saved.totals.cash)
        assertEquals(120000L, saved.totals.mobilepay)
        assertEquals(30000L, saved.totals.revolut)
        assertEquals(16500L, saved.totals.food)
        assertEquals(60000 + 120000 + 30000 - 16500L, saved.totals.total)
        assertEquals(saved.days.sumOf { it.total }, saved.totals.total, "the month total equals the sum of the day totals")
    }

    @Test
    fun `entries are kept when the month is read again, and a later save replaces the day`() {
        val s = setup()
        s.db.saveAccountsMonth(s.therapist, november, listOf(day("2026-11-10", cash = 1000)))
        assertEquals(1000L, s.db.getAccountsMonth(s.therapist, november).days[9].cash)
        s.db.saveAccountsMonth(s.therapist, november, listOf(day("2026-11-10", cash = 2500, food = 100)))
        val d = s.db.getAccountsMonth(s.therapist, november).days[9]
        assertEquals(2500L, d.cash)
        assertEquals(100L, d.food)
        assertEquals(0L, s.db.getAccountsMonth(s.therapist, YearMonth.of(2026, 10)).totals.cash, "another month is untouched")
    }

    @Test
    fun `a day with every amount zero is removed`() {
        val s = setup()
        s.db.saveAccountsMonth(s.therapist, november, listOf(day("2026-11-10", cash = 1000)))
        s.db.saveAccountsMonth(s.therapist, november, listOf(day("2026-11-10")))
        val rows = s.accountRowCount(s.therapist)
        assertEquals(0, rows)
    }

    @Test
    fun `a save changes only the days it names`() {
        val s = setup()
        s.db.saveAccountsMonth(s.therapist, november, listOf(day("2026-11-01", cash = 100), day("2026-11-02", cash = 200)))
        s.db.saveAccountsMonth(s.therapist, november, listOf(day("2026-11-02", cash = 999)))
        val m = s.db.getAccountsMonth(s.therapist, november)
        assertEquals(100L, m.days[0].cash)
        assertEquals(999L, m.days[1].cash)
    }

    @Test
    fun `bad input is refused and nothing from that save is stored`() {
        val s = setup()
        fun refused(vararg days: AccountDayDto) {
            assertThrows(IllegalArgumentException::class.java) { s.db.saveAccountsMonth(s.therapist, november, days.toList()) }
        }
        refused(day("not-a-date", cash = 1))
        refused(day("2026-11-31", cash = 1))                 // no such date
        refused(day("2026-12-01", cash = 1))                 // another month
        refused(day("2026-11-05", cash = -1))
        refused(day("2026-11-05", food = -5))
        refused(day("2026-11-05", cash = ACCOUNT_AMOUNT_MAX_ORE + 1))
        refused(day("2026-11-05", cash = 1), day("2026-11-05", cash = 2))   // repeated day
        // A good day followed by a bad one: the whole save is refused.
        refused(day("2026-11-06", cash = 5000), day("2026-11-07", cash = -1))
        assertEquals(0, s.accountRowCount(s.therapist))
        // The limit itself is accepted.
        s.db.saveAccountsMonth(s.therapist, november, listOf(day("2026-11-08", cash = ACCOUNT_AMOUNT_MAX_ORE)))
        assertEquals(ACCOUNT_AMOUNT_MAX_ORE, s.db.getAccountsMonth(s.therapist, november).totals.cash)
    }

    @Test
    fun `entries belong to the therapist, so a move to another shop keeps them`() {
        val s = setup()
        s.db.saveAccountsMonth(s.therapist, november, listOf(day("2026-11-12", cash = 7700)))
        s.db.moveTherapist(s.therapist, 1, s.shopB)
        assertEquals(7700L, s.db.getAccountsMonth(s.therapist, november).totals.cash)
    }

    @Test
    fun `the owner check used by the routes hides another owner's therapist`() {
        val s = setup()
        assertNull(s.db.getTherapistForOwner(s.otherOwnerTherapist, 1))
        assertEquals(s.therapist, s.db.getTherapistForOwner(s.therapist, 1)?.id)
    }

    @Test
    fun `a deleted therapist's entries stay, and another therapist's entries are separate`() {
        val s = setup()
        s.db.saveAccountsMonth(s.therapist, november, listOf(day("2026-11-12", cash = 7700)))
        s.db.saveAccountsMonth(s.otherOwnerTherapist, november, listOf(day("2026-11-12", cash = 11)))
        assertEquals(7700L, s.db.getAccountsMonth(s.therapist, november).totals.cash)
        s.db.deleteEmployee(s.therapist)
        assertEquals(1, s.accountRowCount(s.therapist), "the entries are kept")
        assertEquals(11L, s.db.getAccountsMonth(s.otherOwnerTherapist, november).totals.cash)
    }

    @Test
    fun `month parser accepts yyyy-MM only`() {
        assertEquals(november, parseAccountsMonth("2026-11"))
        assertNull(parseAccountsMonth(null))
        assertNull(parseAccountsMonth(""))
        assertNull(parseAccountsMonth("2026-13"))
        assertNull(parseAccountsMonth("2026-1"))
        assertNull(parseAccountsMonth("November"))
    }
}
