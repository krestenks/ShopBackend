package asterisk

import DataBase
import VoiceCallOutcome
import VoiceCallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.asteriskjava.manager.event.DialEndEvent
import org.asteriskjava.manager.event.HangupEvent

/**
 * Translates AMI events into call-log updates.
 *
 * Inbound call rows are created by the dialplan's CURL to
 * /api/internal/telephony/call/inbound, keyed by the Asterisk channel UNIQUEID
 * (stored in voice_call.twilio_call_sid — same column, provider-neutral role).
 * This handler closes the loop:
 *
 *   DialEnd(ANSWER) on the GSM leg  → state BRIDGED_TO_OPERATOR
 *   Hangup of the Quectel channel   → terminate row (bridged vs missed by prior state)
 */
class AsteriskEventHandler(
    private val amiClient: AmiClient,
    private val db: DataBase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        amiClient.events
            .onEach { event ->
                try {
                    when (event) {
                        is DialEndEvent -> onDialEnd(event)
                        is HangupEvent -> onHangup(event)
                        else -> {}
                    }
                } catch (e: Exception) {
                    println("[AsteriskEvents] Error handling ${event::class.simpleName}: ${e.message}")
                }
            }
            .launchIn(scope)
    }

    private fun onDialEnd(event: DialEndEvent) {
        if (!event.dialStatus.equals("ANSWER", ignoreCase = true)) return
        val uniqueId = event.uniqueId ?: return
        val record = db.getCallByTwilioSid(uniqueId) ?: return
        if (record.isActive) {
            db.updateCallState(record.id, VoiceCallState.BRIDGED_TO_OPERATOR, "sip_answered")
        }
    }

    private fun onHangup(event: HangupEvent) {
        val channel = event.channel ?: return
        if (!channel.startsWith("Quectel/", ignoreCase = true)) return
        val uniqueId = event.uniqueId ?: return
        val record = db.getCallByTwilioSid(uniqueId) ?: return
        if (!record.isActive) return
        val outcome = if (record.state == VoiceCallState.BRIDGED_TO_OPERATOR.name) {
            VoiceCallOutcome.OPERATOR_BRIDGED
        } else {
            VoiceCallOutcome.OPERATOR_DECLINED
        }
        db.terminateCall(record.id, outcome)
        println("[AsteriskEvents] Call $uniqueId ended (${outcome.name}), cause=${event.cause}")
    }
}
