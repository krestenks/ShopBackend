import JwtConfig.generateToken
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.html.*
import io.ktor.server.http.content.resources
import io.ktor.server.http.content.static
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.pipeline.PipelineContext
import kotlinx.html.*
import kotlinx.serialization.json.Json
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.*
import telephony.BookingConfirmationSms

// ─── Request/response models used by call-log + blacklist endpoints ───────────

@Serializable
data class AddBlacklistRequest(val phone: String, val reason: String? = null)

/** Body for PATCH /api/mobile/appointments/{id}/start — available to both manager and shop roles. */
@Serializable
data class StartAppointmentRequest(val duration: Int)

@Serializable
data class UpdateAppointmentStatusRequest(
    /** "Waiting" | "Ongoing" | "Done" */
    val status: String,
)

@Serializable
data class UpdateAppointmentBulkStatusRequest(
    val appointmentIds: List<Int>,
    /** "Waiting" | "Ongoing" | "Done" */
    val status: String,
)

@Serializable
data class UpdateAppointmentRequest(
    /** "yyyy-MM-dd HH:mm" */
    val appointmentTime: String,
    val duration: Int,
    val price: Double,
    val status: String,
    val actualDurationMinutes: Int? = null,
)

@Serializable
data class CallDetailResponse(val call: VoiceCallRecord, val events: List<VoiceCallEvent>)

@Serializable
data class CreateBookingRequest(
    val shopId: Int,
    val employeeId: Int,
    val customerId: Int? = null,
    val serviceIds: List<Int>,
    val appointmentTime: String,          // "yyyy-MM-dd HH:mm"
    val voiceCallId: Int? = null,         // optional: link booking to ongoing call
    val totalDuration: Int = 0,           // pre-computed by client; used as fallback
    val customerPhone: String? = null,    // caller's phone — auto-creates customer row if provided
    /** If set, overrides the calculated sum of service prices for this appointment. */
    val customPrice: Double? = null,
)

@Serializable
data class CreateBookingResponse(val appointmentId: Int, val voiceCallId: Int?)

@Serializable
data class CreateBookingMultiRequest(
    val shopId: Int,
    val appointmentTime: String,          // "yyyy-MM-dd HH:mm"
    val slots: List<CreateBookingMultiSlot>,
    val voiceCallId: Int? = null,
    val customerId: Int? = null,
    val customerPhone: String? = null,
    /**
     * Optional override for the booked calendar block length (in minutes).
     * If set, the backend will use max(overrideDurationMinutes, computedMaxServiceDuration).
     */
    val overrideDurationMinutes: Int? = null,
    /** Optional custom price per slot (same ordering as slots). */
    val customPrices: List<Double?>? = null,
)

@Serializable
data class CreateBookingMultiSlot(
    val employeeId: Int,
    val serviceIds: List<Int>,
)

@Serializable
data class CreateBookingMultiResponse(
    val appointmentIds: List<Int>,
    val voiceCallId: Int? = null,
)

@Serializable
data class UpdateCustomerNameRequest(val name: String)

@Serializable
data class UpdateCustomerRequest(
    val name: String,
    val status: String,
    val payment: Int,
    val language: Int,
)

@Serializable
data class CustomerDetailResponse(val customer: Customer, val appointments: List<AppointmentWithServices>)

@Serializable
data class LoginRequest(val username: String, val password: String)
@Serializable
data class LoginResponse(
    val token: String,
    val managerId: Int,   // kept for backwards compat
    val role: String,     // "manager" | "shop"
    val userId: Int,      // the DB id that matched (managerId or shopId depending on role)
)
@Serializable
data class MeResponse(
    val role: String,
    val userId: Int,
    val managerId: Int?,
    val shopId: Int?,
    val username: String?,
    /** Manager opted-in to open the web backend (admin UI) inside the app. Default false. */
    val webAdminAccess: Boolean = false,
)

/** SIP registration details for the manager app's softphone (Asterisk mode only). */
@Serializable
data class SipCredentialsResponse(
    val shopId: Int,
    val username: String,
    val password: String,
    val host: String,
    val port: Int,
    val transport: String,
    val shopPhoneNumber: String? = null,
    /** SIM carrier (e.g. "lebara") so the app can offer carrier-specific actions like top-up. */
    val carrier: String? = null,
)

/** Server-side SIP reachability of the caller's own endpoint (Asterisk's qualify verdict). */
@Serializable
data class SipHealthResponse(val reachable: Boolean, val aor: String)

/** Lebara prepaid top-up request (self-service from the app). */
@Serializable
data class TopupRequest(val shopId: Int, val code1: String, val code2: String)
@Serializable
data class TopupResponse(val success: Boolean, val message: String)
/** Latest carrier balance (raw reply text + when it arrived), for the app. */
@Serializable
data class BalanceResponse(val balance: String? = null, val balanceAt: Long? = null)

/** One callable intercom colleague (internal SIP extension). */
@Serializable
data class SipContact(val name: String, val exten: String, val type: String)

@Serializable
data class SipContactsResponse(val contacts: List<SipContact>)

data class LoginInfo(val role: String, val managerId: Int?, val shopId: Int?, val ownerId: Int = 0)

// ─── Availability screen models ───────────────────────────────────────────────

@Serializable
data class AvailabilityEmployee(
    val id: Int,
    val name: String,
    val available: Boolean,
)

@Serializable
data class AvailabilityShop(
    val id: Int,
    val name: String,
    /** Effective open/closed status after override + schedule */
    val status: String,
    /** Current mode: auto/open/closed */
    val mode: String,
    val scheduledOpen: Boolean,
    val employees: List<AvailabilityEmployee>,
)

@Serializable
data class AvailabilityResponse(val shops: List<AvailabilityShop>)

@Serializable
data class SetEmployeeAvailabilityRequest(val available: Boolean)

// ─── Manager on/off-duty (tenant-wide) — controls call ringing + notifications ──

@Serializable
data class DutyStatusResponse(val onDuty: Boolean)

@Serializable
data class SetDutyRequest(val onDuty: Boolean)

/** One manager in the tenant's duty roster (shown to colleagues). */
@Serializable
data class DutyMember(
    val managerId: Int,
    val name: String,
    val onDuty: Boolean,
    val isSelf: Boolean = false,
)

@Serializable
data class DutyTeamResponse(val members: List<DutyMember>)

/** Push (FCM) device-token registration from the app. */
@Serializable
data class RegisterDeviceTokenRequest(
    val token: String,
    val platform: String? = null,
    val deviceName: String? = null,
)

// ─── Phone status (open/closed) override ──────────────────────────────────────

@Serializable
data class PhoneStatusResponse(
    /** Resolved effective status after applying override + schedule: "open" | "closed" */
    val status: String,
    /** Requested mode: "auto" | "open" | "closed" */
    val mode: String,
    /** What the opening-hours schedule says right now (ignores override). */
    val scheduledOpen: Boolean,
)

@Serializable
data class SetPhoneStatusRequest(
    /** "auto" | "open" | "closed" */
    val mode: String,
)

suspend fun PipelineContext<Unit, ApplicationCall>.authenticateManager(): LoginInfo? {
    if (call.request.headers["Authorization"] == null) {
        println("[Auth] FAIL — missing Authorization header (${call.request.uri})")
        call.respond(HttpStatusCode.Unauthorized, "Missing Authorization header")
        return null
    }

    val principal = call.principal<JWTPrincipal>()
    if (principal == null) {
        println("[Auth] FAIL — invalid or expired token (${call.request.uri})")
        call.respond(HttpStatusCode.Unauthorized, "Invalid or expired token")
        return null
    }

    val userId = principal.payload.getClaim("userId")?.asInt()
    val role   = principal.payload.getClaim("role")?.asString()

    // Step 12: extract ownerId from JWT so owner-scoped calls can be enforced.
    val ownerId = principal.payload.getClaim("ownerId")?.asInt() ?: 0
    if (role == "manager" && userId != null) return LoginInfo(role, userId, null, ownerId)
    if (role == "shop"    && userId != null) return LoginInfo(role, null, userId, ownerId)

    println("[Auth] FAIL — unrecognised role='$role' userId=$userId (${call.request.uri})")
    call.respond(HttpStatusCode.Unauthorized, "Invalid role")
    return null
}



