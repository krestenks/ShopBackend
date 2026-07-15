package asterisk

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.asteriskjava.manager.ManagerConnection
import org.asteriskjava.manager.ManagerConnectionFactory
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

    private val connection: ManagerConnection =
        ManagerConnectionFactory(config.amiHost, config.amiPort, config.amiUsername, config.amiSecret)
            .createManagerConnection()

    private val eventFlow = MutableSharedFlow<ManagerEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Stream of raw AMI events (dropped-oldest on overflow; consumers must keep up). */
    val events: SharedFlow<ManagerEvent> = eventFlow

    @Volatile
    var connected = false
        private set

    /**
     * Registers the event listener and logs in on a background daemon thread,
     * retrying every [retryDelayMs] until the first login succeeds.
     */
    fun start(retryDelayMs: Long = 10_000) {
        connection.addEventListener { event -> eventFlow.tryEmit(event) }
        Thread({
            while (true) {
                try {
                    connection.login()
                    connected = true
                    println("[AMI] Connected to ${config.amiHost}:${config.amiPort} as ${config.amiUsername}")
                    return@Thread
                } catch (e: Exception) {
                    connected = false
                    println("[AMI] Login failed (${e.message}) — retrying in ${retryDelayMs / 1000}s")
                    Thread.sleep(retryDelayMs)
                }
            }
        }, "ami-connect").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { connection.logoff() }
        connected = false
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
                    if (parts.size >= 3) parts[0] to parts[2] else null
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
