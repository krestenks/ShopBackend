package asterisk

import DataBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Turns the dialplan's FAILOPEN line into a reliability event and an admin SMS.
 *
 * ## Why this exists
 *
 * The inbound dialplan asks the backend for a routing verdict over a localhost CURL. If that CURL
 * returns nothing — the backend is restarting, or the request failed — no `GotoIf` matches and the
 * call falls through to ringing the manager, with the blacklist **and** opening hours bypassed.
 *
 * The trap is that the `voice_call` row is created inside the very endpoint that did not answer. So
 * a call that leaks this way leaves **no database row at all**, and every query over `voice_call`
 * is blind to it by construction. On 2026-10-16 a reported "blocked numbers get through" could be
 * neither confirmed nor refuted for exactly that reason: the only evidence was a single
 * `func_curl.c: Connection refused` line in Asterisk's own log, four weeks old.
 *
 * Measured cause: the backend is absent for ~9 seconds on every deploy (3 s to stop, then ~6 s for
 * Ktor to bind — systemd reports "Started" long before the port is open). One occurrence in four
 * weeks of retained logs, and zero timeouts, so this is not flakiness; it is our own deploys.
 *
 * ## Why it reads a log file rather than being told directly
 *
 * The event happens precisely when the backend is unreachable, so the dialplan cannot report it to
 * the backend at the time. Asterisk's log is the one place the evidence survives. This reads it
 * after the fact.
 *
 * ## Why this is not a destructive watchdog
 *
 * It only logs, records a reliability event, and sends one admin SMS. It cannot hang up a call,
 * restart anything, reload a module or rewrite config. Alerting on weak evidence is explicitly
 * permitted by docs/recovery-code-rules.md §1; acting on it is not, and this does not act.
 */
class FailOpenLogWatch(
    private val db: DataBase,
    private val alerter: ReliabilityAlerter,
    private val logPath: String = DEFAULT_LOG,
    private val intervalMs: Long = 60_000L,
    /**
     * Only report calls whose Asterisk uniqueid is newer than this.
     *
     * The uniqueid is `<epochSeconds>.<seq>`, so it carries its own timestamp — no parsing of the
     * log's own date format, which has no year in it. This bound is what stops a restart from
     * re-alerting on every old line still in the file.
     */
    private val lookbackMs: Long = 30 * 60_000L,
) {
    companion object {
        const val DEFAULT_LOG = "/var/log/asterisk/messages"
        private const val MARKER = "FAILOPEN"
        /** Read at most this much from the tail; the file is normally tens of KB. */
        private const val MAX_READ_BYTES = 256 * 1024L
        /** Bound on remembered ids, so a flood cannot grow this without limit. */
        private const val MAX_REMEMBERED = 2_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Uniqueids already reported, so each leaked call is announced once. */
    private val reported = LinkedHashSet<String>()

    fun start() {
        val f = File(logPath)
        if (!f.isFile) {
            // Not on the phone server (or the path moved). Say so once; do not pretend to watch.
            println("[FailOpenWatch] NOT started: $logPath is not readable — fail-open calls will go unreported")
            return
        }
        println("[FailOpenWatch] started (interval=${intervalMs / 1000}s log=$logPath)")
        scope.launch {
            while (true) {
                runCatching { sweep(System.currentTimeMillis()) }
                    .onFailure { println("[FailOpenWatch] WARN sweep failed: ${it.message}") }
                delay(intervalMs)
            }
        }
    }

    /** One pass. Visible for tests; [now] is injected so the lookback can be exercised. */
    fun sweep(now: Long): List<FailOpenEvent> {
        val events = parse(readTail(), now, lookbackMs)
        val fresh = events.filter { reported.add(it.uniqueId) }
        if (reported.size > MAX_REMEMBERED) {
            val drop = reported.size - MAX_REMEMBERED
            repeat(drop) { reported.iterator().let { if (it.hasNext()) { it.next(); it.remove() } } }
        }
        for (e in fresh) {
            println(
                "[FailOpenWatch] FAIL-OPEN shop=${e.shopId} caller=${mask(e.caller)} " +
                    "id=${e.uniqueId} verdict=[${e.verdict}] — blacklist and hours were bypassed"
            )
            alerter.record(
                severity = "warn",
                category = "call_verdict_failopen",
                shopId = e.shopId,
                message = "A call from ${mask(e.caller)} on shop ${e.shopId} was connected WITHOUT " +
                    "the blacklist or opening-hours check, because the backend did not answer the " +
                    "routing request (verdict was empty). Almost always a backend restart: the " +
                    "port is closed for about 9 seconds on each deploy. If this appears without a " +
                    "deploy, the backend is failing for another reason and needs looking at.",
                alertAdmin = true,
                // Per call, so two leaks in one window are not collapsed into a single SMS.
                dedupeKey = "failopen:${e.uniqueId}",
            )
            runCatching {
                db.recordReliabilityEvent(
                    severity = "warn",
                    category = "call_verdict_failopen",
                    shopId = e.shopId,
                    message = "Call ${e.uniqueId} from ${mask(e.caller)} bypassed the blacklist and " +
                        "opening-hours check (empty verdict from call/inbound). No voice_call row " +
                        "exists for it.",
                )
            }
        }
        return fresh
    }

    private fun readTail(): String {
        val f = File(logPath)
        if (!f.isFile) return ""
        return runCatching {
            val len = f.length()
            if (len <= MAX_READ_BYTES) f.readText()
            else f.inputStream().use { s ->
                s.skip(len - MAX_READ_BYTES)
                s.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrElse { "" }
    }

    private fun mask(phone: String): String =
        if (phone.length > 6) phone.take(3) + "XXXX" + phone.takeLast(2) else "***"
}

/** One call that was connected without the blacklist/hours check. */
data class FailOpenEvent(
    val shopId: Int,
    val caller: String,
    val uniqueId: String,
    val verdict: String,
)

/**
 * Extracts FAILOPEN events from Asterisk log [text], keeping only calls whose uniqueid is newer
 * than [lookbackMs] before [now].
 *
 * Pure, so the log shapes that matter — including a truncated first line from reading the tail
 * mid-file, and an unparsable uniqueid — can be replayed in a test without an Asterisk.
 */
internal fun parse(text: String, now: Long, lookbackMs: Long): List<FailOpenEvent> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<FailOpenEvent>()
    for (line in text.lineSequence()) {
        if (!line.contains("FAILOPEN")) continue
        val shop = Regex("""FAILOPEN shop=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
        val id = Regex("""\bid=(\d+\.\d+)""").find(line)?.groupValues?.get(1) ?: continue
        // The uniqueid's own epoch seconds; no need to parse the log's yearless date.
        val epochSec = id.substringBefore('.').toLongOrNull() ?: continue
        if (epochSec * 1000L < now - lookbackMs) continue
        val caller = Regex("""caller=(\+?\d+)""").find(line)?.groupValues?.get(1) ?: ""
        val verdict = Regex("""verdict=\[([^\]]*)]""").find(line)?.groupValues?.get(1) ?: ""
        out += FailOpenEvent(shopId = shop, caller = caller, uniqueId = id, verdict = verdict)
    }
    return out
}
