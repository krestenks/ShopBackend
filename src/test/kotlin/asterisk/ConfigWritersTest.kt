package asterisk

import ShopTelephonyConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText

/**
 * Locks down the shape of the generated Asterisk config files (no Asterisk
 * needed — reload is skipped). Guards the DTMF flow's dialplan against
 * accidental syntax drift.
 */
class ConfigWritersTest {

    private fun testConfig(dir: String) = AsteriskConfig(
        amiHost = "127.0.0.1", amiPort = 5038, amiUsername = "test", amiSecret = "s",
        ariBaseUrl = "http://127.0.0.1:8088", ariUsername = "test", ariPassword = "p",
        configPath = dir,
        backendBaseUrl = "http://127.0.0.1:8080",
        internalSecret = "SECRET",
        sipHost = "192.168.0.192", sipPort = 5060,
        promptsPath = "/usr/share/asterisk/sounds/shopbackend",
        ttsLang = "en-GB",
    )

    private val shop = ShopTelephonyConfig(
        shopId = 7,
        phoneNumber = "+4581921779",
        modemDataDevice = "/dev/ttyQuectelShop7",
        modemAlsaDevice = "hw:EC25EUX",
        sipPassword = "pw",
    )

    @Test
    fun `dialplan contains full DTMF flow`() {
        val dir = Files.createTempDirectory("astconf").toString()
        val config = testConfig(dir)
        DialplanWriter(config, AmiClient(config)).regenerate(listOf(shop), reload = false)

        val text = java.nio.file.Paths.get(dir, "extensions_shops.conf").readText()

        // Contexts
        assertTrue(text.contains("[from-gsm-shop7]"))
        assertTrue(text.contains("[from-sip-shop7]"))

        // Verdict routing
        assertTrue(text.contains("""GotoIf($["${'$'}{VERDICT}" = "reject"]?rejected,1)"""))
        assertTrue(text.contains("""GotoIf($["${'$'}{VERDICT}" = "menu_open"]?welcomeopen,1)"""))
        assertTrue(text.contains("""GotoIf($["${'$'}{VERDICT}" = "menu_closed"]?welcomeclosed,1)"""))
        assertTrue(text.contains("""GotoIf($["${'$'}{VERDICT}" = "menu_temp"]?welcometemp,1)"""))
        assertTrue(text.contains("exten => rejected,1,Hangup(21)"))

        // Welcome + menu prompts by absolute path
        assertTrue(text.contains("Playback(/usr/share/asterisk/sounds/shopbackend/shop7-welcome-open)"))
        assertTrue(text.contains("Read(DIGIT,/usr/share/asterisk/sounds/shopbackend/shop7-menu-open,1,,1,15)"))
        assertTrue(text.contains("Read(DIGIT,/usr/share/asterisk/sounds/shopbackend/shop7-menu-closed,1,,1,15)"))

        // Menu actions
        assertTrue(text.contains("/api/internal/telephony/booking-link,secret=SECRET&shopId=7"))
        assertTrue(text.contains("/api/internal/telephony/call/event,secret=SECRET&uniqueid="))
        // Duty-aware routing: dial the backend-computed targets, fall back to the legacy endpoint.
        assertTrue(text.contains("/api/internal/telephony/call/dial-targets,secret=SECRET&shopId=7"))
        assertTrue(text.contains("Set(TARGETS=PJSIP/shop7-manager)"))
        assertTrue(text.contains("Dial($" + "{TARGETS},30)"))
        assertTrue(text.contains("Playback(/usr/share/asterisk/sounds/shopbackend/operator-unavailable)"))
        assertTrue(text.contains("Playback(/usr/share/asterisk/sounds/shopbackend/sms-sent)"))
        assertTrue(text.contains("Playback(/usr/share/asterisk/sounds/shopbackend/menu-invalid-goodbye)"))

        // The closed menu must not offer the operator: only menuopen routes digit 2.
        val menuClosedBlock = text.substringAfter("exten => menuclosed,1").substringBefore("exten =>")
        assertFalse(menuClosedBlock.contains("operator"))

        // No unresolved Kotlin templates leaked into the file
        assertFalse(text.contains("${'$'}d{"))
    }

