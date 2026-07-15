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
        assertTrue(text.contains("Dial(PJSIP/shop7-manager,30)"))
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
