import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.YearMonth

/** Accounts per therapist: job lines per day, day and month totals, validation and owner scoping. Amounts are øre. */
class DailyAccountsTest {

    private val november = YearMonth.of(2026, 11)
    private val day10 = LocalDate.of(2026, 11, 10)

    private class Setup(val db: DataBase, val file: java.nio.file.Path, val shopA: Int, val shopB: Int, val therapist: Int, val otherOwnerTherapist: Int) {
        fun jobRowCount(employeeId: Int): Int =
            java.sql.DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { c ->
                c.createStatement().use { st ->
                    val rs = st.executeQuery("SELECT COUNT(*) FROM account_jobs WHERE employee_id = $employeeId")
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

    private fun job(line: Int, cash: Long = 0, mobilepay: Long = 0, revolut: Long = 0, food: Long = 0) =
        AccountJobDto(line, cash, mobilepay, revolut, food)

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
    fun `a day with no entries has no job lines`() {
        val s = setup()
        val d = s.db.getAccountsDay(s.therapist, day10)
        assertEquals("2026-11-10", d.day)
        assertTrue(d.jobs.isEmpty())
        assertEquals(0L, d.totals.total)
    }

    @Test
    fun `a job total is received minus food, and the day sums its jobs`() {
        val s = setup()
        val saved = s.db.saveAccountsDay(
            s.therapist, day10,
            listOf(
                job(1, cash = 50000, mobilepay = 120000, food = 4500),
                job(2, revolut = 30000),
                job(3, cash = 10000, food = 12000),
            ),
        )
        assertEquals(3, saved.jobs.size)
        assertEquals(50000 + 120000 - 4500L, saved.jobs[0].total)
        assertEquals(30000L, saved.jobs[1].total)
        assertEquals(-2000L, saved.jobs[2].total, "a job can be negative")
        assertEquals(60000L, saved.totals.cash)
        assertEquals(120000L, saved.totals.mobilepay)
        assertEquals(30000L, saved.totals.revolut)
        assertEquals(16500L, saved.totals.food)
        assertEquals(saved.jobs.sumOf { it.total }, saved.totals.total)
    }

    @Test
    fun `the month shows the sum of each day's jobs and a month row`() {
        val s = setup()
        s.db.saveAccountsDay(s.therapist, day10, listOf(job(1, cash = 1000), job(2, cash = 500, food = 100)))
        s.db.saveAccountsDay(s.therapist, LocalDate.of(2026, 11, 11), listOf(job(1, mobilepay = 700)))
        val m = s.db.getAccountsMonth(s.therapist, november)
        val d10 = m.days[9]
        assertEquals(1500L, d10.cash)
        assertEquals(100L, d10.food)
        assertEquals(1400L, d10.total)
        assertEquals(700L, m.days[10].total)
        assertEquals(1500L, m.totals.cash)
        assertEquals(700L, m.totals.mobilepay)
        assertEquals(2100L, m.totals.total)
        assertEquals(m.days.sumOf { it.total }, m.totals.total)
        assertEquals(0L, s.db.getAccountsMonth(s.therapist, YearMonth.of(2026, 10)).totals.total, "another month is untouched")
    }

    @Test
    fun `a save replaces the whole day, and lines keep their position`() {
        val s = setup()
        s.db.saveAccountsDay(s.therapist, day10, listOf(job(1, cash = 100), job(2, cash = 200), job(4, cash = 400)))
        val saved = s.db.saveAccountsDay(s.therapist, day10, listOf(job(2, cash = 250), job(5, cash = 500)))
        assertEquals(listOf(2, 5), saved.jobs.map { it.line }, "lines left out are removed, empty positions stay empty")
        assertEquals(250L, saved.jobs[0].cash)
        assertEquals(750L, saved.totals.cash)
    }

    @Test
    fun `a job line with every amount zero is not stored`() {
        val s = setup()
        s.db.saveAccountsDay(s.therapist, day10, listOf(job(1, cash = 1000), job(2)))
        assertEquals(1, s.jobRowCount(s.therapist))
        s.db.saveAccountsDay(s.therapist, day10, listOf(job(1)))
        assertEquals(0, s.jobRowCount(s.therapist))
    }

    @Test
    fun `a save changes only that day`() {
        val s = setup()
        s.db.saveAccountsDay(s.therapist, LocalDate.of(2026, 11, 1), listOf(job(1, cash = 100)))
        s.db.saveAccountsDay(s.therapist, LocalDate.of(2026, 11, 2), listOf(job(1, cash = 200)))
        s.db.saveAccountsDay(s.therapist, LocalDate.of(2026, 11, 2), listOf(job(1, cash = 999)))
        val m = s.db.getAccountsMonth(s.therapist, november)
        assertEquals(100L, m.days[0].cash)
        assertEquals(999L, m.days[1].cash)
    }

    @Test
    fun `more than five job lines are stored, up to the limit`() {
        val s = setup()
        val many = (1..ACCOUNT_JOB_MAX_LINES).map { job(it, cash = 100L * it) }
        val saved = s.db.saveAccountsDay(s.therapist, day10, many)
        assertEquals(ACCOUNT_JOB_MAX_LINES, saved.jobs.size)
        assertEquals(100L * ACCOUNT_JOB_MAX_LINES * (ACCOUNT_JOB_MAX_LINES + 1) / 2, saved.totals.cash)
    }

    @Test
    fun `bad input is refused and nothing from that save is stored`() {
        val s = setup()
        fun refused(vararg jobs: AccountJobDto) {
            assertThrows(IllegalArgumentException::class.java) { s.db.saveAccountsDay(s.therapist, day10, jobs.toList()) }
        }
        refused(job(0, cash = 1))
        refused(job(ACCOUNT_JOB_MAX_LINES + 1, cash = 1))
        refused(job(1, cash = -1))
        refused(job(1, food = -5))
        refused(job(1, cash = ACCOUNT_AMOUNT_MAX_ORE + 1))
        refused(job(1, cash = 1), job(1, cash = 2))          // repeated line
        // A good line followed by a bad one: the whole save is refused.
        refused(job(1, cash = 5000), job(2, cash = -1))
        assertEquals(0, s.jobRowCount(s.therapist))
        // A refused save leaves the day as it was.
        s.db.saveAccountsDay(s.therapist, day10, listOf(job(1, cash = 300)))
        refused(job(1, cash = 9), job(2, cash = -1))
        assertEquals(300L, s.db.getAccountsDay(s.therapist, day10).totals.cash)
        // The limit itself is accepted.
        s.db.saveAccountsDay(s.therapist, day10, listOf(job(1, cash = ACCOUNT_AMOUNT_MAX_ORE)))
        assertEquals(ACCOUNT_AMOUNT_MAX_ORE, s.db.getAccountsMonth(s.therapist, november).totals.cash)
    }

    @Test
    fun `entries belong to the therapist, so a move to another shop keeps them`() {
        val s = setup()
        s.db.saveAccountsDay(s.therapist, day10, listOf(job(1, cash = 7700)))
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
        s.db.saveAccountsDay(s.therapist, day10, listOf(job(1, cash = 7700)))
        s.db.saveAccountsDay(s.otherOwnerTherapist, day10, listOf(job(1, cash = 11)))
        assertEquals(7700L, s.db.getAccountsMonth(s.therapist, november).totals.cash)
        s.db.deleteEmployee(s.therapist)
        assertEquals(1, s.jobRowCount(s.therapist), "the entries are kept")
        assertEquals(11L, s.db.getAccountsMonth(s.otherOwnerTherapist, november).totals.cash)
    }

    @Test
    fun `month and date parsers accept only their own format`() {
        assertEquals(november, parseAccountsMonth("2026-11"))
        assertNull(parseAccountsMonth(null))
        assertNull(parseAccountsMonth("2026-13"))
        assertNull(parseAccountsMonth("2026-1"))
        assertNull(parseAccountsMonth("November"))
        assertEquals(day10, parseAccountsDate("2026-11-10"))
        assertNull(parseAccountsDate(null))
        assertNull(parseAccountsDate("2026-11-31"))
        assertNull(parseAccountsDate("2026-11"))
        assertNull(parseAccountsDate("10 Nov"))
    }
}
