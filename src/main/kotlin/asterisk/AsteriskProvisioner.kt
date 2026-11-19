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
/**
 * A shop's modem is physically present and holds the right SIM, but cannot carry a line — in
 * practice always because its USB audio class is off, so there is no ALSA card and chan_quectel
 * has no voice path. Thrown rather than returned so the admin UI and the provision endpoint, which
 * both treat "no exception" as success, report the real outcome.
 */
class ModemNotUsableException(message: String) : IllegalStateException(message)

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
        ensureAllManagerEndpoints()
        regenerateShopPrompts(shopId)
        promptGenerator.generateSharedPrompts()
        // resolves the current modem for every shop's IMSI, then writes + reloads
        val trunks = regenerateFiles()

        // No trunk was written for THIS shop -> the line cannot carry calls or SMS. Do not mark it
        // provisioned and do not return normally: callers (the admin UI, the provision endpoint)
        // report success purely from "no exception", so a silent return here is how a dead line ends
        // up displayed as "Provisioned: Yes". Only this shop's own failure throws — a background
        // regeneration pass must not be broken by some other shop's unusable modem.
        if (shopId in trunks.skippedNoAudio) {
            db.recordReliabilityEvent(
                severity = "error",
                category = "provision_no_audio",
                shopId = shopId,
                message = "Shop $shopId could not be provisioned: its modem exposes no ALSA capture " +
                    "device, so no GSM trunk was written. The modem's USB audio class (UAC) is " +
                    "most likely disabled in firmware.",
            )
            throw ModemNotUsableException(
                "Shop $shopId's modem has no audio device, so no GSM trunk could be written — the " +
                    "line would neither ring nor send SMS. The modem's USB audio class (UAC) is " +
                    "most likely disabled in firmware; enable it and rescan, then provision again."
            )
        }

        // Same rule for a shop left with NO device (its SIM is in no modem the scan could see, or the
        // move it needed was dropped because it would clash with another trunk): no trunk was written.
        val resolved = db.getShopTelephonyConfig(shopId)
        if (resolved.modemDataDevice.isNullOrBlank()) {
            db.recordReliabilityEvent(
                severity = "error",
                category = "provision_no_device",
                shopId = shopId,
                message = "Shop $shopId could not be provisioned: no modem device resolved for its SIM " +
                    "(IMSI ${resolved.imsi}) - the SIM was not found in any modem, or its modem's devices " +
                    "are assigned to another shop.",
            )
            throw ModemNotUsableException(
                "Shop $shopId's SIM was not found in any modem, or that modem's devices are already " +
                    "assigned to another shop, so no GSM trunk could be written. Check the SIM is seated " +
                    "and every modem is up, rescan, then provision again."
            )
        }

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
        // One SIP identity per manager for the duty-aware call pool.
        ensureAllManagerEndpoints()
        // GSM extras only for SIM-assigned shops.
        val gsmShops = db.getAllConfiguredShopTelephonyConfigs()
        gsmShops.forEach { regenerateShopPrompts(it.shopId) }
        val trunks = regenerateFiles()
        // A shop whose modem gave us no trunk is NOT provisioned, and must not be stamped as such —
        // the admin UI reads provisionedAt for its Yes/No badge. Shops with no SIM at all are still
        // marked: they are intercom-only by design, not failed. Unlike provisionShop this must not
        // throw; one unusable modem cannot be allowed to abort the whole startup pass.
        allShops.filter { it.id !in trunks.skippedNoAudio }
            .forEach { db.markShopTelephonyProvisioned(it.id) }
        println("[Asterisk] Full provisioning pass complete (${allShops.size} shop(s), ${gsmShops.size} with SIM)")
        if (trunks.skippedNoAudio.isNotEmpty()) {
            println("[Asterisk] NOT provisioned (modem has no audio device): shops ${trunks.skippedNoAudio.joinToString()}")
        }
    }

    /**
     * Applies a call-routing change (manager pool membership or a shop's primary-manager
     * reassignment) to the dialplan — WITHOUT a modem rescan or trunk rewrite. Ensures
     * every manager has its mgr{id} SIP identity (so a newly-pooled manager is dialable),
     * then rewrites and reloads the intercom + per-manager contexts from current DB state.
     * SIM/trunk bindings are untouched. Best-effort: if Asterisk is unreachable the next
     * full provision reconciles.
     */
    suspend fun reprovisionRouting() {
        ensureAllManagerEndpoints()
        val gsmShops = db.getAllConfiguredShopTelephonyConfigs().filter { !it.modemDataDevice.isNullOrBlank() }
        dialplanWriter.regenerate(gsmShops, internalEntries(), managerEntries())
        println("[Asterisk] Reprovisioned call routing (pool/primary-manager change)")
    }

    /**
     * Tears down a deleted manager's mgr{id} SIP identity, then refreshes the dialplan.
     * Call AFTER the manager (and their cascaded pool/duty rows) are removed from the DB,
     * so the regenerated contexts no longer reference them. Best-effort on the ARI delete
     * (a 404 is fine); the reprovision reconciles the rest.
     */
    suspend fun removeManager(managerId: Int) {
        runCatching { ariClient.deleteManagerEndpoint(managerId) }
        reprovisionRouting()
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

    /**
     * Ensures a manager's single SIP identity (mgr{id}) exists: generates a password
     * if missing and pushes the PJSIP objects via ARI. Returns the password.
     */
    suspend fun ensureManagerEndpoint(managerId: Int): String {
        val pw = db.getManagerSipPassword(managerId) ?: generateSipPassword().also {
            db.setManagerSipPassword(managerId, it)
        }
        ariClient.upsertManagerEndpoint(managerId, pw)
        return pw
    }

    /** Pushes a mgr{id} endpoint for every manager. */
    suspend fun ensureAllManagerEndpoints() {
        for (m in db.getAllManagers()) ensureManagerEndpoint(m.id)
    }

    /** Internal-intercom groups: each shop with all shops sharing its manager, plus the
     *  managers covering it (dialable as mgr{id} for manager-to-manager intercom). */
    private fun internalEntries(): List<InternalShopEntry> {
        val shops = db.getAllShops()
        val managersByShop = shops.associate { it.id to db.getManagerIdsForShop(it.id) }
        return RoutingPlanner.internalEntries(shops.map { it.id to it.managerId }, managersByShop)
    }

    /**
     * One dial context per manager: the shops they cover (primary ∪ call pool) for
     * intercom, the subset with a live SIM for bare-number dialing, and the co-managers
     * of those shops as manager-to-manager peers. Reads the DB, then delegates the
     * (pure) assembly to [RoutingPlanner].
     */
    private fun managerEntries(): List<ManagerDialEntry> {
        val gsmShopIds = db.getAllConfiguredShopTelephonyConfigs()
            .filter { !it.modemDataDevice.isNullOrBlank() }
            .map { it.shopId }
            .toSet()
        val managerIds = db.getAllManagers().map { it.id }
        val coveredByManager = managerIds.associateWith { db.getShopsForManager(it).map { s -> s.id } }
        val managersByShop = coveredByManager.values.flatten().toSet()
            .associateWith { db.getManagerIdsForShop(it) }
        return RoutingPlanner.managerEntries(managerIds, coveredByManager, managersByShop, gsmShopIds)
    }

    /**
     * Resolves, for every shop bound to a SIM, the physical modem currently holding
     * that IMSI and caches its device paths in the DB. This is what makes a SIM moved
     * to a different modem automatically follow its shop.
     */
    /**
     * Re-scan modems by IMSI and persist the device-path changes [DevicePathPlanner] allows. The
     * planner refuses any rewrite that would give one device to two trunks, and only moves shops
     * [movable] accepts. Returns the shops actually rewritten.
     */
    private fun applyDevicePlan(context: String, movable: (ShopTelephonyConfig) -> Boolean): List<ShopTelephonyConfig> {
        val detected = runCatching { modemScanner.scan() }.getOrDefault(emptyList())
        if (detected.isEmpty()) return emptyList()
        val plan = DevicePathPlanner.plan(db.getAllConfiguredShopTelephonyConfigs(), detected, movable)
        plan.notes.forEach { println("[Asterisk] WARN $it") }
        plan.refused?.let {
            println("[Recovery] SUPPRESSED device-path rewrite target=$context reason=$it (inconsistent scan - those moves dropped) bound=every scan")
            db.recordReliabilityEvent(
                severity = "warn", category = "device_rewrite_refused", shopId = null,
                message = "Modem re-scan ($context) proposed device paths that would give one device to two " +
                    "trunks, so those changes were NOT applied: $it. Usually one modem was hung or " +
                    "re-enumerating during the scan. If a SIM was deliberately moved, re-provision once all " +
                    "modems are up.",
            )
        }
        val before = db.getAllConfiguredShopTelephonyConfigs().associateBy { it.shopId }
        plan.moves.forEach { move ->
            val old = before[move.shopId]
            println(
                "[Recovery] device-path rewrite target=shop${move.shopId} context=$context " +
                    "evidence=scan data=${old?.modemDataDevice}->${move.modemDataDevice} alsa=${old?.modemAlsaDevice}->${move.modemAlsaDevice} callLive=false",
            )
            db.upsertShopTelephonyConfig(move)
        }
        return plan.moves
    }

    /** Provisioning (startup, admin Save & Provision): any shop may move, under the collision guard. */
    private fun resolveDevicesForAllShops() { applyDevicePlan("provisioning") { true } }

    /**
     * Re-renders one shop's TTS prompts from its voice-config texts. Called on
     * provisioning and whenever the voice config is saved in the admin UI
     * (prompt files are read per call, so no Asterisk reload is needed).
     */
    fun regenerateShopPrompts(shopId: Int) {
        promptGenerator.generateShopPrompts(shopId, db.getShopVoiceConfig(shopId))
    }

    private fun regenerateFiles(): TrunkWriteResult {
        resolveDevicesForAllShops()
        // Only write trunks whose device actually resolved (a modem is present).
        val shops = db.getAllConfiguredShopTelephonyConfigs().filter { !it.modemDataDevice.isNullOrBlank() }
        val trunks = quectelConfigWriter.regenerate(shops)
        dialplanWriter.regenerate(shops, internalEntries(), managerEntries())
        return trunks
    }

    /**
     * Targeted recovery for "the trunk config points at a stale device after a USB re-enumeration"
     * (the 2026-09-05 outage: ttyUSB numbers reshuffled, so shops pointed at SIM-less spares).
     * Re-scans the physically-present modems, re-resolves each shop's device paths by IMSI, and —
     * ONLY if a path actually changed — rewrites and reloads the chan_quectel trunk config. The
     * ModemStuckWatchdog calls this when its restart/reload ladder can't otherwise recover a down
     * modem. Reloads chan_quectel when it rewrites, so callers must ensure no GSM call is live.
     * Returns true if any shop's device path changed (i.e. a re-scan actually fixed something).
     */
    /**
     * Watchdog recovery: re-scan and move ONLY the shops in [downShopIds] (trunks the watchdog has
     * confirmed down). A working trunk is never rewritten on the strength of a scan — see
     * [DevicePathPlanner]. Returns true if a device path actually changed.
     */
    suspend fun resyncModemDevices(downShopIds: Set<Int>): Boolean {
        val moves = applyDevicePlan("watchdog resync of shop(s) ${downShopIds.sorted().joinToString(",")}") { it.shopId in downShopIds }
        if (moves.isEmpty()) return false
        val after = db.getAllConfiguredShopTelephonyConfigs()
        quectelConfigWriter.regenerate(after.filter { !it.modemDataDevice.isNullOrBlank() })  // writes + reloads (call-guarded)
        println("[Asterisk] resyncModemDevices: moved shop(s) ${moves.joinToString(",") { it.shopId.toString() }} — rewrote + reloaded chan_quectel trunks")
        return true
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
