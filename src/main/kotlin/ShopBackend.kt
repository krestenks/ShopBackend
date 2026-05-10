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
import twilio.TwilioSmsService
import twilio.ChatbotConfig
import twilio.twilioRoutes
import twilio.chatTestRoutes
import twilio.chatApiRoutes
import twilio.twilioVoiceRoutes
import twilio.smsRoutes
import callapp.CallAppRapidApiConfig
import callapp.CallAppRapidApiClient
import callapp.CallAppScreeningService
import kotlinx.coroutines.runBlocking

object ShopBackend {
    @JvmStatic
    fun main(args: Array<String>) {
        println("ShopBackend starting...")

        // ── Env diagnostics ───────────────────────────────────────────────────
        printEnvDiagnostics()

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

        val smsService = TwilioSmsService(
            accountSid = chatbotConfig.twilioAccountSid,
            authToken  = chatbotConfig.twilioAuthToken,
        )

        // Background cleanup
        startCleanupScheduler(db)

        // Optional CallApp phone-name screening (set CALLAPP_RAPIDAPI_KEY env var to enable)
        val callAppApiKey = System.getenv("CALLAPP_RAPIDAPI_KEY")
        if (!callAppApiKey.isNullOrBlank()) {
            val callAppConfig = CallAppRapidApiConfig(apiKey = callAppApiKey)
            val callAppClient = CallAppRapidApiClient(callAppConfig)
            val callAppScreening = CallAppScreeningService(db = db, client = callAppClient)
            startCallAppScreeningScheduler(callAppScreening)
            println("[CallApp] Screening enabled. host=${callAppConfig.host} timeout=${callAppConfig.timeoutMs}ms — scheduler: first run in 30s, then every 6h.")
        } else {
            println("[CallApp] Screening DISABLED — CALLAPP_RAPIDAPI_KEY not set.")
        }

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
                cookie<SetupAppRoutes.SetupAppSession>("SETUP_APP_SESSION")
            }
            JwtConfig.install(this)

            routing {
                route("/") { webAdmin.setupRoutes(this) }
                route("/api") { customerApi.setupRoutes(this) }
                // MobileApi defines its own absolute paths like /api/mobile/...
                // so we mount it at root to avoid /mobile/api/mobile/... double-prefix.
                mobileApi.setupRoutes(this)

                // Self-hosted Android APK update endpoints (JWT authenticated)
                appUpdateRoutes()

                // Simple installer page for provisioning new handsets (session + HTML form)
                SetupAppRoutes(db).install(this)

                twilioRoutes(db, chatbotService)
                twilioVoiceRoutes(db)
                chatTestRoutes(db, chatbotService)
                chatApiRoutes(db, chatbotService)
                smsRoutes(db, smsService)
            }
        }.start(wait = true)
    }

    /** Prints each relevant env var with the first 4 chars visible and the rest masked. */
    private fun printEnvDiagnostics() {
        val vars = listOf(
            "PORT",
            "PUBLIC_BASE_URL",
            "PUBLIC_BOOKING_URL",
            "TWILIO_ACCOUNT_SID",
            "TWILIO_AUTH_TOKEN",
            "TWILIO_FROM_NUMBER",
            "CALLAPP_RAPIDAPI_KEY",
            "ADMIN_USERNAME",
            "ADMIN_PASSWORD",
            "LM_STUDIO_URL",
            "LM_MODEL",
        )
        println("──── Environment ────────────────────────────────")
        for (name in vars) {
            val value = System.getenv(name)
            val display = when {
                value.isNullOrBlank() -> "missing"
                value.length <= 4     -> "set (${value.length} chars)"
                else                  -> "set ${value.take(4)}… (${value.length} chars)"
            }
            println("  $name = $display")
        }
        println("─────────────────────────────────────────────────")
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

    /**
     * Runs [CallAppScreeningService.screenPendingCustomers] every 6 hours.
     * The first run is delayed by 30 seconds so startup I/O settles first.
     */
    private fun startCallAppScreeningScheduler(service: CallAppScreeningService) {
        val tf = ThreadFactory { r ->
            Thread(r, "callapp-screening").apply { isDaemon = true }
        }
        val executor = Executors.newSingleThreadScheduledExecutor(tf)
        executor.scheduleAtFixedRate({
            try {
                runBlocking { service.screenPendingCustomers() }
            } catch (e: Exception) {
                println("[CallAppScreening] Scheduler error: ${e.message}")
            }
        }, 30, 6 * 60 * 60L, TimeUnit.SECONDS)
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

                // Data-retention: delete old SMS + call logs, then prune dead customer profiles.
                val commsDeleted = db.deleteExpiredCommunications()
                val customersDeleted = db.deleteExpiredCustomers()
                if (commsDeleted + customersDeleted > 0) {
                    println("[DataRetention] Cleanup cycle: $commsDeleted communications, $customersDeleted customer profiles deleted.")
                }
            } catch (e: Exception) {
                println("Error during cleanup: ${e.message}")
            }
        }, 0, 1, TimeUnit.HOURS)
    }
}
