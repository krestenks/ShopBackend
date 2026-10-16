import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Tests for tenant-wide number blocking.
 *
 * Enforcement was already tenant-wide — a block at any shop rejected the caller everywhere — but
 * the rows were per shop and **removal only ever touched one row**. So unblocking at one shop left
 * the caller blocked by another shop's row, invisibly, because the app's list was scoped to one
 * shop and could not show the other. Two numbers were in exactly that state on 2026-10-16.
 *
 * The properties under test are therefore the symmetry (block and unblock cover the same set) and
 * the history, without which "was this number blocked at 17:10?" is unanswerable — the question
 * that took six wrong hypotheses to settle because `created_at` was overwritten on every re-block
 * and removal recorded nothing at all.
 */
class TenantBlacklistTest {

    private val phone = "+4527822182"

    /** A tenant with three shops, plus a second tenant to prove isolation. */
    private class Fixture(val db: DataBase, val a1: Int, val a2: Int, val a3: Int, val b1: Int)

    private fun fixture(): Fixture {
        val f = Files.createTempFile("blacklist-test", ".db")
        Files.deleteIfExists(f)
        val db = DataBase(f.toString())
        val a1 = db.addShopForOwner(1, "Aim", null, null)
        val a2 = db.addShopForOwner(1, "JJ", null, null)
        val a3 = db.addShopForOwner(1, "Koi", null, null)
        val b1 = db.addShopForOwner(2, "OtherTenant", null, null)
        return Fixture(db, a1, a2, a3, b1)
    }

    // ─── Blocking covers the tenant ──────────────────────────────────────────

    @Test
    fun `a block at one shop blocks every shop of that tenant`() {
        val f = fixture()
        f.db.blockPhoneForOwner(1, phone, "nuisance", "manager:2", f.a1)

        for (shop in listOf(f.a1, f.a2, f.a3)) {
            assertTrue(f.db.isPhoneBlockedForShop(shop, phone),
                "shop $shop must reject a number blocked anywhere in the tenant")
        }
    }

    @Test
    fun `another tenant is unaffected`() {
        val f = fixture()
        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a1)

