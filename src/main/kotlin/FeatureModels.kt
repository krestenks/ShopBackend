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
)

/** A shop as the manager app shows and edits it. */
@Serializable
data class ShopDto(
    val id: Int,
    /** The shop's own name, set by hand. */
    val name: String,
    /** The name calls and messages show: the therapist's name when the shop has one therapist, else the shop name. */
    val displayName: String,
    val address: String?,
    val directions: String?,
    /** The shop's SIM number; null when the shop has no line. */
    val lineNumber: String?,
    val therapists: List<ShopTherapistDto>,
)

@Serializable
data class ShopTherapistDto(val id: Int, val name: String)

/** The address that the app sends to a customer; empty when the shop has none. Only managers can change it. */
@Serializable
data class ShopAddressDto(val canEdit: Boolean, val address: String)

/** Each field is changed only when present. A blank address or directions clears it. */
@Serializable
data class UpdateShopRequest(
    val name: String? = null,
    val address: String? = null,
    val directions: String? = null,
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
const val QUICK_MESSAGE_MAX_LENGTH = 1000
const val THERAPIST_NAME_MAX_LENGTH = 60
const val SHOP_NAME_MAX_LENGTH = 60
const val SHOP_ADDRESS_MAX_LENGTH = 200
const val SHOP_DIRECTIONS_MAX_LENGTH = 1000

/** The largest amount accepted in one cell of the daily accounts, in øre (1,000,000 kr). */
const val ACCOUNT_AMOUNT_MAX_ORE = 100_000_000L

/** One day of a therapist's accounts. Amounts are øre; total = cash - mobilepay - revolut - food. */
@Serializable
data class AccountDayDto(
    val day: String,
    val cash: Long = 0,
    val mobilepay: Long = 0,
    val revolut: Long = 0,
    val food: Long = 0,
    val total: Long = 0,
)

/** Every date of a month with the month totals (`totals.day` holds the month, yyyy-MM). */
@Serializable
data class AccountsMonthDto(val month: String, val days: List<AccountDayDto>, val totals: AccountDayDto)

/** The longest price list text, in UTF-16 characters (an emoji counts as 2, as in an SMS). */
const val PRICE_LIST_MAX_LENGTH = 1000

/** A therapist's saved price list text; empty when none is saved. */
@Serializable
data class TherapistPriceListDto(val therapistId: Int, val name: String, val priceList: String)

/** The price lists of a shop's therapists. `canEdit` is false for the in-shop role. */
@Serializable
data class ShopPriceListsDto(val canEdit: Boolean, val therapists: List<TherapistPriceListDto>)

@Serializable
data class SavePriceListRequest(val priceList: String)

/** The most job lines one therapist can have on one day. */
const val ACCOUNT_JOB_MAX_LINES = 20

/** One job line of a day. `line` is the position (1 first); the total is calculated by the server. */
@Serializable
data class AccountJobDto(
    val line: Int,
    val cash: Long = 0,
    val mobilepay: Long = 0,
    val revolut: Long = 0,
    val food: Long = 0,
    val total: Long = 0,
)

/** The stored job lines of one day (lines with no amounts are absent) and the day totals. */
@Serializable
data class AccountDayJobsDto(val day: String, val jobs: List<AccountJobDto>, val totals: AccountDayDto)

/** All job lines of the day. Lines left out are removed. The totals are ignored. */
@Serializable
data class SaveAccountDayRequest(val jobs: List<AccountJobDto>)