    @Test
    fun `internal intercom contexts generated per manager group`() {
        val dir = Files.createTempDirectory("astconf").toString()
        val config = testConfig(dir)
        DialplanWriter(config, AmiClient(config)).regenerate(
            shops = listOf(shop),  // shop 7 has a SIM
            internal = listOf(
                InternalShopEntry(7, listOf(7, 9), managerIds = listOf(3, 5)),
                InternalShopEntry(9, listOf(7, 9), managerIds = listOf(3, 5)),   // shop 9: no SIM, intercom only
            ),
            reload = false,
        )
        val text = java.nio.file.Paths.get(dir, "extensions_shops.conf").readText()

        // Shared internal extens per group member
        assertTrue(text.contains("[internal-shop7]"))
        assertTrue(text.contains("exten => shopphone9,1,Dial(PJSIP/shop9-phone,45)"))
        // Manager-to-manager: covering managers are dialable as mgr{id} in the shared
        // include, so a call from shop{id}-manager (from-sip-shop{id}) resolves them.
        val internal7 = text.substringAfter("[internal-shop7]").substringBefore("\n[")
        assertTrue(internal7.contains("exten => mgr3,1,Dial(PJSIP/mgr3,45)"))
        assertTrue(internal7.contains("exten => mgr5,1,Dial(PJSIP/mgr5,45)"))
        // In-shop device context: manager exten + group include, and NO GSM patterns
        val ctx7 = text.substringAfter("[from-shopphone-shop7]").substringBefore("[")
        assertTrue(ctx7.contains("exten => manager,1,Dial(PJSIP/shop7-manager,45)"))
        assertTrue(ctx7.contains("include => internal-shop7"))
        assertFalse(ctx7.contains("Quectel/"))
        // GSM shop's manager context includes the internal extens
        // Extract up to the next context header (\n[) — the body itself now contains a
        // '[' in the ExecIf $[...] expression, so a bare "[" delimiter would truncate it.
        val sip7 = text.substringAfter("[from-sip-shop7]").substringBefore("\n[")
        assertTrue(sip7.contains("include => internal-shop7"))
        assertTrue(sip7.contains("Quectel/shop7"))
        // SIM occupied / congestion / far-end busy → report line busy to the app.
        assertTrue(sip7.contains("DIALSTATUS"))
        assertTrue(sip7.contains("Busy(3)"))
        // SIM-less shop still gets a manager context (internal only)
        val sip9 = text.substringAfter("[from-sip-shop9]").substringBefore("\n[")
        assertTrue(sip9.contains("include => internal-shop9"))
        assertFalse(sip9.contains("Quectel/"))
    }

    @Test
    fun `per-manager dial context routes GSM by shop prefix and includes intercom`() {
        val dir = Files.createTempDirectory("astconf").toString()
        val config = testConfig(dir)
        DialplanWriter(config, AmiClient(config)).regenerate(
            shops = listOf(shop),  // shop 7 has a SIM
            internal = listOf(InternalShopEntry(7, listOf(7, 9)), InternalShopEntry(9, listOf(7, 9))),
            managers = listOf(ManagerDialEntry(
                managerId = 3, coveredShopIds = listOf(7, 9), gsmShopIds = listOf(7),
                peerManagerIds = listOf(5),
            )),
            reload = false,
        )
        val text = java.nio.file.Paths.get(dir, "extensions_shops.conf").readText()
        val ctx = text.substringAfter("[from-mgr3]").substringBefore("\n[")

        // GSM outbound: parse "shop{id}-{number}" and hand to that shop's outbound context.
        assertTrue(ctx.contains("exten => _shopX.,1"))
        assertTrue(ctx.contains("Set(SHOPSEL=$" + "{CUT(REST,-,1)})"))
        assertTrue(ctx.contains("Goto(from-sip-shop$" + "{SHOPSEL},$" + "{NUM},1)"))
        // Single-SIM convenience → bare number to the only GSM shop (7).
        assertTrue(ctx.contains("exten => _+X.,1,Goto(from-sip-shop7,"))
        // Manager-to-manager intercom: the peer colleague is dialable, self is not.
        assertTrue(ctx.contains("exten => mgr5,1,Dial(PJSIP/mgr5,45)"))
        assertFalse(ctx.contains("exten => mgr3,1,"))
        // Intercom includes for every covered shop.
        assertTrue(ctx.contains("include => internal-shop7"))
        assertTrue(ctx.contains("include => internal-shop9"))
        // No unresolved Kotlin templates.
        assertFalse(text.contains("${'$'}d{"))
    }

    @Test
    fun `manager context only lists shared-shop peers as intercom targets`() {
        val dir = Files.createTempDirectory("astconf").toString()
        val config = testConfig(dir)
        DialplanWriter(config, AmiClient(config)).regenerate(
            shops = listOf(shop),
            internal = listOf(InternalShopEntry(7, listOf(7))),
            managers = listOf(
                // mgr3 shares a shop with mgr5 only; mgr8 is unrelated.
                ManagerDialEntry(managerId = 3, coveredShopIds = listOf(7), gsmShopIds = listOf(7), peerManagerIds = listOf(5)),
                ManagerDialEntry(managerId = 5, coveredShopIds = listOf(7), gsmShopIds = listOf(7), peerManagerIds = listOf(3)),
                ManagerDialEntry(managerId = 8, coveredShopIds = emptyList(), gsmShopIds = emptyList(), peerManagerIds = emptyList()),
            ),
            reload = false,
        )
        val text = java.nio.file.Paths.get(dir, "extensions_shops.conf").readText()

        val ctx3 = text.substringAfter("[from-mgr3]").substringBefore("\n[")
        assertTrue(ctx3.contains("exten => mgr5,1,Dial(PJSIP/mgr5,45)"))
        assertFalse(ctx3.contains("mgr8"))   // unrelated manager is not callable

        val ctx8 = text.substringAfter("[from-mgr8]").substringBefore("\n[")
        assertFalse(ctx8.contains("exten => mgr"))   // no peers → no manager-to-manager extensions
    }

    @Test
    fun `quectel trunk section uses RoEdAl UAC keys`() {
        val dir = Files.createTempDirectory("astconf").toString()
        val config = testConfig(dir)
        QuectelConfigWriter(config, AmiClient(config)).regenerate(listOf(shop), reload = false)

        val text = java.nio.file.Paths.get(dir, "quectel_shops.conf").readText()
        assertTrue(text.contains("[shop7]"))
        assertTrue(text.contains("data=/dev/ttyQuectelShop7"))
        assertTrue(text.contains("uac=on"))
        assertTrue(text.contains("alsadev=hw:EC25EUX"))
        assertTrue(text.contains("context=from-gsm-shop7"))
    }
}
