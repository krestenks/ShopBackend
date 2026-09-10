package asterisk

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.asteriskjava.manager.ManagerConnection
import org.asteriskjava.manager.ManagerConnectionFactory
import org.asteriskjava.manager.ManagerConnectionState
import org.asteriskjava.manager.action.AbstractManagerAction
import org.asteriskjava.manager.action.CommandAction
import org.asteriskjava.manager.action.ManagerAction
import org.asteriskjava.manager.action.OriginateAction
import org.asteriskjava.manager.event.ManagerEvent
import org.asteriskjava.manager.response.CommandResponse
import org.asteriskjava.manager.response.ManagerResponse

/**
 * Custom AMI action provided by chan_quectel (RoEdAl fork): sends an SMS out of
 * a GSM trunk. asterisk-java serialises the bean properties into AMI headers.
 */
class QuectelSendSmsAction(
    private val device: String,
    private val number: String,
    private val message: String,
) : AbstractManagerAction() {
    override fun getAction() = "QuectelSendSMS"
    fun getDevice() = device
    fun getNumber() = number
    fun getMessage() = message
}

/**
 * Thin wrapper around the Asterisk Manager Interface.
 *
 * Connects lazily in the background (with retry) so the backend still starts when
 * Asterisk is down; asterisk-java re-connects automatically after a successful login.
 */
class AmiClient(private val config: AsteriskConfig) {

    companion object {
        /** chan_quectel `show devices` states that mean a voice call is in progress on the modem. */
        val CALL_STATES = setOf(
            "dialing", "incoming", "active", "alerting", "held", "waiting", "ringing", "calling",
        )
    }

    private val connection: ManagerConnection =
        ManagerConnectionFactory(config.amiHost, config.amiPort, config.amiUsername, config.amiSecret)
            .createManagerConnection()

    private val eventFlow = MutableSharedFlow<ManagerEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Stream of raw AMI events (dropped-oldest on overflow; consumers must keep up). */
    val events: SharedFlow<ManagerEvent> = eventFlow

    /**
     * True only while the AMI socket is genuinely logged in. Derived from asterisk-java's LIVE
     * connection state, not a one-shot flag — so it correctly flips back to false on a POST-login
     * disconnect and back to true once asterisk-java reconnects. The old boolean was set true once
     * after first login and never reset except in [stop], so a dropped AMI socket read "connected"
     * forever and made every reachability check (sip-health, the SMS monitor) silently lie —
     * inverting the whole fleet's notion of "reachable" to "unreachable" on any AMI hiccup. [audit H1]
     */
    val connected: Boolean
        get() = runCatching { connection.state == ManagerConnectionState.CONNECTED }.getOrDefault(false)

    /**
     * Registers the event listener and logs in on a background daemon thread,
     * retrying every [retryDelayMs] until the first login succeeds. asterisk-java keeps the socket
     * alive and reconnects on its own afterwards; [connected] tracks that live state.
     */
    fun start(retryDelayMs: Long = 10_000) {
        connection.addEventListener { event -> eventFlow.tryEmit(event) }
        Thread({
            while (true) {
                try {
                    connection.login()
                    println("[AMI] Connected to ${config.amiHost}:${config.amiPort} as ${config.amiUsername}")
                    return@Thread
                } catch (e: Exception) {
                    println("[AMI] Login failed (${e.message}) — retrying in ${retryDelayMs / 1000}s")
                    Thread.sleep(retryDelayMs)
                }
            }
        }, "ami-connect").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { connection.logoff() }
    }

    fun sendAction(action: ManagerAction, timeoutMs: Long = 10_000): ManagerResponse =
        connection.sendAction(action, timeoutMs)

    /** Runs an Asterisk CLI command over AMI and returns its output lines. */
    fun command(cliCommand: String): List<String> {
        val response = sendAction(CommandAction(cliCommand))
        return (response as? CommandResponse)?.result ?: listOfNotNull(response.message)
    }

    fun reloadChanQuectel() {
        command("module reload chan_quectel.so")
    }

