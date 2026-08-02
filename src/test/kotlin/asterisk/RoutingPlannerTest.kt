package asterisk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks down the pure routing-plan assembly that feeds the dialplan — specifically the
 * manager-to-manager peer logic and its reprovision-on-pool-change behaviour, without
 * needing Asterisk or a live DB (the provisioner just supplies these projections).
 */
class RoutingPlannerTest {

    @Test
    fun `adding a manager to a shop pool surfaces them as a peer`() {
        // Shop 1's primary is mgr 10; only mgr 10 covers it → no peer.
        val before = RoutingPlanner.managerEntries(
            managerIds = listOf(10, 20),
            coveredShopsByManager = mapOf(10 to listOf(1), 20 to emptyList()),
            managerIdsByShop = mapOf(1 to listOf(10)),
            gsmShopIds = setOf(1),
        )
        assertTrue(before.first { it.managerId == 10 }.peerManagerIds.isEmpty())

        // Add mgr 20 to shop 1's pool → shop 1 is now covered by {10, 20}.
        val after = RoutingPlanner.managerEntries(
            managerIds = listOf(10, 20),
            coveredShopsByManager = mapOf(10 to listOf(1), 20 to listOf(1)),
            managerIdsByShop = mapOf(1 to listOf(10, 20)),
            gsmShopIds = setOf(1),
        )
        assertEquals(listOf(20), after.first { it.managerId == 10 }.peerManagerIds)
        assertEquals(listOf(10), after.first { it.managerId == 20 }.peerManagerIds)
    }

    @Test
    fun `managers without a shared shop are not peers`() {
        val entries = RoutingPlanner.managerEntries(
            managerIds = listOf(10, 20),
            coveredShopsByManager = mapOf(10 to listOf(1), 20 to listOf(2)),
            managerIdsByShop = mapOf(1 to listOf(10), 2 to listOf(20)),
            gsmShopIds = emptySet(),
        )
        assertTrue(entries.first { it.managerId == 10 }.peerManagerIds.isEmpty())
        assertTrue(entries.first { it.managerId == 20 }.peerManagerIds.isEmpty())
    }

    @Test
    fun `a manager is never their own peer and peers dedupe across shared shops`() {
        // mgr 10 covers shops 1 and 2, both also covered by mgr 20 → peer 20 listed once.
        val m10 = RoutingPlanner.managerEntries(
            managerIds = listOf(10, 20),
            coveredShopsByManager = mapOf(10 to listOf(1, 2), 20 to listOf(1, 2)),
            managerIdsByShop = mapOf(1 to listOf(10, 20), 2 to listOf(10, 20)),
            gsmShopIds = setOf(1),
        ).first { it.managerId == 10 }

        assertEquals(listOf(20), m10.peerManagerIds)   // deduped, self excluded
        assertEquals(listOf(1), m10.gsmShopIds)          // only shop 1 has a SIM
    }

    @Test
    fun `internal intercom groups shops by primary manager and lists covering managers`() {
        // shops 1 & 2 share primary mgr 10; shop 3's primary is mgr 20.
        // Covering sets (primary ∪ pool): shop 1 = {10,20}, shop 2 = {10}, shop 3 = {20}.
        val managersByShop = mapOf(1 to listOf(10, 20), 2 to listOf(10), 3 to listOf(20))
        val entries = RoutingPlanner.internalEntries(listOf(1 to 10, 2 to 10, 3 to 20), managersByShop)

        assertEquals(setOf(1, 2), entries.first { it.shopId == 1 }.groupShopIds.toSet())
        assertEquals(setOf(1, 2), entries.first { it.shopId == 2 }.groupShopIds.toSet())
        assertEquals(listOf(3), entries.first { it.shopId == 3 }.groupShopIds)

        // Covering managers become mgr{id} intercom targets in that shop's shared include.
        assertEquals(listOf(10, 20), entries.first { it.shopId == 1 }.managerIds)
        assertEquals(listOf(10), entries.first { it.shopId == 2 }.managerIds)
        assertEquals(listOf(20), entries.first { it.shopId == 3 }.managerIds)
    }
}
