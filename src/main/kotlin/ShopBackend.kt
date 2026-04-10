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
import twilio.TwilioChatbotService
import twilio.ChatbotConfig
import twilio.twilioRoutes
import twilio.chatTestRoutes
import twilio.chatApiRoutes
import twilio.twilioVoiceRoutes

object ShopBackend {
    @JvmStatic
    fun main(args: Array<String>) {
        println("ShopBackend starting...")

        // Parse CLI args
        val dbPath = parseDbArg(args)
        if (!dbPath.isNullOrBlank()) println("Using database path: $dbPath")

        // Create DB
        // IMPORTANT: don't pass an empty string to DataBase(..), because File("") resolves to the
        // current directory and SQLite will fail with SQLITE_CANTOPEN_ISDIR.
        val db = if (!dbPath.isNullOrBlank()) DataBase(dbPath) else DataBase()
        println("Database initialized.")

        // Defensive cleanup: terminate any stale active calls from a previous run.
        // We use a threshold to avoid killing truly ongoing calls during a restart.
        runCatching {
            val terminated = db.terminateActiveCalls(
                olderThanMs = 30 * 60 * 1000L,
                note = "startup_cleanup",
            )
            if (terminated > 0) println("Startup cleanup: terminated $terminated stale active calls")
        }.onFailure { e ->
            println("Startup cleanup error: ${e.message}")
        }

        // Demo hash
        val hash = BCrypt.hashpw("1234", BCrypt.gensalt(12))
        println("Generated bcrypt hash: $hash")

        // Instantiate route handlers
        val webAdmin = WebAdmin(db)
        val customerApi = CustomerApi(db)
        val mobileApi = MobileApi(db)

        // Initialize chatbot
        val chatbotConfig = ChatbotConfig(
            twilioAccountSid = System.getenv("TWILIO_ACCOUNT_SID") ?: "",
            twilioAuthToken = System.getenv("TWILIO_AUTH_TOKEN") ?: "",
            twilioFromNumber = System.getenv("TWILIO_FROM_NUMBER") ?: "",
            lmStudioUrl = System.getenv("LM_STUDIO_URL") ?: "http://localhost:1234/v1",
            lmStudioModel = System.getenv("LM_MODEL") ?: "essentialai/rnj-1"
        )
        val chatbotService = TwilioChatbotService(db, chatbotConfig)
        println("Chatbot initialized. LM Studio: ${chatbotConfig.lmStudioUrl}")

        // Background cleanup
        startCleanupScheduler(db)

        // Server
        val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
        val host = "0.0.0.0"

        println("ShopBackend listening on $host:$port")

        embeddedServer(Netty, port = port, host = host) {
            install(ContentNegotiation) {
                json(Json { prettyPrint = false })
            }
            install(Sessions) {
                cookie<WebAdmin.AdminSession>("ADMIN_SESSION")
            }
            JwtConfig.install(this)

            routing {
                route("/") { webAdmin.setupRoutes(this) }
                route("/api") { customerApi.setupRoutes(this) }
                // MobileApi defines its own absolute paths like /api/mobile/...
                // so we mount it at root to avoid /mobile/api/mobile/... double-prefix.
                mobileApi.setupRoutes(this)
                twilioRoutes(db, chatbotService)
                twilioVoiceRoutes(db)
                chatTestRoutes(db, chatbotService)
                chatApiRoutes(db, chatbotService)
            }
        }.start(wait = true)
    }

    private fun parseDbArg(args: Array<String>): String? {
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
            Thread(r, "cleanup-scheduler").apply { isDaemon = true }
        }
        val executor = Executors.newSingleThreadScheduledExecutor(tf)
        executor.scheduleAtFixedRate({
            try {
                db.deleteOldBookingTokens()

                // Also clear stuck active calls (e.g. if Twilio status callbacks were missed).
                val terminated = db.terminateActiveCalls(
                    olderThanMs = 30 * 60 * 1000L,
                    note = "scheduled_cleanup",
                )
                if (terminated > 0) println("Cleanup: terminated $terminated stale active calls")
            } catch (e: Exception) {
                println("Error during cleanup: ${e.message}")
            }
        }, 0, 1, TimeUnit.HOURS)
    }
}
