package asterisk

import asterisk.RecoveryAudit.CallState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The property under test is the one that caused every incident in
 * docs/recovery-paths-audit-2026-09-13.md: a reply we could not use must be UNKNOWN, never IDLE.
 *
 * IDLE is a licence to destroy things. If an AMI error, a permissions change or a truncated reply
 * can masquerade as "nothing is happening", the canary will cheerfully report that no call was in
 * progress while a recovery action hangs one up — which is exactly how `AmiClient.command()`'s
 * `?: listOfNotNull(response.message)` turned a failed query into "no channels".
 */
class RecoveryAuditTest {

    private val realListingIdle = listOf(
        "Channel              Location             State   Application(Data)",
        "0 active channels",
        "0 active calls",
        "51 calls processed",
    )

    private val realListingBusy = listOf(
        "Channel              Location             State   Application(Data)",
        "Quectel/shop1-030000006b from-gsm-shop1:1 Up      Dial(PJSIP/mgr2)",
        "2 active channels",
        "1 active calls",
        "52 calls processed",
    )

    @Test
    fun `a real listing with no calls is IDLE`() {
        assertEquals(CallState.IDLE, RecoveryAudit.parseCallState(realListingIdle))
    }

    @Test
    fun `a real listing with a call is LIVE`() {
        assertEquals(CallState.LIVE, RecoveryAudit.parseCallState(realListingBusy))
    }

    @Test
    fun `an AMI error string is UNKNOWN, not IDLE`() {
        // This is the shape AmiClient.command() returns when the response is not a CommandResponse:
        // a non-empty list containing an error message. It contains no channels, so a naive
        // "no Quectel/ lines" check would conclude the box is idle. It must not.
        assertEquals(CallState.UNKNOWN, RecoveryAudit.parseCallState(listOf("Invalid/unknown command")))
        assertEquals(CallState.UNKNOWN, RecoveryAudit.parseCallState(listOf("Permission denied")))
    }

    @Test
    fun `an empty or absent reply is UNKNOWN, not IDLE`() {
        assertEquals(CallState.UNKNOWN, RecoveryAudit.parseCallState(emptyList()))
        assertEquals(CallState.UNKNOWN, RecoveryAudit.parseCallState(null))
    }

    @Test
    fun `a listing truncated before the summary line is UNKNOWN`() {
        // Channels are visible but the "N active calls" line never arrived. We cannot tell whether
        // more channels followed, so we must not claim to know.
        assertEquals(
            CallState.UNKNOWN,
            RecoveryAudit.parseCallState(
                listOf(
                    "Channel              Location             State   Application(Data)",
                    "Quectel/shop1-030000006b from-gsm-shop1:1 Up      Dial(PJSIP/mgr2)",
                )
            ),
        )
    }

    @Test
    fun `singular 'active call' is parsed`() {
        assertEquals(CallState.LIVE, RecoveryAudit.parseCallState(listOf("1 active call")))
    }

    @Test
    fun `a ringing channel with no bridged call is LIVE, not IDLE`() {
        // An inbound leg that is ringing but not yet answered is a channel with no call. Reporting
        // IDLE here would let a guard authorise hanging up on a phone that is ringing right now,
        // so either counter being non-zero must read as LIVE.
        assertEquals(
            CallState.LIVE,
            RecoveryAudit.parseCallState(
                listOf(
                    "Channel              Location             State   Application(Data)",
                    "PJSIP/mgr2-00000003  from-gsm-shop1:1     Ring    AppDial((Outgoing Line))",
                    "1 active channels",
                    "0 active calls",
                )
            ),
        )
    }
}
