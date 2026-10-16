import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The app parses BlacklistEntry. Adding `deactivatedAt` must not change the JSON the app already
 * receives, or an installed build that does not ignore unknown keys would fail to parse its
 * blacklist screen — and pushing an app release to fix that is a separate, slower operation than
 * a backend deploy.
 */
class BlacklistWireFormatTest {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    @Test
    fun `an active entry serialises without the new field`() {
        val e = BlacklistEntry(
            id = 7, shopId = 1, phoneE164 = "+4527822182",
            reason = "nuisance", createdAt = 1760000000000L, active = true,
        )
        val s = json.encodeToString(BlacklistEntry.serializer(), e)

        assertFalse(s.contains("deactivatedAt"),
            "a null default must not be emitted, or the wire format changed under the app: $s")
        assertEquals(
            """{"id":7,"shopId":1,"phoneE164":"+4527822182","reason":"nuisance","createdAt":1760000000000,"active":true}""",
            s,
        )
    }
}
