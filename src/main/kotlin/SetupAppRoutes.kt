import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.respondHtml
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.html.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * /setup-app      — Session-authenticated install page for provisioning handsets.
 *                   Generates one-time QR codes that expire after 30 min.
 * /setup-app/install/t/{token}     — Public (token-protected) install page.
 * /setup-app/install/t/{token}/apk — Public download; consumes the token on first use.
 */
class SetupAppRoutes(
    private val db: DataBase,
    private val apkDir: File = File("data/apk"),
    private val baseUrl: String = System.getenv("PUBLIC_BASE_URL")?.trimEnd('/') ?: "",
) {
    @Serializable
    data class SetupAppSession(
        val role: String,
        val userId: Int,
        val username: String,
    )

    private val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Copenhagen"))

    // ── Version info from version.json ───────────────────────────────────────

    private data class VersionInfo(
        val versionCode: Int?,
        val versionName: String?,
        val apkUrl: String?,
        val apkFilename: String?,
        val sha256: String?,
        val required: Boolean?,
        val releaseNotes: String?,
    )

    private fun readVersionInfo(): VersionInfo {
        val raw = File(apkDir, "version.json").takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: "{}"
        return runCatching {
            val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching null
            val apkUrl = (obj["apkUrl"] as? JsonPrimitive)?.content
            VersionInfo(
                versionCode  = (obj["versionCode"] as? JsonPrimitive)?.content?.toIntOrNull(),
                versionName  = (obj["versionName"] as? JsonPrimitive)?.content,
                apkUrl       = apkUrl,
                apkFilename  = apkUrl?.substringAfterLast('/'),
                sha256       = (obj["sha256"] as? JsonPrimitive)?.content,
                required     = (obj["required"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
                releaseNotes = (obj["releaseNotes"] as? JsonPrimitive)?.content,
            )
        }.getOrNull() ?: VersionInfo(null, null, null, null, null, null, null)
    }

    // ── QR code generation ────────────────────────────────────────────────────

    private fun generateQrPng(content: String, size: Int = 300): ByteArray {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                img.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
            }
        }
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        return baos.toByteArray()
    }

    // ── HTML helpers ──────────────────────────────────────────────────────────

    private val extraCss = """
        .version-box { background:#1e2a3a; color:#e8edf3; border-radius:8px; padding:16px 20px; margin:12px 0; border-left:4px solid #4a90d9; }
        .version-box p { margin:4px 0; color:#e8edf3; }
        .version-box strong { color:#ffffff; }
        .qr-wrap { text-align:center; margin:20px 0; }
        .qr-wrap img { max-width:240px; border:1px solid #ddd; padding:8px; border-radius:8px; background:#fff; }
        .qr-expiry { font-size:0.85em; color:#666; margin-top:6px; }
        .badge-required { background:#e53935; color:#fff; border-radius:4px; padding:1px 6px; font-size:0.8em; }
    """.trimIndent()

    private fun HTML.setupHead(titleText: String) {
        head {
            meta { charset = "utf-8" }
            meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
            title { +titleText }
            link(rel = "stylesheet", href = "/static/admin.css", type = "text/css")
            style { unsafe { raw(extraCss) } }
        }
    }

    /** Full admin-style sidebar layout for authenticated setup-app pages. */
    private suspend fun ApplicationCall.respondSetupPage(
        titleText: String,
        bodyContent: FlowContent.() -> Unit,
    ) {
        respondHtml {
            setupHead(titleText)
            body {
                div("layout") {
                    div("sidebar") {
                        div("brand") {
                            div {
                                div("brand-title") { +"ShopManager" }
                                div("brand-sub") { +"Install" }
                            }
                        }
                        div("nav") {
                            a(href = "/") { span { +"🏠" }; span { +"Admin" } }
                            a(href = "/setup-app/download", classes = "active") { span { +"📲" }; span { +"Install app" } }
                            div("spacer") {}
                            a(href = "/setup-app/logout") { span { +"🚪" }; span { +"Logout" } }
                        }
                    }
                    div("main") {
                        div("page-header") { h1("page-title") { +titleText } }
                        div("panel") { bodyContent() }
                    }
                }
            }
        }
    }

    // ── Route installation ────────────────────────────────────────────────────

    fun install(r: Route) {
        r.route("") {

            // ── Auth guard for /setup-app/* (except login, public install, static) ──
            intercept(ApplicationCallPipeline.Plugins) {
                val path = call.request.path()
                if (!path.startsWith("/setup-app")) return@intercept
                val isPublic = path == "/setup-app" ||
                        path == "/setup-app/login" ||
                        path.startsWith("/setup-app/install/t/") ||
                        path.startsWith("/static/")
                if (!isPublic && call.sessions.get<SetupAppSession>() == null) {
                    call.respondRedirect("/setup-app")
                    finish()
                }
            }

            // ── GET /setup-app — login form ────────────────────────────────────
            get("/setup-app") {
                if (call.sessions.get<SetupAppSession>() != null) {
                    call.respondRedirect("/setup-app/download")
                    return@get
                }
                call.respondHtml {
                    setupHead("Setup App")
                    body {
                        div("center") {
                            div("card") {
                                h2 { +"Setup App" }
                                p("hint") { +"Sign in to provision a new handset." }
                                form(action = "/setup-app/login", method = FormMethod.post) {
                                    label { +"Username" }
                                    textInput { name = "username"; placeholder = "manager01" }
                                    label { +"Password" }
                                    passwordInput { name = "password"; placeholder = "••••" }
                                    br()
                                    submitInput(classes = "btn primary") { value = "Login" }
                                }
                            }
                        }
                    }
                }
            }

            // ── POST /setup-app/login ──────────────────────────────────────────
            post("/setup-app/login") {
                val params = call.receiveParameters()
                val username = params["username"]?.trim().orEmpty()
                val password = params["password"].orEmpty()

                val appAccount = db.authenticateAppAccount(username, password)
                val manager = if (appAccount == null) db.authenticateManager(username, password) else null
                val shop = if (appAccount == null && manager == null) db.authenticateShop(username, password) else null

                val session: SetupAppSession? = when {
                    appAccount != null -> {
                        val (refType, refId) = appAccount
                        SetupAppSession(role = if (refType == "shop") "shop" else "manager", userId = refId, username = username)
                    }
                    manager != null -> SetupAppSession("manager", manager.id, username)
                    shop != null    -> SetupAppSession("shop",    shop.id,    username)
                    else            -> null
                }

                if (session == null) {
                    call.respondHtml {
                        setupHead("Setup App")
                        body {
                            div("center") { div("card") {
                                h2 { +"Setup App" }
                                p("hint") { +"Invalid credentials." }
                                a(href = "/setup-app", classes = "btn") { +"Try again" }
                            } }
                        }
                    }
                    return@post
                }
                call.sessions.set(session)
                call.respondRedirect("/setup-app/download")
            }

            // ── GET /setup-app/logout ──────────────────────────────────────────
            get("/setup-app/logout") {
                call.sessions.clear<SetupAppSession>()
                call.respondRedirect("/setup-app")
            }

            // ── GET /setup-app/download — version info + QR generator ──────────
            get("/setup-app/download") {
                val session = call.sessions.get<SetupAppSession>()!!
                val v = readVersionInfo()
                val tokenParam = call.request.queryParameters["token"]

                val qrTokenData: Pair<String, DataBase.InstallToken>? = if (tokenParam != null) {
                    val tok = db.getInstallToken(tokenParam)
                    if (tok?.isValid == true) tokenParam to tok else null
                } else null

                call.respondSetupPage("Install ShopManager App") {
                    // Version info box
                    if (v.versionName != null) {
                        div("version-box") {
                            p { strong { +"Version: " }; +v.versionName }
                            if (v.versionCode != null) p { +"Build: ${v.versionCode}" }
                            if (v.apkFilename != null) p { +"File: ${v.apkFilename}" }
                            if (v.releaseNotes != null) p { em { +v.releaseNotes } }
                            if (v.required == true) p { span("badge-required") { +"Required update" } }
                        }
                    } else {
                        p { +"No release found yet. Upload an APK to data/apk/ first." }
                    }

                    hr {}

                    if (v.apkFilename != null) {
                        h3 { +"One-time install QR code" }
                        p("hint") { +"Generate a QR code valid for 24 hours. The link can only be used once." }

                        if (qrTokenData != null) {
                            val (tok, tokenInfo) = qrTokenData
                            val installUrl = "$baseUrl/setup-app/install/t/$tok"
                            val expiryDate = java.time.Instant.ofEpochMilli(tokenInfo.expiresAt)
                                .atZone(java.time.ZoneId.of("Europe/Copenhagen"))
                                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                            div("qr-wrap") {
                                img(src = "/setup-app/download/qr.png?token=$tok", alt = "QR install code")
                                p("qr-expiry") { +"Valid until $expiryDate — single use" }
                                p { small { +installUrl } }
                            }
                        }

                        form(action = "/setup-app/generate-token", method = FormMethod.post) {
                            submitInput(classes = "btn primary") { value = "⟳ Generate new QR code" }
                        }
                    }
                }
            }

            // ── GET /setup-app/download/qr.png?token=xxx ──────────────────────
            get("/setup-app/download/qr.png") {
                val token = call.request.queryParameters["token"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val tok = db.getInstallToken(token)
                if (tok == null || !tok.isValid) {
                    call.respond(HttpStatusCode.Gone)
                    return@get
                }
                val installUrl = "$baseUrl/setup-app/install/t/$token"
                val png = generateQrPng(installUrl, size = 300)
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respondBytes(png, ContentType.Image.PNG)
            }

            // ── POST /setup-app/generate-token ────────────────────────────────
            post("/setup-app/generate-token") {
                val session = call.sessions.get<SetupAppSession>()!!
                val v = readVersionInfo()
                val apkFilename = v.apkFilename
                if (apkFilename.isNullOrBlank()) {
                    call.respond(HttpStatusCode.ServiceUnavailable, "No APK available")
                    return@post
                }
                val token = db.createInstallToken(
                    apkFilename  = apkFilename,
                    versionCode  = v.versionCode ?: 0,
                    versionName  = v.versionName ?: "?",
                    createdBy    = session.username,
                    ttlMillis    = 24 * 60 * 60 * 1000L,
                )
                call.respondRedirect("/setup-app/download?token=$token")
            }

            // ── GET /setup-app/install/t/{token} — public install page ─────────
            get("/setup-app/install/t/{token}") {
                val rawToken = call.parameters["token"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val tok = db.getInstallToken(rawToken)

                call.respondHtml {
                    setupHead("Install ShopManager")
                    body {
                        div("center") {
                            div("card") {
                                h2 { +"Install ShopManager" }
                                when {
                                    tok == null || tok.isExpired -> {
                                        p { +"This install link has expired or is not valid." }
                                        p("hint") { +"Ask an admin to generate a new QR code." }
                                    }
                                    tok.isUsed -> {
                                        p { +"This install link has already been used." }
                                        p("hint") { +"Ask an admin to generate a new QR code." }
                                    }
                                    else -> {
                                        div("version-box") {
                                            p { strong { +"Version: " }; +tok.versionName }
                                            p { +"Build: ${tok.versionCode}" }
                                            p { +"File: ${tok.apkFilename}" }
                                        }
                                        p("hint") { +"Tap the button below to download and install the APK." }
                                        p("hint") { +"This link works once and expires 24 hours after generation." }
                                        a(
                                            href = "/setup-app/install/t/$rawToken/apk",
                                            classes = "btn primary",
                                        ) { +"Download ShopManager APK" }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── GET /setup-app/install/t/{token}/apk — public download (consumes token) ──
            get("/setup-app/install/t/{token}/apk") {
                val rawToken = call.parameters["token"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val tok = db.getInstallToken(rawToken)

                if (tok == null || !tok.isValid) {
                    call.respond(HttpStatusCode.Gone, "This install link has expired or has already been used.")
                    return@get
                }

                val f = File(apkDir, tok.apkFilename)
                if (!f.exists() || !f.isFile) {
                    call.respond(HttpStatusCode.NotFound, "APK file not found on server.")
                    return@get
                }

                // Consume the token — one-time use
                val consumed = db.consumeInstallToken(rawToken)
                if (!consumed) {
                    call.respond(HttpStatusCode.Gone, "This install link has already been used.")
                    return@get
                }

                call.response.headers.append(HttpHeaders.ContentType, "application/vnd.android.package-archive")
                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"${tok.apkFilename}\"")
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respondFile(f)
            }

            // ── Legacy session-protected APK download (still works) ────────────
            get("/setup-app/apk/{filename}") {
                val fileName = call.parameters["filename"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                if (fileName.contains("..") || fileName.contains('\\') || fileName.contains('/')) {
                    return@get call.respond(HttpStatusCode.BadRequest)
                }
                val f = File(apkDir, fileName)
                if (!f.exists() || !f.isFile) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                call.response.headers.append(HttpHeaders.ContentType, "application/vnd.android.package-archive")
                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.respondFile(f)
            }
        }
    }
}
