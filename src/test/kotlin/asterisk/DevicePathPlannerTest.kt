package asterisk

import ShopTelephonyConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A modem scan is weak evidence taken exactly when something is wrong. These pin that it can never
 * rewrite a working trunk or hand one device to two trunks — the 2026-09-12 outage, replayed.
 */
class DevicePathPlannerTest {
    private fun shop(id: Int, imsi: String, tty: String, card: String) =
        ShopTelephonyConfig(shopId = id, imsi = imsi, modemDataDevice = tty, modemAlsaDevice = card)
    private fun modem(imsi: String, tty: String?, card: String?) =
        DetectedModem(usbPort = "x", atDevice = tty, alsaDevice = card, imsi = imsi)

    private val shop5 = shop(5, "I5", "/dev/p3", "hw:CARD=EC25EUX_2")
    private val shop6 = shop(6, "I6", "/dev/p4", "hw:CARD=EC25EUX_3")

    @Test fun `2026-09-12 replay - hung modem not found, healthy shop scanned onto its card - refused`() {
        // shop6 hung (IMSI not reported, keeps EC25EUX_3); the scan puts healthy shop5 on EC25EUX_3
        val detected = listOf(modem("I5", "/dev/p3", "hw:CARD=EC25EUX_3"))
        val provisioning = DevicePathPlanner.plan(listOf(shop5, shop6), detected) { true }
        assertTrue(provisioning.moves.isEmpty(), "must not rewrite")
        assertNotNull(provisioning.refused)
        assertTrue(provisioning.refused!!.contains("EC25EUX_3"))

        val watchdog = DevicePathPlanner.plan(listOf(shop5, shop6), detected) { it.shopId in setOf(6) }
        assertTrue(watchdog.moves.isEmpty(), "shop5 is working, shop6 was not found: nothing to move")
        assertTrue(watchdog.notes.any { it.startsWith("shop 5:") && it.contains("working") })
        assertTrue(watchdog.notes.any { it.startsWith("shop 6:") && it.contains("no modem") })
    }

    @Test fun `a genuine re-enumeration that swaps two sound cards is allowed`() {
        val detected = listOf(modem("I5", "/dev/p3", "hw:CARD=EC25EUX_3"), modem("I6", "/dev/p4", "hw:CARD=EC25EUX_2"))
        val plan = DevicePathPlanner.plan(listOf(shop5, shop6), detected) { true }
        assertNull(plan.refused)
        assertEquals(mapOf(5 to "hw:CARD=EC25EUX_3", 6 to "hw:CARD=EC25EUX_2"), plan.moves.associate { it.shopId to it.modemAlsaDevice })
    }

    @Test fun `the watchdog only moves confirmed-down trunks`() {
        val detected = listOf(modem("I5", "/dev/p9", "hw:CARD=EC25EUX_2"), modem("I6", "/dev/p8", "hw:CARD=EC25EUX_3"))
        val plan = DevicePathPlanner.plan(listOf(shop5, shop6), detected) { it.shopId == 6 }
        assertEquals(listOf(6), plan.moves.map { it.shopId })
        assertEquals("/dev/p8", plan.moves.single().modemDataDevice)
    }

    @Test fun `watchdog - a down shop found on a working shop's card is refused`() {
        // shop6 is down and found, but the scan puts it on the card healthy shop5 is using
        val detected = listOf(modem("I6", "/dev/p4", "hw:CARD=EC25EUX_2"))
        val plan = DevicePathPlanner.plan(listOf(shop5, shop6), detected) { it.shopId == 6 }
        assertTrue(plan.moves.isEmpty())
        assertTrue(plan.refused!!.contains("EC25EUX_2"))
    }

    @Test fun `a clash on the AT device is refused too`() {
        val detected = listOf(modem("I6", "/dev/p3", "hw:CARD=EC25EUX_3"))
        val plan = DevicePathPlanner.plan(listOf(shop5, shop6), detected) { true }
        assertTrue(plan.moves.isEmpty())
        assertTrue(plan.refused!!.contains("/dev/p3"))
    }

    @Test fun `only the clashing move is dropped - the others still apply`() {
        val shop1 = shop(1, "I1", "/dev/p1", "hw:CARD=EC25EUX")
        // shop1 legitimately moved to a free card; shop5 would land on shop6's card while shop6 is missing
        val detected = listOf(modem("I1", "/dev/p1", "hw:CARD=EC25EUX_9"), modem("I5", "/dev/p3", "hw:CARD=EC25EUX_3"))
        val plan = DevicePathPlanner.plan(listOf(shop1, shop5, shop6), detected) { true }
        assertEquals(listOf(1), plan.moves.map { it.shopId })
        assertTrue(plan.refused!!.contains("EC25EUX_3"))
    }

