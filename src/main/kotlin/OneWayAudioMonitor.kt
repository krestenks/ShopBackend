import asterisk.AmiClient
import asterisk.ReliabilityAlerter

/**
 * Detects one-way audio on an answered manager call and alerts the admin.
 *
 * The failure (observed 2026-09-09, proven by packet capture): a manager phone answers a customer
 * call and Asterisk streams the customer's audio TO the phone fine (~100 RTP pkt/s), but the phone
 * sends NO RTP back for the whole call — the manager's microphone/uplink is dead, so the customer
 * hears silence and hangs up after ~15 s (the call logs as OPERATOR_BRIDGED, cause=31). Asterisk
 * does not drop it (rtp_timeout=0) and the modem is fine, so nothing surfaced it — it was only ever
 * caught from customer complaints. Restarting the ShopManager app on the handset restores the uplink.
 *
 * Detection: read live per-channel RTP counters from `pjsip show channelstats` (Receive Count /
 * Transmit Count). For each manager channel (mgrN / shopN-manager), if we are TRANSMITTING to the
 * phone (Tx past [MIN_TX_COUNT]) but the Receive Count has not moved for [ONEWAY_STALL_MS] while the
 * call is up, that is one-way audio (customer hears silence) → alert admin once per call. A single
 * received packet resets the stall, so ordinary jitter/loss never trips it. Both-directions-silent
 * is deliberately NOT flagged here (that is a media-path/coverage stall, a different fault).
 *
 * NB: the first cut read RTP via `CHANNEL(rtpqos,audio,rxcount)` over AMI Getvar — validated
 * 2026-09-10 to return null mid-call on this Asterisk (18.10), so the monitor silently skipped every
 * call and never alerted (proven against a real one-way drop). `pjsip show channelstats` DOES report
 * a live, incrementing Receive Count, so it is the source now.
 *
 * Set ONEWAY_MONITOR_ENABLED=false to disable.
 */
class OneWayAudioMonitor(
    private val amiClient: AmiClient,
    private val alerter: ReliabilityAlerter,
    private val enabled: Boolean = System.getenv("ONEWAY_MONITOR_ENABLED")?.lowercase() != "false",
) {
    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val MIN_UPTIME_S = 4            // ignore brand-new channels still negotiating media
        const val ONEWAY_STALL_MS = 6_000L    // no inbound RTP for this long (while outbound flows) = one-way
        const val MIN_TX_COUNT = 50L          // require real downstream media before judging (rules out idle/dead)
        // channelstats prints the ChannelId WITHOUT the "PJSIP/" prefix and may truncate a long name
        // in its fixed-width column, so match by PREFIX (not an anchored full match).
        val CHANSTATS_MGR_RE = Regex("""^(mgr\d+|shop\d+-manager)-""")
        val GSM_RE = Regex("""Quectel/shop(\d+)-""")
    }

    /** Per-call RTP progress, keyed by channel uniqueid. */
    private class Track(var lastRx: Long, var lastTx: Long, var lastRxProgressAt: Long, var alerted: Boolean)

    private val tracks = HashMap<String, Track>()

    fun start() {
        if (!enabled) {
            println("[OneWayAudio] disabled (ONEWAY_MONITOR_ENABLED=false)")
            return
        }
        Thread({
            while (!amiClient.connected) Thread.sleep(5_000)
            println("[OneWayAudio] started (poll=${POLL_INTERVAL_MS / 1000}s, stall=${ONEWAY_STALL_MS / 1000}s)")
            while (true) {
                try {
                    runCheck()
                } catch (e: Exception) {
                    println("[OneWayAudio] check failed: ${e.message}")
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }, "one-way-audio-monitor").apply { isDaemon = true }.start()
    }

    private fun runCheck() {
        if (!amiClient.connected) return
        val now = System.currentTimeMillis()

        // Best-effort affected shop: the single GSM leg currently up (for the log + preferred SIM).
        val gsmShop = amiClient.command("core show channels concise")
            .mapNotNull { GSM_RE.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .distinct().singleOrNull()

        // Live per-channel RTP counters. A channelstats data line, whitespace-split, with an optional
        // leading BridgeId, is:  [BridgeId] ChannelId UpTime Codec  RxCount RxLost RxPct RxJit  TxCount ...
        // so relative to the ChannelId token: +1 UpTime, +3 RxCount, +7 TxCount.
        val lines = amiClient.command("pjsip show channelstats")
        val liveIds = HashSet<String>()

        for (line in lines) {
            val tok = line.trim().split(Regex("\\s+"))
            val i = tok.indexOfFirst { CHANSTATS_MGR_RE.containsMatchIn(it) }
            if (i < 0 || tok.size < i + 8) continue
            val chanId = tok[i]
            val upSec = parseUpTime(tok[i + 1])
            val rx = tok[i + 3].toLongOrNull() ?: continue
            val tx = tok[i + 7].toLongOrNull() ?: continue
            liveIds.add(chanId)
            if (upSec < MIN_UPTIME_S) continue

            val t = tracks.getOrPut(chanId) { Track(rx, tx, now, false) }
            if (rx > t.lastRx) {          // any inbound packet clears the stall
                t.lastRx = rx
                t.lastRxProgressAt = now
            }
            t.lastTx = tx

            val downstreamFlowing = tx >= MIN_TX_COUNT
            val inboundStalled = now - t.lastRxProgressAt >= ONEWAY_STALL_MS
            if (!t.alerted && downstreamFlowing && inboundStalled) {
                t.alerted = true
                val mgr = chanId.substringBefore('-')
                val msg = "One-way audio: '$mgr' answered a call but is receiving NO microphone audio " +
                    "(rx=$rx flat, tx=$tx after ${upSec}s) — the customer hears silence. Restart the " +
                    "ShopManager app on that manager's phone to restore its mic uplink."
                println("[OneWayAudio] $msg (chan=$chanId)")
                alerter.record("error", "one_way_audio", gsmShop, msg, alertAdmin = true)
            }
        }
        tracks.keys.retainAll(liveIds)   // forget calls that have ended
    }

    /** "HH:MM:SS" / "MM:SS" / "SS" → seconds. */
    private fun parseUpTime(hms: String): Int {
        val p = hms.split(":").map { it.toIntOrNull() ?: return 0 }
        return when (p.size) {
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            2 -> p[0] * 60 + p[1]
            1 -> p[0]
            else -> 0
        }
    }
}
