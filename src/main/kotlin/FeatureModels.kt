import kotlinx.serialization.Serializable

// Wire models for the therapist page, the quick messages and the customer timeline.

/** A therapist (an employee row) with the shop it works in and that shop's line number. */
@Serializable
data class TherapistDto(
    val id: Int,
    val name: String,
    /** The therapist's own mobile number; null when none is entered. */
    val mobile: String?,
    val shopId: Int,
    val shopName: String,
    /** The shop's SIM number; null when the shop has no line. */
    val lineNumber: String?,
    /** "dedicated" when the shop has exactly one therapist, otherwise "shared". */
    val numberMode: String,
    /** True when the shop has "Use employee name" on, so renaming this therapist renames the shop. */
    val shopUsesEmployeeName: Boolean = false,
)

/** A shop as the manager app shows and edits it. */
@Serializable
data class ShopDto(
    val id: Int,
    val name: String,
    val address: String?,
    val directions: String?,
    /** The stored option "Use employee name". */
    val useEmployeeName: Boolean,
    /** True when the option is on and the shop has exactly one therapist, so the name follows. */
    val nameFollowActive: Boolean,
    /** The shop's SIM number; null when the shop has no line. */
    val lineNumber: String?,
    val therapists: List<ShopTherapistDto>,
)

@Serializable
data class ShopTherapistDto(val id: Int, val name: String)

/** Each field is changed only when present. A blank address or directions clears it. */
@Serializable
data class UpdateShopRequest(
    val name: String? = null,
    val address: String? = null,
    val directions: String? = null,
    val useEmployeeName: Boolean? = null,
)

@Serializable
data class UpdateTherapistRequest(
    val name: String? = null,
    val shopId: Int? = null,
)

@Serializable
data class UpdateTherapistResponse(
    val therapist: TherapistDto,
    val employeeRenamed: Boolean,
    val shopRenamed: Boolean,
)

@Serializable
data class QuickMessageDto(val id: Int, val text: String, val sortOrder: Int)

@Serializable
data class QuickMessageRequest(val text: String)

@Serializable
data class QuickMessageOrderRequest(val ids: List<Int>)

/** One call or SMS in a customer's history. */
@Serializable
data class TimelineEntry(
    /** "sms" or "call". */
    val type: String,
    val id: Int,
    val shopId: Int,
    val shopName: String?,
    val at: Long,
    /** "inbound" or "outbound". */
    val direction: String,
    /** SMS text; null for calls. */
    val body: String? = null,
    /** SMS delivery status; null for calls. */
    val status: String? = null,
    /** Call outcome; null for SMS. */
    val outcome: String? = null,
    /** Call length in seconds; null for SMS and for calls that have not ended. */
    val durationSeconds: Long? = null,
)

@Serializable
data class TimelineResponse(
    val entries: List<TimelineEntry>,
    /** Pass as `before` to get the next older page; null when there is nothing older. */
    val nextBefore: Long? = null,
)

/** Limits for the per-manager quick messages. */
const val QUICK_MESSAGE_MAX_COUNT = 20
const val QUICK_MESSAGE_MAX_LENGTH = 320
const val THERAPIST_NAME_MAX_LENGTH = 60
const val SHOP_NAME_MAX_LENGTH = 60
const val SHOP_ADDRESS_MAX_LENGTH = 200
const val SHOP_DIRECTIONS_MAX_LENGTH = 1000