class MobileApi(
    private val db: DataBase,
    private val telephonyService: telephony.TelephonyService,
    private val asteriskAdmin: asterisk.AsteriskAdmin? = null,
) {
    fun setupRoutes(routing: Route) {
        routing {

            // SMS sender (Asterisk/GSM)
            val smsService = telephonyService

            post("/api/mobile/login") {
                val loginRequest = call.receive<LoginRequest>()
                val username = loginRequest.username.trim()
                val password = loginRequest.password

                println("[MobileApi/login] Attempt username='$username'")

                // ── 1. Dedicated app_account table (preferred) ───────────────
                val appAccount = db.authenticateAppAccount(username, password)
                if (appAccount != null) {
                    val (refType, refId) = appAccount
                    val role = if (refType == "shop") "shop" else "manager"
                    val tokenVersion = db.getAppAccountTokenVersion(role, refId)
                    val ownerId = if (role == "manager") db.getOwnerIdForManager(refId) ?: 0
                                  else db.getOwnerIdForShop(refId) ?: 0
                    val token = generateToken(refId, role = role, tokenVersion = tokenVersion, ownerId = ownerId)
                    println("[MobileApi/login] OK via app_account: $role id=$refId version=$tokenVersion ownerId=$ownerId")
                    call.respond(LoginResponse(token = token, managerId = refId, role = role, userId = refId))
                    return@post
                }

                // ── 2. Fallback: manager table (backwards compat) ────────────
                val manager = db.authenticateManager(username, password)
                if (manager != null) {
                    val ownerId = db.getOwnerIdForManager(manager.id) ?: 0
                    val token = generateToken(manager.id, role = "manager", ownerId = ownerId)
                    println("[MobileApi/login] OK via managers table: id=${manager.id} name='${manager.name}' ownerId=$ownerId")
                    call.respond(LoginResponse(token = token, managerId = manager.id, role = "manager", userId = manager.id))
                    return@post
                }

                // ── 3. Fallback: shop table (legacy, rarely used) ────────────
                val shop = db.authenticateShop(username, password)
                if (shop != null) {
                    val ownerId = db.getOwnerIdForShop(shop.id) ?: 0
                    val token = generateToken(shop.id, role = "shop", ownerId = ownerId)
                    println("[MobileApi/login] OK via shops table: id=${shop.id} name='${shop.name}' ownerId=$ownerId")
                    call.respond(LoginResponse(token = token, managerId = shop.id, role = "shop", userId = shop.id))
                    return@post
                }

                println("[MobileApi/login] FAIL: no match for username='$username'")
                call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
            }

            authenticate("jwt") {

                // ─────────────────────────────────────────────────────────────
                // SIP credentials (self-hosted Asterisk telephony)
                // ─────────────────────────────────────────────────────────────

                /**
                 * SIP registration details for the manager app's softphone.
                 * 404 when the shop has no
                 * provisioned GSM telephony yet — the app treats that as
                 * "softphone disabled for this shop".
                 *
                 * GET /api/mobile/telephony/sip-credentials?shopId=N
                 */
                get("/api/mobile/telephony/sip-credentials") {
                    val loginInfo = authenticateManager() ?: return@get
                    val shopId = call.request.queryParameters["shopId"]?.toIntOrNull()

                    // No shopId + manager role → the manager's single pool identity (mgr{id}).
                    // This is the identity the duty-aware routing forks the call to.
                    if (shopId == null) {
                        if (loginInfo.role != "manager" || loginInfo.managerId == null) {
                            return@get call.respond(HttpStatusCode.BadRequest, "Missing shopId")
                        }
                        val admin = asteriskAdmin
                            ?: return@get call.respond(HttpStatusCode.NotFound, "Self-hosted telephony not enabled")
                        val pw = db.getManagerSipPassword(loginInfo.managerId)
                        if (pw.isNullOrBlank()) {
                            return@get call.respond(HttpStatusCode.NotFound, "Manager not provisioned for telephony")
                        }
                        if (admin.config.sipHost.isBlank()) {
                            return@get call.respond(HttpStatusCode.ServiceUnavailable, "ASTERISK_SIP_HOST not configured on server")
                        }
                        return@get call.respond(SipCredentialsResponse(
                            shopId = 0,
                            username = admin.config.managerEndpointId(loginInfo.managerId),
                            password = pw,
                            host = admin.config.sipHost,
                            port = admin.config.sipPort,
                            transport = "udp",
                            shopPhoneNumber = null,
                        ))
                    }

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@get
                    }
                    val admin = asteriskAdmin
                        ?: return@get call.respond(HttpStatusCode.NotFound, "Self-hosted telephony not enabled")
                    val tele = db.getShopTelephonyConfig(shopId)
                    // Manager app registers shop{id}-manager; the in-shop device shop{id}-phone.
                    val (username, password) = if (loginInfo.role == "shop") {
                        admin.config.phoneEndpointId(shopId) to tele.sipPhonePassword
                    } else {
                        admin.config.endpointId(shopId) to tele.sipPassword
                    }
                    if (password.isNullOrBlank()) {
                        call.respond(HttpStatusCode.NotFound, "Shop not provisioned for telephony")
                        return@get
                    }
                    if (admin.config.sipHost.isBlank()) {
                        call.respond(HttpStatusCode.ServiceUnavailable, "ASTERISK_SIP_HOST not configured on server")
                        return@get
                    }
                    call.respond(SipCredentialsResponse(
                        shopId = shopId,
                        username = username,
                        password = password,
                        host = admin.config.sipHost,
                        port = admin.config.sipPort,
                        transport = "udp",
                        shopPhoneNumber = tele.phoneNumber,
                        carrier = tele.carrier,
                    ))
                }

                /**
                 * Server-side SIP reachability of the caller's own endpoint — the app polls this on
                 * a slow loop and re-registers when it flips to unreachable. Unlike the app's own
                 * registration state, this is Asterisk's qualify verdict (the authoritative
                 * "Unavailable"), delivered over HTTP so it's observable even when SIP itself is
                 * wedged. Manager → mgr{id} (the pooled inbound identity); shop → shop{id}-phone.
                 *
                 * GET /api/mobile/telephony/sip-health
                 */
                get("/api/mobile/telephony/sip-health") {
                    val loginInfo = authenticateManager() ?: return@get
                    val admin = asteriskAdmin
                        ?: return@get call.respond(HttpStatusCode.NotFound, "Self-hosted telephony not enabled")
                    val aor = when {
                        loginInfo.role == "manager" && loginInfo.managerId != null ->
                            admin.config.managerEndpointId(loginInfo.managerId)
                        loginInfo.role == "shop" && loginInfo.shopId != null ->
                            admin.config.phoneEndpointId(loginInfo.shopId)
                        else -> return@get call.respond(HttpStatusCode.BadRequest, "No SIP identity for this account")
                    }
                    val reachable = admin.amiClient.pjsipReachableAors().contains(aor)
                    call.respond(SipHealthResponse(reachable = reachable, aor = aor))
                }

                /**
                 * Lebara prepaid top-up (self-service): sends "Topup <8-digit> <6-digit>" to 5010
                 * out the shop's own SIM. Only for shops whose carrier is Lebara.
                 *
                 * POST /api/mobile/telephony/topup  { shopId, code1, code2 }
                 */
                post("/api/mobile/telephony/topup") {
                    val loginInfo = authenticateManager() ?: return@post
                    val req = runCatching { call.receive<TopupRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, TopupResponse(false, "Invalid request"))
                    if (!isAuthorizedForShop(loginInfo, req.shopId, db)) {
                        return@post call.respond(HttpStatusCode.Forbidden, TopupResponse(false, "Not authorized for this shop"))
                    }
                    val tele = db.getShopTelephonyConfig(req.shopId)
                    if (tele.carrier != telephony.LebaraTopup.CARRIER) {
                        return@post call.respond(HttpStatusCode.BadRequest, TopupResponse(false, "This SIM is not a Lebara line"))
                    }
                    val result = telephony.LebaraTopup.send(telephonyService, req.shopId, req.code1, req.code2)
                    if (result.success) call.respond(TopupResponse(true, "Top-up sent to 5010"))
                    else call.respond(HttpStatusCode.fromValue(result.status), TopupResponse(false, result.errorMessage ?: result.body))
                }

                /** GET /api/mobile/telephony/balance?shopId=N — latest stored carrier balance. */
                get("/api/mobile/telephony/balance") {
                    val loginInfo = authenticateManager() ?: return@get
                    val shopId = call.request.queryParameters["shopId"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing shopId")
                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        return@get call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                    }
                    val tele = db.getShopTelephonyConfig(shopId)
                    call.respond(BalanceResponse(tele.balance, tele.balanceAt))
                }

                /** POST /api/mobile/telephony/balance/refresh?shopId=N — text "balance" to 5010; reply arrives async. */
                post("/api/mobile/telephony/balance/refresh") {
                    val loginInfo = authenticateManager() ?: return@post
                    val shopId = call.request.queryParameters["shopId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, TopupResponse(false, "Missing shopId"))
                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        return@post call.respond(HttpStatusCode.Forbidden, TopupResponse(false, "Not authorized for this shop"))
                    }
                    if (db.getShopTelephonyConfig(shopId).carrier != telephony.LebaraTopup.CARRIER) {
                        return@post call.respond(HttpStatusCode.BadRequest, TopupResponse(false, "This SIM is not a Lebara line"))
                    }
                    val r = telephony.LebaraTopup.requestBalance(telephonyService, shopId)
                    if (r.success) call.respond(TopupResponse(true, "Balance requested — reply arrives shortly"))
                    else call.respond(HttpStatusCode.fromValue(r.status), TopupResponse(false, r.errorMessage ?: r.body))
                }

                /**
                 * Callable intercom colleagues for the caller.
                 * Shop role: its manager + sibling shops. Manager role: all their shops.
                 *
                 * GET /api/mobile/telephony/contacts
                 */
                get("/api/mobile/telephony/contacts") {
                    val loginInfo = authenticateManager() ?: return@get
                    if (asteriskAdmin == null) {
                        call.respond(SipContactsResponse(emptyList()))
                        return@get
                    }
                    val contacts = mutableListOf<SipContact>()
                    if (loginInfo.role == "shop") {
                        val shopId = loginInfo.shopId ?: return@get call.respond(HttpStatusCode.Forbidden)
                        val managerId = db.getShopById(shopId)?.managerId
                        if (managerId != null && managerId > 0) {
                            val managerName = db.getManagerById(managerId)?.name ?: "Manager"
                            contacts += SipContact(name = managerName, exten = "manager", type = "manager")
                            db.getShopsForManager(managerId)
                                .filter { it.id != shopId }
                                .forEach { contacts += SipContact(name = it.name, exten = "shopphone${it.id}", type = "shop") }
                        }
                    } else {
                        val managerId = loginInfo.managerId ?: return@get call.respond(HttpStatusCode.Forbidden)
                        val covered = db.getShopsForManager(managerId)
                        covered.forEach { contacts += SipContact(name = it.name, exten = "shopphone${it.id}", type = "shop") }
                        // Colleague managers: co-managers of any covered shop (primary ∪ pool),
                        // callable via the mgr{id} intercom exten in the caller's from-mgr context.
                        covered.flatMap { db.getManagerIdsForShop(it.id) }
                            .filter { it != managerId }
                            .distinct()
                            .mapNotNull { pid -> db.getManagerById(pid)?.let { pid to it.name } }
                            .forEach { (pid, name) -> contacts += SipContact(name = name, exten = "mgr$pid", type = "manager") }
                    }
                    call.respond(SipContactsResponse(contacts))
                }

                // ─────────────────────────────────────────────────────────────
                // Customer detail endpoints
                // ─────────────────────────────────────────────────────────────

                /**
                 * GET customer details + appointment history for a shop.
                 *
                 * GET /api/mobile/manager/shops/{shopId}/customers/{customerId}
                 */
                get("/api/mobile/manager/shops/{shopId}/customers/{customerId}") {
                    val loginInfo = authenticateManager() ?: return@get
                    val shopId = call.parameters["shopId"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing shopId")
                    val customerId = call.parameters["customerId"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing customerId")

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@get
                    }

                    val customer = db.getCustomerById(customerId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, "Customer not found")

                    val appts = db.getAppointmentsForCustomer(customerId, shopId = shopId)
                    call.respond(CustomerDetailResponse(customer = customer, appointments = appts))
                }

                /**
                 * PATCH customer name
                 *
                 * PATCH /api/mobile/manager/shops/{shopId}/customers/{customerId}/name
                 * Body: {"name":"..."}
                 */
                patch("/api/mobile/manager/shops/{shopId}/customers/{customerId}/name") {
                    val loginInfo = authenticateManager() ?: return@patch
                    val shopId = call.parameters["shopId"]?.toIntOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing shopId")
                    val customerId = call.parameters["customerId"]?.toIntOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing customerId")

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@patch
                    }

                    val body = runCatching { call.receive<UpdateCustomerNameRequest>() }.getOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "Invalid request body")

                    val name = body.name.trim()
                    if (name.isBlank()) {
                        return@patch call.respond(HttpStatusCode.BadRequest, "name is required")
                    }

                    val existing = db.getCustomerById(customerId)
                        ?: return@patch call.respond(HttpStatusCode.NotFound, "Customer not found")

                    // Basic safety: ensure this customer actually has appointments for this shop.
                    // (We don't have an explicit customer->shop relation in DB).
                    val hasAny = db.getAppointmentsForCustomer(customerId, shopId = shopId).isNotEmpty()
                    if (!hasAny) {
                        // Keep behavior strict so we don't accidentally edit a customer from another shop.
                        return@patch call.respond(HttpStatusCode.Forbidden, "Customer not linked to this shop")
                    }

                    db.updateCustomerName(existing.id, name)
                    call.respond(HttpStatusCode.NoContent)
                }

                /**
                 * PUT /api/mobile/manager/shops/{shopId}/customers/{customerId}
                 * Update all editable fields (name, status, payment, language) except phone/id.
                 */
                put("/api/mobile/manager/shops/{shopId}/customers/{customerId}") {
                    val loginInfo = authenticateManager() ?: return@put
                    val shopId = call.parameters["shopId"]?.toIntOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing shopId")
                    val customerId = call.parameters["customerId"]?.toIntOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing customerId")

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@put
                    }

                    val body = runCatching { call.receive<UpdateCustomerRequest>() }.getOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid request body")

                    db.getCustomerById(customerId)
                        ?: return@put call.respond(HttpStatusCode.NotFound, "Customer not found")

                    db.updateCustomerEditable(customerId, body.name, body.status, body.payment, body.language)
                    call.respond(HttpStatusCode.NoContent)
                }

                // ─────────────────────────────────────────────────────────────
                // Phone open/closed status (schedule + manual override)
                // ─────────────────────────────────────────────────────────────

                fun normalizeMode(input: String?): String {
                    return when (input?.trim()?.lowercase()) {
                        "open" -> "open"
                        "closed" -> "closed"
                        "auto", null, "" -> "auto"
                        else -> "auto"
                    }
                }

                fun buildPhoneStatus(shopId: Int): PhoneStatusResponse {
                    val voice = db.getShopVoiceConfig(shopId)
                    val mode = normalizeMode(voice.phoneOverride)
                    val scheduledOpen = db.isShopOpenByScheduleNow(shopId)
                    val isOpen = when (mode) {
                        "open" -> true
                        "closed" -> false
                        else -> scheduledOpen
                    }
                    val status = if (isOpen) "open" else "closed"
                    return PhoneStatusResponse(status = status, mode = mode, scheduledOpen = scheduledOpen)
                }

                /** Get current phone status for a shop */
                get("/api/mobile/manager/shops/{shopId}/phone-status") {
                    val loginInfo = authenticateManager() ?: return@get
                    val shopId = call.parameters["shopId"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing shopId")

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@get
                    }

                    call.respond(buildPhoneStatus(shopId))
                }

                /** Set phone mode for a shop: auto/open/closed */
                put("/api/mobile/manager/shops/{shopId}/phone-status") {
                    val loginInfo = authenticateManager() ?: return@put
                    val shopId = call.parameters["shopId"]?.toIntOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing shopId")

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@put
                    }

                    val body = runCatching { call.receive<SetPhoneStatusRequest>() }.getOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid request body")

                    val mode = normalizeMode(body.mode)
                    val dbValue: String? = when (mode) {
                        "auto" -> null
                        else -> mode
                    }
                    db.upsertShopPhoneOverride(shopId, dbValue)

                    call.respond(buildPhoneStatus(shopId))
                }

                // ─────────────────────────────────────────────────────────────
                // Availability screen (shops + employees) — manager-controlled
                // ─────────────────────────────────────────────────────────────

                /**
                 * GET /api/mobile/manager/availability
                 * Returns all manager shops with effective shop open/closed status and employee availability.
                 */
                get("/api/mobile/manager/availability") {
                    val loginInfo = authenticateManager() ?: return@get
                    if (loginInfo.role != "manager" || loginInfo.managerId == null) {
                        return@get call.respond(HttpStatusCode.Forbidden, "Managers only")
                    }

                    val shops = db.getShopsForManager(loginInfo.managerId)
                    val out = shops.map { shop ->
                        val phone = buildPhoneStatus(shop.id)
                        val employees = db.getEmployeesForShop(shop.id)
                        val availability = db.listEmployeeAvailabilityForShop(shop.id)
                            .associateBy { it.employeeId }

                        AvailabilityShop(
                            id = shop.id,
                            name = shop.name,
                            status = phone.status,
                            mode = phone.mode,
                            scheduledOpen = phone.scheduledOpen,
                            employees = employees.map { emp ->
                                val avail = availability[emp.id]?.available ?: true
                                AvailabilityEmployee(id = emp.id, name = emp.name, available = avail)
                            },
                        )
                    }

                    call.respond(AvailabilityResponse(shops = out))
                }

                // ─────────────────────────────────────────────────────────────
                // On/off duty (tenant-wide). Off-duty managers are excluded from
                // call ringing (Phase 3) and should mute local notifications.
                // ─────────────────────────────────────────────────────────────

                /** GET /api/mobile/duty → {"onDuty": true|false} */
                get("/api/mobile/duty") {
                    val loginInfo = authenticateManager() ?: return@get
                    if (loginInfo.role != "manager" || loginInfo.managerId == null) {
                        return@get call.respond(HttpStatusCode.Forbidden, "Managers only")
                    }
                    call.respond(DutyStatusResponse(db.isManagerOnDuty(loginInfo.managerId)))
                }

                /** POST /api/mobile/duty  body {"onDuty": true|false} → the new state */
                post("/api/mobile/duty") {
                    val loginInfo = authenticateManager() ?: return@post
                    if (loginInfo.role != "manager" || loginInfo.managerId == null) {
                        return@post call.respond(HttpStatusCode.Forbidden, "Managers only")
                    }
                    val body = runCatching { call.receive<SetDutyRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid request body")
                    db.setManagerDuty(loginInfo.managerId, body.onDuty)
                    println("[Duty] manager=${loginInfo.managerId} onDuty=${body.onDuty}")
                    call.respond(DutyStatusResponse(body.onDuty))
                }

                /**
                 * GET /api/mobile/duty/team → the tenant's managers and who is on duty,
                 * so a manager can see which colleagues are available. Sorted on-duty first.
                 */
                get("/api/mobile/duty/team") {
                    val loginInfo = authenticateManager() ?: return@get
                    if (loginInfo.role != "manager" || loginInfo.managerId == null) {
                        return@get call.respond(HttpStatusCode.Forbidden, "Managers only")
                    }
                    val ownerId = loginInfo.ownerId.takeIf { it != 0 } ?: db.getOwnerIdForManager(loginInfo.managerId)
                    val managers = if (ownerId != null) db.getManagersByOwner(ownerId) else db.getAllManagers()
                    val members = managers
                        .map { m -> DutyMember(m.id, m.name, db.isManagerOnDuty(m.id), isSelf = m.id == loginInfo.managerId) }
                        .sortedWith(compareByDescending<DutyMember> { it.onDuty }.thenBy { it.name.lowercase() })
                    call.respond(DutyTeamResponse(members))
                }

                // ─────────────────────────────────────────────────────────────
                // Push (FCM) device-token registration. Used to wake on-duty
                // managers on an incoming call (Phase 5; sender not yet live).
                // ─────────────────────────────────────────────────────────────

                /** POST /api/mobile/device-token  body {token, platform?, deviceName?} */
                post("/api/mobile/device-token") {
                    val loginInfo = authenticateManager() ?: return@post
                    val body = runCatching { call.receive<RegisterDeviceTokenRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid request body")
                    if (body.token.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, "token required")
                    }
                    val refId = (if (loginInfo.role == "shop") loginInfo.shopId else loginInfo.managerId)
                        ?: return@post call.respond(HttpStatusCode.Forbidden)
                    db.upsertDeviceToken(loginInfo.role, refId, body.token, body.platform, body.deviceName)
                    call.respond(HttpStatusCode.NoContent)
                }

                /** DELETE /api/mobile/device-token?token=... — deregister on logout. */
                delete("/api/mobile/device-token") {
                    authenticateManager() ?: return@delete
                    val token = call.request.queryParameters["token"]?.takeIf { it.isNotBlank() }
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "token required")
                    db.deleteDeviceToken(token)
                    call.respond(HttpStatusCode.NoContent)
                }

                /**
                 * PATCH /api/mobile/manager/shops/{shopId}/employees/{employeeId}/availability
                 * Body: {"available":true/false}
                 */
                patch("/api/mobile/manager/shops/{shopId}/employees/{employeeId}/availability") {
                    val loginInfo = authenticateManager() ?: return@patch
                    if (loginInfo.role != "manager" || loginInfo.managerId == null) {
                        return@patch call.respond(HttpStatusCode.Forbidden, "Managers only")
                    }

                    val shopId = call.parameters["shopId"]?.toIntOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing shopId")
                    val employeeId = call.parameters["employeeId"]?.toIntOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing employeeId")

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        return@patch call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                    }

                    val body = runCatching { call.receive<SetEmployeeAvailabilityRequest>() }.getOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "Invalid request body")

                    val ok = db.setEmployeeAvailable(shopId = shopId, employeeId = employeeId, available = body.available)
                    if (!ok) {
                        return@patch call.respond(HttpStatusCode.NotFound, "Employee not assigned to shop")
                    }

                    call.respond(HttpStatusCode.NoContent)
                }

                /**
                 * PATCH /api/mobile/manager/appointments/{appointmentId}/status
                 * Body: { "status": "Waiting" | "Ongoing" | "Done" }
                 */
                patch("/api/mobile/manager/appointments/{appointmentId}/status") {
                    val loginInfo = authenticateManager() ?: return@patch
                    val appointmentId = call.parameters["appointmentId"]?.toIntOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing appointmentId")

                    val appt = db.getAppointmentById(appointmentId)
                        ?: return@patch call.respond(HttpStatusCode.NotFound, "Appointment not found")

                    if (!isAuthorizedForShop(loginInfo, appt.shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@patch
                    }

                    val body = runCatching { call.receive<UpdateAppointmentStatusRequest>() }.getOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "Invalid request body")

                    val allowedStatuses = setOf("Waiting", "Ongoing", "Done")
                    if (body.status !in allowedStatuses) {
                        return@patch call.respond(HttpStatusCode.BadRequest, "status must be one of: Waiting, Ongoing, Done")
                    }

                    db.updateAppointmentStatus(appointmentId, body.status)
                    val updated = (db.getAppointmentWithServicesById(appointmentId)
                        ?: return@patch call.respond(HttpStatusCode.InternalServerError, "Failed to read updated appointment"))
                        .enrichWithStatusActions()
                    call.respond(updated)
                }

                /**
                 * PATCH /api/mobile/manager/appointments/status-bulk
                 * Body: { "appointmentIds": [1,2,...], "status": "..." }
                 * Updates all listed appointments to the same status.
                 */
                /**
                 * PATCH /api/mobile/appointments/{appointmentId}/start
                 *
                 * Sets the appointment's start time to now and updates the duration.
                 * Available to BOTH manager and shop roles (unlike the full PUT edit endpoint).
                 * Called by the Start button so the calendar block shifts to actual arrival time.
                 *
                 * Body: { "duration": 60 }
                 */
                patch("/api/mobile/appointments/{appointmentId}/start") {
                    val loginInfo = authenticateManager() ?: return@patch
                    val appointmentId = call.parameters["appointmentId"]?.toIntOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing appointmentId")

                    val appt = db.getAppointmentById(appointmentId)
                        ?: return@patch call.respond(HttpStatusCode.NotFound, "Appointment not found")

                    if (!isAuthorizedForShop(loginInfo, appt.shopId, db)) {
                        return@patch call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                    }

                    val body = runCatching { call.receive<StartAppointmentRequest>() }.getOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "Invalid request body")

                    db.updateAppointmentStartTime(
                        appointmentId  = appointmentId,
                        dateTimeMillis = System.currentTimeMillis(),
                        duration       = body.duration.coerceAtLeast(1),
                    )

                    val updated = (db.getAppointmentWithServicesById(appointmentId)
                        ?: return@patch call.respond(HttpStatusCode.InternalServerError, "Failed to read updated appointment"))
                        .enrichWithStatusActions()
                    call.respond(updated)
                }

                patch("/api/mobile/manager/appointments/status-bulk") {
                    val loginInfo = authenticateManager() ?: return@patch
                    val body = runCatching { call.receive<UpdateAppointmentBulkStatusRequest>() }.getOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "Invalid request body")

                    val allowedStatuses = setOf("Waiting", "Ongoing", "Done")
                    if (body.status !in allowedStatuses) {
                        return@patch call.respond(HttpStatusCode.BadRequest, "status must be one of: Waiting, Ongoing, Done")
                    }
                    if (body.appointmentIds.isEmpty()) {
                        return@patch call.respond(HttpStatusCode.BadRequest, "appointmentIds is required")
                    }

                    for (appointmentId in body.appointmentIds) {
                        val appt = db.getAppointmentById(appointmentId) ?: continue
                        if (!isAuthorizedForShop(loginInfo, appt.shopId, db)) continue
                        db.updateAppointmentStatus(appointmentId, body.status)
                    }
                    call.respond(HttpStatusCode.NoContent)
                }

                /**
                 * PUT /api/mobile/manager/appointments/{appointmentId}
                 * Edit date/time, duration, price, status, actualDurationMinutes.
                 * Body: UpdateAppointmentRequest
                 */
                put("/api/mobile/manager/appointments/{appointmentId}") {
                    val loginInfo = authenticateManager() ?: return@put
                    val appointmentId = call.parameters["appointmentId"]?.toIntOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing appointmentId")

                    val appt = db.getAppointmentById(appointmentId)
                        ?: return@put call.respond(HttpStatusCode.NotFound, "Appointment not found")

                    if (!isAuthorizedForShop(loginInfo, appt.shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@put
                    }

                    val body = runCatching { call.receive<UpdateAppointmentRequest>() }.getOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid request body")

                    val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    val localDateTime = runCatching {
                        java.time.LocalDateTime.parse(body.appointmentTime, formatter)
                    }.getOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid appointmentTime format (expected yyyy-MM-dd HH:mm)")

                    val zoneId = java.time.ZoneId.of("Europe/Copenhagen")
                    val dateTimeMillis = localDateTime.atZone(zoneId).toInstant().toEpochMilli()

                    db.updateAppointmentFull(
                        appointmentId = appointmentId,
                        dateTime = dateTimeMillis,
                        duration = body.duration,
                        price = body.price,
                        status = body.status,
                        actualDurationMinutes = body.actualDurationMinutes,
                    )
                    val updated = (db.getAppointmentWithServicesById(appointmentId)
                        ?: return@put call.respond(HttpStatusCode.InternalServerError, "Failed to read updated appointment"))
                        .enrichWithStatusActions()
                    call.respond(updated)
                }

                delete("/api/mobile/manager/appointments/{appointmentId}") {
                    val loginInfo = authenticateManager() ?: return@delete
                    val appointmentId = call.parameters["appointmentId"]?.toIntOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing appointmentId")

                    val appt = db.getAppointmentById(appointmentId)
                        ?: return@delete call.respond(HttpStatusCode.NotFound, "Appointment not found")

                    if (!isAuthorizedForShop(loginInfo, appt.shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@delete
                    }

                    val deleted = db.deleteAppointment(appointmentId)
                    if (deleted) {
                        println("[appointments] Deleted appointment $appointmentId")
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Appointment not found")
                    }
                }

                get("/api/mobile/manager/appointments") {
                    var shopId: Int? = null
                    val loginInfo = authenticateManager()
                    if (loginInfo == null) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }

                    if(loginInfo.role == "shop")
                    {
                      shopId = loginInfo.shopId
                    }
                    else {
                        shopId = call.request.queryParameters["shop_id"]?.toIntOrNull()
                    }

                    if (shopId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing or invalid shop_id")
                        return@get
                    }

                    // Auto-complete any Ongoing appointments that have been running for >2 h
                    db.autoCompleteStaleOngoingAppointments()
                    // Enrich with server-clock-based action booleans (avoids phone timezone/DST issues)
                    val appointments = db.getAppointmentsForShop(shopId).enrichWithStatusActions()
                    call.respond(appointments)
                }


                post("/api/mobile/manager/booking/create") {
                    val loginInfo = authenticateManager()
                    if (loginInfo == null) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }

                    val params = call.receiveParameters()
                    val shopId = params["shop_id"]?.toIntOrNull()
                    val employeeId = params["employee_id"]?.toIntOrNull()
                    var customerId = params["customer_id"]?.toIntOrNull()
                    val serviceIds = params.getAll("service_ids")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
                    val dateTimeStr = params["appointment_time"] // "yyyy-MM-dd HH:mm"

                    if (shopId == null || employeeId == null || serviceIds.isEmpty() || dateTimeStr.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Missing or invalid parameters")
                        return@post
                    }

                    if(customerId == null)
                    {
                        customerId = 0
                    }

                    if (loginInfo.role != "manager") {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@post
                    }

                    // Shop closed (manual override) => block all bookings
                    val voice = db.getShopVoiceConfig(shopId)
                    if (voice.phoneOverride?.trim()?.lowercase() == "closed") {
                        call.respond(HttpStatusCode.Conflict, "Shop is currently closed for bookings")
                        return@post
                    }

                    // Employee unavailable => block booking
                    if (!db.isEmployeeAvailable(shopId = shopId, employeeId = employeeId)) {
                        call.respond(HttpStatusCode.Conflict, "Employee is currently unavailable")
                        return@post
                    }

                    // Parse datetime
                    val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    val localDateTime = try {
                        java.time.LocalDateTime.parse(dateTimeStr, formatter)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid date/time format")
                        return@post
                    }
                    val zoneId = java.time.ZoneId.of("Europe/Copenhagen")
                    val dateTimeMillis = localDateTime.atZone(zoneId).toInstant().toEpochMilli()

                    // Duration sum
                    val totalDuration = serviceIds.mapNotNull { db.getServiceById(it)?.duration }.sum()
                    if (totalDuration == 0) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid service durations")
                        return@post
                    }

                    // Check overlap
                    val overlapping = db.isAppointmentOverlapping(employeeId, dateTimeMillis, totalDuration)
                    if (overlapping) {
                        call.respond(HttpStatusCode.Conflict, "Time slot is already booked")
                        return@post
                    }

                    // Create booking (customerId nullable or passed in if you want)
                    val appointmentId = db.addAppointment(employeeId, shopId, customerId, dateTimeMillis, totalDuration)
                    if (appointmentId == -1) {
                        call.respond(HttpStatusCode.InternalServerError, "Failed to create appointment")
                        return@post
                    }
                    db.addServicesToAppointment(appointmentId, serviceIds)

                    // Send booking confirmation SMS (best-effort)
                    runCatching {
                        val toPhone = customerId?.takeIf { it > 0 }?.let { db.getCustomerById(it)?.phone }?.trim().orEmpty()
                        if (toPhone.isNotBlank()) {
                            val sms = BookingConfirmationSms.send(
                                db = db,
                                smsService = smsService,
                                shopId = shopId,
                                toPhoneE164 = toPhone,
                                appointmentTimeMillis = dateTimeMillis,
                                appointmentCount = 1,
                            )
                            if (!sms.success) println("[BookingConfirmSMS] Failed: status=${sms.status} body=${sms.body}")
                        }
                    }.onFailure { e ->
                        println("[BookingConfirmSMS] Exception: ${e.message}")
                    }

                    call.respond(HttpStatusCode.Created, mapOf("appointment_id" to appointmentId))
                }


                get("/api/mobile/me") {
                    val loginInfo = authenticateManager() ?: return@get
                    val (username, shopId) = when (loginInfo.role) {
                        "manager" -> {
                            val mgr = loginInfo.managerId?.let { db.getManagerById(it) }
                            Pair(mgr?.username, null)
                        }
                        "shop" -> {
                            val shop = loginInfo.shopId?.let { db.getShopById(it) }
                            Pair(shop?.name, loginInfo.shopId)
                        }
                        else -> Pair(null, null)
                    }
                    val webAdminAccess = loginInfo.managerId?.let { db.getManagerWebAdminAccess(it) } ?: false
                    call.respond(MeResponse(
                        role = loginInfo.role,
                        userId = loginInfo.managerId ?: loginInfo.shopId ?: -1,
                        managerId = loginInfo.managerId,
                        shopId = loginInfo.shopId,
                        username = username,
                        webAdminAccess = webAdminAccess,
                    ))
                }

                get("/api/mobile/manager/shops") {
                    val loginInfo = authenticateManager()  // This returns an object with role, managerId, shopId

                    if (loginInfo == null) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }

                    when {
                        loginInfo.role == "manager" && loginInfo.managerId != null -> {
                            val shops = db.getShopsForManager(loginInfo.managerId)
                            call.respond(shops) // List<Shop>
                        }

                        loginInfo.role == "shop" && loginInfo.shopId != null -> {
                            val shop = db.getShopById(loginInfo.shopId)
                            if (shop != null) {
                                call.respond(listOf(shop)) // Wrap in list to match manager output
                            } else {
                                call.respond(HttpStatusCode.NotFound, "Shop not found")
                            }
                        }

                        else -> {
                            call.respond(HttpStatusCode.Forbidden, "Invalid role or missing ID")
                        }
                    }
                }


                get("/api/mobile/manager/employees") {
                    val loginInfo = authenticateManager()
                    if (loginInfo == null) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }

                    // Shop-role: use their own shopId; manager-role: take from query param
                    val shopId = if (loginInfo.role == "shop") loginInfo.shopId
                                 else call.request.queryParameters["shop_id"]?.toIntOrNull()

                    if (shopId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing or invalid shop_id")
                        return@get
                    }

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@get
                    }

                    // Only return employees that are currently available, to reduce booking confusion.
                    val employees = db.getAvailableEmployeesForShop(shopId)
                    call.respond(employees)
                }

                get("/api/mobile/manager/services") {
                    val loginInfo = authenticateManager()
                    if (loginInfo == null) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }

                    val employeeId = call.request.queryParameters["employee_id"]?.toIntOrNull()
                    if (employeeId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing or invalid employee_id")
                        return@get
                    }

                    // Both roles can look up services for an employee
                    val services = db.getServicesForEmployee(employeeId)
                    call.respond(services)
                }

                get("/api/mobile/manager/timeslots") {
                    val loginInfo = authenticateManager()
                    if (loginInfo == null) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }

                    val employeeId = call.request.queryParameters["employee_id"]?.toIntOrNull()
                    val shopId = if (loginInfo.role == "shop") loginInfo.shopId
                                 else call.request.queryParameters["shop_id"]?.toIntOrNull()
                    val date = call.request.queryParameters["date"]
                    val duration = call.request.queryParameters["duration"]?.toIntOrNull()

                    if (employeeId == null || shopId == null || date.isNullOrBlank() || duration == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing or invalid parameters")
                        return@get
                    }

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@get
                    }

                    val timeSlots = db.getAvailableTimeSlots(employeeId, shopId, date, duration)
                    call.respond(timeSlots)
                }

                // ─────────────────────────────────────────────────────────────
                // Voice call log endpoints
                // ─────────────────────────────────────────────────────────────

                /**
                 * GET /api/mobile/manager/shops/{shopId}/calls/active
                 * Returns all active (ongoing) inbound calls for a shop.
                 * The phone app polls this to show live incoming calls.
                 */
                get("/api/mobile/manager/shops/{shopId}/calls/active") {
                    val loginInfo = authenticateManager() ?: return@get
                    val shopId = call.parameters["shopId"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing shopId")

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@get
                    }

                    call.respond(db.getActiveCallsForShop(shopId))
                }

                /**
                 * GET /api/mobile/manager/shops/{shopId}/calls
                 * Returns recent call history for a shop (default last 50).
                 */
                get("/api/mobile/manager/shops/{shopId}/calls") {
                    val loginInfo = authenticateManager() ?: return@get
                    val shopId = call.parameters["shopId"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing shopId")
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@get
                    }

                    call.respond(db.getRecentCallsForShop(shopId, limit))
                }

                /**
                 * GET /api/mobile/manager/calls/{callId}
                 * Returns a single call record with its event timeline.
                 */
                get("/api/mobile/manager/calls/{callId}") {
                    val loginInfo = authenticateManager() ?: return@get
                    val callId = call.parameters["callId"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing callId")

                    val record = db.getCallById(callId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, "Call not found")

                    if (!isAuthorizedForShop(loginInfo, record.shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@get
                    }

                    val events = db.getCallEvents(callId)
                    call.respond(CallDetailResponse(record, events))
                }

                // ─────────────────────────────────────────────────────────────
                // Blacklist endpoints (data stored now; Android UI in step 2)
                // ─────────────────────────────────────────────────────────────

                /**
                 * GET /api/mobile/manager/shops/{shopId}/blacklist
                 */
                get("/api/mobile/manager/shops/{shopId}/blacklist") {
                    val loginInfo = authenticateManager() ?: return@get
                    val shopId = call.parameters["shopId"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing shopId")

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@get
                    }

                    call.respond(db.listBlacklist(shopId))
                }

                /**
                 * POST /api/mobile/manager/shops/{shopId}/blacklist
                 * Body: { "phone": "+4512345678", "reason": "optional" }
                 */
                post("/api/mobile/manager/shops/{shopId}/blacklist") {
                    val loginInfo = authenticateManager() ?: return@post
                    val shopId = call.parameters["shopId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing shopId")

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@post
                    }

                    val body = runCatching { call.receive<AddBlacklistRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid request body")

                    if (body.phone.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, "phone is required")
                    }

                    db.addBlacklistEntry(shopId, body.phone, body.reason)
                    call.respond(HttpStatusCode.Created)
                }

                /**
                 * DELETE /api/mobile/manager/shops/{shopId}/blacklist/{entryId}
                 * Soft-removes (deactivates) the blacklist entry.
                 */
                delete("/api/mobile/manager/shops/{shopId}/blacklist/{entryId}") {
                    val loginInfo = authenticateManager() ?: return@delete
                    val shopId = call.parameters["shopId"]?.toIntOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing shopId")
                    val entryId = call.parameters["entryId"]?.toIntOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing entryId")

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@delete
                    }

                    val removed = db.removeBlacklistEntryById(entryId)
                    if (removed) call.respond(HttpStatusCode.NoContent)
                    else call.respond(HttpStatusCode.NotFound, "Blacklist entry not found")
                }

                // ─────────────────────────────────────────────────────────────
                // JSON booking create with optional call linkage
                // ─────────────────────────────────────────────────────────────

                /**
                 * POST /api/mobile/manager/booking/create-json
                 *
                 * Accepts JSON body (see CreateBookingRequest).
                 * If voiceCallId is provided, the created booking is atomically linked
                 * to the ongoing call in voice_call.linked_booking_id.
                 *
                 * Operator use-case: operator creates a booking while still on the
                 * phone with the customer; the booking shows up linked to the call log.
                 */
                post("/api/mobile/manager/booking/create-json") {
                    val loginInfo = authenticateManager() ?: return@post

                    val rawBody = runCatching { call.receive<CreateBookingRequest>() }.getOrElse { ex ->
                        println("[create-json] Body deserialization FAILED: ${ex.message}")
                        null
                    } ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid request body")

                    val body = rawBody
                    val shopId     = body.shopId
                    val employeeId = body.employeeId
                    val serviceIds = body.serviceIds
                    val dateTimeStr = body.appointmentTime

                    println("[create-json] Received: shopId=$shopId employeeId=$employeeId serviceIds=$serviceIds dateTime='$dateTimeStr' clientTotalDuration=${body.totalDuration}")

                    if (serviceIds.isEmpty() || dateTimeStr.isBlank()) {
                        println("[create-json] 400: serviceIds empty or dateTimeStr blank")
                        call.respond(HttpStatusCode.BadRequest, "Missing or invalid parameters")
                        return@post
                    }

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        println("[create-json] 403: not authorized for shopId=$shopId")
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@post
                    }

                    // Shop closed (manual override) => block all bookings
                    val voice = db.getShopVoiceConfig(shopId)
                    if (voice.phoneOverride?.trim()?.lowercase() == "closed") {
                        call.respond(HttpStatusCode.Conflict, "Shop is currently closed for bookings")
                        return@post
                    }

                    // Employee unavailable => block booking
                    if (!db.isEmployeeAvailable(shopId = shopId, employeeId = employeeId)) {
                        call.respond(HttpStatusCode.Conflict, "Employee is currently unavailable")
                        return@post
                    }

                    // Resolve customer: explicit id > phone lookup/create > 0 (walk-in)
                    val customerId: Int = when {
                        body.customerId != null && body.customerId > 0 -> body.customerId
                        !body.customerPhone.isNullOrBlank() -> {
                            val cid = db.ensureCustomerByPhone(body.customerPhone)
                            println("[create-json] resolved customerPhone=${body.customerPhone} -> customerId=$cid")
                            cid
                        }
                        else -> 0
                    }

                    val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    val localDateTime = runCatching {
                        java.time.LocalDateTime.parse(dateTimeStr, formatter)
                    }.getOrNull()
                        ?: run {
                            println("[create-json] 400: invalid date/time format '$dateTimeStr'")
                            return@post call.respond(HttpStatusCode.BadRequest, "Invalid date/time format")
                        }

                    val zoneId = java.time.ZoneId.of("Europe/Copenhagen")
                    val dateTimeMillis = localDateTime.atZone(zoneId).toInstant().toEpochMilli()

                    // Use batch SUM query — avoids individual getServiceById nulls
                    val dbDuration = db.getTotalDurationForServices(serviceIds)
                    println("[create-json] dbDuration=$dbDuration clientTotalDuration=${body.totalDuration}")
                    val totalDuration = if (dbDuration > 0) dbDuration else body.totalDuration
                    if (totalDuration <= 0) {
                        println("[create-json] 400: cannot determine duration. serviceIds=$serviceIds dbDuration=$dbDuration clientTotalDuration=${body.totalDuration}")
                        call.respond(HttpStatusCode.BadRequest, "Could not determine service duration for serviceIds=$serviceIds")
                        return@post
                    }
                    println("[create-json] using totalDuration=$totalDuration")

                    if (db.isAppointmentOverlapping(employeeId, dateTimeMillis, totalDuration)) {
                        call.respond(HttpStatusCode.Conflict, "Time slot is already booked")
                        return@post
                    }

                    val appointmentId = db.addAppointment(employeeId, shopId, customerId, dateTimeMillis, totalDuration)
                    if (appointmentId == -1) {
                        call.respond(HttpStatusCode.InternalServerError, "Failed to create appointment")
                        return@post
                    }
                    db.addServicesToAppointment(appointmentId, serviceIds)

                    // Optional price override (special offer). If set, it wins over the service sum.
                    body.customPrice?.let { overridePrice ->
                        db.updateAppointmentPrice(appointmentId, overridePrice)
                    }

                    // Link to ongoing call if callId provided
                    val linkedCallId = body.voiceCallId
                    if (linkedCallId != null && linkedCallId > 0) {
                        db.linkBookingToCall(linkedCallId, appointmentId)
                    }

                    // Send booking confirmation SMS (best-effort)
                    runCatching {
                        val toPhone = db.getCustomerById(customerId)?.phone?.trim().orEmpty()
                        if (toPhone.isNotBlank()) {
                            val sms = BookingConfirmationSms.send(
                                db = db,
                                smsService = smsService,
                                shopId = shopId,
                                toPhoneE164 = toPhone,
                                appointmentTimeMillis = dateTimeMillis,
                                appointmentCount = 1,
                            )
                            if (!sms.success) println("[BookingConfirmSMS] Failed: status=${sms.status} body=${sms.body}")
                        }
                    }.onFailure { e ->
                        println("[BookingConfirmSMS] Exception: ${e.message}")
                    }

                    call.respond(
                        HttpStatusCode.Created,
                        CreateBookingResponse(appointmentId = appointmentId, voiceCallId = linkedCallId),
                    )
                }

                /**
                 * POST /api/mobile/manager/booking/create-multi-json
                 *
                 * Creates multiple appointments at the SAME start time atomically.
                 * Either all slots are booked or none are (DB transaction).
                 */
                post("/api/mobile/manager/booking/create-multi-json") {
                    val loginInfo = authenticateManager() ?: return@post

                    val body = runCatching { call.receive<CreateBookingMultiRequest>() }.getOrElse { ex ->
                        println("[create-multi-json] Body deserialization FAILED: ${ex.message}")
                        null
                    } ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid request body")

                    val shopId = body.shopId
                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        return@post call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                    }

                    // Shop closed (manual override) => block all bookings
                    val voice = db.getShopVoiceConfig(shopId)
                    if (voice.phoneOverride?.trim()?.lowercase() == "closed") {
                        return@post call.respond(HttpStatusCode.Conflict, "Shop is currently closed for bookings")
                    }

                    if (body.slots.isEmpty()) {
                        return@post call.respond(HttpStatusCode.BadRequest, "slots is required")
                    }

                    // Parse datetime
                    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    val localDateTime = runCatching { LocalDateTime.parse(body.appointmentTime, formatter) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid appointmentTime format")

                    val zoneId = java.time.ZoneId.of("Europe/Copenhagen")
                    val dateTimeMillis = localDateTime.atZone(zoneId).toInstant().toEpochMilli()

                    // Resolve customer: explicit id > phone lookup/create > 0 (walk-in)
                    val customerId: Int = when {
                        body.customerId != null && body.customerId > 0 -> body.customerId
                        !body.customerPhone.isNullOrBlank() -> {
                            val cid = db.ensureCustomerByPhone(body.customerPhone)
                            println("[create-multi-json] resolved customerPhone=${body.customerPhone} -> customerId=$cid")
                            cid
                        }
                        else -> 0
                    }

                    val appointmentIds = try {
                        val blocks = body.slots.map { slot ->
                            require(slot.employeeId > 0) { "Invalid employeeId" }
                            require(slot.serviceIds.isNotEmpty()) { "serviceIds required" }
                            slot.employeeId to slot.serviceIds
                        }

                        val unavailable = blocks.firstOrNull { (employeeId, _) -> !db.isEmployeeAvailable(shopId = shopId, employeeId = employeeId) }
                        if (unavailable != null) {
                            return@post call.respond(HttpStatusCode.Conflict, "Employee is currently unavailable")
                        }

                        db.createAppointmentsSameTime(
                            shopId = shopId,
                            customerId = customerId,
                            dateTimeMillis = dateTimeMillis,
                            blocks = blocks,
                            overrideDurationMinutes = body.overrideDurationMinutes,
                        )
                    } catch (e: IllegalStateException) {
                        // overlap detected
                        return@post call.respond(HttpStatusCode.Conflict, e.message ?: "Conflict")
                    } catch (e: IllegalArgumentException) {
                        return@post call.respond(HttpStatusCode.BadRequest, e.message ?: "Bad request")
                    }

                    // Optional per-slot price override
                    body.customPrices?.let { prices ->
                        for (i in appointmentIds.indices) {
                            val p = prices.getOrNull(i)
                            if (p != null && p > 0.0) {
                                db.updateAppointmentPrice(appointmentIds[i], p)
                            }
                        }
                    }

                    // Link to ongoing call if callId provided
                    val linkedCallId = body.voiceCallId
                    if (linkedCallId != null && linkedCallId > 0) {
                        // voice_call has only one linked_booking_id, so link the first one.
                        appointmentIds.firstOrNull()?.let { firstId ->
                            db.linkBookingToCall(linkedCallId, firstId)
                        }
                    }

                    // Send booking confirmation SMS once (best-effort)
                    runCatching {
                        val toPhone = db.getCustomerById(customerId)?.phone?.trim().orEmpty()
                        if (toPhone.isNotBlank()) {
                            val sms = BookingConfirmationSms.send(
                                db = db,
                                smsService = smsService,
                                shopId = shopId,
                                toPhoneE164 = toPhone,
                                appointmentTimeMillis = dateTimeMillis,
                                appointmentCount = appointmentIds.size,
                            )
                            if (!sms.success) println("[BookingConfirmSMS] Failed: status=${sms.status} body=${sms.body}")
                        }
                    }.onFailure { e ->
                        println("[BookingConfirmSMS] Exception: ${e.message}")
                    }

                    call.respond(
                        HttpStatusCode.Created,
                        CreateBookingMultiResponse(appointmentIds = appointmentIds, voiceCallId = linkedCallId),
                    )
                }

                // ─────────────────────────────────────────────────────────────
                // Group chat  (manager + all their shops share one room)
                // ─────────────────────────────────────────────────────────────

                /** GET /api/mobile/group-chat/messages?shopId=N&limit=200 — one room per shop (pool). */
                get("/api/mobile/group-chat/messages") {
                    val loginInfo = authenticateManager() ?: return@get
                    val shopId = call.request.queryParameters["shopId"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing shopId")
                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        return@get call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                    }
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 200
                    call.respond(GroupChatMessagesResponse(db.getGroupChatMessages(shopId, limit)))
                }

                /** POST /api/mobile/group-chat/messages?shopId=N  body: {"body":"..."} */
                post("/api/mobile/group-chat/messages") {
                    val loginInfo = authenticateManager() ?: return@post
                    val shopId = call.request.queryParameters["shopId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing shopId")
                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        return@post call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                    }
                    val req = runCatching { call.receive<SendGroupChatMessageRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid request body")
                    val body = req.body.trim()
                    if (body.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, "body must not be blank")
                    }

                    val senderType: String
                    val senderId: Int
                    val senderName: String
                    when (loginInfo.role) {
                        "manager" -> {
                            senderType = "manager"
                            senderId   = loginInfo.managerId!!
                            senderName = db.getManagerById(senderId)?.name ?: "Manager"
                        }
                        "shop" -> {
                            senderType = "shop"
                            senderId   = loginInfo.shopId!!
                            senderName = db.getShopById(senderId)?.name ?: "Shop"
                        }
                        else -> return@post call.respond(HttpStatusCode.Forbidden, "Unknown role")
                    }

                    val msg = db.insertGroupChatMessage(
                        shopId     = shopId,
                        senderType = senderType,
                        senderId   = senderId,
                        senderName = senderName,
                        body       = body,
                    )
                    call.respond(HttpStatusCode.Created, msg)
                }

                // ─────────────────────────────────────────────────────────────
                // Force logout — remote session revocation
                // ─────────────────────────────────────────────────────────────

                /**
                 * POST /api/mobile/manager/shops/{shopId}/force-logout
                 *
                 * Bumps the shop account's token_version so the next API call from
                 * the shop phone returns 401 — effectively revoking the active session.
                 *
                 * Use case: stolen shop phone.
                 * Caller must be a manager that owns the target shop.
                 */
                post("/api/mobile/manager/shops/{shopId}/force-logout") {
                    val loginInfo = authenticateManager() ?: return@post
                    if (loginInfo.role != "manager") {
                        call.respond(HttpStatusCode.Forbidden, "Manager role required")
                        return@post
                    }
                    val shopId = call.parameters["shopId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing shopId")

                    if (!isAuthorizedForShop(loginInfo, shopId, db)) {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@post
                    }

                    db.bumpAppAccountTokenVersion("shop", shopId)
                    println("[ForceLogout] Manager ${loginInfo.managerId} force-logged out shop $shopId")
                    call.respond(HttpStatusCode.OK, "Shop phone session revoked")
                }

                /**
                 * POST /api/mobile/manager/force-logout/{managerId}
                 *
                 * Bumps a manager account's token_version.
                 * Currently restricted to self (a manager cannot revoke another manager's session
                 * from the phone app — use the web admin for that).
                 *
                 * Use case: manager wants to remotely sign out their own stolen phone.
                 */
                post("/api/mobile/manager/force-logout/{managerId}") {
                    val loginInfo = authenticateManager() ?: return@post
                    if (loginInfo.role != "manager") {
                        call.respond(HttpStatusCode.Forbidden, "Manager role required")
                        return@post
                    }
                    val targetManagerId = call.parameters["managerId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing managerId")

                    if (loginInfo.managerId != targetManagerId) {
                        call.respond(HttpStatusCode.Forbidden, "You can only force-logout your own account from the app. Use the web admin to revoke other sessions.")
                        return@post
                    }

                    db.bumpAppAccountTokenVersion("manager", targetManagerId)
                    println("[ForceLogout] Manager $targetManagerId force-logged out their own account")
                    call.respond(HttpStatusCode.OK, "Manager session revoked")
                }

            }
        }
    }
}