    @Test fun `an incomplete scan result never moves a shop`() {
        val detected = listOf(modem("I6", "/dev/p8", null))
        val plan = DevicePathPlanner.plan(listOf(shop5, shop6), detected) { true }
        assertTrue(plan.moves.isEmpty())
        assertTrue(plan.notes.any { it.startsWith("shop 6:") && it.contains("not both devices") })
    }

    @Test fun `nothing changed and nothing found are both no-ops`() {
        val same = listOf(modem("I5", "/dev/p3", "hw:CARD=EC25EUX_2"), modem("I6", "/dev/p4", "hw:CARD=EC25EUX_3"))
        assertTrue(DevicePathPlanner.plan(listOf(shop5, shop6), same) { true }.moves.isEmpty())
        assertTrue(DevicePathPlanner.plan(listOf(shop5, shop6), emptyList()) { true }.moves.isEmpty())
    }

    // ---- a trunk that is not connected reports no IMSI (Nana, 2026-11-17) ----

    private val nanaPath = "/dev/serial/by-path/pci-0000:00:10.0-usb-0:1.4.3.2:1.0-port0"
    private val nana = shop(8, "I8", nanaPath, "hw:CARD=EC25EUX_6")
    private fun socketModem(socket: String, card: String?, state: String?, imsi: String? = null) = DetectedModem(
        usbPort = "1-$socket", atDevice = "/dev/serial/by-path/pci-0000:00:10.0-usb-0:$socket:1.0-port0",
        alsaDevice = card, imsi = imsi, trunkState = state, held = state != null,
    )

    @Test fun `a down trunk on the same socket gets the new sound card and keeps its AT path`() {
        val detected = listOf(socketModem("1.4.3.2", "hw:CARD=EC25EUX", "Not connected Start scheduled"))
        val plan = DevicePathPlanner.plan(listOf(nana), detected) { true }
        assertEquals(1, plan.moves.size)
        assertEquals("hw:CARD=EC25EUX", plan.moves[0].modemAlsaDevice)
        assertEquals(nanaPath, plan.moves[0].modemDataDevice)
    }

    @Test fun `a trunk that is Free is never moved by the socket rule`() {
        val detected = listOf(socketModem("1.4.3.2", "hw:CARD=EC25EUX", "Free"))
        assertTrue(DevicePathPlanner.plan(listOf(nana), detected) { true }.moves.isEmpty())
    }

    @Test fun `an unknown trunk state is never moved by the socket rule`() {
        assertTrue(DevicePathPlanner.plan(listOf(nana), listOf(socketModem("1.4.3.2", "hw:CARD=EC25EUX", null))) { true }.moves.isEmpty())
        assertTrue(DevicePathPlanner.plan(listOf(nana), listOf(socketModem("1.4.3.2", "hw:CARD=EC25EUX", "Ring"))) { true }.moves.isEmpty())
    }

    @Test fun `the socket rule waits for the watchdog to confirm the trunk down`() {
        val detected = listOf(socketModem("1.4.3.2", "hw:CARD=EC25EUX", "Not connected"))
        val plan = DevicePathPlanner.plan(listOf(nana), detected) { false }
        assertTrue(plan.moves.isEmpty())
        assertTrue(plan.notes.any { it.contains("not confirmed down") })
    }

    @Test fun `a modem on the socket that reports another IMSI is a different SIM and is not touched by the socket rule`() {
        val detected = listOf(socketModem("1.4.3.2", "hw:CARD=EC25EUX", "Not connected", imsi = "OTHER"))
        assertTrue(DevicePathPlanner.plan(listOf(nana), detected) { true }.moves.isEmpty())
    }

    @Test fun `the socket rule does not give a sound card that another trunk holds`() {
        val other = shop(9, "I9", "/dev/serial/by-path/pci-0000:00:10.0-usb-0:1.4.3.1:1.0-port0", "hw:CARD=EC25EUX")
        val detected = listOf(socketModem("1.4.3.2", "hw:CARD=EC25EUX", "Not connected"))
        val plan = DevicePathPlanner.plan(listOf(nana, other), detected) { true }
        assertTrue(plan.moves.isEmpty())
        assertNotNull(plan.refused)
    }

    @Test fun `two modems on the socket or no socket in the path change nothing`() {
        val twice = listOf(socketModem("1.4.3.2", "hw:CARD=A", "Not connected"), socketModem("1.4.3.2", "hw:CARD=B", "Not connected"))
        assertTrue(DevicePathPlanner.plan(listOf(nana), twice) { true }.moves.isEmpty())
        val noSocket = shop(8, "I8", "/dev/ttyUSB3", "hw:CARD=EC25EUX_6")
        assertTrue(DevicePathPlanner.plan(listOf(noSocket), listOf(socketModem("1.4.3.2", "hw:CARD=EC25EUX", "Not connected"))) { true }.moves.isEmpty())
    }
}
