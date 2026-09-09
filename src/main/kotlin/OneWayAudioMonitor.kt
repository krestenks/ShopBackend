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
 * Detection: for each Up PJSIP manager channel (mgrN / shopN-manager), sample the audio RTP
 * counters live via `CHANNEL(rtpqos,audio,{rx,tx}count)`. If we are TRANSMITTING to the phone
 * (txcount past [MIN_TX_COUNT]) but have RECEIVED nothing new for [ONEWAY_STALL_MS] while the call
 * is up, that is one-way audio (customer hears silence) → alert admin once per call. A single
 * received packet resets the stall, so ordinary jitter/loss never trips it. Both-directions-silent
 * is deliberately NOT flagged here (that is a media-path/coverage stall, a different fault).
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
        val MGR_RE = Regex("""PJSIP/(?:mgr\d+|shop\d+-manager)-""")
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
        // Concise fields: name!ctx!exten!prio!state!app!data!cid!acct!ama!duration!bridged!uniqueid
        val lines = amiClient.command("core show channels concise")
        val now = System.currentTimeMillis()
        val liveUids = HashSet<String>()

        // Best-effort affected shop: the single GSM leg currently up (used for the log + preferred
        // sender SIM). Null when ambiguous or absent — the alert still fires, just without a shop.
        val gsmShop = lines.mapNotNull { GSM_RE.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .distinct().singleOrNull()

        for (line in lines) {
            val f = line.split("!")
            val name = f.getOrNull(0) ?: continue
            if (!MGR_RE.containsMatchIn(name)) continue
            if (!f.getOrNull(4).equals("Up", ignoreCase = true)) continue
            val durS = f.getOrNull(10)?.toIntOrNull() ?: 0
            val uid = f.getOrNull(12)?.takeIf { it.isNotBlank() } ?: name
            liveUids.add(uid)
            if (durS < MIN_UPTIME_S) continue

            val rx = amiClient.getChannelVar(name, "CHANNEL(rtpqos,audio,rxcount)")?.toLongOrNull() ?: continue
            val tx = amiClient.getChannelVar(name, "CHANNEL(rtpqos,audio,txcount)")?.toLongOrNull() ?: continue

            val t = tracks.getOrPut(uid) { Track(rx, tx, now, false) }
            if (rx > t.lastRx) {          // any inbound packet clears the stall
                t.lastRx = rx
                t.lastRxProgressAt = now
            }
            t.lastTx = tx

            val downstreamFlowing = tx >= MIN_TX_COUNT
            val inboundStalled = now - t.lastRxProgressAt >= ONEWAY_STALL_MS
            if (!t.alerted && downstreamFlowing && inboundStalled) {
                t.alerted = true
                val mgr = name.removePrefix("PJSIP/").substringBefore('-')
                val msg = "One-way audio: '$mgr' answered a call but is sending NO microphone audio " +
                    "(rx=$rx tx=$tx after ${durS}s) — the customer hears silence. Restart the ShopManager " +
                    "app on that manager's phone to restore its mic uplink."
                println("[OneWayAudio] $msg (uid=$uid)")
                alerter.record("error", "one_way_audio", gsmShop, msg, alertAdmin = true)
            }
        }
        tracks.keys.retainAll(liveUids)   // forget calls that have ended
    }
}
