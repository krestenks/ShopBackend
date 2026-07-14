package asterisk

import DataBase
import com.fazecast.jSerialComm.SerialPort
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ModemInfo(
    val devicePath: String,
    /** Other paths pointing at the same tty (udev symlinks like /dev/ttyQuectelShop1). */
    val aliases: List<String> = emptyList(),
    /** Shop currently configured to use this device (by path or alias), if any. */
    val assignedShopId: Int? = null,
    /** chan_quectel state if the trunk is active in Asterisk ("Free", "Not connected", ...). */
    val asteriskState: String? = null,
    /** Results of AT probing — only attempted for unassigned, inactive ports. */
    val probed: Boolean = false,
    val imsi: String? = null,
    val signalStrength: String? = null,
    val phoneNumber: String? = null,
    val probeError: String? = null,
)

/**
 * Discovers candidate GSM modem AT ports.
 *
 * IMPORTANT: ports belonging to a provisioned trunk are actively held by
 * chan_quectel — writing AT commands to them would corrupt the driver's state
 * machine. Those are reported from DB/AMI state only; raw AT probing (AT+CIMI,
 * AT+CSQ, AT+CNUM) is reserved for ports not assigned to any shop.
 */
class ModemScanner(
    private val db: DataBase,
    private val config: AsteriskConfig,
    private val amiClient: AmiClient?,
) {

    fun scan(probeUnassigned: Boolean = true): List<ModemInfo> {
        val devDir = File("/dev")
        if (!devDir.isDirectory) return emptyList()  // not running on the phone server

        // Candidate ttys: raw USB serial ports.
        val ttys = devDir.listFiles { f -> f.name.startsWith("ttyUSB") }?.toList().orEmpty()

        // udev symlinks (e.g. /dev/ttyQuectelShop1 → ttyUSB3) grouped by their target.
        val aliasesByTarget: Map<String, List<String>> = devDir
            .listFiles { f -> f.name.startsWith("ttyQuectel") }
            ?.mapNotNull { link ->
                runCatching { link.canonicalPath }.getOrNull()?.let { it to link.absolutePath }
            }
            ?.groupBy({ it.first }, { it.second })
            .orEmpty()

        val shopByDevice: Map<String, Int> = db.getAllConfiguredShopTelephonyConfigs()
            .mapNotNull { cfg -> cfg.modemDataDevice?.let { it to cfg.shopId } }
            .toMap()

        val asteriskStates = amiClient?.quectelDeviceStates().orEmpty()

        return ttys.sortedBy { it.name }.map { tty ->
            val path = tty.absolutePath
            val aliases = aliasesByTarget[runCatching { tty.canonicalPath }.getOrDefault(path)].orEmpty()
            val allNames = listOf(path) + aliases
            val shopId = allNames.firstNotNullOfOrNull { shopByDevice[it] }
            val state = shopId?.let { asteriskStates[config.trunkName(it)] }
            val busy = shopId != null || state != null

            if (busy || !probeUnassigned) {
                ModemInfo(path, aliases, shopId, state)
            } else {
                probePort(path, aliases)
            }
        }
    }

    private fun probePort(path: String, aliases: List<String>): ModemInfo {
        val port = SerialPort.getCommPort(path)
        return try {
            port.baudRate = 115200
            if (!port.openPort()) {
                return ModemInfo(path, aliases, probed = true, probeError = "could not open port")
            }
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1200, 500)

            fun at(cmd: String): String {
                port.outputStream.write("$cmd\r\n".toByteArray())
                port.outputStream.flush()
                Thread.sleep(400)
                val buf = ByteArray(4096)
                val n = port.inputStream.read(buf)
                return if (n > 0) String(buf, 0, n) else ""
            }

            // Sanity check first — non-modem serial devices just time out here.
            if (!at("AT").contains("OK")) {
                return ModemInfo(path, aliases, probed = true, probeError = "no AT response")
            }

            val imsi = at("AT+CIMI").lines().map { it.trim() }.firstOrNull { it.matches(Regex("\\d{14,15}")) }
            val csq = Regex("\\+CSQ: (\\d+),").find(at("AT+CSQ"))?.groupValues?.get(1)
            val number = Regex("\\+CNUM: [^,]*,\"([^\"]+)\"").find(at("AT+CNUM"))?.groupValues?.get(1)

            ModemInfo(
                devicePath = path,
                aliases = aliases,
                probed = true,
                imsi = imsi,
                signalStrength = csq?.let { rssiToDbm(it.toInt()) },
                phoneNumber = number,
            )
        } catch (e: Exception) {
            ModemInfo(path, aliases, probed = true, probeError = e.message)
        } finally {
            runCatching { port.closePort() }
        }
    }

    /** AT+CSQ rssi (0-31, 99=unknown) → human-readable dBm. */
    private fun rssiToDbm(rssi: Int): String =
        if (rssi in 0..31) "${-113 + 2 * rssi} dBm" else "unknown"
}
