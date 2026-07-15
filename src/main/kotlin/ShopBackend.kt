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
import chatbot.ChatbotService
import chatbot.ChatbotConfig
import chatbot.chatTestRoutes
import chatbot.chatApiRoutes
import telephony.smsRoutes
import callapp.CallAppRapidApiConfig
import callapp.CallAppRapidApiClient
import callapp.CallAppScreeningService
import asterisk.AmiClient
import asterisk.AriClient
import asterisk.AsteriskAdmin
import asterisk.AsteriskConfig
import asterisk.AsteriskEventHandler
import asterisk.AsteriskProvisioner
import asterisk.AsteriskTelephonyService
import asterisk.DialplanWriter
import asterisk.ModemScanner
import asterisk.PromptGenerator
import asterisk.QuectelConfigWriter
import asterisk.internalTelephonyRoutes
import telephony.TelephonyService
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

        // Ensure owner-scoped DB indexes (idempotent)
        runCatching { db.ensureOwnerIndexes() }
            .onSuccess { println("Owner indexes ensured.") }
            .onFailure { e -> println("Owner index warning: ${e.message}") }
        // Retroactively link historical SMS messages that pre-date the auto-create logic.
        runCatching { db.backfillSmsCustomers() }
            .onFailure { e -> println("SMS customer backfill warning: ${e.message}") }

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

        // LM Studio chatbot (disabled by default: LM Studio runs locally on the
        // dev machine). Set CHATBOT_ENABLED=true to re-enable the test pages/API.
        val chatbotEnabled = System.getenv("CHATBOT_ENABLED")?.equals("true", ignoreCase = true) == true
        val chatbotService: ChatbotService? = if (chatbotEnabled) {
            val chatbotConfig = ChatbotConfig(
                lmStudioUrl = System.getenv("LM_STUDIO_URL") ?: "http://localhost:1234/v1",
                lmStudioModel = System.getenv("LM_MODEL") ?: "essentialai/rnj-1"
            )
            ChatbotService(db, chatbotConfig).also {
                println("Chatbot initialized. LM Studio: ${chatbotConfig.lmStudioUrl}")
            }
        } else {
            println("Chatbot DISABLED — set CHATBOT_ENABLED=true to enable (requires reachable LM Studio).")
            null
        }

        // ── Telephony: self-hosted Asterisk/Quectel GSM stack ────────────────
        val asteriskConfig = AsteriskConfig.fromEnv()
        val amiClient = AmiClient(asteriskConfig).also { it.start() }
        AsteriskEventHandler(amiClient, db).start()
        val ariClient = AriClient(asteriskConfig)
        val modemScanner = ModemScanner(db, asteriskConfig, amiClient)
        val provisioner = AsteriskProvisioner(
            db = db,
            config = asteriskConfig,
            ariClient = ariClient,
            quectelConfigWriter = QuectelConfigWriter(asteriskConfig, amiClient),
            dialplanWriter = DialplanWriter(asteriskConfig, amiClient),
            promptGenerator = PromptGenerator(asteriskConfig),
            modemScanner = modemScanner,
        )
        val asteriskAdmin = AsteriskAdmin(
            config = asteriskConfig,
            amiClient = amiClient,
            provisioner = provisioner,
            modemScanner = modemScanner,
        )
        val telephonyService: TelephonyService = AsteriskTelephonyService(amiClient, asteriskConfig, db)
        println("[Telephony] Asterisk AMI ${asteriskConfig.amiHost}:${asteriskConfig.amiPort}, configs in ${asteriskConfig.configPath}")

        // Instantiate route handlers
        val webAdmin = WebAdmin(db, telephonyService, asteriskAdmin)
        val customerApi = CustomerApi(db, telephonyService)
        val mobileApi = MobileApi(db, telephonyService, asteriskAdmin)

        // Background cleanup
        startCleanupScheduler(db)

        // Optional CallApp phone-name screening (set CALLAPP_RAPIDAPI_KEY env var to enable)
        val callAppApiKey = System.getenv("CALLAPP_RAPIDAPI_KEY")
        val callAppScreening: CallAppScreeningService? = if (!callAppApiKey.isNullOrBlank()) {
            val callAppConfig = CallAppRapidApiConfig(apiKey = callAppApiKey)
            val callAppClient = CallAppRapidApiClient(callAppConfig)
            CallAppScreeningService(db = db, client = callAppClient).also { svc ->
                startCallAppScreeningScheduler(svc)
                println("[CallApp] Screening enabled. host=${callAppConfig.host} timeout=${callAppConfig.timeoutMs}ms — scheduler: first run in 30s, then every 6h.")
            }
        } else {
            println("[CallApp] Screening DISABLED — CALLAPP_RAPIDAPI_KEY not set.")
            null
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
                cookie<WebAdmin.OwnerSession>("OWNER_SESSION")
                cookie<WebAdmin.ImpersonationSession>("IMPERSONATION_SESSION")
                cookie<SetupAppRoutes.SetupAppSession>("SETUP_APP_SESSION")
            }
            JwtConfig.install(this, db)

            routing {
                route("/") {
                    webAdmin.setupRoutes(this)
                    financialReportRoutes(db)
                }
                route("/api") { customerApi.setupRoutes(this) }
                // MobileApi defines its own absolute paths like /api/mobile/...
                // so we mount it at root to avoid /mobile/api/mobile/... double-prefix.
                mobileApi.setupRoutes(this)

                // Mobile financial reports (JWT token in query param for WebView)
                mobileFinancialReportRoutes(db)

                // Self-hosted Android APK update endpoints (JWT authenticated)
                appUpdateRoutes()

                // Simple installer page for provisioning new handsets (session + HTML form)
                SetupAppRoutes(db).install(this)

                // Chatbot test pages/API (LM Studio) — only when enabled.
                if (chatbotService != null) {
                    chatTestRoutes(db, chatbotService)
                    chatApiRoutes(db, chatbotService)
                }
                smsRoutes(db, telephonyService, callAppScreening)

                // Asterisk dialplan → backend callbacks (inbound SMS/call, menu actions, provisioning)
                internalTelephonyRoutes(db, asteriskConfig, provisioner, telephonyService, callAppScreening)
            }
        }.start(wait = true)
    }

    /** Prints each relevant env var with the first 4 chars visible and the rest masked. */
    private fun printEnvDiagnostics() {
        val vars = listOf(
            "PORT",
            "PUBLIC_BASE_URL",
            "PUBLIC_BOOKING_URL",
            "CALLAPP_RAPIDAPI_KEY",
            "ADMIN_USERNAME",
            "ADMIN_PASSWORD",
            "LM_STUDIO_URL",
            "LM_MODEL",
            "ASTERISK_AMI_SECRET",
            "ASTERISK_ARI_PASSWORD",
            "ASTERISK_INTERNAL_SECRET",
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

                // Also clear stuck active calls (e.g. if hangup events were missed).
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
