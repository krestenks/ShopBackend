package asterisk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Central outbound-SMS queue. Every sender routes through here so that, per modem:
 *  - sends are strictly SERIALIZED (never pile concurrent SMS onto one AT port), and
 *  - each send is HELD until the modem has no active voice call — avoiding the AT-port contention
 *    that can make chan_quectel lose a hangup and wedge the modem in "Dialing".
 *
 * One worker per trunk, so different shops' SIMs still send in parallel. Callers get a [Deferred]
 * and never block a request/monitor thread. `immediate=true` skips the idle-wait — used only for
 * SMS sent *during* a call (the IVR booking-link), which would otherwise deadlock (the call can't
 * end until the IVR, which is blocked on the send, proceeds).
 */
class SmsQueue(
    private val amiClient: AmiClient,
    /** Max time to hold a non-immediate SMS while the modem is on a call, then send anyway. */
    private val idleWaitMaxMs: Long = System.getenv("SMS_CALL_GATE_MS")?.toLongOrNull() ?: 15_000L,
) {
    private data class Item(
        val to: String,
        val body: String,
        val immediate: Boolean,
        val result: CompletableDeferred<AmiSmsResult>,
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val workers = HashMap<String, Channel<Item>>()

    /** Enqueue an SMS on [trunk]; returns a [Deferred] with the send result. Never blocks the caller. */
    @Synchronized
    fun enqueue(trunk: String, to: String, body: String, immediate: Boolean = false): Deferred<AmiSmsResult> {
        val result = CompletableDeferred<AmiSmsResult>()
        val chan = workers.getOrPut(trunk) { startWorker(trunk) }
        if (!chan.trySend(Item(to, body, immediate, result)).isSuccess) {
            result.complete(AmiSmsResult(false, "SMS queue unavailable"))
        }
        return result
    }

    private fun startWorker(trunk: String): Channel<Item> {
        val chan = Channel<Item>(Channel.UNLIMITED)
        scope.launch {
            for (item in chan) {
                val res = try {
                    if (!item.immediate) waitForIdle(trunk)
                    amiClient.sendSms(trunk, item.to, item.body)
                } catch (e: Exception) {
                    AmiSmsResult(false, "queue send failed: ${e.message}")
                }
                println("[SmsQueue] $trunk → ${item.to}: ${if (res.success) "sent" else "FAILED (${res.detail})"}")
                item.result.complete(res)
            }
        }
        return chan
    }

    private suspend fun waitForIdle(trunk: String) {
        if (idleWaitMaxMs <= 0) return
        val deadline = System.currentTimeMillis() + idleWaitMaxMs
        var waited = false
        while (amiClient.trunkInCall(trunk)) {
            if (System.currentTimeMillis() >= deadline) {
                println("[SmsQueue] $trunk still on a call after ${idleWaitMaxMs / 1000}s — sending anyway")
                return
            }
            waited = true
            delay(1_000)
        }
        if (waited) println("[SmsQueue] $trunk: waited for the call to clear before sending")
    }
}
