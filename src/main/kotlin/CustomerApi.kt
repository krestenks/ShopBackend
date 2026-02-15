import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.*
import kotlinx.html.*
import shared.components.BookingUI

class CustomerApi(private val db: DataBase) {
    fun setupRoutes(routing: Route) {
        routing {
            staticResources("/static", "static")

            sharedBookingRoutes(db)

            get("/appointments/add") {
                call.respondHtml {
                    body {
                        unsafe {
                            +"""<!DOCTYPE html>
<html>
<head><title>Book Appointment</title></head>
<body>
${BookingUI.getFormHtml(shopId = 1, customerId = 1)}
</body>
</html>"""
                        }
                    }
                }
            }
        }
    }
}
