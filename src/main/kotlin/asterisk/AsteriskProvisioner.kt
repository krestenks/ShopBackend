package asterisk

import DataBase
import ShopTelephonyConfig
import java.security.SecureRandom

/**
 * Orchestrates everything needed to make a shop live on the GSM phone system:
 *
 *  1. PJSIP endpoint/auth/aor for the manager app — via ARI push config (no reload)
 *  2. chan_quectel trunk — quectel_shops.conf regenerated + module reload
 *  3. dialplan contexts — extensions_shops.conf regenerated + dialplan reload
 *
 * Config files are regenerated wholesale from the DB (single source of truth), so
 * provisioning is idempotent and there is no section-surgery to go wrong.
 */
class AsteriskProvisioner(
    private val db: DataBase,
    private val config: AsteriskConfig,
    private val ariClient: AriClient,
    private val quectelConfigWriter: QuectelConfigWriter,
    private val dialplanWriter: DialplanWriter,
    private val promptGenerator: PromptGenerator,
    private val modemScanner: ModemScanner,
) {

    /**
     * Binds a shop to a SIM (by IMSI) and provisions it. The IMSI is the stable key;
     * the physical modem holding it is resolved dynamically. If that SIM was bound to
     * another shop it's moved here (a SIM maps to one shop).
     */
    suspend fun assignShopToImsi(shopId: Int, imsi: String): ShopTelephonyConfig {
        db.clearImsiFromOtherShops(imsi, keepShopId = shopId)
        val existing = db.getShopTelephonyConfig(shopId)
        db.upsertShopTelephonyConfig(existing.copy(imsi = imsi.trim()))
        return provisionShop(shopId)
    }

    /**
     * Provisions one shop. Requires an IMSI to be assigned. Resolves the current
     * modem for that IMSI (auto-following a SIM moved to another slot), caches its
     * device paths, then (re)writes all config and reloads.
     * @return the (possibly updated) telephony config, including the SIP password.
     */
    suspend fun provisionShop(shopId: Int): ShopTelephonyConfig {
        var telephony = db.getShopTelephonyConfig(shopId)
        require(!telephony.imsi.isNullOrBlank()) { "Shop $shopId has no SIM (IMSI) assigned" }

        if (telephony.sipPassword.isNullOrBlank()) {
            telephony = telephony.copy(sipPassword = generateSipPassword())
            db.upsertShopTelephonyConfig(telephony)
        }

        ariClient.upsertEndpoint(shopId, telephony.sipPassword!!)
        regenerateShopPrompts(shopId)
        promptGenerator.generateSharedPrompts()
        regenerateFiles()   // resolves the current modem for every shop's IMSI, then writes + reloads
        db.markShopTelephonyProvisioned(shopId)
        val after = db.getShopTelephonyConfig(shopId)
        println("[Asterisk] Provisioned shop $shopId (trunk=${config.trunkName(shopId)}, imsi=${after.imsi}, device=${after.modemDataDevice})")
        return after
    }

    /**
     * Removes a shop's PJSIP endpoint and regenerates the config files. The caller
     * should first clear/remove the shop's row in shop_telephony_config (the files
     * are rebuilt from whatever the DB currently says).
     */
    suspend fun deprovisionShop(shopId: Int) {
        ariClient.deleteEndpoint(shopId)
        regenerateFiles()
        println("[Asterisk] Deprovisioned shop $shopId")
    }

    /**
     * Re-applies everything for all configured shops — endpoint upserts + both config
     * files. Safe to run at startup: brings a rebuilt/blank Asterisk back in sync.
     */
    suspend fun provisionAllConfigured() {
        val shops = db.getAllConfiguredShopTelephonyConfigs()
        promptGenerator.generateSharedPrompts()
        for (shop in shops) {
            val password = shop.sipPassword?.takeIf { it.isNotBlank() } ?: generateSipPassword().also {
                db.upsertShopTelephonyConfig(shop.copy(sipPassword = it))
            }
            ariClient.upsertEndpoint(shop.shopId, password)
            regenerateShopPrompts(shop.shopId)
        }
        regenerateFiles()
        shops.forEach { db.markShopTelephonyProvisioned(it.shopId) }
        println("[Asterisk] Full provisioning pass complete (${shops.size} shop(s))")
    }

    /**
     * Resolves, for every shop bound to a SIM, the physical modem currently holding
     * that IMSI and caches its device paths in the DB. This is what makes a SIM moved
     * to a different modem automatically follow its shop.
     */
    private fun resolveDevicesForAllShops() {
        val detected = runCatching { modemScanner.scan() }.getOrDefault(emptyList())
        if (detected.isEmpty()) return
        for (shop in db.getAllConfiguredShopTelephonyConfigs()) {
            val imsi = shop.imsi ?: continue
            val modem = detected.firstOrNull { it.imsi == imsi }
            if (modem == null) {
                println("[Asterisk] WARN shop ${shop.shopId}: no modem currently reports IMSI $imsi")
                continue
            }
            if (modem.atDevice != shop.modemDataDevice || modem.alsaDevice != shop.modemAlsaDevice) {
                db.upsertShopTelephonyConfig(shop.copy(
                    modemDataDevice = modem.atDevice,
                    modemAlsaDevice = modem.alsaDevice,
                ))
            }
        }
    }

    /**
     * Re-renders one shop's TTS prompts from its voice-config texts. Called on
     * provisioning and whenever the voice config is saved in the admin UI
     * (prompt files are read per call, so no Asterisk reload is needed).
     */
    fun regenerateShopPrompts(shopId: Int) {
        promptGenerator.generateShopPrompts(shopId, db.getShopVoiceConfig(shopId))
    }

    private fun regenerateFiles() {
        resolveDevicesForAllShops()
        // Only write trunks whose device actually resolved (a modem is present).
        val shops = db.getAllConfiguredShopTelephonyConfigs().filter { !it.modemDataDevice.isNullOrBlank() }
        quectelConfigWriter.regenerate(shops)
        dialplanWriter.regenerate(shops)
    }

    /** Removes a shop's SIM binding and re-provisions the rest. */
    suspend fun unassignShop(shopId: Int) {
        val cfg = db.getShopTelephonyConfig(shopId)
        db.upsertShopTelephonyConfig(cfg.copy(imsi = null, modemDataDevice = null, modemAlsaDevice = null))
        deprovisionShop(shopId)
    }

    private fun generateSipPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..24).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
