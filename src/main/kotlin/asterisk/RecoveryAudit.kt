package asterisk

/**
 * One place every automated destructive action announces itself.
 *
 * Why this exists: the CLCC reaper hung up healthy calls ~139 times a day for two weeks and logged
 * a WARNING every single time. The information was there; nothing surfaced it. Documentation and
 * review depend on foresight — on having imagined the failure. This does not: it reports what
 * actually happened, so a recovery bug we never thought of still announces itself the first day it
 * fires.
 *
 * The canary is [CallState.LIVE]. A recovery action firing while a call is in progress is a bug
 * essentially by definition — see docs/recovery-code-rules.md. That is the condition worth waking
 * someone for, and it is cheap to detect.
 *
 * This class is observability only. It never blocks, never throws into its caller, and never
 * decides anything — deliberately, because a bug in the thing that watches recovery must not become
 * a new way to break recovery.
 */
object RecoveryAudit {

    /** Evidence quality behind a destructive act. See docs/recovery-code-rules.md §1. */
    enum class Evidence {
        /** The system positively asserted the bad state (a hangup URC, a dead fd, a parsed "no"). */
        STRONG,

        /** Inferred from something missing: a timeout, a missed poll, an unparsed or absent reply. */
        WEAK,
    }

    /** Whether a call was in progress. UNKNOWN is a first-class answer, never folded into IDLE. */
    enum class CallState { LIVE, IDLE, UNKNOWN }

    /** How far the damage reaches if the action was wrong. */
    enum class Blast {
        /** One call. */ CALL,
        /** One trunk / one shop's modem. */ TRUNK,
        /** One manager handset. */ PHONE,
        /** Every trunk on the box — a chan_quectel reload, an Asterisk restart. */ PBX,
    }

    /**
     * `N active calls` in `core show channels`. Matching this is also how we know we received a
     * real listing rather than an AMI error string: absence of the line means we could not ask,
     * which is [CallState.UNKNOWN], not [CallState.IDLE]. Collapsing those two is the exact bug
     * this class exists to catch (AmiClient.command() still does it — audit finding 6).
     */
    private val ACTIVE_CALLS_RE = Regex("""(\d+)\s+active\s+calls?""", RegexOption.IGNORE_CASE)

    /**
     * Record that a destructive recovery action fired.
     *
     * Call this immediately BEFORE the destructive act, so the call-state reading describes the
     * situation the action is about to damage rather than the wreckage afterwards.
     *
     * @param action what is being done, imperative and specific ("quectel restart", "chan_quectel reload")
     * @param target what it is being done to (trunk, AOR, "PBX")
     * @param evidence the actual values acted on, with numbers — not a restatement of [action]
     * @param strength [Evidence.WEAK] means this action is only defensible if it is non-destructive
     * @param blast how far the damage reaches
     */
    fun fired(
        action: String,
        target: String,
        evidence: String,
        strength: Evidence,
        blast: Blast,
        ami: AmiClient? = null,
        alerter: ReliabilityAlerter? = null,
        shopId: Int? = null,
    ) {
        // Never let auditing break the thing it audits.
        runCatching {
            val callState = callState(ami)

            val line = "[Recovery] $action target=$target blast=${blast.name.lowercase()} " +
                "evidence=$evidence strength=${strength.name} callLive=${callState.name.lowercase()}"
            println(line)

            // A recovery action that fires during a live call is the signature of this entire bug
            // class. Alert on it, and on WEAK evidence we could not even verify was safe.
            val severity = when {
                callState == CallState.LIVE -> "error"
                callState == CallState.UNKNOWN && strength == Evidence.WEAK -> "warn"
                else -> null
            } ?: return@runCatching

            val why = if (callState == CallState.LIVE) {
                "FIRED DURING A LIVE CALL"
            } else {
                "fired on WEAK evidence and could not confirm the box was idle"
            }

            alerter?.record(
                severity = severity,
                // Distinct category so ReliabilityAlerter's per-category cooldown keeps this from
                // storming, and so it is greppable on its own.
                category = "recovery_during_call",
                shopId = shopId,
                message = "$action on $target $why (blast=${blast.name.lowercase()}, " +
                    "evidence=$evidence). This is very likely a bug in our own recovery logic — " +
                    "see docs/recovery-code-rules.md.",
                alertAdmin = callState == CallState.LIVE,
            )
        }.onFailure { println("[Recovery] audit failed for $action/$target: ${it.message}") }
    }

    /**
     * Is a call in progress right now?
     *
     * Deliberately does NOT use [AmiClient.trunkInCall] or a bare channel grep: both treat a failed
     * or unparseable AMI reply as "nothing is happening". We validate that we got a real listing
     * and otherwise answer [CallState.UNKNOWN].
     */
    fun callState(ami: AmiClient?): CallState {
        val client = ami ?: return CallState.UNKNOWN
        if (!client.connected) return CallState.UNKNOWN

        return runCatching { parseCallState(client.command("core show channels")) }
            .getOrDefault(CallState.UNKNOWN)
    }

    /**
     * Parse `core show channels` output into a call state. Split out from [callState] so the rule
     * that matters — an unusable reply is UNKNOWN, never IDLE — is unit-testable without an AMI.
     */
    internal fun parseCallState(lines: List<String>?): CallState {
        if (lines.isNullOrEmpty()) return CallState.UNKNOWN
        val match = lines.firstNotNullOfOrNull { ACTIVE_CALLS_RE.find(it) }
            ?: return CallState.UNKNOWN          // no "N active calls" line => this is not a listing
        val active = match.groupValues[1].toIntOrNull() ?: return CallState.UNKNOWN
        return if (active > 0) CallState.LIVE else CallState.IDLE
    }
}
