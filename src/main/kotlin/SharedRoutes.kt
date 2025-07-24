// SharedRoutes.kt

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

// Accepts a DataBase reference (or other services) as a parameter
fun Route.sharedBookingRoutes(db: DataBase) {


    get("/api/link/2918315f16ff") {
        // This is where you verify token and redirect to booking
        // Or return booking info as JSON

        call.respondHtml {
            body {
                h3 { +"Nice booking." }
                a("/login") { +"Try again" }
            }
        }
    }


    // Add more customer API endpoints as needed

    get("/api/employees") {
        val shopId = call.request.queryParameters["shop_id"]?.toIntOrNull()
        if (shopId == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing or invalid shop_id")
            return@get
        }
        val employees = db.getEmployeesForShop(shopId)
        call.respond(EmployeeList(employees))
    }

    get("/api/services") {
        val employeeId = call.request.queryParameters["employee_id"]?.toIntOrNull()
        if (employeeId == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing or invalid employee_id")
            return@get
        }
        val services = db.getServicesForEmployee(employeeId)
        call.respond(ServiceList(services))
    }

    get("/api/timeslots") {
        val employeeId = call.request.queryParameters["employee_id"]?.toIntOrNull()
        val shopId = call.request.queryParameters["shop_id"]?.toIntOrNull()
        val date = call.request.queryParameters["date"]
        val duration = call.request.queryParameters["duration"]?.toIntOrNull()

        if (employeeId == null || shopId == null || date == null || duration == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing or invalid parameters")
            return@get
        }

        val timeSlots = db.getAvailableTimeSlots(employeeId, shopId, date, duration)
        call.respond(timeSlots)
    }

    // Handle POST to create appointment
    post("/appointments/submit") {
        val params = call.receiveParameters()
        val employeeId = params["employee_id"]?.toIntOrNull()
        val shopId = params["shop_id"]?.toIntOrNull()
        val dateTimeStr = params["appointment_time"] // Expected format: "yyyy-MM-dd HH:mm"
        val serviceIds = params.getAll("service_ids")?.mapNotNull { it.toIntOrNull() } ?: emptyList()

        if (employeeId == null || shopId == null || dateTimeStr.isNullOrBlank() || serviceIds.isEmpty()) {
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
        val appointmentId = db.addAppointment(employeeId, shopId, dateTimeMillis, totalDuration)
        if (appointmentId == -1) {
            call.respond(HttpStatusCode.InternalServerError, "Failed to create appointment.")
            return@post
        }
        db.addServicesToAppointment(appointmentId, serviceIds)

        // Redirect or respond success
        call.respondRedirect("/booking/confirmation?appointment_id=$appointmentId")
    }

    get("/booking/confirmation") {
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

                br()
                a(href = "/") { +"← Back to homepage" }
            }
        }
    }
}
