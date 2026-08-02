package asterisk

import java.io.File

/**
 * Flashes Quectel EC25 modem firmware via QFirehose.
 *
 * The flash runs DETACHED from the backend as a transient systemd unit (its own cgroup), so a
 * backend restart/redeploy can NEVER interrupt a flash mid-way — interrupting a flash bricks the
 * modem. Output is redirected to a per-port log file that the admin UI polls for progress.
 *
 * Proven command (see phone-server memory): sudo QFirehose -f <fwDir> -s /sys/bus/usb/devices/<usbPort>
 * Only ONE flash runs at a time. Callers should restrict this to UNASSIGNED modems.
 */
class ModemFirmwareUpdater(
    private val qfirehoseBin: String =
        System.getenv("QFIREHOSE_BIN")?.trim()?.takeIf { it.isNotBlank() } ?: "/home/phone/qfirehose/QFirehose",
    private val fwDir: String =
        System.getenv("MODEM_FW_DIR")?.trim()?.takeIf { it.isNotBlank() } ?: "/home/phone/ec25fw",
    private val logDir: File =
        File(System.getenv("MODEM_FW_LOG_DIR")?.trim()?.takeIf { it.isNotBlank() } ?: "data/fw"),
) {
    data class StartResult(val ok: Boolean, val message: String)
    data class Status(val running: Boolean, val result: String, val log: String)

    /** USB sysfs port like "1-1.2.1". Strictly validated — it goes into the systemd-run command. */
    private val portRe = Regex("^[0-9]+-[0-9.]+$")
    private fun unit(port: String) = "modem-fw-" + port.replace('.', '-').replace(':', '-')
    private fun logFile(port: String) = File(logDir, unit(port) + ".log")

    private fun sh(cmd: String): String = try {
        val p = ProcessBuilder("/bin/bash", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Exception) { "error: ${e.message}" }

    fun isRunning(port: String): Boolean = sh("systemctl is-active ${unit(port)}").trim() == "active"

    /** The systemd unit of an in-progress flash on ANY modem (null if none) — used to enforce one-at-a-time. */
    fun activeUnit(): String? =
        sh("systemctl list-units --type=service --state=active --no-legend 'modem-fw-*.service' 2>/dev/null")
            .lineSequence().map { it.trim().removePrefix("●").trim().substringBefore(' ') }
            .firstOrNull { it.startsWith("modem-fw-") }

    fun start(port: String): StartResult {
        if (!portRe.matches(port)) return StartResult(false, "Invalid USB port")
        if (!File("/sys/bus/usb/devices/$port").exists()) return StartResult(false, "Modem $port is not present")
        activeUnit()?.let { return StartResult(false, "A firmware update is already running ($it) — wait for it to finish") }

        logDir.mkdirs()
        val u = unit(port)
        val log = logFile(port).absolutePath
        sh("sudo systemctl reset-failed $u 2>/dev/null")
        // Detached transient unit; QFirehose stdout+stderr → the per-port log. Paths are fixed config,
        // port is regex-validated, so this command string is safe to assemble.
        val inner = "$qfirehoseBin -f $fwDir -s /sys/bus/usb/devices/$port > $log 2>&1"
        val out = sh("sudo systemd-run --unit=$u --description=ec25-fw-$port /bin/bash -c '$inner'")
        return if (out.contains("Running as unit") || isRunning(port))
            StartResult(true, "Firmware update started for $port. Do NOT unplug or power off the modem.")
        else StartResult(false, "Failed to start: ${out.trim().take(300)}")
    }

    fun status(port: String): Status {
        val running = isRunning(port)
        val result = if (running) "running"
        else sh("systemctl show ${unit(port)} -p Result --value").trim().ifBlank { "—" }
        val log = logFile(port).takeIf { it.exists() }?.readText()?.takeLast(6000) ?: "(no log yet)"
        return Status(running, result, log)
    }
}
