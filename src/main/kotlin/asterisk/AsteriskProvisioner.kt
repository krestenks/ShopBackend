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
        // A different SIM means a different (unknown) number — clear the stale one.
        val numberStillValid = existing.imsi == imsi.trim()
        db.upsertShopTelephonyConfig(existing.copy(
            imsi = imsi.trim(),
            phoneNumber = if (numberStillValid) existing.phoneNumber else null,
        ))
        return provisionShop(shopId)
    }

    /**
     * Provisions one shop. Requires an IMSI to be assigned. Resolves the current
     * modem for that IMSI (auto-following a SIM moved to another slot), caches its
     * device paths, then (re)writes all config and reloads.
     * @return the (possibly updated) telephony config, including the SIP password.
     */
    suspend fun provisionShop(shopId: Int): ShopTelephonyConfig {
        val telephony = db.getShopTelephonyConfig(shopId)
        require(!telephony.imsi.isNullOrBlank()) { "Shop $shopId has no SIM (IMSI) assigned" }

        ensureSipAccounts(shopId)
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
        promptGenerator.generateSharedPrompts()
        // Internal SIP accounts (manager + in-shop device) exist for EVERY shop —
        // intercom calling must not depend on a SIM being assigned.
        val allShops = db.getAllShops()
        for (shop in allShops) {
            ensureSipAccounts(shop.id)
        }
        // GSM extras only for SIM-assigned shops.
        val gsmShops = db.getAllConfiguredShopTelephonyConfigs()
        gsmShops.forEach { regenerateShopPrompts(it.shopId) }
        regenerateFiles()
        allShops.forEach { db.markShopTelephonyProvisioned(it.id) }
        println("[Asterisk] Full provisioning pass complete (${allShops.size} shop(s), ${gsmShops.size} with SIM)")
    }

    /**
     * Makes sure both SIP accounts (manager app + in-shop device) exist for a shop:
     * generates missing passwords and pushes the PJSIP objects via ARI.
     */
    suspend fun ensureSipAccounts(shopId: Int): ShopTelephonyConfig {
        var cfg = db.getShopTelephonyConfig(shopId)
        if (cfg.sipPassword.isNullOrBlank() || cfg.sipPhonePassword.isNullOrBlank()) {
            cfg = cfg.copy(
                sipPassword = cfg.sipPassword?.takeIf { it.isNotBlank() } ?: generateSipPassword(),
                sipPhonePassword = cfg.sipPhonePassword?.takeIf { it.isNotBlank() } ?: generateSipPassword(),
            )
            db.upsertShopTelephonyConfig(cfg)
        }
        ariClient.upsertEndpoint(shopId, cfg.sipPassword!!)
        ariClient.upsertPhoneEndpoint(shopId, cfg.sipPhonePassword!!)
        return cfg
    }

    /** Internal-intercom groups: each shop with all shops sharing its manager. */
    private fun internalEntries(): List<InternalShopEntry> {
        val shops = db.getAllShops()
        val byManager = shops.groupBy { it.managerId }
        return shops.map { s ->
            val group = byManager[s.managerId].orEmpty().map { it.id }.ifEmpty { listOf(s.id) }
            InternalShopEntry(s.id, group)
        }
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
        dialplanWriter.regenerate(shops, internalEntries())
    }

    /**
     * Removes a shop's SIM binding (GSM trunk goes away) and regenerates. The SIP
     * accounts stay — internal intercom keeps working without a SIM.
     */
    suspend fun unassignShop(shopId: Int) {
        val cfg = db.getShopTelephonyConfig(shopId)
        db.upsertShopTelephonyConfig(cfg.copy(imsi = null, modemDataDevice = null, modemAlsaDevice = null))
        regenerateFiles()
        println("[Asterisk] Unassigned SIM from shop $shopId (intercom endpoints kept)")
    }

    private fun generateSipPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..24).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
