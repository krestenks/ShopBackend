package asterisk

import java.io.File

/**
 * A physically-present EC25 modem, resolved from sysfs/procfs at scan time.
 * The device paths change across replug/reboot, so nothing here is persisted —
 * we re-resolve on every scan/provision and key everything on the SIM's IMSI.
 */
data class PhysicalModem(
    /** USB device name / stable port path, e.g. "1-1.2.2". */
    val usbPort: String,
    /** AT/data serial device (USB interface .2), e.g. "/dev/ttyUSB3". Null if not found. */
    val atDevice: String?,
    /** ALSA card id for UAC audio, e.g. "EC25Shop1" or "EC25EUX". Null if no card. */
    val alsaCardId: String?,
    /** ALSA card index, e.g. 2. */
    val alsaCardIndex: Int?,
) {
    /** chan_quectel alsadev= value, preferring the stable card id. */
    val alsaDev: String?
        get() = alsaCardId?.let { "hw:CARD=$it" } ?: alsaCardIndex?.let { "hw:$it" }
}

/**
 * Discovers EC25 modems by walking /sys and /proc — no root, no udev. For each
 * modem it finds the current AT port (USB interface .2) and ALSA sound card, so
 * the backend can write chan_quectel config with the right device paths for
 * whichever physical slot a given SIM is in right now.
 */
class ModemResolver {

    companion object {
        private const val EC25_VENDOR = "2c7c"
        private const val EC25_PRODUCT = "0125"
        /** UAC shifts the AT command port to USB interface .2 (if02). */
        private const val AT_INTERFACE_SUFFIX = ":1.2"
    }

    fun listPhysicalModems(): List<PhysicalModem> {
        val usbRoot = File("/sys/bus/usb/devices")
        if (!usbRoot.isDirectory) return emptyList()  // not on the phone server

        return usbRoot.listFiles().orEmpty()
            .filter { it.name.matches(Regex("\\d+-[\\d.]+")) }   // USB device dirs, not interfaces (which contain ':')
            .filter { isEc25(it) }
            .map { dev ->
                val usbPort = dev.name
                PhysicalModem(
                    usbPort = usbPort,
                    atDevice = resolveAtDevice(usbPort),
                    alsaCardId = null,
                    alsaCardIndex = null,
                ).let { m ->
                    val (idx, id) = resolveAlsaCard(usbPort)
                    m.copy(alsaCardIndex = idx, alsaCardId = id)
                }
            }
            .sortedBy { it.usbPort }
    }

    private fun isEc25(devDir: File): Boolean {
        val vendor = File(devDir, "idVendor").takeIf { it.canRead() }?.readText()?.trim()?.lowercase()
        val product = File(devDir, "idProduct").takeIf { it.canRead() }?.readText()?.trim()?.lowercase()
        return vendor == EC25_VENDOR && product == EC25_PRODUCT
    }

    /** /sys/bus/usb/devices/<port>:1.2/{ttyUSBn|tty/ttyUSBn} → /dev/ttyUSBn */
    private fun resolveAtDevice(usbPort: String): String? {
        val ifaceDir = File("/sys/bus/usb/devices/$usbPort$AT_INTERFACE_SUFFIX")
        if (!ifaceDir.isDirectory) return null
        val tty = ifaceDir.listFiles()?.firstOrNull { it.name.startsWith("ttyUSB") }?.name
            ?: File(ifaceDir, "tty").listFiles()?.firstOrNull { it.name.startsWith("ttyUSB") }?.name
        return tty?.let { "/dev/$it" }
    }

    /** Finds the ALSA sound card whose USB parent device is [usbPort]. */
    private fun resolveAlsaCard(usbPort: String): Pair<Int?, String?> {
        val soundRoot = File("/sys/class/sound")
        if (!soundRoot.isDirectory) return null to null
        val card = soundRoot.listFiles().orEmpty()
            .filter { it.name.matches(Regex("card\\d+")) }
            .firstOrNull { c ->
                // card/device → the USB interface; its path contains "/<usbPort>/"
                runCatching { File(c, "device").canonicalPath }.getOrDefault("").contains("/$usbPort/")
            } ?: return null to null
        val index = card.name.removePrefix("card").toIntOrNull()
        val id = File(card, "id").takeIf { it.canRead() }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        return index to id
    }
}
