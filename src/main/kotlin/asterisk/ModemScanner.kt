package asterisk

import DataBase
import com.fazecast.jSerialComm.SerialPort
import kotlinx.serialization.Serializable

/**
 * One detected modem in a scan: its SIM identity (IMSI/IMEI), signal, current
 * device paths, and which shop (if any) is bound to its SIM.
 */
@Serializable
data class DetectedModem(
    val usbPort: String,
    val atDevice: String? = null,
    val alsaDevice: String? = null,
    val imsi: String? = null,
    val imei: String? = null,
    val provider: String? = null,
    val signal: String? = null,
    /** chan_quectel state when this modem is a live trunk ("Free", "Ring", ...); else null. */
    val trunkState: String? = null,
    /** True when the modem is currently held by chan_quectel as a trunk. */
    val held: Boolean = false,
    val assignedShopId: Int? = null,
    val assignedShopName: String? = null,
    val error: String? = null,
)

/**
 * Enumerates physically-present EC25 modems and reports each with its SIM's IMSI
 * and the shop bound to that IMSI (if any). For modems currently held by
 * chan_quectel it reads identity over AMI (can't AT-probe a held port); for free
 * modems it AT-probes directly. Device paths come from [ModemResolver].
 */
class ModemScanner(
    private val db: DataBase,
    private val config: AsteriskConfig,
    private val amiClient: AmiClient?,
    private val resolver: ModemResolver = ModemResolver(),
) {

    fun scan(): List<DetectedModem> {
        val physical = resolver.listPhysicalModems()
        if (physical.isEmpty()) return emptyList()

        // Which USB port each live chan_quectel trunk sits on, read from AMI (its
        // "Data" device) — so we read a held modem's identity over AMI rather than
        // AT-probing a busy port. Independent of the DB, so it's correct even before
        // any shop has an IMSI assigned.
        val heldByUsbPort: Map<String, String> = trunkUsbPorts(physical)

        val shopNames = db.getAllShops().associate { it.id to it.name }

        return physical.map { m ->
            val trunk = heldByUsbPort[m.usbPort]
            val base = DetectedModem(
                usbPort = m.usbPort,
                atDevice = m.atDevice,
                alsaDevice = m.alsaDev,
                held = trunk != null,
            )
            val filled = if (trunk != null) readFromAmi(base, trunk) else probe(base)
            val shopId = filled.imsi?.let { db.findShopIdByImsi(it) }
            filled.copy(assignedShopId = shopId, assignedShopName = shopId?.let { shopNames[it] })
        }.sortedBy { it.usbPort }
    }

    /** usbPort → trunk name, for every live chan_quectel device (via AMI). */
    private fun trunkUsbPorts(physical: List<PhysicalModem>): Map<String, String> {
        val ami = amiClient ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (trunk in ami.quectelDeviceStates().keys) {
            val dataDev = ami.quectelDeviceState(trunk)["Data"] ?: continue
            val port = physical.firstOrNull { canonicalTty(it.atDevice ?: "") == canonicalTty(dataDev) }?.usbPort
            if (port != null) result[port] = trunk
        }
        return result
    }

    private fun readFromAmi(base: DetectedModem, trunk: String): DetectedModem {
        val st = amiClient?.quectelDeviceState(trunk).orEmpty()
        if (st.isEmpty()) return base.copy(error = "trunk state unavailable")
        return base.copy(
            imsi = st["IMSI"]?.takeIf { it.matches(Regex("\\d{14,15}")) },
            imei = st["IMEI"]?.takeIf { it.isNotBlank() },
            provider = st["Provider Name"]?.takeIf { it.isNotBlank() && it != "Unknown" },
            signal = st["RSSI"]?.takeIf { it.isNotBlank() },
            trunkState = st["State"]?.takeIf { it.isNotBlank() },
        )
    }

    /** AT-probes a free modem for IMSI/IMEI/signal. Safe only when not held by chan_quectel. */
    private fun probe(base: DetectedModem): DetectedModem {
        val path = base.atDevice ?: return base.copy(error = "no AT device")
        val port = SerialPort.getCommPort(path)
        return try {
            port.baudRate = 115200
            if (!port.openPort()) return base.copy(error = "could not open $path")
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1200, 500)

            fun at(cmd: String): String {
                port.outputStream.write("$cmd\r\n".toByteArray())
                port.outputStream.flush()
                Thread.sleep(400)
                val buf = ByteArray(4096)
                val n = port.inputStream.read(buf)
                return if (n > 0) String(buf, 0, n) else ""
            }

            if (!at("AT").contains("OK")) return base.copy(error = "no AT response")
            val imsi = at("AT+CIMI").lines().map { it.trim() }.firstOrNull { it.matches(Regex("\\d{14,15}")) }
            val imei = at("AT+CGSN").lines().map { it.trim() }.firstOrNull { it.matches(Regex("\\d{15}")) }
            val csq = Regex("\\+CSQ: (\\d+),").find(at("AT+CSQ"))?.groupValues?.get(1)
            val cops = Regex("\\+COPS: \\d+,\\d+,\"([^\"]+)\"").find(at("AT+COPS?"))?.groupValues?.get(1)
            base.copy(
                imsi = imsi,
                imei = imei,
                signal = csq?.let { rssiToDbm(it.toInt()) },
                provider = cops,
                trunkState = "free",
            )
        } catch (e: Exception) {
            base.copy(error = e.message)
        } finally {
            runCatching { port.closePort() }
        }
    }

    /**
     * Sends a test SMS to discover a modem's own number. For a held trunk it goes
     * via chan_quectel/AMI; for a free modem it AT-injects on the port directly.
     * @return human-readable result.
     */
    fun sendTestSms(usbPort: String, toNumber: String, body: String): String {
        val all = resolver.listPhysicalModems()
        val physical = all.firstOrNull { it.usbPort == usbPort } ?: return "modem $usbPort not found"
        // Held trunk → send through chan_quectel; else AT-inject on the free port.
        val trunk = trunkUsbPorts(all)[usbPort]
        if (trunk != null && amiClient != null) {
            val r = amiClient.sendSms(trunk, toNumber, body)
            return if (r.success) "sent via trunk $trunk" else "failed: ${r.detail}"
        }
        val path = physical.atDevice ?: return "no AT device for $usbPort"
        return atSendSms(path, toNumber, body)
    }

    private fun atSendSms(path: String, toNumber: String, body: String): String {
        val port = SerialPort.getCommPort(path)
        return try {
            port.baudRate = 115200
            if (!port.openPort()) return "could not open $path"
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1500, 500)
            fun write(s: String) { port.outputStream.write(s.toByteArray()); port.outputStream.flush() }
            write("AT+CMGF=1\r"); Thread.sleep(500)
            write("AT+CMGS=\"$toNumber\"\r"); Thread.sleep(800)
            write(body.replace("\r", " ").replace("\n", " ") + ""); Thread.sleep(6000)
            val buf = ByteArray(4096); val n = port.inputStream.read(buf)
            val resp = if (n > 0) String(buf, 0, n) else ""
            if (resp.contains("+CMGS") || resp.contains("OK")) "sent (AT)" else "failed: ${resp.trim().take(60)}"
        } catch (e: Exception) {
            "failed: ${e.message}"
        } finally {
            runCatching { port.closePort() }
        }
    }

    private fun canonicalTty(path: String): String =
        runCatching { java.io.File(path).canonicalPath }.getOrDefault(path)

    private fun rssiToDbm(rssi: Int): String =
        if (rssi in 0..31) "${-113 + 2 * rssi} dBm" else "unknown"
}
