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
import shared.components.bookingForm
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.*

@Serializable
data class LoginRequest(val username: String, val password: String)
@Serializable
data class LoginResponse(val token: String, val managerId: Int)
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
    fun setupRoutes(routing: Routing) {
        routing {

            post("/api/mobile/login") {
                val loginRequest = call.receive<LoginRequest>()
                val username = loginRequest.username
                val password = loginRequest.password

                val manager = db.authenticateManager(username, password)
                if (manager != null) {
                    val token = generateToken(manager.id, role = "manager")
                    call.respond(LoginResponse(token, manager.id))
                    return@post
                }

                val shop = db.authenticateShop(username, password)
                if (shop != null) {
                    val token = generateToken(shop.id, role = "shop")
                    call.respond(LoginResponse(token, shop.id))
                    return@post
                }

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

                    val shopId = call.request.queryParameters["shop_id"]?.toIntOrNull()
                    if (shopId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing or invalid shop_id")
                        return@get
                    }

                    if (loginInfo.role != "manager") {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@get
                    }

                    val employees = db.getEmployeesForShop(shopId)
                    call.respond(employees)
                }

                get("/api/mobile/manager/employees") {
                    val loginInfo = authenticateManager()
                    if (loginInfo == null) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }

                    val shopId = call.request.queryParameters["shop_id"]?.toIntOrNull()
                    if (shopId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing or invalid shop_id")
                        return@get
                    }

                    if (loginInfo.role != "manager") {
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

                    if (loginInfo.role != "manager") {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@get
                    }

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
                    val shopId = call.request.queryParameters["shop_id"]?.toIntOrNull()
                    val date = call.request.queryParameters["date"]
                    val duration = call.request.queryParameters["duration"]?.toIntOrNull()

                    if (employeeId == null || shopId == null || date.isNullOrBlank() || duration == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing or invalid parameters")
                        return@get
                    }

                    if (loginInfo.role != "manager") {
                        call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
                        return@get
                    }

                    val timeSlots = db.getAvailableTimeSlots(employeeId, shopId, date, duration)
                    call.respond(timeSlots)
                }


            }
        }
    }
}
