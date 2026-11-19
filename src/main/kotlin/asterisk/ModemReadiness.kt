package asterisk

import kotlinx.serialization.Serializable

/**
 * What the AT port of one modem reported. A field is null when the command did not answer, which is
 * different from an answer that says "off".
 */
@Serializable
data class ModemReadings(
    /** `READY`, or the error text of `AT+CPIN?` (`no SIM` when the modem reports error 10). */
    val simState: String? = null,
    /** True when the network registration status of `AT+CEREG?` or `AT+CREG?` is 1 (home) or 5 (roaming). */
    val registered: Boolean? = null,
    /** First field of `AT+QNWINFO`, for example `FDD LTE`. */
    val accessTechnology: String? = null,
    /** `AT+QMBNCFG="AutoSel"`: 1 when the modem selects the carrier profile itself. */
    val autoSelect: Int? = null,
    /** Name of the carrier profile with selected=1 in `AT+QMBNCFG="List"`. */
    val activeProfile: String? = null,
    /** First number of `AT+QCFG="ims"`: 1 when VoLTE is enabled. */
    val imsConfigured: Boolean? = null,
    /** Second number of `AT+QCFG="ims"`: 1 when the modem has registered with the carrier's IMS. */
    val imsRegistered: Boolean? = null,
    /** `AT+CGACT?` for context 2, the bearer that carries IMS. */
    val imsContextActive: Boolean? = null,
    /** Last field of `AT+QCFG="usbcfg"`: 1 when the USB audio interface is on. */
    val usbAudio: Boolean? = null,
)

enum class CheckStatus { OK, FIXABLE, BLOCKED, UNKNOWN }

@Serializable
data class ReadinessCheck(val name: String, val status: CheckStatus, val reason: String)

/** Turns the text that the modem returned for each command into [ModemReadings]. */
object ModemReadingsParser {
    fun simState(response: String?): String? {
        if (response == null) return null
        Regex("\\+CPIN: ?([A-Z ]+)").find(response)?.let { return it.groupValues[1].trim() }
        if (response.contains("+CME ERROR: 10") || response.contains("SIM not inserted", ignoreCase = true)) return "no SIM"
        return null
    }

