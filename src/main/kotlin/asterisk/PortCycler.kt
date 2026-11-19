package asterisk

import DataBase
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides whether the USB hub port of a modem may be switched off and on.
 *
 * TRIGGER:  an administrator presses "Power cycle" for one modem. Nothing calls this on a timer.
 * EVIDENCE: STRONG. The driver itself reports the trunk state, and AMI must have answered for every trunk.
 * BLAST:    the one modem, and its trunk if it has one. The trunk is down until the modem enumerates again.
 * GUARD:    allowed only when no trunk holds the modem, or the holding trunk is Free or in a down state.
 *           A ringing, dialing or active trunk, a state that is not known, and an AMI that did not answer all refuse.
 * IF WRONG: a call on that trunk would be dropped. The guard is read immediately before the helper starts;
 *           a call that begins in the few milliseconds between the two is the remaining risk.
 */
object PortCycleGuard {
    data class Decision(val allowed: Boolean, val reason: String)

    fun decide(lookup: TrunkLookup): Decision = when (lookup) {
        TrunkLookup.Unknown -> Decision(false, "AMI could not say whether a trunk holds this modem")
        TrunkLookup.None -> Decision(true, "no trunk holds the modem")
        is TrunkLookup.Held -> {
            val state = lookup.state?.trim().orEmpty()
            when {
                state.equals("free", ignoreCase = true) -> Decision(true, "trunk ${lookup.trunk} is Free")
                DevicePathPlanner.isDownState(state) -> Decision(true, "trunk ${lookup.trunk} is down (state '$state')")
                else -> Decision(false, "trunk ${lookup.trunk} is '${state.ifEmpty { "unknown" }}', which may be a call")
            }
        }
    }
}

/** Switches the hub port of one modem off and on through a helper that only accepts EC25 sockets. */
class PortCycler(
    private val scanner: ModemScanner,
    private val db: DataBase,
    private val helperPath: String = System.getenv("EC25_PORT_CYCLE_HELPER") ?: "/usr/local/bin/ec25-port-cycle",
) {
    private val running = AtomicBoolean(false)
    private val socketName = Regex("^\\d+-\\d+(\\.\\d+)+$")

    /** Returns a sentence for the administrator. */
    fun cycle(usbPort: String): String {
        if (!socketName.matches(usbPort)) return "Not a USB socket name: $usbPort"
        if (scanner.physicalModem(usbPort) == null) return "No EC25 modem on $usbPort."
        if (!running.compareAndSet(false, true)) return "Another power cycle is running. Wait for it to finish."
        try {
            // The guard is read immediately before the helper starts.
            val lookup = scanner.lookupTrunk(usbPort)
            val decision = PortCycleGuard.decide(lookup)
            if (!decision.allowed) {
                println("[Recovery] SUPPRESSED power-cycle target=$usbPort reason=${decision.reason}")
                return "Refused: ${decision.reason}."
            }
            println("[Recovery] power-cycle target=$usbPort evidence=${decision.reason} callLive=false")
            db.recordReliabilityEvent(
                severity = "warn", category = "modem_power_cycle", shopId = null,
                message = "Hub port of modem $usbPort switched off and on by an administrator (${decision.reason}).",
            )
            return runHelper(usbPort)
        } finally {
            running.set(false)
        }
    }

    private fun runHelper(usbPort: String): String = try {
        val process = ProcessBuilder("sudo", "-n", helperPath, usbPort).redirectErrorStream(true).start()
        val finished = process.waitFor(90, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText().trim()
        when {
            !finished -> { process.destroyForcibly(); "The helper did not finish within 90 s: ${output.takeLast(200)}" }
            process.exitValue() == 0 -> output.lines().lastOrNull().orEmpty().ifBlank { "Power cycle done." }
            else -> "Power cycle failed (exit ${process.exitValue()}): ${output.takeLast(200)}"
        }
    } catch (e: Exception) {
        "Could not start the helper $helperPath: ${e.message}"
    }
}
