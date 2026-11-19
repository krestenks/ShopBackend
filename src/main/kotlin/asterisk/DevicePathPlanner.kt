package asterisk

import ShopTelephonyConfig

/**
 * Outcome of re-scanning the modems by IMSI against the configured trunks.
 *
 * @property moves shops to rewrite, already carrying their new device paths; empty = change nothing
 * @property refused why some moves were dropped (they would give one device to two trunks), or null
 * @property notes per-shop observations worth logging (missing IMSI, a working trunk left alone, ...)
 */
data class DevicePathPlan(
    val moves: List<ShopTelephonyConfig>,
    val refused: String? = null,
    val notes: List<String> = emptyList(),
)

/**
 * Decides which trunks' device paths a modem scan may change. Pure, so the failure it exists to
 * prevent can be replayed in a test.
 *
 * A scan is WEAK evidence: it is taken exactly when something is wrong (a modem hung or mid
 * USB re-enumeration), so it can be incomplete or momentarily inconsistent. 2026-09-12 16:03: the
 * watchdog's resync ran while shop6's modem was hung (its IMSI not found, so it kept its old
 * paths), the scan put the HEALTHY shop5 on shop6's sound card, the rewrite restarted shop5 onto a
 * card another trunk held, and shop5 could not open audio until a manual restart 6 minutes later.
 *
 * So, fail closed:
 *  - a shop is only moved if [movable] allows it — the watchdog passes "confirmed down". A trunk that
 *    is Free or on a call proves at least its AT path works (a crossed sound card can still read Free,
 *    so it is not proof of everything), and a scan taken mid-incident does not outrank that;
 *  - a shop is only moved onto a COMPLETE result (both an AT device and a sound card);
 *  - a move is dropped if the resulting config would give one device to two trunks, repeated until
 *    no move clashes. Only the clashing moves are dropped, so one hung modem keeping a stale card
 *    cannot block every other shop's correct move. Checking the resulting config (not "is this device
 *    already taken") still allows a genuine re-enumeration that SWAPS two shops' sound cards, since
 *    card names follow USB enumeration order.
 */
object DevicePathPlanner {
    /** Trunk states in which the driver has no working connection to the modem, so nothing runs on it. */
    private val DOWN_STATE_PREFIXES = listOf("not connected", "not initialized", "not initia")

    /** The part of a serial or sysfs path that names the USB socket, for example `1.4.3.2`. */
    private val SOCKET_IN_SERIAL_PATH = Regex("usb(?:v2)?-\\d+:([0-9.]+):")

    private fun socketOfSerialPath(path: String?): String? =
        path?.let { SOCKET_IN_SERIAL_PATH.find(it)?.groupValues?.get(1) }

    fun isDownState(state: String?): Boolean {
        val s = state?.trim()?.lowercase() ?: return false
        return DOWN_STATE_PREFIXES.any { s.startsWith(it) }
    }