    /** `+CEREG: 0,1` and `+CEREG: 2,1,"3715","2A42E0C",7` both carry the status in the second field. */
    fun registered(cereg: String?, creg: String?): Boolean? {
        fun stat(text: String?, tag: String): Int? =
            text?.let { Regex("\\+$tag: ?\\d+,(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val statuses = listOfNotNull(stat(cereg, "CEREG"), stat(creg, "CREG"))
        if (statuses.isEmpty()) return null
        return statuses.any { it == 1 || it == 5 }
    }

    fun accessTechnology(qnwinfo: String?): String? =
        qnwinfo?.let { Regex("\\+QNWINFO: \"([^\"]*)\"").find(it)?.groupValues?.get(1) }?.takeIf { it.isNotBlank() }

    fun autoSelect(response: String?): Int? =
        response?.let { Regex("\\+QMBNCFG: \"AutoSel\",(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** The profile whose second number (selected) is 1. */
    fun activeProfile(list: String?): String? {
        if (list == null) return null
        return Regex("\\+QMBNCFG: \"List\",\\d+,1,\\d+,\"([^\"]+)\"").find(list)?.groupValues?.get(1)
    }

    /** Returns configured and registered, or null when the command did not answer. */
    fun ims(response: String?): Pair<Boolean, Boolean>? {
        val m = response?.let { Regex("\\+QCFG: \"ims\",(\\d+),(\\d+)").find(it) } ?: return null
        return (m.groupValues[1] == "1") to (m.groupValues[2] == "1")
    }

    fun contextActive(cgact: String?, context: Int): Boolean? {
        if (cgact == null || !cgact.contains("+CGACT")) return null
        // A context that is not listed is not defined yet, which is different from a defined context that is off.
        val m = Regex("\\+CGACT: $context,(\\d+)").find(cgact) ?: return null
        return m.groupValues[1] == "1"
    }

    fun usbAudio(usbcfg: String?): Boolean? {
        val m = usbcfg?.let { Regex("\\+QCFG: \"usbcfg\",([^\\r\\n]+)").find(it) } ?: return null
        val fields = m.groupValues[1].trim().split(',').map { it.trim() }
        if (fields.size < 9) return null
        return fields.last() == "1"
    }
}

/**
 * Decides, from the readings of a modem that no trunk holds, whether a line on it will work, and
 * what to do about each check that fails.
 *
 * OK: nothing to do. FIXABLE: [ModemPreparePlan] can apply the fix. BLOCKED: a person has to act.
 * UNKNOWN: the modem did not answer, so the check says nothing either way.
 */
object ModemReadiness {
    const val UAC_HINT = "AT+QCFG=\"usbcfg\",0x2C7C,0x0125,1,1,1,1,1,0,1 then AT+CFUN=1,1 (see docs/phone-server-setup.md section 7b)"

    fun evaluate(r: ModemReadings): List<ReadinessCheck> {
        val checks = mutableListOf<ReadinessCheck>()
        val simReady = r.simState == "READY"

        checks += when {
            r.simState == null -> ReadinessCheck("SIM", CheckStatus.UNKNOWN, "The modem did not answer the SIM query.")
            simReady -> ReadinessCheck("SIM", CheckStatus.OK, "SIM is ready.")
            r.simState == "no SIM" -> ReadinessCheck(
                "SIM", CheckStatus.BLOCKED,
                "No SIM detected. The modem only detects a SIM when it starts: power-cycle the modem after inserting it.",
            )
            else -> ReadinessCheck("SIM", CheckStatus.BLOCKED, "SIM is not usable: ${r.simState}.")
        }

        checks += when (r.usbAudio) {
            true -> ReadinessCheck("Audio", CheckStatus.OK, "USB audio is on.")
            false -> ReadinessCheck("Audio", CheckStatus.BLOCKED, "USB audio is off, so the modem cannot carry a call. $UAC_HINT")
            null -> ReadinessCheck("Audio", CheckStatus.UNKNOWN, "The USB layout was not read.")
        }

        checks += when (r.autoSelect) {
            1 -> ReadinessCheck("Carrier profile", CheckStatus.OK, "Carrier profile auto-select is on.")
            0 -> ReadinessCheck(
                "Carrier profile", CheckStatus.FIXABLE,
                "Carrier profile auto-select is off. Without a profile some SMS sends time out.",
            )
            else -> ReadinessCheck("Carrier profile", CheckStatus.UNKNOWN, "The carrier profile setting was not read.")
        }

        checks += when {
            !simReady -> ReadinessCheck("Network", CheckStatus.UNKNOWN, "Needs a SIM.")
            r.registered == true -> ReadinessCheck("Network", CheckStatus.OK, "Registered on the network (${r.accessTechnology ?: "unknown technology"}).")
            r.registered == false -> ReadinessCheck("Network", CheckStatus.BLOCKED, "Not registered on the network. Check the antenna, the signal and that the SIM has credit.")
            else -> ReadinessCheck("Network", CheckStatus.UNKNOWN, "The registration status was not read.")
        }

        checks += when (r.imsConfigured) {
            true -> ReadinessCheck("VoLTE setting", CheckStatus.OK, "VoLTE is enabled.")
            false -> ReadinessCheck("VoLTE setting", CheckStatus.FIXABLE, "VoLTE is not enabled.")
            null -> ReadinessCheck("VoLTE setting", CheckStatus.UNKNOWN, "The VoLTE setting was not read.")
        }

        checks += when {
            !simReady -> ReadinessCheck("VoLTE registration", CheckStatus.UNKNOWN, "Needs a SIM.")
            r.imsRegistered == true -> ReadinessCheck("VoLTE registration", CheckStatus.OK, "Registered with the carrier's IMS.")
            r.imsRegistered == null -> ReadinessCheck("VoLTE registration", CheckStatus.UNKNOWN, "The VoLTE state was not read.")
            r.autoSelect != 1 || r.activeProfile == null ->
                ReadinessCheck("VoLTE registration", CheckStatus.UNKNOWN, "Waits for the carrier profile to become active.")
            r.imsContextActive == false -> ReadinessCheck(
                "VoLTE registration", CheckStatus.FIXABLE,
                "Not registered: the IMS data bearer (context 2) is not up. Activating it normally fixes this.",
            )
            else -> ReadinessCheck(
                "VoLTE registration", CheckStatus.UNKNOWN,
                "Not registered yet although the IMS bearer is up. Registration can take about 10 minutes after attach.",
            )
        }
        return checks
    }

    /**
     * True when no check is FIXABLE or BLOCKED and the SIM and the audio checks are OK. Other UNKNOWN
     * checks (for example VoLTE registration still pending) are shown but do not stop a line. A modem
     * that answered nothing has no OK SIM check, so it is never ready.
     */
    fun isReady(checks: List<ReadinessCheck>): Boolean =
        checks.none { it.status == CheckStatus.FIXABLE || it.status == CheckStatus.BLOCKED } &&
            checks.any { it.name == "SIM" && it.status == CheckStatus.OK } &&
            checks.any { it.name == "Audio" && it.status == CheckStatus.OK }
}

/** One AT command to send, and what it changes. */
data class PrepareStep(val command: String, val description: String, val restartsModem: Boolean = false)

/**
 * Plans the writes that make a modem match docs/phone-server-setup.md section 7b. It never writes the
 * USB layout, because changing the layout re-enumerates the device and can leave it unusable.
 */
object ModemPreparePlan {
    fun plan(r: ModemReadings): List<PrepareStep> {
        val steps = mutableListOf<PrepareStep>()
        val simReady = r.simState == "READY"
        val profileChanges = r.autoSelect == 0
        val imsChanges = r.imsConfigured == false
        if (profileChanges) {
            steps += PrepareStep("AT+QMBNCFG=\"AutoSel\",1", "Turn on carrier profile auto-select")
        }
        if (imsChanges) {
            steps += PrepareStep("AT+QCFG=\"ims\",1", "Turn on VoLTE")
        }
        // The IMS bearer is only brought up by hand when no setting changes. A changed setting takes
        // effect after the restart below, and the bearer then comes up by itself.
        val bearerDown = simReady && !profileChanges && !imsChanges && r.autoSelect == 1 && r.activeProfile != null &&
            r.imsRegistered == false && r.imsContextActive == false
        if (bearerDown) {
            steps += PrepareStep("AT+CGACT=1,2", "Bring up the IMS data bearer")
        }
        if (profileChanges || imsChanges) {
            steps += PrepareStep("AT+CFUN=1,1", "Restart the modem so that the changed settings take effect", restartsModem = true)
        }
        return steps
    }
}
