import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the one guarantee the system owner asked for:
 *
 * > "As long as recordings are not available for playback from shop managers or owners through
 * > the web backend we are good."
 *
 * These are source-level structural assertions rather than HTTP tests. That is deliberate: the
 * risk is not that today's handlers are wrong — they check `AdminSession` explicitly — but that a
 * later edit quietly opens a door, by adding a `/recordings` prefix to the public allowlist, by
 * putting a recording route under the owner portal, or by exposing a recording table through the
 * mobile API. A running-server test would not catch any of those as directly as reading the
 * routing table does.
 *
 * If these fail, read them as "someone moved a wall", not as a flaky test.
 */
class RecordingAccessIsolationTest {

    private val webAdmin = File("src/main/kotlin/WebAdmin.kt").readText()
    private val mobileApi = File("src/main/kotlin/MobileApi.kt").readText()
    private val shopBackend = File("src/main/kotlin/ShopBackend.kt").readText()

    /** The recording tables. Any route reading one of these is a playback surface. */
    private val recordingTables = listOf(
        "call_recording", "call_recording_rule", "call_recording_access",
    )

    /** The DB accessors that return recording data. */
    private val recordingAccessors = listOf(
        "listCallRecordings(", "getCallRecording(", "listCallRecordingRules(",
        "listCallRecordingAccess(", "addCallRecordingRule(", "deactivateCallRecordingRule(",
    )

    // ─── The mobile app must have no recording surface at all ────────────────

    @Test
    fun `the mobile API never touches a recording table or accessor`() {
        for (table in recordingTables) {
            assertFalse(mobileApi.contains(table),
                "MobileApi.kt must not reference '$table' — the manager app is JWT-authenticated " +
                    "and every manager holds a token")
        }
        for (accessor in recordingAccessors) {
            assertFalse(mobileApi.contains(accessor),
                "MobileApi.kt must not call '$accessor'")
        }
    }

    // ─── Recording routes must not be publicly reachable ─────────────────────

    @Test
    fun `no recording path is in the public allowlist`() {
        // The global intercept exempts these prefixes from requiring an AdminSession.
        val allowlist = webAdmin
            .substringAfter("val isPublic = ")
            .substringBefore("// Owner-login portal pages")

        assertFalse(allowlist.contains("/recordings"),
            "a /recordings entry in isPublic would expose playback to anyone who can reach the port")
        assertTrue(allowlist.contains("\"/login\""), "sanity: the allowlist was located correctly")
    }

