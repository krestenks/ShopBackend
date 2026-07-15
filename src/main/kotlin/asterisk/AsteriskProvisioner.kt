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
) {

    /**
     * Provisions one shop. Requires modem_data_device to be set in shop_telephony_config.
     * Generates and persists a SIP password on first provisioning.
     * @return the (possibly updated) telephony config, including the SIP password.
     */
    suspend fun provisionShop(shopId: Int): ShopTelephonyConfig {
        var telephony = db.getShopTelephonyConfig(shopId)
        require(!telephony.modemDataDevice.isNullOrBlank()) {
            "Shop $shopId has no modem_data_device configured"
        }

        if (telephony.sipPassword.isNullOrBlank()) {
            telephony = telephony.copy(sipPassword = generateSipPassword())
            db.upsertShopTelephonyConfig(telephony)
        }

        ariClient.upsertEndpoint(shopId, telephony.sipPassword!!)
        regenerateShopPrompts(shopId)
        promptGenerator.generateSharedPrompts()
        regenerateFiles()
        db.markShopTelephonyProvisioned(shopId)
        println("[Asterisk] Provisioned shop $shopId (trunk=${config.trunkName(shopId)}, endpoint=${config.endpointId(shopId)})")
        return db.getShopTelephonyConfig(shopId)
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
     * Re-renders one shop's TTS prompts from its voice-config texts. Called on
     * provisioning and whenever the voice config is saved in the admin UI
     * (prompt files are read per call, so no Asterisk reload is needed).
     */
    fun regenerateShopPrompts(shopId: Int) {
        promptGenerator.generateShopPrompts(shopId, db.getShopVoiceConfig(shopId))
    }

    private fun regenerateFiles() {
        val shops = db.getAllConfiguredShopTelephonyConfigs()
        quectelConfigWriter.regenerate(shops)
        dialplanWriter.regenerate(shops)
    }

    private fun generateSipPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..24).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
