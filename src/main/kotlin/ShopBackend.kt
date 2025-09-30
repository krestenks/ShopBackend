import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.*
import io.ktor.server.sessions.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import org.mindrot.jbcrypt.BCrypt

object ShopBackend {
    @JvmStatic
    fun main(args: Array<String>) {
        println("ShopBackend starting…")

        // --- Parse CLI args ---
        val dbPath = parseDbArg(args) ?: ""   // empty means "use default inside DataBase"
        if (dbPath.isNotBlank()) println("Using database path: $dbPath")

        // --- Create DB (constructor accepts optional path) ---
        val db = DataBase(dbPath)
        println("Database initialized.")

        // just keeping your demo hash/log
        val hash = BCrypt.hashpw("1234", BCrypt.gensalt(12))
        println("Generated bcrypt hash: $hash")

        // --- Instantiate route handlers ---
        val webAdmin = WebAdmin(db)
        val customerApi = CustomerApi(db)
        val mobileApi = MobileApi(db)

        // --- Background cleanup every hour ---
        startCleanupScheduler(db)

        // --- Single server, single port ---
        val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
        val host = "0.0.0.0"

        println("ShopBackend listening on $host:$port")

        embeddedServer(Netty, port = port, host = host) {
            // global plugins
            install(ContentNegotiation) {
                json(Json { prettyPrint = false })
            }
            install(Sessions) {
                cookie<WebAdmin.AdminSession>("ADMIN_SESSION")
            }
            // If your mobile API needs JWT globally, you can install once here.
            // If it should only apply under /mobile, keep auth config local in that route.
            JwtConfig.install(this)

            routing {
                // Admin at /
                route("/") { webAdmin.setupRoutes(this) }

                // Customer API at /api
                route("/api") { customerApi.setupRoutes(this) }

                // Mobile API at /mobile
                route("/mobile") { mobileApi.setupRoutes(this) }
            }

        }.start(wait = true)

        println("Server started on $host:$port")
    }

    private fun parseDbArg(args: Array<String>): String? {
        // supports: --db=/path/file.db  OR  --db /path/file.db
        val key = "--db"
        for (i in args.indices) {
            val a = args[i]
            if (a == key && i + 1 < args.size) {
                return args[i + 1]
            }
            if (a.startsWith("$key=")) {
                return a.substringAfter('=')
            }
        }
        return null
    }

    private fun startCleanupScheduler(db: DataBase) {
        val tf = ThreadFactory { r ->
            Thread(r, "cleanup-scheduler").apply {
                isDaemon = true // don’t block shutdown
            }
        }
        val executor = Executors.newSingleThreadScheduledExecutor(tf)
        executor.scheduleAtFixedRate({
            try {
                db.deleteOldBookingTokens()
            } catch (e: Exception) {
                println("Error during booking token cleanup: ${e.message}")
            }
        }, 0, 1, TimeUnit.HOURS)
    }
}
