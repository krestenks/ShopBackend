package asterisk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The readings below are replies recorded from the EliteDesk modems on 2026-11-17. */
class ModemReadinessTest {
    private fun status(checks: List<ReadinessCheck>, name: String) = checks.first { it.name == name }.status

    @Test fun `parses the replies of a working modem`() {
        assertEquals("READY", ModemReadingsParser.simState("\r\n+CPIN: READY\r\n\r\nOK\r\n"))
        assertEquals("no SIM", ModemReadingsParser.simState("\r\n+CME ERROR: 10\r\n"))
        assertTrue(ModemReadingsParser.registered("+CEREG: 0,1\r\nOK", null)!!)
        assertTrue(ModemReadingsParser.registered("+CEREG: 2,1,\"3715\",\"2A42E0C\",7", null)!!)
        assertFalse(ModemReadingsParser.registered("+CEREG: 0,0", "+CREG: 0,2")!!)
        assertTrue(ModemReadingsParser.registered("+CEREG: 0,0", "+CREG: 0,1")!!)
        assertNull(ModemReadingsParser.registered(null, null))
        assertEquals("FDD LTE", ModemReadingsParser.accessTechnology("+QNWINFO: \"FDD LTE\",\"23802\",\"LTE BAND 1\",500"))
        assertEquals(1, ModemReadingsParser.autoSelect("+QMBNCFG: \"AutoSel\",1\r\nOK"))
        assertEquals(Pair(true, false), ModemReadingsParser.ims("+QCFG: \"ims\",1,0"))
        assertEquals(Pair(true, true), ModemReadingsParser.ims("+QCFG: \"ims\",1,1"))
        assertNull(ModemReadingsParser.ims(""))
    }

    @Test fun `finds the selected carrier profile in the list`() {
        val list = "+QMBNCFG: \"List\",0,1,1,\"ROW_Generic_3GPP\",0x0501081F,202504101\r\n" +
            "+QMBNCFG: \"List\",1,0,0,\"TW_Mobile_China_VoLTE\",0x0580FB81,202201111"
        assertEquals("ROW_Generic_3GPP", ModemReadingsParser.activeProfile(list))
        assertNull(ModemReadingsParser.activeProfile("+QMBNCFG: \"List\",1,0,0,\"Other\",0x1,1"))
    }

    @Test fun `reads the bearer of context 2`() {
        assertEquals(false, ModemReadingsParser.contextActive("+CGACT: 1,1\r\n+CGACT: 2,0\r\n+CGACT: 3,0", 2))
        assertEquals(true, ModemReadingsParser.contextActive("+CGACT: 1,1\r\n+CGACT: 2,1", 2))
        assertNull(ModemReadingsParser.contextActive("", 2))
    }

    @Test fun `reads the audio flag of the usb layout`() {
        assertEquals(true, ModemReadingsParser.usbAudio("+QCFG: \"usbcfg\",0x2C7C,0x0125,0,0,1,0,0,0,1\r\nOK"))
        assertEquals(false, ModemReadingsParser.usbAudio("+QCFG: \"usbcfg\",0x2C7C,0x0125,1,1,1,1,1,0,0"))
        assertNull(ModemReadingsParser.usbAudio("+QCFG: \"usbcfg\",0x2C7C"))
    }

    @Test fun `a spare without a SIM is blocked on the SIM and not on the network`() {
        val checks = ModemReadiness.evaluate(
            ModemReadings(simState = "no SIM", usbAudio = true, autoSelect = 1, imsConfigured = true, imsRegistered = false, imsContextActive = false),
        )
        assertEquals(CheckStatus.BLOCKED, status(checks, "SIM"))
        assertEquals(CheckStatus.UNKNOWN, status(checks, "Network"))
        assertEquals(CheckStatus.UNKNOWN, status(checks, "VoLTE registration"))
        assertFalse(ModemReadiness.isReady(checks))
    }

    @Test fun `Mon after setup is ready`() {
        val checks = ModemReadiness.evaluate(
            ModemReadings("READY", true, "FDD LTE", 1, "ROW_Generic_3GPP", true, true, true, true),
        )
        assertTrue(checks.all { it.status == CheckStatus.OK }, checks.toString())
        assertTrue(ModemReadiness.isReady(checks))
    }

    @Test fun `Nina on 2026-11-17 is fixable on the IMS bearer`() {
        val r = ModemReadings("READY", true, "FDD LTE", 1, "ROW_Generic_3GPP", true, false, false, true)
        val checks = ModemReadiness.evaluate(r)
        assertEquals(CheckStatus.FIXABLE, status(checks, "VoLTE registration"))
        assertEquals(listOf("AT+CGACT=1,2"), ModemPreparePlan.plan(r).map { it.command })
    }

    @Test fun `registration pending with the bearer up is unknown and plans no writes`() {
        val r = ModemReadings("READY", true, "FDD LTE", 1, "ROW_Generic_3GPP", true, false, true, true)
        assertEquals(CheckStatus.UNKNOWN, status(ModemReadiness.evaluate(r), "VoLTE registration"))
        assertTrue(ModemPreparePlan.plan(r).isEmpty())
    }

    @Test fun `a modem without a carrier profile gets the setting and one restart, and no bearer write`() {
        val r = ModemReadings("READY", true, "FDD LTE", 0, null, false, false, false, true)
        val plan = ModemPreparePlan.plan(r)
        assertEquals(listOf("AT+QMBNCFG=\"AutoSel\",1", "AT+QCFG=\"ims\",1", "AT+CFUN=1,1"), plan.map { it.command })
        assertEquals(listOf(false, false, true), plan.map { it.restartsModem })
    }

    @Test fun `missing audio is blocked and never planned, so the layout is not written`() {
        val r = ModemReadings("READY", true, "FDD LTE", 1, "ROW_Generic_3GPP", true, true, true, usbAudio = false)
        assertEquals(CheckStatus.BLOCKED, status(ModemReadiness.evaluate(r), "Audio"))
        assertTrue(ModemPreparePlan.plan(r).none { it.command.contains("usbcfg") })
    }

    @Test fun `a modem that answered nothing is never ready`() {
        assertFalse(ModemReadiness.isReady(ModemReadiness.evaluate(ModemReadings())))
    }

    @Test fun `a SIM that is busy after boot is not ready`() {
        // AT+CPIN? answered with +CME ERROR: 14, which is not parsed as a state
        val r = ModemReadings(simState = null, usbAudio = true, autoSelect = 1, imsConfigured = true)
        assertFalse(ModemReadiness.isReady(ModemReadiness.evaluate(r)))
    }

    @Test fun `a context that is not listed is unknown and plans no bearer write`() {
        assertNull(ModemReadingsParser.contextActive("+CGACT: 1,1\r\n+CGACT: 3,0", 2))
        val r = ModemReadings("READY", true, "FDD LTE", 1, "ROW_Generic_3GPP", true, false, null, true)
        assertTrue(ModemPreparePlan.plan(r).isEmpty())
    }

    @Test fun `turning VoLTE on restarts the modem and does not write the bearer in the same plan`() {
        val r = ModemReadings("READY", true, "FDD LTE", 1, "ROW_Generic_3GPP", false, false, false, true)
        assertEquals(listOf("AT+QCFG=\"ims\",1", "AT+CFUN=1,1"), ModemPreparePlan.plan(r).map { it.command })
    }

    @Test fun `a modem that did not answer plans nothing`() {
        val r = ModemReadings()
        assertTrue(ModemPreparePlan.plan(r).isEmpty())
        assertTrue(ModemReadiness.evaluate(r).none { it.status == CheckStatus.FIXABLE })
    }
}
