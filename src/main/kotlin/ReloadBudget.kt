/**
 * How many PBX-wide `module reload chan_quectel.so` one trunk's down-episode may spend.
 *
 * A reload re-inits EVERY trunk, so it is the most expensive rung of the down-recovery ladder. A
 * reload that did not bring a trunk back the first time will not the fifth time, and each repeat
 * takes the healthy shops' lines down with it (2026-09-17: shop5's modem hung for 2.5 h and the
 * ladder reloaded the whole PBX four times). So: one reload per episode, where an episode ends when
 * the trunk is seen healthy again ([reset]).
 *
 * Bounded, not permanent (recovery-code-rules §4): after [rearmAfterMs] the trunk earns one more
 * reload, so a fault that only a reload can clear is not locked out for the rest of a long outage.
 */
class ReloadBudget(private val rearmAfterMs: Long) {
    private val spentAt = HashMap<String, Long>()

    /** True if [trunk] may trigger a PBX reload now. */
    fun available(trunk: String, now: Long): Boolean = msUntilRearm(trunk, now) == 0L

    /** Record that [trunk] triggered a reload at [now]. */
    fun spend(trunk: String, now: Long) { spentAt[trunk] = now }

    /** The trunk recovered: its next down-episode starts with a fresh budget. */
    fun reset(trunk: String) { spentAt.remove(trunk) }

    /** Milliseconds until [trunk] may reload again; 0 if it may now. */
    fun msUntilRearm(trunk: String, now: Long): Long {
        val t = spentAt[trunk] ?: return 0L
        return maxOf(0L, rearmAfterMs - (now - t))
    }
}