    /**
     * A trunk that is not connected reports no IMSI, so no modem matches the shop by IMSI. When
     * the modem sitting on the USB socket of the shop's current AT path also reports no IMSI and its
     * trunk is in a known down state, the sound card of that socket is the only thing that can have
     * changed (card ids follow USB registration order). Only the sound card is updated; the AT path
     * is kept. Every other case changes nothing.
     *
     * TRIGGER:  a modem scan during provisioning or a watchdog resync finds no modem with the shop's IMSI.
     * EVIDENCE: WEAK (a missing IMSI), so the move is limited to a trunk that the driver reports as down
     *           and to the sound card name, and the usual collision check applies to the result.
     * BLAST:    one trunk that is already down. A working trunk is never moved by this rule.
     * GUARD:    trunk state must be a known down state; one modem on the socket; both devices resolved;
     *           the watchdog passes [movable] = confirmed down. The config reload that follows checks for a live call.
     * IF WRONG: a trunk that was down stays down; no customer is affected.
     */
    private fun sameSocketSoundCardMove(
        shop: ShopTelephonyConfig,
        detected: List<DetectedModem>,
        movable: (ShopTelephonyConfig) -> Boolean,
        notes: MutableList<String>,
    ): ShopTelephonyConfig? {
        val socket = socketOfSerialPath(shop.modemDataDevice) ?: return null
        val onSocket = detected.filter { it.usbPort.substringAfter('-') == socket }
        val modem = onSocket.singleOrNull() ?: return null
        if (modem.imsi != null || !isDownState(modem.trunkState)) return null
        val card = modem.alsaDevice
        if (card.isNullOrBlank() || card == shop.modemAlsaDevice) return null
        if (!movable(shop)) {
            notes += "shop ${shop.shopId}: socket $socket now has sound card $card but the trunk is not confirmed down — not moving it"
            return null
        }
        notes += "shop ${shop.shopId}: trunk is down on socket $socket and its sound card changed from " +
            "${shop.modemAlsaDevice} to $card — correcting the sound card only"
        return shop.copy(modemAlsaDevice = card)
    }

    fun plan(
        shops: List<ShopTelephonyConfig>,
        detected: List<DetectedModem>,
        movable: (ShopTelephonyConfig) -> Boolean,
    ): DevicePathPlan {
        if (detected.isEmpty()) return DevicePathPlan(emptyList(), notes = listOf("modem scan found nothing — changing nothing"))
        val notes = mutableListOf<String>()
        val moves = mutableListOf<ShopTelephonyConfig>()
        for (shop in shops) {
            val imsi = shop.imsi ?: continue
            val modem = detected.firstOrNull { it.imsi == imsi }
            if (modem == null) {
                notes += "shop ${shop.shopId}: no modem currently reports IMSI $imsi"
                sameSocketSoundCardMove(shop, detected, movable, notes)?.let { moves += it }
                continue
            }
            if (modem.atDevice == shop.modemDataDevice && modem.alsaDevice == shop.modemAlsaDevice) continue
            val target = "${modem.atDevice} / ${modem.alsaDevice}"
            if (modem.atDevice.isNullOrBlank() || modem.alsaDevice.isNullOrBlank()) {
                notes += "shop ${shop.shopId}: scan found its modem but not both devices ($target) — not moving it"
                continue
            }
            if (!movable(shop)) {
                notes += "shop ${shop.shopId}: scan says $target but the trunk is working on its current config — not moving it"
                continue
            }
            moves += shop.copy(modemDataDevice = modem.atDevice, modemAlsaDevice = modem.alsaDevice)
        }
        if (moves.isEmpty()) return DevicePathPlan(emptyList(), notes = notes)

        var kept: List<ShopTelephonyConfig> = moves
        val refused = mutableListOf<String>()
        while (true) {
            val result = shops.map { s -> kept.firstOrNull { it.shopId == s.shopId } ?: s }
            fun shared(device: (ShopTelephonyConfig) -> String?): Map<String, List<Int>> =
                result.mapNotNull { s -> device(s)?.takeIf { it.isNotBlank() }?.let { it to s.shopId } }
                    .groupBy({ it.first }, { it.second })
                    .filterValues { it.size > 1 }
            val clashes = shared { it.modemDataDevice } + shared { it.modemAlsaDevice }
            val clashing = kept.filter { m ->
                clashes.any { (dev, ids) -> m.shopId in ids && (m.modemDataDevice == dev || m.modemAlsaDevice == dev) }
            }
            if (clashing.isEmpty()) break   // any remaining clash predates this scan; not ours to fix
            clashes.forEach { (dev, ids) -> refused += "$dev would be shared by shops ${ids.joinToString(",")}" }
            kept = kept - clashing.toSet()
        }
        return DevicePathPlan(kept, refused = refused.distinct().joinToString("; ").ifEmpty { null }, notes = notes)
    }
}
