// SharedRoutes.kt

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.br
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.head
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.title
import kotlinx.html.ul
import shared.components.bookingForm


suspend fun ApplicationCall.authenticateBookingToken(db: DataBase): BookingTokenInfo? {
    val token = request.queryParameters["token"]
        ?: request.headers["X-Booking-Token"]
        ?: request.cookies["booking_token"] // ✅ new fallback
    if (token == null) return null
    return db.getBookingTokenInfo(token)
}

// Accepts a DataBase reference (or other services) as a parameter
fun Route.sharedBookingRoutes(db: DataBase) {

    get("/api/book") {
        val token = call.request.queryParameters["token"]
        if (token == null) {
            return@get call.respond(HttpStatusCode.BadRequest, "Missing token")
        }

        val bookingInfo = db.getBookingTokenInfo(token)
        if (bookingInfo == null) {
            return@get call.respond(HttpStatusCode.NotFound, "Invalid or expired token")
        }

        call.response.cookies.append("booking_token", token, path = "/", httpOnly = true)

        // Extract data from DB row
        val shopId = bookingInfo.shopId
        val customerId = bookingInfo.customerId

        println("Serving bookingform $shopId, $customerId")

        // Serve the HTML form
        call.respondHtml {
            head {
                title { +"Book Appointment" }
            }
            body {
                bookingForm(shopId = shopId, customerId = customerId)
            }
        }
    }


    post("/api/booking/create") {
        val params = call.receiveParameters()
        val shopId = params["shop_id"]?.toIntOrNull()
        val phone = params["phone"]

        if (shopId == null || phone == null) {
            return@post call.respond(HttpStatusCode.BadRequest, "Missing shop_id or phone")
        }

        val customerId = db.ensureCustomerByPhone(phone) // implement if needed
        val bookingToken = db.generateBookingToken( customerId, shopId, phone)

        val baseUrl = System.getenv("PUBLIC_BOOKING_URL") ?: "http://localhost:9091"
        val fullUrl = "$baseUrl/api/book?token=$bookingToken"

        call.respondText(
            """Book here: $fullUrl""",
            ContentType.Text.Plain
        )
    }

    get("/api/shops") {
        val bookingInfo = call.authenticateBookingToken(db)
        if (bookingInfo == null) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid or missing booking token.")
            return@get
        }

        // Optional: Use only customerId/shopId from token to prevent tampering
        val customerId = bookingInfo.customerId
        val shopId = bookingInfo.shopId
        val shops = db.getAllShops()
        call.respond(ShopList(shops))
    }

    get("/api/employees") {
        val bookingInfo = call.authenticateBookingToken(db)
        if (bookingInfo == null) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid or missing booking token.")
            return@get
        }

        // Optional: Use only customerId/shopId from token to prevent tampering
        val customerId = bookingInfo.customerId
        var shopId: Int? = bookingInfo.shopId
        if(shopId != null && shopId < 0)
        {
            shopId = call.request.queryParameters["shop_id"]?.toIntOrNull()
        }
        if(shopId == null) { shopId = 0}

        val employees = db.getEmployeesForShop(shopId)
        call.respond(EmployeeList(employees))
    }

    get("/api/services") {
        val bookingInfo = call.authenticateBookingToken(db)
        if (bookingInfo == null) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid or missing booking token.")
            return@get
        }

        val employeeId = call.request.queryParameters["employee_id"]?.toIntOrNull()
        if (employeeId == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing or invalid employee_id")
            return@get
        }
        val services = db.getServicesForEmployee(employeeId)
        call.respond(ServiceList(services))
    }

    get("/api/timeslots") {
        val bookingInfo = call.authenticateBookingToken(db)
        if (bookingInfo == null) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid or missing booking token.")
            return@get
        }

        // Enforce shopId from token, but allow override only if current shopId is -1
        val tokenShopId = bookingInfo.shopId
        val queryShopId = call.request.queryParameters["shop_id"]?.toIntOrNull()

        val shopId = if (tokenShopId == -1 && queryShopId != null) {
            queryShopId
        } else {
            tokenShopId
        }

        if (shopId == null || shopId <= 0) {
            call.respond(HttpStatusCode.BadRequest, "Missing or invalid shop_id")
            return@get
        }
        val employeeId = call.request.queryParameters["employee_id"]?.toIntOrNull()
        val date = call.request.queryParameters["date"]
        val duration = call.request.queryParameters["duration"]?.toIntOrNull()

        if (employeeId == null || date == null || duration == null || shopId == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing or invalid parameters")
            return@get
        }

        val timeSlots = db.getAvailableTimeSlots(employeeId, shopId, date, duration)
        call.respond(timeSlots)
    }

    // Handle POST to create appointment
    post("/api/booking/submit") {
        val bookingInfo = call.authenticateBookingToken(db)
        if (bookingInfo == null) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid or missing booking token.")
            return@post
        }

        // Optional: Use only customerId/shopId from token to prevent tampering
        val customerId = bookingInfo.customerId
        // Enforce shopId from token, but allow override only if current shopId is -1
        val tokenShopId = bookingInfo.shopId
        val params = call.receiveParameters()
        val queryShopId = call.request.queryParameters["shop_id"]?.toIntOrNull()
            ?: params["shop_id"]?.toIntOrNull()

        println("/api/booking/submit $tokenShopId, $queryShopId")

        val shopId = if (tokenShopId == -1 && queryShopId != null) {
            queryShopId
        } else {
            tokenShopId
        }

        if (shopId == null || shopId <= 0) {
            call.respond(HttpStatusCode.BadRequest, "Missing or invalid shop_id")
            return@post
        }

        val employeeId = params["employee_id"]?.toIntOrNull()
        val dateTimeStr = params["appointment_time"] // Expected format: "yyyy-MM-dd HH:mm"
        val serviceIds = params.getAll("service_ids")?.mapNotNull { it.toIntOrNull() } ?: emptyList()

        if (employeeId == null || dateTimeStr.isNullOrBlank() || serviceIds.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "Missing required form data.")
            return@post
        }

        // Parse date_time string to epoch milliseconds
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val localDateTime = try {
            java.time.LocalDateTime.parse(dateTimeStr, formatter)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid date/time format.")
            return@post
        }
        val zoneId = java.time.ZoneId.systemDefault()
        val dateTimeMillis = localDateTime.atZone(zoneId).toInstant().toEpochMilli()

        // Calculate total duration of selected services
        val totalDuration = serviceIds.mapNotNull { db.getServiceById(it)?.duration }.sum()
        if (totalDuration == 0) {
            call.respond(HttpStatusCode.BadRequest, "Invalid service durations.")
            return@post
        }

        // Check for overlapping appointments before booking
        val overlapping = db.isAppointmentOverlapping(employeeId, dateTimeMillis, totalDuration)
        if (overlapping) {
            call.respond(HttpStatusCode.Conflict, "The selected time slot is already booked.")
            return@post
        }

        // Add appointment and services
        val appointmentId = db.addAppointment(employeeId, shopId, customerId, dateTimeMillis, totalDuration)
        if (appointmentId == -1) {
            call.respond(HttpStatusCode.InternalServerError, "Failed to create appointment.")
            return@post
        }
        db.addServicesToAppointment(appointmentId, serviceIds)

        // Redirect or respond success
        call.respondRedirect("/api/booking/confirmation?appointment_id=$appointmentId")
    }

    get("/api/booking/confirmation") {
        val bookingInfo = call.authenticateBookingToken(db)
        if (bookingInfo == null) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid or missing booking token.")
            return@get
        }

        db.markBookingTokenUsed(bookingInfo.token)

        val appointmentId = call.request.queryParameters["appointment_id"]?.toIntOrNull()

        if (appointmentId == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing or invalid appointment_id")
            return@get
        }

        val appointment = db.getAppointmentById(appointmentId)
        if (appointment == null) {
            call.respond(HttpStatusCode.NotFound, "Appointment not found")
            return@get
        }

        val shop = db.getShopById(appointment.shopId)
        val employee = db.getEmployeeById(appointment.employeeId)
        val services = db.getServicesForAppointment(appointmentId)

        if (shop == null || employee == null) {
            call.respond(HttpStatusCode.InternalServerError, "Missing shop or employee information")
            return@get
        }

        val formatter = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy 'at' HH:mm")
        val dateTimeFormatted = java.time.Instant.ofEpochMilli(appointment.dateTime)
            .atZone(java.time.ZoneId.systemDefault())
            .format(formatter)

        call.respondHtml {
            head {
                title("Booking Confirmation")
            }
            body {
                h1 { +"Booking Confirmed" }
                p { +"📅 Your appointment is booked for $dateTimeFormatted." }
                p { +"👤 Employee: ${employee.name}" }
                p { +"🏠 Shop address: ${shop.address}" }

                if (!shop.directions.isNullOrBlank()) {
                    p {
                        +"📍 Directions: "
                        a(href = shop.directions, target = "_blank") { +"Open in Maps" }
                    }
                }

                if (services.isNotEmpty()) {
                    h2 { +"🛠 Selected Services" }
                    ul {
                        services.forEach {
                            li { +"${it.name} (${it.duration} min, ${it.price} kr)" }
                        }
                    }
                }
            }
        }
    }
}