    @Test
    fun `recording routes do not live under the owner portal prefix`() {
        // Paths starting with /owner are gated on OwnerSession INSTEAD of AdminSession, so a
        // recording route under that prefix would be reachable by a logged-in shop owner.
        val ownerPrefixed = Regex("""(get|post)\("(/owner[^"]*)"""")
            .findAll(webAdmin).map { it.groupValues[2] }.toList()

        for (path in ownerPrefixed) {
            assertFalse(path.contains("recording", ignoreCase = true),
                "$path is owner-gated, so it must not serve recordings")
        }
    }

    @Test
    fun `every recording route is registered under the recordings prefix`() {
        val recordingRoutes = Regex("""(get|post)\("(/[^"]*recording[^"]*)"""", RegexOption.IGNORE_CASE)
            .findAll(webAdmin).map { it.groupValues[2] }.toList()

        assertTrue(recordingRoutes.isNotEmpty(), "sanity: there should be recording routes by now")
        for (path in recordingRoutes) {
            assertTrue(path.startsWith("/recordings"),
                "$path serves recordings but sits outside /recordings, where the prefix reasoning " +
                    "in WebAdmin's recording section no longer covers it")
        }
    }

    // ─── Every handler checks for itself, not just via the global gate ───────

    @Test
    fun `every recording route calls requireAdminForRecordings`() {
        // Defence in depth: the global intercept is one edit away from changing, so each handler
        // re-checks. Count route declarations under /recordings and require one guard call each.
        val routeCount = Regex("""(get|post)\("/recordings[^"]*"""").findAll(webAdmin).count()
        // Count CALL SITES only ("call.requireAdminForRecordings()"), not the helper's own
        // declaration — including the declaration made this assertion satisfiable with one
        // handler unguarded, which is exactly the bug it is supposed to catch.
        val guardCount = webAdmin.split("call.requireAdminForRecordings()").size - 1

        assertTrue(routeCount > 0, "sanity: there should be /recordings routes")
        assertEquals(routeCount, guardCount,
            "found $routeCount /recordings routes but $guardCount guard calls — every handler " +
                "must check AdminSession itself, not rely on the global intercept")
    }

    @Test
    fun `the guard refuses an owner session explicitly and answers 404`() {
        val guard = webAdmin
            .substringAfter("suspend fun ApplicationCall.requireAdminForRecordings()")
            .substringBefore("get(\"/recordings\")")

        assertTrue(guard.contains("sessions.get<AdminSession>()"),
            "the guard must require an AdminSession")
        assertTrue(guard.contains("OwnerSession"),
            "the guard must consider and log an owner attempt")
        assertTrue(guard.contains("NotFound"),
            "refusals must be 404, not 403: whether a number is under covert recording is itself " +
                "the sensitive fact, and 403 confirms the page exists")
        assertFalse(guard.contains("Forbidden"), "403 would confirm the resource exists")
    }

    // ─── The audio stream specifically ───────────────────────────────────────

    @Test
    fun `the audio route confines the served file to the recordings directory`() {
        val audio = webAdmin
            .substringAfter("get(\"/recordings/audio\")")
            .substringBefore("// ── Flagged-for-deletion")

        assertTrue(audio.contains("requireAdminForRecordings()"), "admin check required")
        assertTrue(audio.contains("canonicalFile"),
            "the path must be canonicalised before the containment check, or ../ defeats it")
        assertTrue(audio.contains("CallRecordingPaths.directory"),
            "the served file must be confined to the configured recordings directory, so a " +
                "tampered path column cannot read arbitrary files")
        assertTrue(audio.contains("logCallRecordingAccess"),
            "every playback must be recorded in the chain of custody")
    }

    @Test
    fun `recordings are not served from a static route`() {
        // A staticFiles/static mapping over the recordings directory would bypass every check
        // above, since the intercept allowlist already exempts /static/.
        assertFalse(
            shopBackend.contains("CallRecordingPaths") &&
                Regex("""static\w*\s*\(""").containsMatchIn(shopBackend) &&
                shopBackend.contains("recordings"),
            "recordings must never be exposed through a static file route",
        )
        assertFalse(webAdmin.contains("staticFiles(\"/recordings\""),
            "no static mapping over the recordings path")
    }

    // ─── The internal endpoint is not a playback surface ─────────────────────

    @Test
    fun `the internal dialplan endpoint returns a path but never audio`() {
        val routes = File("src/main/kotlin/asterisk/InternalTelephonyRoutes.kt").readText()
        val decision = routes
            .substringAfter("post(\"/api/internal/telephony/call/record-decision\")")
            .substringBefore("// Digit 1 in the menu")

        assertTrue(decision.contains("respondText"), "it answers with text, not a file")
        assertFalse(decision.contains("respondFile"),
            "the shared-secret endpoint must never serve audio")
        assertFalse(decision.contains("listCallRecordings"),
            "it must not enumerate recordings")
    }

    // ─── The control on the customer page ────────────────────────────────────

    @Test
    fun `the customer page recording block is gated on an AdminSession`() {
        // The whole web backend already requires an AdminSession, but the block that switches
        // covert recording on and off re-checks at the point of use, so it cannot leak if the
        // global gate is ever changed.
        val editPage = webAdmin
            .substringAfter("""get("/customers/edit")""")
            .substringBefore("""get("/customers/comms")""")

        assertTrue(editPage.contains("Call recording"),
            "sanity: the recording block should be on the customer page")
        val block = editPage.substringAfter("// ── Call recording")
        assertTrue(
            editPage.substringBefore("// ── Call recording").length < editPage.length,
            "sanity: block marker found",
        )
        assertTrue(block.contains("sessions.get<AdminSession>() != null"),
            "the recording controls must be rendered only for an admin session")
        // The audio link must point at the single guarded streaming route, not at a file path.
        assertTrue(block.contains("/recordings/audio?id="),
            "playback must go through the guarded streaming route")
        assertFalse(Regex("""href\s*=\s*"[^"]*\.wav""").containsMatchIn(block),
            "the page must never link straight to a .wav on disk")
    }

    @Test
    fun `the toggle posts to a guarded route under the recordings prefix`() {
        assertTrue(webAdmin.contains("""post("/recordings/toggle")"""),
            "the customer-page checkbox must post to a /recordings route, so the single-prefix " +
                "audit rule still answers 'is anything outside /recordings serving recordings?'")
        assertFalse(webAdmin.contains("""post("/customers/recording")"""),
            "a recording route outside /recordings defeats the prefix invariant")
    }

    @Test
    fun `turning recording on requires a stated reason`() {
        val toggle = webAdmin
            .substringAfter("""post("/recordings/toggle")""")
            .substringBefore("""get("/recordings/audio")""")

        assertTrue(toggle.contains("reason.isBlank()"),
            "enabling covert recording with no stated purpose must be refused, not silently allowed")
        assertTrue(toggle.contains("requireAdminForRecordings()"))
    }

    @Test
    fun `sanity - the files under test were actually read`() {
        assertTrue(webAdmin.length > 10_000)
        assertTrue(mobileApi.length > 10_000)
        assertEquals(true, webAdmin.contains("AdminSession"))
    }
}
