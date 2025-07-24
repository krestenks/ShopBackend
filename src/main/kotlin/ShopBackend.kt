import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.server.sessions.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.routing.*
import org.mindrot.jbcrypt.BCrypt
import kotlinx.coroutines.*

class ShopBackend {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            println("ShopBackend started!")

            val db = DataBase() // This loads or creates the DB
            println("Database initialized.")

            val hash = BCrypt.hashpw("1234", BCrypt.gensalt(12))
            println("Generated bcrypt hash: $hash")

            val webAdmin = WebAdmin(db)
            val customerApi = CustomerApi(db) // You'll define this next

            runBlocking {
                launch {
                    embeddedServer(Netty, port = 9090) {
                        install(ContentNegotiation) {
                            json()
                        }
                        install(Sessions) {
                            cookie<WebAdmin.AdminSession>("ADMIN_SESSION")
                        }
                        routing {
                            webAdmin.setupRoutes(this)
                        }
                    }.start(wait = false)
                    println("Admin server started on port 9090")
                }

                launch {
                    embeddedServer(Netty, port = 9091) {
                        install(ContentNegotiation) {
                            json()
                        }
                        routing {
                            customerApi.setupRoutes(this)
                        }
                        println("Customer API server started on port 9091")
                    }.start(wait = true)

                }
            }
        }
    }
}