// ─── Group chat DTOs ──────────────────────────────────────────────────────────

@Serializable
data class SendGroupChatMessageRequest(val body: String)

@Serializable
data class GroupChatMessagesResponse(val messages: List<GroupChatMessage>)

// ─── Auth helper ──────────────────────────────────────────────────────────────

/**
 * Given the current caller's [LoginInfo], resolve the manager_id for the group chat room.
 * - Manager → their own id.
 * - Shop    → looked up via shops.manager_id.
 * Returns null if the manager cannot be resolved (un-assigned shop etc.).
 */
private fun resolveManagerId(loginInfo: LoginInfo, db: DataBase): Int? = when (loginInfo.role) {
    "manager" -> loginInfo.managerId
    "shop"    -> loginInfo.shopId?.let { db.getManagerIdForShop(it) }
    else      -> null
}

private fun isAuthorizedForShop(loginInfo: LoginInfo, shopId: Int, db: DataBase): Boolean {
    // Step 12: owner-scope guard — if the JWT carries a non-zero ownerId the shop must belong to that owner.
    if (loginInfo.ownerId != 0 && !db.isShopOwnedBy(shopId, loginInfo.ownerId)) return false
    return when (loginInfo.role) {
        "shop"    -> loginInfo.shopId == shopId
        "manager" -> loginInfo.managerId != null && db.isManagerOfShop(loginInfo.managerId, shopId)
        else      -> false
    }
}