        assertFalse(f.db.isPhoneBlockedForShop(f.b1, phone),
            "one tenant's block must not reach another tenant's line")
    }

    // ─── The bug: unblocking must cover the same set ─────────────────────────

    @Test
    fun `unblocking lifts the block on every shop, not just the one it was added from`() {
        val f = fixture()
        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a1)
        f.db.unblockPhoneForOwner(1, phone, "manager:2")

        for (shop in listOf(f.a1, f.a2, f.a3)) {
            assertFalse(f.db.isPhoneBlockedForShop(shop, phone), "still blocked at shop $shop")
        }
    }

    /**
     * Builds the legacy per-shop shape that still exists in production: a separate row per shop.
     * [blockPhoneForOwner] cannot produce it any more, and a test that used it would pass against
     * a single row while proving nothing about the rows that caused the bug.
     */
    private fun Fixture.legacyRowsAtEveryShop(number: String) {
        val now = System.currentTimeMillis()
        db.insertLegacyPerShopBlacklistRow(a1, 1, number, now)
        db.insertLegacyPerShopBlacklistRow(a2, 1, number, now)
        db.insertLegacyPerShopBlacklistRow(a3, 1, number, now)
        check(db.countBlacklistRows(number, activeOnly = true) == 3) {
            "fixture must really create three rows, or this test is vacuous"
        }
    }

    @Test
    fun `unblocking clears rows left by SEVERAL shops - the state two numbers were stuck in`() {
        val f = fixture()
        f.legacyRowsAtEveryShop(phone)
        assertTrue(f.db.isPhoneBlockedForShop(f.a2, phone))

        // One unblock, from one shop.
        assertTrue(f.db.removeBlacklistEntry(f.a1, phone), "unblock should report success")

        assertEquals(0, f.db.countBlacklistRows(phone, activeOnly = true),
            "every row must be lifted, not just the one the unblock was issued from")
        assertFalse(f.db.isPhoneBlockedForShop(f.a1, phone))
        assertFalse(f.db.isPhoneBlockedForShop(f.a2, phone),
            "this is the bug: another shop's row kept the caller blocked with nothing in the UI")
        assertFalse(f.db.isPhoneBlockedForShop(f.a3, phone))
    }

    @Test
    fun `deleting by entry id also lifts the whole tenant`() {
        val f = fixture()
        f.legacyRowsAtEveryShop(phone)
        val entry = f.db.listBlacklist(f.a1).single { it.phoneE164 == phone }

        assertTrue(f.db.removeBlacklistEntryById(entry.id))
        assertEquals(0, f.db.countBlacklistRows(phone, activeOnly = true),
            "deleting one id must lift the number everywhere, since that is how it is enforced")
        assertFalse(f.db.isPhoneBlockedForShop(f.a2, phone), "the other shop's row must be lifted too")
    }

    @Test
    fun `the legacy multi-row shape still shows as ONE entry in the list`() {
        val f = fixture()
        f.legacyRowsAtEveryShop(phone)

        assertEquals(1, f.db.listBlacklist(f.a1).count { it.phoneE164 == phone },
            "three rows for one number must not appear as three blocks to remove")
    }

    // ─── One list, shared by every shop ─────────────────────────────────────

    @Test
    fun `every shop of the tenant sees the same list, once per number`() {
        val f = fixture()
        f.db.addBlacklistEntry(f.a1, phone, null)
        f.db.addBlacklistEntry(f.a2, phone, null)      // same number, second shop
        f.db.addBlacklistEntry(f.a2, "+4511112222", null)

        val fromA1 = f.db.listBlacklist(f.a1).map { it.phoneE164 }.sorted()
        val fromA3 = f.db.listBlacklist(f.a3).map { it.phoneE164 }.sorted()

        assertEquals(fromA1, fromA3, "a block must be visible from every shop, so it can be removed")
        assertEquals(listOf("+4511112222", phone), fromA1)
        assertEquals(1, fromA1.count { it == phone }, "one entry per number, not one per shop row")
    }

    @Test
    fun `another tenant's list does not leak`() {
        val f = fixture()
        f.db.addBlacklistEntry(f.a1, phone, null)
        assertTrue(f.db.listBlacklist(f.b1).none { it.phoneE164 == phone })
    }

    // ─── created_at must survive a re-block ─────────────────────────────────

    @Test
    fun `re-blocking does not overwrite the original block time`() {
        val f = fixture()
        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a1)
        val first = f.db.listBlacklist(f.a1).single().createdAt

        Thread.sleep(5)
        f.db.unblockPhoneForOwner(1, phone, "manager:2")
        Thread.sleep(5)
        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a2)

        assertEquals(first, f.db.listBlacklist(f.a1).single().createdAt,
            "created_at means 'first blocked'; overwriting it is what made 2026-10-16 unanswerable")
    }

    // ─── History ─────────────────────────────────────────────────────────────

    @Test
    fun `block and unblock are both recorded, with who did it`() {
        val f = fixture()
        f.db.blockPhoneForOwner(1, phone, "threats", "manager:2", f.a1)
        f.db.unblockPhoneForOwner(1, phone, "admin")

        val h = f.db.getBlacklistHistory(phone)
        assertEquals(2, h.size, "both transitions must be recorded")
        assertEquals("unblock", h[0].action, "newest first")
        assertEquals("admin", h[0].actor)
        assertEquals("block", h[1].action)
        assertEquals("manager:2", h[1].actor)
        assertEquals("threats", h[1].reason)
    }

    @Test
    fun `an unblock that changed nothing is not recorded as one`() {
        val f = fixture()
        assertEquals(0, f.db.unblockPhoneForOwner(1, phone, "admin"),
            "nothing was blocked, so nothing was lifted")
        assertTrue(f.db.getBlacklistHistory(phone).isEmpty(),
            "a no-op must not leave a misleading 'unblock' in the history")
    }

    @Test
    fun `deleting a customer records the unblock it silently performs`() {
        val f = fixture()
        val id = f.db.insertNewCustomer(phone)
        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a1)

        f.db.deleteCustomer(id)

        assertFalse(f.db.isPhoneBlockedForShop(f.a1, phone), "customer delete lifts the block")
        val h = f.db.getBlacklistHistory(phone)
        assertEquals("unblock", h.first().action)
        assertTrue(h.first().actor!!.startsWith("customer-delete:"),
            "the history must name what lifted it, or the block vanishing is unexplainable")
    }

    // ─── Point-in-time: the question we could not answer ────────────────────

    @Test
    fun `was-blocked-at distinguishes blocked, not blocked, and unknown`() {
        val f = fixture()
        val before = System.currentTimeMillis()

        // Nothing recorded yet: "unknown", NOT "no".
        assertNull(f.db.wasPhoneBlockedAt(phone, before),
            "answering 'no' from missing history is the mistake this exists to prevent")

        Thread.sleep(5)
        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a1)
        val whileBlocked = System.currentTimeMillis()
        Thread.sleep(5)
        f.db.unblockPhoneForOwner(1, phone, "admin")
        Thread.sleep(5)
        val afterUnblock = System.currentTimeMillis()

        assertEquals(true, f.db.wasPhoneBlockedAt(phone, whileBlocked))
        assertEquals(false, f.db.wasPhoneBlockedAt(phone, afterUnblock))
        assertNull(f.db.wasPhoneBlockedAt(phone, before),
            "still unknown before any history existed")
    }

    @Test
    fun `the 2026-10-16 shape - a block recorded after the call - reads as unknown, not as no`() {
        // That incident: calls at 17:08 and 17:10 rang through, and the number's only blacklist
        // row carried created_at 21:29 because a later re-block had overwritten it. From the row
        // alone it was impossible to say whether a block existed at 17:10.
        //
        // History fixes that for everything from here on, but it must NOT pretend to answer for
        // instants it has no record of. An instant before the first recorded event is "unknown",
        // and claiming "not blocked" there would be the same unfounded confidence that sent the
        // original investigation down six wrong paths.
        val f = fixture()
        val seventeenTen = System.currentTimeMillis()
        Thread.sleep(5)
        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a1)   // blocked later that evening

        assertNull(f.db.wasPhoneBlockedAt(phone, seventeenTen),
            "no record at that instant -> 'cannot tell', never a confident 'no'")
        assertEquals(true, f.db.wasPhoneBlockedAt(phone, System.currentTimeMillis()),
            "and from the block onwards the answer is definite")

        // A second call AFTER the block is now answerable with certainty — the case this unlocks.
        f.db.unblockPhoneForOwner(1, phone, "admin")
        Thread.sleep(5)
        assertEquals(false, f.db.wasPhoneBlockedAt(phone, System.currentTimeMillis()),
            "once there is history, 'not blocked' is a fact rather than an absence of evidence")
    }

    // ─── Enforcement edges ──────────────────────────────────────────────────

    @Test
    fun `a blank number is never blocked`() {
        val f = fixture()
        assertFalse(f.db.isPhoneBlockedForShop(f.a1, ""))
        assertFalse(f.db.blockPhoneForOwner(1, "   ", null, "admin", f.a1))
    }

    @Test
    fun `blocking is idempotent and leaves the number blocked`() {
        val f = fixture()
        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a1)
        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a2)
        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a3)

        assertTrue(f.db.isPhoneBlockedForShop(f.a2, phone))
        assertEquals(1, f.db.listBlacklist(f.a1).count { it.phoneE164 == phone })
    }

    @Test
    fun `a number blocked then unblocked then blocked again ends blocked`() {
        val f = fixture()
        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a1)
        f.db.unblockPhoneForOwner(1, phone, "admin")
        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a1)

        assertTrue(f.db.isPhoneBlockedForShop(f.a3, phone))
        assertEquals(3, f.db.getBlacklistHistory(phone).size, "all three transitions recorded")
    }

    @Test
    fun `deactivatedAt is actually stored on unblock and cleared on re-block`() {
        val f = fixture()
        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a1)
        assertNull(f.db.getBlacklistRow(1, phone)!!.deactivatedAt,
            "a freshly blocked row has never been lifted")

        val before = System.currentTimeMillis()
        f.db.unblockPhoneForOwner(1, phone, "admin")

        // Read the row itself, not the active-only list: a list that filters out inactive rows can
        // only ever report null here, which is how the first version of this test passed against
        // the data-class default while the column was in fact write-only.
        val lifted = f.db.getBlacklistRow(1, phone)!!
        assertFalse(lifted.active)
        assertNotNull(lifted.deactivatedAt, "unblocking must record WHEN it happened")
        assertTrue(lifted.deactivatedAt!! >= before, "and it must be the time of the unblock")

        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a1)
        val reblocked = f.db.getBlacklistRow(1, phone)!!
        assertTrue(reblocked.active)
        assertNull(reblocked.deactivatedAt,
            "a re-blocked row must not still carry a deactivation time")
    }

    // ─── Rows whose stored owner has drifted must stay removable ────────────

    @Test
    fun `a row with a NULL owner_id is still listed and still removable`() {
        val f = fixture()
        // The shape a missing backfill or an un-cascaded owner change would leave behind.
        // Enforcement resolves the owner through `shops`, so such a row IS enforced — if listing
        // or removal keyed on phone_blacklist.owner_id instead, it would be a block with no way
        // out: rejected on every call, invisible in the app, and impossible to lift.
        f.db.insertLegacyPerShopBlacklistRow(f.a1, null, phone, System.currentTimeMillis())

        assertTrue(f.db.isPhoneBlockedForShop(f.a1, phone), "it must be enforced")
        assertTrue(f.db.listBlacklist(f.a1).any { it.phoneE164 == phone },
            "anything enforced must be visible, or it cannot be removed")
        assertTrue(f.db.removeBlacklistEntry(f.a1, phone), "and it must actually come off")
        assertFalse(f.db.isPhoneBlockedForShop(f.a1, phone))
    }

    @Test
    fun `a row whose stored owner points at the WRONG tenant is still removable`() {
        val f = fixture()
        // Shop a1 belongs to owner 1, but the row claims owner 2 — what reassigning a shop without
        // cascading used to leave behind.
        f.db.insertLegacyPerShopBlacklistRow(f.a1, 2, phone, System.currentTimeMillis())

        assertTrue(f.db.isPhoneBlockedForShop(f.a1, phone), "enforcement follows the shop's owner")
        assertTrue(f.db.listBlacklist(f.a1).any { it.phoneE164 == phone },
            "so listing must follow the shop's owner too, not the stale column")
        assertTrue(f.db.removeBlacklistEntry(f.a1, phone))
        assertFalse(f.db.isPhoneBlockedForShop(f.a1, phone))
    }

    // ─── Inconclusive owner must not authorise a hang-up ────────────────────

    @Test
    fun `a shop with no owner fails OPEN, because the action it gates is destructive`() {
        val f = fixture()
        f.db.blockPhoneForOwner(1, phone, null, "manager:2", f.a1)
        // addShop is the legacy ownerless insert; the startup backfill already ran, so this row
        // keeps owner_id NULL.
        f.db.addShop("Ownerless", null, null)
        val ownerless = f.db.getAllShops().single { it.name == "Ownerless" }.id
        check(f.db.getOwnerIdForShop(ownerless) == null) {
            "fixture must really produce an ownerless shop, or this test proves nothing"
        }

        // The tenant cannot be determined, so the blacklist cannot be evaluated. Rejecting here
        // would hang up on a caller using WEAK evidence, and an earlier draft went further still
        // and matched every tenant's blocks. docs/recovery-code-rules.md §1-2: let it through and
        // raise the alarm instead.
        assertFalse(f.db.isPhoneBlockedForShop(ownerless, phone),
            "an unresolvable owner must never authorise cause 21 on a caller")
    }
}
