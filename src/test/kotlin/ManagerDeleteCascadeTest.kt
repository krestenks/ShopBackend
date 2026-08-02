import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * deleteManager must cascade the manager's call-pool (shop_manager) and duty
 * (manager_duty) rows. Otherwise a deleted manager lingers in shops' covering sets
 * (getManagerIdsForShop) — which drives the dialplan's dialable mgr{id} targets — and
 * in duty-aware routing. Runs against a throwaway on-disk SQLite DB (no Asterisk).
 */
class ManagerDeleteCascadeTest {

    private fun freshDb(): DataBase {
        val f = Files.createTempFile("shopmgr-test", ".db")
        Files.deleteIfExists(f) // let DataBase create it fresh
        return DataBase(f.toString())
    }

    @Test
    fun `deleteManager cascades pool and duty rows and drops the manager from covering sets`() {
        val db = freshDb()
        val keep = db.addManagerForOwner(1, "Keep", "keep", "pw", null)
        val gone = db.addManagerForOwner(1, "Gone", "gone", "pw", null)

        // Both cover shop 5 via the call pool; both explicitly OFF duty (a row exists —
        // note isManagerOnDuty defaults to true when absent, so we assert on false→true).
        db.setShopPool(5, listOf(keep, gone))
        db.setManagerDuty(keep, false)
        db.setManagerDuty(gone, false)

        assertTrue(db.getManagerIdsForShop(5).containsAll(listOf(keep, gone)))
        assertFalse(db.isManagerOnDuty(gone))
        assertFalse(db.isManagerOnDuty(keep))

        db.deleteManager(gone)

        // Pool + covering set no longer include the deleted manager; the survivor stays.
        assertEquals(listOf(keep), db.getShopPoolManagerIds(5))
        assertFalse(db.getManagerIdsForShop(5).contains(gone))
        assertTrue(db.getManagerIdsForShop(5).contains(keep))
        // Duty row cascaded → reverts to the absent-row default (true); survivor untouched.
        assertTrue(db.isManagerOnDuty(gone))
        assertFalse(db.isManagerOnDuty(keep))
        // Manager record itself removed.
        assertNull(db.getManagerById(gone))
    }
}
