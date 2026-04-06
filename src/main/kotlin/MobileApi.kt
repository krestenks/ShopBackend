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
import twilio.TwilioVoiceCallService

// ─── Request/response models used by call-log + blacklist endpoints ───────────

@Serializable
data class AddBlacklistRequest(val phone: String, val reason: String? = null)

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
)

@Serializable
data class CreateBookingResponse(val appointmentId: Int, val voiceCallId: Int?)

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
)
data class LoginInfo(val role: String, val managerId: Int?, val shopId: Int?)

suspend fun PipelineContext<Unit, ApplicationCall>.authenticateManager(): LoginInfo? {
    val authHeader = call.request.headers["Authorization"]
    println("Authorization header received: $authHeader")  // Log the token

    if (authHeader == null) {
        call.respond(HttpStatusCode.Unauthorized, "Missing Authorization header")
        return null
    }

    val principal = call.principal<JWTPrincipal>()
    if (principal == null) {
        call.respond(HttpStatusCode.Unauthorized, "Invalid or expired token")
        return null
    }

    val userId = principal.payload.getClaim("userId")?.asInt()
    val role = principal.payload.getClaim("role")?.asString()
    println("authenticateManager: $userId, $role")

    if (role == "manager" && userId != null) {
       return LoginInfo(role, userId, null)
    }
    else if (role == "shop" && userId != null) {
        return LoginInfo(role, null, userId)
    }

    call.respond(HttpStatusCode.Unauthorized, "Invalid role")
    return null

}



class MobileApi(private val db: DataBase) {
    fun setupRoutes(routing: Route) {
        routing {

            // Twilio voice service configuration
            val voiceService = TwilioVoiceCallService(
                accountSid = System.getenv("TWILIO_ACCOUNT_SID") ?: "",
                authToken = System.getenv("TWILIO_AUTH_TOKEN") ?: "",
                fromNumber = System.getenv("TWILIO_FROM_NUMBER") ?: "",
                publicBaseUrl = System.getenv("PUBLIC_BASE_URL") ?: (System.getenv("PUBLIC_BOOKING_URL") ?: "http://localhost:8080"),
            )

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
                    val token = generateToken(refId, role = role)
                    println("[MobileApi/login] OK via app_account: $role id=$refId")
                    call.respond(LoginResponse(token = token, managerId = refId, role = role, userId = refId))
                    return@post
                }

                // ── 2. Fallback: manager table (backwards compat) ────────────
                val manager = db.authenticateManager(username, password)
                if (manager != null) {
                    val token = generateToken(manager.id, role = "manager")
                    println("[MobileApi/login] OK via managers table: id=${manager.id} name='${manager.name}'")
                    call.respond(LoginResponse(token = token, managerId = manager.id, role = "manager", userId = manager.id))
                    return@post
                }

                // ── 3. Fallback: shop table (legacy, rarely used) ────────────
                val shop = db.authenticateShop(username, password)
                if (shop != null) {
                    val token = generateToken(shop.id, role = "shop")
                    println("[MobileApi/login] OK via shops table: id=${shop.id} name='${shop.name}'")
                    call.respond(LoginResponse(token = token, managerId = shop.id, role = "shop", userId = shop.id))
                    return@post
                }

                println("[MobileApi/login] FAIL: no match for username='$username'")
                call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
            }

            authenticate("jwt") {

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

                    val appointments = db.getAppointmentsForShop(shopId)
                    call.respond(appointments)
                }


                /**
                 * Call the customer tied to an existing appointment and play a TTS message.
                 *
                 * Request: JSON {"message":"..."} (optional)
                 * Response: JSON {"success":true/false, "status":int, "twilio":string}
                 */
                post("/api/mobile/manager/appointments/{appointmentId}/call-customer") {
                    val loginInfo = authenticateManager()
                    if (loginInfo == null) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }

                    val appointmentId = call.parameters["appointmentId"]?.toIntOrNull()
                    if (appointmentId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing or invalid appointmentId")
                        return@post
                    }

                    val appointment = db.getAppointmentWithServicesById(appointmentId)
                        ?: run {
                            call.respond(HttpStatusCode.NotFound, "Appointment not found")
                            return@post
                        }

                    if (appointment.customer?.phone.isNullOrBlank()) {
                        call.respond(HttpStatusCode.NotFound, "Appointment/customer phone not found")
                        return@post
                    }

                    // Authorization: shops can only call for their own appointments. Managers can call for shops they own.
                    when (loginInfo.role) {
                        "shop" -> {
                            if (loginInfo.shopId != appointment.shopId) {
                                call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                                return@post
                            }
                        }
                        "manager" -> {
                            val managerId = loginInfo.managerId
                            if (managerId == null || !db.isManagerOfShop(managerId, appointment.shopId)) {
                                call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                                return@post
                            }
                        }
                        else -> {
                            call.respond(HttpStatusCode.Forbidden, "Invalid role")
                            return@post
                        }
                    }

                    @Serializable
                    data class CallCustomerRequest(val message: String? = null)

                    val body = runCatching { call.receive<CallCustomerRequest>() }.getOrNull()
                    val message = body?.message?.takeIf { it.isNotBlank() }
                        ?: "Hello. We are ready for you now. Please come to the door."

                    val toPhone = appointment.customer!!.phone
                    println("[TwilioVoice] Calling customer $toPhone for appointment $appointmentId (shop ${appointment.shopId})")
                    val result = voiceService.callCustomer(toPhoneE164 = toPhone, message = message, appointmentId = appointmentId)

                    call.respond(
                        mapOf(
                            "success" to result.success,
                            "status" to result.status,
                            "twilio" to result.body,
                        )
                    )
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

                    // Parse datetime
                    val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    val localDateTime = try {
                        java.time.LocalDateTime.parse(dateTimeStr, formatter)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid date/time format")
                        return@post
                    }
                    val zoneId = java.time.ZoneId.systemDefault()
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
                    call.respond(MeResponse(
                        role = loginInfo.role,
                        userId = loginInfo.managerId ?: loginInfo.shopId ?: -1,
                        managerId = loginInfo.managerId,
                        shopId = loginInfo.shopId,
                        username = username,
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

                    val employees = db.getEmployeesForShop(shopId)
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

                    val customerId = body.customerId ?: 0

                    val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    val localDateTime = runCatching {
                        java.time.LocalDateTime.parse(dateTimeStr, formatter)
                    }.getOrNull()
                        ?: run {
                            println("[create-json] 400: invalid date/time format '$dateTimeStr'")
                            return@post call.respond(HttpStatusCode.BadRequest, "Invalid date/time format")
                        }

                    val zoneId = java.time.ZoneId.systemDefault()
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

                    // Link to ongoing call if callId provided
                    val linkedCallId = body.voiceCallId
                    if (linkedCallId != null && linkedCallId > 0) {
                        db.linkBookingToCall(linkedCallId, appointmentId)
                    }

                    call.respond(
                        HttpStatusCode.Created,
                        CreateBookingResponse(appointmentId = appointmentId, voiceCallId = linkedCallId),
                    )
                }

            }
        }
    }
}

// ─── Auth helper ──────────────────────────────────────────────────────────────

private fun isAuthorizedForShop(loginInfo: LoginInfo, shopId: Int, db: DataBase): Boolean {
    return when (loginInfo.role) {
        "shop"    -> loginInfo.shopId == shopId
        "manager" -> loginInfo.managerId != null && db.isManagerOfShop(loginInfo.managerId, shopId)
        else      -> false
    }
}