    /**
     * Trunk state per chan_quectel device from `quectel show devices`
     * ("Free", "Not connected", "Ring", ...). Empty when AMI is down.
     */
    fun quectelDeviceStates(): Map<String, String> {
        if (!connected) return emptyMap()
        return try {
            // Columns: ID  Group  State  RSSI  Mode  Provider Name  Model  Firmware  IMEI  IMSI  Number
            command("quectel show devices")
                .drop(1)
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 3) return@mapNotNull null
                    // State can be multi-word ("Not initialized", "Not connected"): take every
                    // token between Group (idx 1) and the first numeric column (RSSI), so we don't
                    // truncate it to just "Not". Falls back to the single token if there's no RSSI.
                    val rssiIdx = (2 until parts.size).firstOrNull { parts[it].toIntOrNull() != null } ?: 3
                    val state = parts.subList(2, rssiIdx.coerceIn(3, parts.size)).joinToString(" ")
                    parts[0] to state.ifBlank { parts[2] }
                }
                .toMap()
        } catch (e: Exception) {
            println("[AMI] quectel show devices failed: ${e.message}")
            emptyMap()
        }
    }

    fun reloadDialplan() {
        command("dialplan reload")
    }

    /**
     * AOR names that currently have at least one REACHABLE (Avail) contact, from
     * `pjsip show contacts`. This is Asterisk's authoritative qualify verdict — used by the
     * mobile sip-health endpoint so a phone can learn it has gone Unavailable and re-register.
     * Empty when AMI is down (caller treats that as inconclusive, not "unreachable").
     *
     * Line shape: `  Contact:  mgr2/sip:mgr2@ip:port   <hash>  Avail   <rtt>`
     */
    fun pjsipReachableAors(): Set<String> {
        if (!connected) return emptySet()
        return try {
            command("pjsip show contacts").mapNotNull { line ->
                val t = line.trim()
                if (!t.startsWith("Contact:")) return@mapNotNull null
                val cols = t.removePrefix("Contact:").trim().split(Regex("\\s+"))
                // cols: [aor/contactUri, hash, status, rtt]
                val aor = cols.getOrNull(0)?.substringBefore('/')?.takeIf { it.isNotBlank() }
                if (aor != null && cols.getOrNull(2) == "Avail") aor else null
            }.toSet()
        } catch (e: Exception) {
            println("[AMI] pjsip show contacts failed: ${e.message}")
            emptySet()
        }
    }

    /**
     * Labeled fields from `quectel show device state <trunk>` (IMEI, IMSI, State,
     * RSSI, "Provider Name", "GSM Registration Status", ...). Empty on failure.
     * NOTE: chan_quectel caches IMSI/ICCID — after a hot SIM swap this can be stale
     * until the modem is rebooted.
     */
    fun quectelDeviceState(trunk: String): Map<String, String> {
        if (!connected) return emptyMap()
        return try {
            command("quectel show device state $trunk").mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) return@mapNotNull null
                val key = line.substring(0, i).trim()
                val value = line.substring(i + 1).trim()
                if (key.isEmpty()) null else key to value
            }.toMap()
        } catch (e: Exception) {
            println("[AMI] quectel show device state $trunk failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Sends an SMS out of [trunkName] (e.g. "shop3"). Prefers the chan_quectel AMI
     * action; falls back to the CLI command (validated on the phone server) if the
     * action is missing in the installed driver build.
     */
    fun sendSms(trunkName: String, toNumberE164: String, message: String): AmiSmsResult {
        // Raw send. Serialization + call-gating live in [SmsQueue], which all senders go through.
        // AMI headers are line-based — a raw newline would corrupt the protocol frame.
        val safeMessage = message.replace("\r", "").replace("\n", "\\n")
        try {
            val response = sendAction(QuectelSendSmsAction(trunkName, toNumberE164, safeMessage))
            if (!response.response.equals("Error", ignoreCase = true)) {
                return AmiSmsResult(true, response.message ?: "queued")
            }
            // Unknown action → fall through to CLI; any other error is a real failure.
            if (response.message?.contains("Invalid/unknown command", ignoreCase = true) != true) {
                return AmiSmsResult(false, response.message ?: "AMI error")
            }
        } catch (e: Exception) {
            println("[AMI] QuectelSendSMS action failed (${e.message}) — falling back to CLI")
        }
        return try {
            val cliMessage = safeMessage.replace("\"", "'")
            val out = command("quectel sms send $trunkName $toNumberE164 \"$cliMessage\"")
            val text = out.joinToString(" ").trim()
            AmiSmsResult(!text.contains("error", ignoreCase = true), text.ifBlank { "queued" })
        } catch (e: Exception) {
            AmiSmsResult(false, "AMI unavailable: ${e.message}")
        }
    }

    /** True while this modem has any active voice call (per `quectel show devices`). */
    fun trunkInCall(trunk: String): Boolean =
        quectelDeviceStates()[trunk]?.trim()?.lowercase()?.let { it in CALL_STATES } ?: false

    /**
     * Originates a call from a shop's GSM trunk to [destination], connecting it to
     * [context]/[exten] when answered. Used for backend-initiated calls (e.g. TTS
     * announcements); normal outbound calls go app → PJSIP → dialplan instead.
     */
    fun originate(trunkName: String, destination: String, context: String, exten: String = "s", callerId: String? = null): ManagerResponse {
        val action = OriginateAction().apply {
            channel = "Quectel/$trunkName/$destination"
            this.context = context
            this.exten = exten
            priority = 1
            timeout = 30_000
            async = true
            if (callerId != null) this.callerId = callerId
        }
        return sendAction(action)
    }
}

data class AmiSmsResult(val success: Boolean, val detail: String)
