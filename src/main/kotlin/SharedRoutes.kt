// SharedRoutes.kt

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import kotlinx.html.*
import shared.components.BookingUI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import twilio.BookingConfirmationSms
import twilio.TwilioSmsService


suspend fun ApplicationCall.authenticateBookingToken(db: DataBase): BookingTokenInfo? {
    val token = request.queryParameters["token"]
        ?: request.headers["X-Booking-Token"]
        ?: request.cookies["booking_token"] // ✅ new fallback
    if (token == null) return null
    return db.getBookingTokenInfo(token)
}

// Accepts a DataBase reference (or other services) as a parameter
fun Route.sharedBookingRoutes(db: DataBase) {

    // SMS confirmation sender (used for both SMS-link bookings and manager-app bookings)
    val smsService = TwilioSmsService(
        accountSid = System.getenv("TWILIO_ACCOUNT_SID") ?: "",
        authToken = System.getenv("TWILIO_AUTH_TOKEN") ?: "",
    )

    @Serializable
    data class MultiTimeSlotsBlock(val employeeId: Int, val duration: Int)

    @Serializable
    data class MultiTimeSlotsRequest(val shopId: Int, val date: String, val blocks: List<MultiTimeSlotsBlock>)

    @Serializable
    data class MultiSubmitBlock(val employeeId: Int, val serviceIds: List<Int>)

    @Serializable
    data class MultiSubmitPayload(val appointmentTime: String, val date: String? = null, val blocks: List<MultiSubmitBlock>)

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
        call.respondText(
            """
            <!DOCTYPE html>
            <html>
            <head>
              <title>Book Appointment</title>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <link rel="stylesheet" href="/static/booking.css" type="text/css" />
            </head>
            <body>
            ${BookingUI.getFormHtml(shopId, customerId)}

            <script src="/static/booking-ui.js"></script>
            </body>
            </html>
            """.trimIndent(),
            ContentType.Text.Html
        )
    }

    // Multi-therapist: return intersection of start times across blocks.
    post("/api/timeslots/multi") {
        val bookingInfo = call.authenticateBookingToken(db)
        if (bookingInfo == null) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid or missing booking token.")
            return@post
        }

        val body = call.receiveText()
        val req = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(MultiTimeSlotsRequest.serializer(), body)
        }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
            return@post
        }

        // Enforce shopId from token unless token allows override (-1)
        val shopId = if (bookingInfo.shopId == -1) req.shopId else bookingInfo.shopId
        if (shopId <= 0) {
            call.respond(HttpStatusCode.BadRequest, "Invalid shopId")
            return@post
        }

        // Shop closed => bookings not possible
        val voice = db.getShopVoiceConfig(shopId)
        if (voice.phoneOverride?.trim()?.lowercase() == "closed") {
            call.respond(emptyArray<String>())
            return@post
        }

        if (req.blocks.isEmpty()) {
            call.respond(emptyArray<String>())
            return@post
        }

        val blocks = req.blocks.map { it.employeeId to it.duration }
        val slots = db.getAvailableTimeSlotsMulti(shopId, req.date, blocks)
        call.respond(slots)
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

        val baseUrl = PublicBaseUrl.fromCall(call)
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

        // Hide employees that are marked unavailable (manager toggle) to reduce confusion in booking UI.
        val employees = db.getAvailableEmployeesForShop(shopId)
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

        // Shop closed => bookings not possible
        val voice = db.getShopVoiceConfig(shopId)
        if (voice.phoneOverride?.trim()?.lowercase() == "closed") {
            call.respond(emptyArray<String>())
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

        // Shop closed => bookings not possible
        val voice = db.getShopVoiceConfig(shopId)
        if (voice.phoneOverride?.trim()?.lowercase() == "closed") {
            call.respond(HttpStatusCode.Conflict, "Shop is currently closed for bookings")
            return@post
        }

        val employeeId = params["employee_id"]?.toIntOrNull()
        val dateTimeStr = params["appointment_time"] // Expected format: "yyyy-MM-dd HH:mm"
        val serviceIds = params.getAll("service_ids")?.mapNotNull { it.toIntOrNull() } ?: emptyList()

        if (employeeId == null || dateTimeStr.isNullOrBlank() || serviceIds.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "Missing required form data.")
            return@post
        }

        // Employee unavailable => bookings not possible
        if (!db.isEmployeeAvailable(shopId = shopId, employeeId = employeeId)) {
            call.respond(HttpStatusCode.Conflict, "Employee is currently unavailable")
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
        // IMPORTANT: server runs in UTC on Upsun, but booking times are in local shop time.
        // Use explicit timezone to avoid +2h offset in the mobile app.
        val zoneId = java.time.ZoneId.of("Europe/Copenhagen")
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

        // Send booking confirmation SMS to customer (best-effort; booking should still succeed if SMS fails)
        runCatching {
            val phone = db.getCustomerById(customerId)?.phone?.trim().orEmpty()
            if (phone.isNotBlank()) {
                val sms = BookingConfirmationSms.send(
                    db = db,
                    smsService = smsService,
                    shopId = shopId,
                    toPhoneE164 = phone,
                    appointmentTimeMillis = dateTimeMillis,
                    appointmentCount = 1,
                )
                if (!sms.success) println("[BookingConfirmSMS] Failed: status=${sms.status} body=${sms.body}")
            }
        }.onFailure { e ->
            println("[BookingConfirmSMS] Exception: ${e.message}")
        }

        // Redirect or respond success
        call.respondRedirect("/api/booking/confirmation?appointment_id=$appointmentId")
    }

    // Multi-therapist submit endpoint.
    post("/api/booking/submit-multi") {
        val bookingInfo = call.authenticateBookingToken(db)
        if (bookingInfo == null) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid or missing booking token.")
            return@post
        }

        val params = call.receiveParameters()
        val payloadRaw = params["payload"]
        if (payloadRaw.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing payload")
            return@post
        }

        val payload = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(MultiSubmitPayload.serializer(), payloadRaw)
        }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, "Invalid payload JSON")
            return@post
        }

        if (payload.blocks.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "No therapists")
            return@post
        }

        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val localDateTime = try {
            java.time.LocalDateTime.parse(payload.appointmentTime, formatter)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid date/time format.")
            return@post
        }

        val zoneId = java.time.ZoneId.of("Europe/Copenhagen")
        val dateTimeMillis = localDateTime.atZone(zoneId).toInstant().toEpochMilli()

        val shopId = bookingInfo.shopId
        if (shopId <= 0) {
            call.respond(HttpStatusCode.BadRequest, "Invalid shop")
            return@post
        }

        // Shop closed => bookings not possible
        val voice = db.getShopVoiceConfig(shopId)
        if (voice.phoneOverride?.trim()?.lowercase() == "closed") {
            call.respond(HttpStatusCode.Conflict, "Shop is currently closed for bookings")
            return@post
        }

        val blocks = payload.blocks.map { it.employeeId to it.serviceIds }

        // Employee unavailable => bookings not possible
        val unavailable = payload.blocks.firstOrNull { !db.isEmployeeAvailable(shopId = shopId, employeeId = it.employeeId) }
        if (unavailable != null) {
            call.respond(HttpStatusCode.Conflict, "Employee is currently unavailable")
            return@post
        }

        val appointmentIds = try {
            db.createAppointmentsSameTime(
                shopId = shopId,
                customerId = bookingInfo.customerId,
                dateTimeMillis = dateTimeMillis,
                blocks = blocks,
                overrideDurationMinutes = null,
            )
        } catch (e: Exception) {
            call.respond(HttpStatusCode.Conflict, "Could not book all therapists at that time. Please choose another time.")
            return@post
        }

        // Send booking confirmation SMS once (best-effort)
        runCatching {
            val phone = db.getCustomerById(bookingInfo.customerId)?.phone?.trim().orEmpty()
            if (phone.isNotBlank()) {
                val sms = BookingConfirmationSms.send(
                    db = db,
                    smsService = smsService,
                    shopId = shopId,
                    toPhoneE164 = phone,
                    appointmentTimeMillis = dateTimeMillis,
                    appointmentCount = appointmentIds.size,
                )
                if (!sms.success) println("[BookingConfirmSMS] Failed: status=${sms.status} body=${sms.body}")
            }
        }.onFailure { e ->
            println("[BookingConfirmSMS] Exception: ${e.message}")
        }

        // show confirmation for the first appointment, and pass the rest as query params
        val qs = buildString {
            append("/api/booking/confirmation-multi?ids=")
            append(appointmentIds.joinToString(","))
        }
        call.respondRedirect(qs)
    }

    get("/api/booking/confirmation-multi") {
        val bookingInfo = call.authenticateBookingToken(db)
        if (bookingInfo == null) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid or missing booking token.")
            return@get
        }

        db.markBookingTokenUsed(bookingInfo.token)

        val ids = call.request.queryParameters["ids"]
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.distinct()
            ?: emptyList()

        if (ids.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "Missing ids")
            return@get
        }

        val appointments = ids.mapNotNull { id ->
            val appt = db.getAppointmentById(id) ?: return@mapNotNull null
            val employee = db.getEmployeeById(appt.employeeId)
            val services = db.getServicesForAppointment(id)
            Triple(appt, employee, services)
        }

        if (appointments.isEmpty()) {
            call.respond(HttpStatusCode.NotFound, "Appointments not found")
            return@get
        }

        val shop = db.getShopById(appointments.first().first.shopId)
        if (shop == null) {
            call.respond(HttpStatusCode.InternalServerError, "Missing shop information")
            return@get
        }

        val formatterOut = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy 'at' HH:mm")
        val dateTimeFormatted = java.time.Instant.ofEpochMilli(appointments.first().first.dateTime)
            .atZone(java.time.ZoneId.of("Europe/Copenhagen"))
            .format(formatterOut)

        call.respondHtml {
            head {
                meta { charset = "utf-8" }
                meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
                title("Booking Confirmation")
                link(rel = "stylesheet", href = "/static/booking.css", type = "text/css")
            }
            body {
                div(classes = "booking-page") {
                    div(classes = "booking-card") {
                        div(classes = "booking-header") {
                            h1(classes = "booking-title") { +"Bookings confirmed" }
                            p(classes = "booking-subtitle") { +"All therapists were booked for the same start time." }
                        }

                        div(classes = "success") { +"✅ Confirmed" }

                        div(classes = "details") {
                            p { +"📅 $dateTimeFormatted" }
                            p { +"🏠 ${shop.address}" }

                            if (!shop.directions.isNullOrBlank()) {
                                p {
                                    +"📍 "
                                    a(href = shop.directions, target = "_blank") { +"Open directions" }
                                }
                            }

                            hr {}
                            h2 { +"Therapists" }
                            ul {
                                appointments.forEach { (appt, emp, svcs) ->
                                    li {
                                        +"${emp?.name ?: "Employee #${appt.employeeId}"}: "
                                        if (svcs.isEmpty()) {
                                            +"(no services)"
                                        } else {
                                            +svcs.joinToString(", ") { s -> "${s.name} (${s.duration} min)" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
            .atZone(java.time.ZoneId.of("Europe/Copenhagen"))
            .format(formatter)

        call.respondHtml {
            head {
                meta { charset = "utf-8" }
                meta {
                    name = "viewport"
                    content = "width=device-width, initial-scale=1"
                }
                title("Booking Confirmation")
                link(rel = "stylesheet", href = "/static/booking.css", type = "text/css")
            }
            body {
                div(classes = "booking-page") {
                    div(classes = "booking-card") {
                        div(classes = "booking-header") {
                            h1(classes = "booking-title") { +"Booking confirmed" }
                            p(classes = "booking-subtitle") { +"Your appointment has been booked." }
                        }

                        div(classes = "success") {
                            +"✅ Confirmed"
                        }

                        div(classes = "details") {
                            p { +"📅 $dateTimeFormatted" }
                            p { +"👤 ${employee.name}" }
                            p { +"🏠 ${shop.address}" }

                            if (!shop.directions.isNullOrBlank()) {
                                p {
                                    +"📍 "
                                    a(href = shop.directions, target = "_blank") { +"Open directions" }
                                }
                            }

                            if (services.isNotEmpty()) {
                                hr {}
                                h2 { +"Selected services" }
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
        }
    }
}
