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
    /** Module firmware revision, e.g. "EC25EUXGAR08A19M1G" (AT+QGMR). */
    val firmware: String? = null,
    /** chan_quectel state when this modem is a live trunk ("Free", "Ring", ...); else null. */
    val trunkState: String? = null,
    /** True when the modem is currently held by chan_quectel as a trunk. */
    val held: Boolean = false,
    val assignedShopId: Int? = null,
    val assignedShopName: String? = null,
    val error: String? = null,
    /** Radio settings read over the AT port. Only read for a modem that no trunk holds. */
    val readings: ModemReadings? = null,
    /** Result of [ModemReadiness.evaluate] for [readings]; empty when the modem was not read. */
    val checks: List<ReadinessCheck> = emptyList(),
)

/** Which trunk holds a modem, as far as AMI could tell. */
sealed interface TrunkLookup {
    /** AMI did not answer, or a trunk's device could not be read, so a trunk may hold the modem. */
    data object Unknown : TrunkLookup
    /** AMI answered for every trunk and none holds the modem. */
    data object None : TrunkLookup
    data class Held(val trunk: String, val state: String?) : TrunkLookup
}

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
    private val radio: ModemRadio = ModemRadio(),
) {

    /**
     * Finds the trunk that holds the modem on [usbPort]. Every answer other than "AMI answered for all
     * trunks" is [TrunkLookup.Unknown], so a caller that must not touch a held modem fails closed.
     */
    fun lookupTrunk(usbPort: String): TrunkLookup {
        // Without AMI nothing can say whether a trunk holds the modem.
        val ami = amiClient ?: return TrunkLookup.Unknown
        val states = ami.quectelDeviceStatesOrNull() ?: return TrunkLookup.Unknown
        val physical = resolver.listPhysicalModems()
        val target = physical.firstOrNull { it.usbPort == usbPort } ?: return TrunkLookup.Unknown
        // A modem whose AT port is not resolved cannot be compared with a trunk's device.
        if (target.atDevice.isNullOrBlank()) return TrunkLookup.Unknown
        for ((trunk, state) in states) {
            val details = ami.quectelDeviceState(trunk)
            val data = details["Data"]
            if (data.isNullOrBlank()) {
                // A trunk without a readable device may be the one that holds this modem.
                return TrunkLookup.Unknown
            }
            if (canonicalTty(data) == canonicalTty(target.atDevice)) {
                return TrunkLookup.Held(trunk, details["State"]?.takeIf { it.isNotBlank() } ?: state)
            }
        }
        return TrunkLookup.None
    }

    fun physicalModem(usbPort: String): PhysicalModem? = resolver.listPhysicalModems().firstOrNull { it.usbPort == usbPort }

    /** One reader or writer at a time on the AT ports that this class opens. */
    private val radioLock = Any()

    /** Runs [block] while no scan, read or prepare of this class runs. Used by the power cycle. */
    fun <T> exclusively(block: () -> T): T = synchronized(radioLock) { block() }

    /**
     * Applies the radio settings that [ModemPreparePlan] plans for the modem on [usbPort].
     *
     * TRIGGER:  an administrator presses "Prepare" for one modem.
     * EVIDENCE: STRONG. The settings are read back from the modem itself.
     * BLAST:    the one modem; when the carrier profile changes, that modem restarts once.
     * GUARD:    runs only when AMI answered for every trunk and none holds the modem.
     * IF WRONG: a modem that no trunk holds restarts or changes a setting. No call can be on it.
     */
    fun prepareModem(usbPort: String): String {
        val modem = physicalModem(usbPort) ?: return "No EC25 modem on $usbPort."
        val path = modem.atDevice ?: return "The modem on $usbPort has no AT port."
        when (val lookup = lookupTrunk(usbPort)) {
            TrunkLookup.None -> Unit
            TrunkLookup.Unknown -> {
                println("[Recovery] SUPPRESSED prepare-modem target=$usbPort reason=AMI could not say whether a trunk holds the modem")
                return "Refused: AMI could not say whether a trunk holds this modem."
            }
            is TrunkLookup.Held -> {
                println("[Recovery] SUPPRESSED prepare-modem target=$usbPort reason=trunk ${lookup.trunk} holds the modem")
                return "Refused: trunk ${lookup.trunk} holds this modem. Prepare works on modems that no trunk holds."
            }
        }
        synchronized(radioLock) {
            val before = radio.read(path) ?: return "Could not read the modem on $usbPort."
            val plan = ModemPreparePlan.plan(before)
            if (plan.isEmpty()) {
                val open = ModemReadiness.evaluate(before).filter { it.status != CheckStatus.OK }
                return if (open.isEmpty()) "Nothing to change. All checks pass."
                else "Nothing the backend can change. " + open.joinToString(" ") { "${it.name}: ${it.reason}" }
            }
            // The first lookup is some seconds old after the reads; ask again immediately before the writes.
            if (lookupTrunk(usbPort) != TrunkLookup.None) {
                println("[Recovery] SUPPRESSED prepare-modem target=$usbPort reason=a trunk may hold the modem now")
                return "Refused: a trunk may hold this modem now. Nothing was changed."
            }
            println("[Recovery] prepare-modem target=$usbPort evidence=$before steps=${plan.joinToString(";") { it.command }} callLive=false")
            return radio.prepare(path, plan).joinToString(" ")
        }
    }

    /** [includeRadio] also reads the radio settings of every modem that no trunk holds. */
    fun scan(includeRadio: Boolean = false): List<DetectedModem> {
        val physical = resolver.listPhysicalModems()
        if (physical.isEmpty()) return emptyList()

        // Which USB port each live chan_quectel trunk sits on, read from AMI (its
        // "Data" device) — so we read a held modem's identity over AMI rather than
        // AT-probing a busy port. Independent of the DB, so it's correct even before
        // any shop has an IMSI assigned.
        val heldByUsbPort: Map<String, String> = trunkUsbPorts(physical)

        // Fail closed on "I could not ask" — but NOT on "the answer was empty".
        //
        // Probing writes AT commands to a port chan_quectel may own, and AT-port contention is the
        // documented way the driver loses a hangup and wedges (audit finding 8), so we must not probe
        // a modem whose trunk status is unknown. The question is whether AMI ANSWERED, though, not
        // whether any trunk happened to resolve: an empty answer is legitimate on a box that has not
        // been provisioned yet, and keying on emptiness deadlocks it — no trunk means no probe, no
        // probe means no IMSI, and no IMSI means no trunk can ever be created. It also disabled the
        // USB-re-enumeration self-heal, whose whole premise is that every configured device path has
        // stopped matching a present modem.
        val amiUsable = amiClient == null || amiClient.quectelDeviceStatesOrNull() != null

        val shopNames = db.getAllShops().associate { it.id to it.name }

        return physical.map { m ->
            val trunk = heldByUsbPort[m.usbPort]
            val base = DetectedModem(
                usbPort = m.usbPort,
                atDevice = m.atDevice,
                alsaDevice = m.alsaDev,
                held = trunk != null,
            )
            val filled = when {
                trunk != null -> readFromAmi(base, trunk)
                !amiUsable -> {
                    println("[ModemScanner] ${m.usbPort}: AMI did not answer 'quectel show devices', " +
                        "so we cannot tell whether a trunk holds this modem — not probing")
                    base.copy(error = "not probed (AMI unavailable)")
                }
                else -> probe(base).let { probed ->
                    val path = probed.atDevice
                    // The radio read sends about ten commands, so it needs the strict answer: only when AMI
                    // answered for every trunk and none holds the modem, asked now and not from the snapshot above.
                    if (includeRadio && probed.error == null && path != null && lookupTrunk(m.usbPort) == TrunkLookup.None) {
                        val readings = synchronized(radioLock) { radio.read(path) }
                        probed.copy(readings = readings, checks = readings?.let { ModemReadiness.evaluate(it) }.orEmpty())
                    } else probed
                }
            }
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
            firmware = st["Firmware"]?.substringBefore('_')?.takeIf { it.isNotBlank() },
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
            // AT+QGMR → the module firmware revision line. AT+QGMR returns the FULL build
            // (e.g. EC25EUXGAR08A19M1G_A0.302.A0.302) while chan_quectel reports only the short
            // revision (EC25EUXGAR08A19M1G) — normalize to the short form so free vs held modems
            // show the same value (drop the _A0.302… build suffix).
            val fw = at("AT+QGMR").lines().map { it.trim() }
                .firstOrNull { it.isNotBlank() && it != "OK" && !it.startsWith("AT") && !it.startsWith("+") }
                ?.substringBefore('_')
            base.copy(
                imsi = imsi,
                imei = imei,
                signal = csq?.let { rssiToDbm(it.toInt()) },
                provider = cops,
                firmware = fw,
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
