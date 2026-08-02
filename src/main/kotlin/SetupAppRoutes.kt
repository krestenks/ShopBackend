import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
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
    /** Control-plane admin API base (reachable from this edge box), e.g. http://192.168.0.2:8090. */
    private val controlPlaneUrl: String = System.getenv("CONTROL_PLANE_URL")?.trimEnd('/') ?: "",
    /** This edge box's per-tenant control-plane token (scopes invites to this tenant). */
    private val edgeToken: String = System.getenv("CONTROL_PLANE_EDGE_TOKEN") ?: "",
) {
    @Serializable
    data class SetupAppSession(
        val role: String,
        val userId: Int,
        val username: String,
    )

    // ── Control-plane client (for the one-tap add-phone flow) ─────────────────
    private val cpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 8_000 }
    }

    private data class Onboarding(val deepLink: String, val downloadUrl: String, val expiration: String?)

    /** Asks the control plane (scoped to this tenant by [edgeToken]) for a join invite + a one-time
     *  APK download link. */
    private suspend fun requestOnboarding(label: String): Onboarding {
        val invBody = cpClient.post("$controlPlaneUrl/api/invites") {
            header(HttpHeaders.Authorization, "Bearer $edgeToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("deviceLabel", label) }.toString())
        }.bodyAsText()
        val inv = Json.parseToJsonElement(invBody).jsonObject
        val deepLink = inv["deepLink"]?.jsonPrimitive?.contentOrNull
            ?: error("control plane invite failed: ${invBody.take(200)}")
        val dlBody = cpClient.post("$controlPlaneUrl/api/download-token") {
            header(HttpHeaders.Authorization, "Bearer $edgeToken")
        }.bodyAsText()
        val dl = Json.parseToJsonElement(dlBody).jsonObject
        val downloadUrl = dl["url"]?.jsonPrimitive?.contentOrNull
            ?: error("control plane download-token failed: ${dlBody.take(200)}")
        return Onboarding(deepLink, downloadUrl, inv["expiration"]?.jsonPrimitive?.contentOrNull)
    }

    // ── Tenant device list / revoke (proxied to the control plane via the scoped edge token) ──
    @Serializable
    private data class CpNode(
        val id: String,
        val name: String = "",
        val user: String = "",
        val tags: List<String> = emptyList(),
        val addresses: List<String> = emptyList(),
        val online: Boolean = false,
        val label: String? = null,
    )
    @Serializable
    private data class CpNodes(val nodes: List<CpNode> = emptyList())

    private val cpJson = Json { ignoreUnknownKeys = true }

    /** Lists this tenant's *phones*. The control plane scopes the result to this edge's tenant via
     *  [edgeToken]; we additionally hide the tenant's own edge/server node (named `edge-{ownerId}`)
     *  so it can't be shown or accidentally revoked from this handset-management page. */
    private suspend fun listDevices(): List<CpNode> {
        val body = cpClient.get("$controlPlaneUrl/api/nodes") {
            header(HttpHeaders.Authorization, "Bearer $edgeToken")
        }.bodyAsText()
        return cpJson.decodeFromString(CpNodes.serializer(), body).nodes
            .filterNot { Regex("^edge-\\d+$").matches(it.name) }
    }

    /** Sets (or clears, when blank) a phone's friendly name via the scoped control-plane token. */
    private suspend fun setDeviceLabel(id: String, label: String): Boolean {
        val resp = cpClient.post("$controlPlaneUrl/api/nodes/$id/label") {
            header(HttpHeaders.Authorization, "Bearer $edgeToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("label", label) }.toString())
        }
        return resp.status.isSuccess()
    }

    /** Revokes a node: [remove] deletes it (must re-onboard), otherwise expires (logs out). */
    private suspend fun revokeDevice(id: String, remove: Boolean): Boolean {
        val resp = if (remove)
            cpClient.delete("$controlPlaneUrl/api/nodes/$id") {
                header(HttpHeaders.Authorization, "Bearer $edgeToken")
            }
        else
            cpClient.post("$controlPlaneUrl/api/nodes/$id/expire") {
                header(HttpHeaders.Authorization, "Bearer $edgeToken")
            }
        return resp.status.isSuccess()
    }

    private fun String.enc() = URLEncoder.encode(this, "UTF-8")

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

    private fun sha256(f: File): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val r = ins.read(buf); if (r <= 0) break; d.update(buf, 0, r) }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /** Base URL for the OTA apkUrl. Reuses the host of the currently-published apkUrl (known-good
     *  tailnet MagicDNS), else APP_UPDATE_BASE_URL, else PUBLIC_BASE_URL. */
    private fun currentUpdateBase(): String {
        readVersionInfo().apkUrl?.let { existing ->
            val idx = existing.indexOf("/api/app/download/")
            if (idx > 0) return existing.substring(0, idx)
        }
        return System.getenv("APP_UPDATE_BASE_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: baseUrl
    }

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
        table.devices { width:100%; margin:8px 0; border:1px solid var(--border); border-radius:12px; overflow:hidden; }
        table.devices th, table.devices td { border-bottom:1px solid rgba(255,255,255,0.06); padding:8px 10px; text-align:left; font-size:0.9em; }
        table.devices th { background:rgba(255,255,255,0.03); color:var(--muted); text-transform:uppercase; font-size:12px; letter-spacing:0.6px; font-weight:700; }
        .dev-online { color:var(--ok); font-weight:600; }
        .dev-offline { color:var(--muted); }
        .btn.danger { background:#e53935; color:#fff; }
        table.devices form { display:inline; margin:0 2px; }
        table.devices .btn { padding:4px 10px; font-size:0.85em; }
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

    /**
     * Page layout for /setup-app pages. Platform admins get the SAME unified, grouped admin sidebar
     * as the rest of the backend (so these pages feel like one app). Non-admin self-service users
     * (a manager installing on their own handset) get a minimal Install/Logout nav.
     */
    private suspend fun ApplicationCall.respondSetupPage(
        titleText: String,
        activePath: String? = null,
        bodyContent: FlowContent.() -> Unit,
    ) {
        val isAdmin = sessions.get<WebAdmin.AdminSession>() != null
        respondHtml {
            setupHead(titleText)
            body {
                div("layout") {
                    if (isAdmin) {
                        adminSidebar(activePath, logoutHref = "/setup-app/logout")
                    } else {
                        div("sidebar") {
                            div("brand") {
                                div {
                                    div("brand-title") { +"ShopManager" }
                                    div("brand-sub") { +"Install" }
                                }
                            }
                            div("nav") {
                                a(href = "/setup-app/download", classes = if (activePath == "/setup-app/download") "active" else null) {
                                    span { +"📲" }; span { +"Install app" }
                                }
                                div("spacer") {}
                                a(href = "/setup-app/logout") { span { +"🚪" }; span { +"Logout" } }
                            }
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
                // Also accept a platform AdminSession as valid auth for all /setup-app pages
                val hasAdminSession = call.sessions.get<WebAdmin.AdminSession>() != null
                if (!isPublic && call.sessions.get<SetupAppSession>() == null && !hasAdminSession) {
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
                // Platform admin is already authenticated — auto-create a SetupAppSession
                // so they can access the install page without a separate login.
                val adminSession = call.sessions.get<WebAdmin.AdminSession>()
                if (adminSession != null) {
                    call.sessions.set(SetupAppSession(role = "admin", userId = 0, username = adminSession.username))
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

                // ── 1. Check platform admin credentials first ──────────────────
                val envUser = System.getenv("ADMIN_USERNAME")?.trim()?.takeIf { it.isNotBlank() }
                val envPass = System.getenv("ADMIN_PASSWORD")?.trim()?.takeIf { it.isNotBlank() }
                val allowedAdminUsername = envUser ?: "admin"
                val expectedHash = "\$2a\$12\$bRyq/lqNzQbmYGAzS2V2qexIOd3es/8.URdwPmcamFTBGieqsodpW"
                val isAdminLogin = username == allowedAdminUsername && (
                    if (envPass != null) password == envPass
                    else org.mindrot.jbcrypt.BCrypt.checkpw(password, expectedHash)
                )
                if (isAdminLogin) {
                    println("[SetupApp/login] Platform admin signed in — creating SetupAppSession")
                    call.sessions.set(SetupAppSession(role = "admin", userId = 0, username = username))
                    call.respondRedirect("/setup-app/download")
                    return@post
                }

                // ── 2. Check owner account credentials ─────────────────────────
                val ownerResult = db.authenticateOwnerAccount(username, password)
                if (ownerResult != null) {
                    val (ownerId, _) = ownerResult
                    val owner = db.getOwnerById(ownerId)
                    println("[SetupApp/login] Owner '${owner?.name}' signed in — creating SetupAppSession")
                    call.sessions.set(SetupAppSession(role = "owner", userId = ownerId, username = username))
                    call.respondRedirect("/setup-app/download")
                    return@post
                }

                // ── 3. Check manager / shop / app-account credentials ──────────
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
                // Ensure a SetupAppSession exists — auto-create one for platform admins
                // who navigated here directly (e.g. via a bookmarked URL).
                if (call.sessions.get<SetupAppSession>() == null) {
                    val adminSession = call.sessions.get<WebAdmin.AdminSession>()
                    if (adminSession != null) {
                        call.sessions.set(SetupAppSession(role = "admin", userId = 0, username = adminSession.username))
                    }
                }
                val session = call.sessions.get<SetupAppSession>()!!
                val v = readVersionInfo()
                val tokenParam = call.request.queryParameters["token"]

                val qrTokenData: Pair<String, DataBase.InstallToken>? = if (tokenParam != null) {
                    val tok = db.getInstallToken(tokenParam)
                    if (tok?.isValid == true) tokenParam to tok else null
                } else null

                call.respondSetupPage("Install ShopManager App", "/setup-app/download") {
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

            // ── GET /setup-app/add-phone — one-tap onboarding: install QR + join QR ──
            get("/setup-app/add-phone") {
                if (controlPlaneUrl.isBlank() || edgeToken.isBlank()) {
                    call.respondSetupPage("Add a phone", "/setup-app/add-phone") {
                        p { +"Onboarding isn't configured on this edge box." }
                        p("hint") { +"Set CONTROL_PLANE_URL and CONTROL_PLANE_EDGE_TOKEN, then restart the backend." }
                    }
                    return@get
                }
                val label = call.request.queryParameters["label"]?.trim()?.takeIf { it.isNotBlank() }
                // No name yet → ask for one first (the name shows in the Phones list once it joins).
                if (label == null) {
                    call.respondSetupPage("Add a phone", "/setup-app/add-phone") {
                        p("hint") { +"Name the phone, then generate its install + join codes. The name appears on the Phones page." }
                        form(action = "/setup-app/add-phone", method = FormMethod.get) {
                            label { +"Phone name" }
                            textInput { name = "label"; attributes["placeholder"] = "e.g. Front desk"; attributes["required"] = "true"; attributes["autofocus"] = "true" }
                            br()
                            submitInput(classes = "btn primary") { value = "Generate codes" }
                        }
                    }
                    return@get
                }
                val ob = runCatching { requestOnboarding(label) }.getOrElse { e ->
                    call.respondSetupPage("Add a phone", "/setup-app/add-phone") {
                        p { +"Couldn't reach the control plane." }
                        p("hint") { +(e.message ?: "unknown error") }
                        a(href = "/setup-app/add-phone", classes = "btn") { +"Retry" }
                    }
                    return@get
                }
                call.respondSetupPage("Add a phone", "/setup-app/add-phone") {
                    p { strong { +"Phone: " }; +label }
                    p("hint") { +"On the new phone, scan these in order:" }
                    h3 { +"1 — Install the app" }
                    div("qr-wrap") {
                        img(src = "/setup-app/add-phone/qr.png?data=${ob.downloadUrl.enc()}", alt = "Install QR")
                        p { small { +ob.downloadUrl } }
                        p("qr-expiry") { +"Single-use install link" }
                    }
                    hr {}
                    h3 { +"2 — Join (after installing & opening the app)" }
                    div("qr-wrap") {
                        img(src = "/setup-app/add-phone/qr.png?data=${ob.deepLink.enc()}", alt = "Join QR")
                        ob.expiration?.let { p("qr-expiry") { +"Join code expires $it" } }
                    }
                    hr {}
                    h3 { +"Add another phone" }
                    form(action = "/setup-app/add-phone", method = FormMethod.get) {
                        label { +"Phone name" }
                        textInput { name = "label"; attributes["placeholder"] = "e.g. Back office"; attributes["required"] = "true" }
                        br()
                        submitInput(classes = "btn primary") { value = "Generate codes" }
                    }
                }
            }

            // ── GET /setup-app/add-phone/qr.png?data=… — QR for an arbitrary string ──
            get("/setup-app/add-phone/qr.png") {
                val data = call.request.queryParameters["data"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respondBytes(generateQrPng(data, size = 320), ContentType.Image.PNG)
            }

            // ── GET /setup-app/devices — this tenant's joined phones + revoke ──
            get("/setup-app/devices") {
                if (controlPlaneUrl.isBlank() || edgeToken.isBlank()) {
                    call.respondSetupPage("Devices", "/setup-app/devices") {
                        p { +"Device management isn't configured on this edge box." }
                        p("hint") { +"Set CONTROL_PLANE_URL and CONTROL_PLANE_EDGE_TOKEN, then restart the backend." }
                    }
                    return@get
                }
                val devices = runCatching { listDevices() }.getOrElse { e ->
                    call.respondSetupPage("Devices", "/setup-app/devices") {
                        p { +"Couldn't reach the control plane." }
                        p("hint") { +(e.message ?: "unknown error") }
                        a(href = "/setup-app/devices", classes = "btn") { +"Retry" }
                    }
                    return@get
                }
                call.respondSetupPage("Devices", "/setup-app/devices") {
                    p("hint") { +"Phones joined to your secure network. \"Log out\" disconnects a phone (it can rejoin); \"Remove\" revokes it (must be re-onboarded)." }
                    if (devices.isEmpty()) {
                        p { +"No phones have joined yet." }
                    } else {
                        table(classes = "devices") {
                            tr { th { +"Phone" }; th { +"Address" }; th { +"Status" }; th { +"Rename" }; th { +"Actions" } }
                            devices.sortedBy { (it.label ?: it.name).lowercase() }.forEach { d ->
                                tr {
                                    td {
                                        +(d.label ?: d.name)
                                        if (d.label != null) { br(); small("dev-offline") { +d.name } }
                                    }
                                    td { +(d.addresses.firstOrNull { it.startsWith("100.") } ?: d.addresses.firstOrNull() ?: "—") }
                                    td {
                                        if (d.online) span("dev-online") { +"● online" }
                                        else span("dev-offline") { +"○ offline" }
                                    }
                                    td {
                                        form(action = "/setup-app/devices/${d.id}/label", method = FormMethod.post) {
                                            textInput { name = "label"; value = d.label ?: ""; attributes["placeholder"] = "name"; attributes["style"] = "width:130px;display:inline-block;margin-right:4px;padding:4px 8px" }
                                            submitInput(classes = "btn") { value = "Save" }
                                        }
                                    }
                                    td {
                                        form(action = "/setup-app/devices/${d.id}/logout", method = FormMethod.post) {
                                            submitInput(classes = "btn") { value = "Log out" }
                                        }
                                        form(action = "/setup-app/devices/${d.id}/remove", method = FormMethod.post) {
                                            attributes["onsubmit"] =
                                                "return confirm('Remove ${d.name}? The phone must be re-onboarded to return.')"
                                            submitInput(classes = "btn danger") { value = "Remove" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    form(action = "/setup-app/devices", method = FormMethod.get) {
                        submitInput(classes = "btn") { value = "⟳ Refresh" }
                    }
                }
            }

            // ── POST /setup-app/devices/{id}/logout | /remove — revoke (scoped to tenant) ──
            post("/setup-app/devices/{id}/logout") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                runCatching { revokeDevice(id, remove = false) }
                call.respondRedirect("/setup-app/devices")
            }
            post("/setup-app/devices/{id}/remove") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                runCatching { revokeDevice(id, remove = true) }
                call.respondRedirect("/setup-app/devices")
            }
            post("/setup-app/devices/{id}/label") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val newLabel = call.receiveParameters()["label"]?.trim() ?: ""
                runCatching { setDeviceLabel(id, newLabel) }
                call.respondRedirect("/setup-app/devices")
            }

            // ── GET /setup-app/updates — OTA release management (publish version.json) ──
            get("/setup-app/updates") {
                val isAdmin = call.sessions.get<WebAdmin.AdminSession>() != null ||
                        call.sessions.get<SetupAppSession>()?.role == "admin"
                if (!isAdmin) { call.respondRedirect("/setup-app/download"); return@get }
                val v = readVersionInfo()
                val apks = apkDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()
                call.respondSetupPage("App updates", "/setup-app/updates") {
                    div("version-box") {
                        if (v.versionName != null) {
                            p { strong { +"Currently published: " }; +"${v.versionName} (build ${v.versionCode ?: "?"})" }
                            if (v.apkFilename != null) p { +"File: ${v.apkFilename}" }
                            if (v.sha256 != null) p { small { +"sha256 ${v.sha256.take(16)}…" } }
                            if (v.required == true) p { span("badge-required") { +"Required update" } }
                            if (v.releaseNotes != null) p { em { +v.releaseNotes } }
                        } else {
                            p { +"No update is currently published." }
                        }
                    }
                    hr {}
                    h3 { +"Publish a new version" }
                    p("hint") { +"Upload a RELEASE-signed APK (same key as installed phones). This writes version.json; phones self-update on next launch or via ‘Check for updates’." }
                    form(action = "/setup-app/updates/publish", method = FormMethod.post, encType = FormEncType.multipartFormData) {
                        label { +"APK file" }
                        fileInput { name = "apk"; attributes["accept"] = ".apk"; attributes["required"] = "true" }
                        label { +"Version name (e.g. 1.0.43)" }
                        textInput { name = "versionName"; attributes["required"] = "true" }
                        label { +"Version code (integer, higher than ${v.versionCode ?: 0})" }
                        textInput { name = "versionCode"; attributes["inputmode"] = "numeric"; attributes["required"] = "true" }
                        label { +"Release notes" }
                        textArea { name = "releaseNotes"; rows = "3" }
                        label { checkBoxInput { name = "required" }; +" Force this update (required)" }
                        br()
                        submitInput(classes = "btn primary") { value = "⬆ Publish update" }
                    }
                    hr {}
                    h3 { +"APKs on the server" }
                    if (apks.isEmpty()) {
                        p { +"None uploaded yet." }
                    } else {
                        table(classes = "devices") {
                            tr { th { +"File" }; th { +"Size" } }
                            apks.forEach { f ->
                                tr { td { +f.name }; td { +"${f.length() / 1_000_000} MB" } }
                            }
                        }
                    }
                }
            }

            // ── POST /setup-app/updates/publish — save APK + write version.json (admin only) ──
            post("/setup-app/updates/publish") {
                val isAdmin = call.sessions.get<WebAdmin.AdminSession>() != null ||
                        call.sessions.get<SetupAppSession>()?.role == "admin"
                if (!isAdmin) {
                    call.respond(HttpStatusCode.Forbidden, "Publishing updates is admin-only.")
                    return@post
                }
                var versionCode: Long? = null
                var versionName = ""
                var releaseNotes = ""
                var required = false
                var tmpFile: File? = null

                call.receiveMultipart().forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> when (part.name) {
                            "versionCode" -> versionCode = part.value.trim().toLongOrNull()
                            "versionName" -> versionName = part.value.trim()
                            "releaseNotes" -> releaseNotes = part.value.trim()
                            "required" -> required = part.value == "on" || part.value.equals("true", true)
                        }
                        is PartData.FileItem -> {
                            apkDir.mkdirs()
                            val t = File(apkDir, "upload-${System.nanoTime()}.tmp")
                            part.streamProvider().use { input -> t.outputStream().use { input.copyTo(it) } }
                            tmpFile = t
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                val tmp = tmpFile
                if (tmp == null || versionName.isBlank() || versionCode == null) {
                    tmp?.delete()
                    call.respondSetupPage("App updates", "/setup-app/updates") {
                        p { +"❌ Need an APK file, a version name and a numeric version code." }
                        a(href = "/setup-app/updates", classes = "btn") { +"Back" }
                    }
                    return@post
                }

                val finalName = "shopmanager-$versionName.apk"
                val finalFile = File(apkDir, finalName)
                tmp.copyTo(finalFile, overwrite = true)
                tmp.delete()

                val sha = sha256(finalFile)
                val apkUrl = "${currentUpdateBase().trimEnd('/')}/api/app/download/$finalName"
                val manifest = buildJsonObject {
                    put("versionCode", versionCode)
                    put("versionName", versionName)
                    put("apkUrl", apkUrl)
                    put("sha256", sha)
                    put("required", required)
                    put("releaseNotes", releaseNotes)
                }
                File(apkDir, "version.json").writeText(
                    Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), manifest),
                    Charsets.UTF_8,
                )
                call.respondRedirect("/setup-app/updates")
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
