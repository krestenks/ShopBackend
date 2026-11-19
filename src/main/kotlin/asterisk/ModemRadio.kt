package asterisk

import com.fazecast.jSerialComm.SerialPort

/**
 * Reads and prepares the radio settings of a modem over its AT port.
 *
 * The caller must make sure that no chan_quectel trunk holds the port. Two readers on one port
 * lose replies, and chan_quectel loses hangups that way (docs/recovery-paths-audit-2026-09-13.md,
 * finding 8). [ModemScanner] only passes modems that no trunk holds.
 */
class ModemRadio {

    /** One open AT port. Each command waits for its own final reply instead of a fixed delay. */
    private class AtSession(path: String) : AutoCloseable {
        private val port: SerialPort = SerialPort.getCommPort(path)
        val opened: Boolean

        init {
            port.baudRate = 115200
            port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0)
            opened = port.openPort()
        }

        /** The reply text, or null when nothing at all came back within [timeoutMs]. */
        fun send(command: String, timeoutMs: Long = 3_000): String? {
            drain()
            port.outputStream.write("$command\r".toByteArray())
            port.outputStream.flush()
            val reply = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val waiting = port.bytesAvailable()
                if (waiting > 0) {
                    val buffer = ByteArray(waiting)
                    val count = port.readBytes(buffer, buffer.size)
                    if (count > 0) reply.append(String(buffer, 0, count))
                    val text = reply.toString()
                    if (text.contains("\r\nOK\r\n") || text.contains("ERROR")) break
                } else {
                    Thread.sleep(25)
                }
            }
            return reply.toString().ifEmpty { null }
        }

        private fun drain() {
            val waiting = port.bytesAvailable()
            if (waiting > 0) port.readBytes(ByteArray(waiting), waiting)
        }

        override fun close() {
            runCatching { port.closePort() }
        }
    }

    /** Reads every setting that [ModemReadiness] needs. Null when the port cannot be opened. */
    fun read(atPath: String): ModemReadings? = try {
        AtSession(atPath).use { s ->
            if (!s.opened) return null
            readFrom(s)
        }
    } catch (e: Exception) {
        println("[ModemRadio] read of $atPath failed: ${e.message}")
        null
    }

    private fun readFrom(s: AtSession): ModemReadings {
        val ims = ModemReadingsParser.ims(s.send("AT+QCFG=\"ims\""))
        return ModemReadings(
            simState = ModemReadingsParser.simState(s.send("AT+CPIN?")),
            registered = ModemReadingsParser.registered(s.send("AT+CEREG?"), s.send("AT+CREG?")),
            accessTechnology = ModemReadingsParser.accessTechnology(s.send("AT+QNWINFO")),
            autoSelect = ModemReadingsParser.autoSelect(s.send("AT+QMBNCFG=\"AutoSel\"")),
            activeProfile = ModemReadingsParser.activeProfile(s.send("AT+QMBNCFG=\"List\"", 5_000)),
            imsConfigured = ims?.first,
            imsRegistered = ims?.second,
            imsContextActive = ModemReadingsParser.contextActive(s.send("AT+CGACT?"), 2),
            usbAudio = ModemReadingsParser.usbAudio(s.send("AT+QCFG=\"usbcfg\"")),
        )
    }

    /**
     * Sends the steps of [plan] in order and stops at the first step that does not answer OK.
     * Returns one line per step that was sent. A step that restarts the modem is the last one.
     */
    fun prepare(atPath: String, plan: List<PrepareStep>): List<String> {
        if (plan.isEmpty()) return listOf("Nothing to change.")
        val lines = mutableListOf<String>()
        try {
            AtSession(atPath).use { s ->
                if (!s.opened) return listOf("Could not open $atPath.")
                for (step in plan) {
                    val reply = s.send(step.command, timeoutMs = if (step.command.startsWith("AT+CGACT")) 20_000 else 5_000)
                    val ok = reply?.contains("OK") == true
                    lines += "${step.description}: ${step.command} -> ${if (ok) "OK" else reply?.trim()?.take(80) ?: "no answer"}"
                    if (!ok) {
                        lines += "Stopped: the later steps depend on this one."
                        break
                    }
                }
            }
        } catch (e: Exception) {
            lines += "Failed: ${e.message}"
        }
        return lines
    }
}
