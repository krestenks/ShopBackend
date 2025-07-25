import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.resources
import io.ktor.server.http.content.static
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.html.*
import org.mindrot.jbcrypt.BCrypt
import shared.components.bookingForm
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class CustomerApi(private val db: DataBase) {
    fun setupRoutes(routing: Routing) {
        routing {
            static("/static") {
                resources("static")
            }

            sharedBookingRoutes(db) // <- Include shared routes

            // Show form to create a new appointment
            // post is in shared routes
            get("/appointments/add") {
                call.respondHtml {
                    body {
                        header()
                        bookingForm(customerId = 1) // or from call.parameters
                    }
                }
            }


        }
    }
}
