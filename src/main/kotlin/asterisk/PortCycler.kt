package asterisk

import DataBase
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides whether the USB hub port of a modem may be switched off and on.
 *
 * TRIGGER:  an administrator presses "Power cycle" (or "Force") for one modem. Nothing calls this on a timer.
 * EVIDENCE: STRONG. The driver itself reports the trunk state, and AMI must have answered for every trunk.
 *           For Force, Asterisk's channel list must be complete and show no channel of that trunk.
 * BLAST:    the one modem, and its trunk if it has one. The trunk is down until the modem enumerates again.
 * GUARD:    allowed without Force only when no trunk holds the modem, or the holding trunk is Free or in a down state.
 *           A trunk in any other state (ringing, dialing, active, initializing, stopped, unknown) refuses.
 *           Force is the bound on that refusal: a stuck state such as Dialing after a lost hangup holds the guard
 *           true for ever, so an administrator may force it when Asterisk has no channel of that trunk.
 *           A channel list that could not be read, or a channel that exists, refuses even with Force.
 * IF WRONG: a call on that trunk would be dropped. The guard is read immediately before the helper starts;
 *           a call that begins in the few milliseconds between the two is the remaining risk.
 */
object PortCycleGuard {
    data class Decision(val allowed: Boolean, val reason: String)

    fun decide(lookup: TrunkLookup, trunkHasChannel: Boolean? = null, force: Boolean = false): Decision = when (lookup) {
        TrunkLookup.Unknown -> Decision(false, "AMI could not say whether a trunk holds this modem")
        TrunkLookup.None -> Decision(true, "no trunk holds the modem")
        is TrunkLookup.Held -> {
            val state = lookup.state?.trim().orEmpty()
            when {
                state.equals("free", ignoreCase = true) -> Decision(true, "trunk ${lookup.trunk} is Free")
                DevicePathPlanner.isDownState(state) -> Decision(true, "trunk ${lookup.trunk} is down (state '$state')")
                !force -> Decision(
                    false,
                    "trunk ${lookup.trunk} is '${state.ifEmpty { "unknown" }}', which may be a call. " +
                        "If the line is stuck, use Force: it needs Asterisk to show no channel of this trunk",
                )
                trunkHasChannel == null -> Decision(false, "the channel list of Asterisk could not be read, so Force cannot be checked")
                trunkHasChannel -> Decision(false, "Asterisk has a channel of trunk ${lookup.trunk}, so a call may be live")
                else -> Decision(true, "forced: trunk ${lookup.trunk} is '$state' but Asterisk has no channel of it")
            }
        }
    }
}

/** Switches the hub port of one modem off and on through a helper that only accepts EC25 sockets. */
class PortCycler(
    private val scanner: ModemScanner,
    private val db: DataBase,
    private val amiClient: AmiClient?,
    /** True while a firmware update runs on any modem; a power cycle during an update can ruin the modem. */
    private val firmwareBusy: () -> Boolean = { false },
    private val helperPath: String = System.getenv("EC25_PORT_CYCLE_HELPER") ?: "/usr/local/bin/ec25-port-cycle",
) {
    private val running = AtomicBoolean(false)
    private val socketName = Regex("^\\d+-\\d+(\\.\\d+)+$")

    /** Returns a sentence for the administrator. */
    fun cycle(usbPort: String, force: Boolean = false): String {
        if (!socketName.matches(usbPort)) return "Not a USB socket name: $usbPort"
        if (scanner.physicalModem(usbPort) == null) return "No EC25 modem on $usbPort."
        if (firmwareBusy()) return "Refused: a firmware update is running. Wait until it has finished."
        if (!running.compareAndSet(false, true)) return "Another power cycle is running. Wait for it to finish."
        try {
            // No scan, read or prepare runs on the AT ports while the port is switched.
            return scanner.exclusively {
                // The guard is read immediately before the helper starts.
                val lookup = scanner.lookupTrunk(usbPort)
                val held = lookup as? TrunkLookup.Held
                val channel = if (force && held != null) amiClient?.trunkHasChannelOrNull(held.trunk) else null
                val decision = PortCycleGuard.decide(lookup, channel, force)
                if (!decision.allowed) {
                    println("[Recovery] SUPPRESSED power-cycle target=$usbPort reason=${decision.reason} bound=force")
                    "Refused: ${decision.reason}."
                } else {
                    println("[Recovery] power-cycle target=$usbPort evidence=${decision.reason} callLive=false")
                    val result = runHelper(usbPort)
                    // Recorded after the act, so that no database write sits between the guard and the helper.
                    runCatching {
                        db.recordReliabilityEvent(
                            severity = "warn", category = "modem_power_cycle", shopId = null,
                            message = "Hub port of modem $usbPort switched off and on by an administrator (${decision.reason}): $result",
                        )
                    }
                    result
                }
            }
        } finally {
            running.set(false)
        }
    }

    private fun runHelper(usbPort: String): String = try {
        val process = ProcessBuilder("sudo", "-n", helperPath, usbPort).redirectErrorStream(true).start()
        val finished = process.waitFor(90, TimeUnit.SECONDS)
        if (!finished) {
            // Kill first: reading the output of a running process would block past the timeout.
            process.destroyForcibly()
            "The helper did not finish within 90 s and was stopped."
        } else {
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.exitValue() == 0) output.lines().lastOrNull().orEmpty().ifBlank { "Power cycle done." }
            else "Power cycle failed (exit ${process.exitValue()}): ${output.takeLast(200)}"
        }
    } catch (e: Exception) {
        "Could not start the helper $helperPath: ${e.message}"
    }
}
