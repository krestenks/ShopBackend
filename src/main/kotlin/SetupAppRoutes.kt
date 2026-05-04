import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import java.io.File

/**
 * Simple "Setup App" page for downloading the latest APK to a new handset.
 *
 * Requirements:
 * - User must login as shop or manager (same credentials as mobile app)
 * - Backend serves a simple HTML form (no JS build step)
 */
class SetupAppRoutes(
    private val db: DataBase,
    private val apkDir: File = File("data/apk"),
) {
    @Serializable
    data class SetupAppSession(
        val role: String, // "manager" | "shop"
        val userId: Int,
        val username: String,
    )

    private fun ApplicationCall.isSetupAppPublicPath(): Boolean {
        val path = request.path()
        return path == "/setup-app" || path == "/setup-app/login" || path.startsWith("/static/")
    }

    fun install(r: Route) {
        r.route("") {
            // Guard: require SetupAppSession for all /setup-app routes except login.
            intercept(ApplicationCallPipeline.Plugins) {
                val path = call.request.path()
                if (!path.startsWith("/setup-app")) return@intercept
                if (call.isSetupAppPublicPath()) return@intercept

                if (call.sessions.get<SetupAppSession>() == null) {
                    call.respondRedirect("/setup-app")
                    finish()
                }
            }

            get("/setup-app") {
                val session = call.sessions.get<SetupAppSession>()
                if (session != null) {
                    call.respondRedirect("/setup-app/download")
                    return@get
                }

                call.respondHtml {
                    head {
                        meta { charset = "utf-8" }
                        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
                        title { +"Setup App" }
                        link(rel = "stylesheet", href = "/static/admin.css", type = "text/css")
                    }
                    body {
                        div("center") {
                            div("card") {
                                h2 { +"Setup App" }
                                p("hint") { +"Sign in to download the latest ShopManager APK." }
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

            post("/setup-app/login") {
                val params = call.receiveParameters()
                val username = params["username"]?.trim().orEmpty()
                val password = params["password"].orEmpty()

                // Reuse the same auth order as MobileApi:
                // 1) app_account table (preferred)
                // 2) managers table (backwards compat)
                // 3) shops table (legacy)
                val appAccount = db.authenticateAppAccount(username, password)
                val manager = if (appAccount == null) db.authenticateManager(username, password) else null
                val shop = if (appAccount == null && manager == null) db.authenticateShop(username, password) else null
                val session = when {
                    appAccount != null -> {
                        val (refType, refId) = appAccount
                        val role = if (refType == "shop") "shop" else "manager"
                        SetupAppSession(role = role, userId = refId, username = username)
                    }
                    manager != null -> SetupAppSession(role = "manager", userId = manager.id, username = username)
                    shop != null -> SetupAppSession(role = "shop", userId = shop.id, username = username)
                    else -> null
                }

                if (session == null) {
                    call.respondHtml {
                        head {
                            meta { charset = "utf-8" }
                            meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
                            title { +"Setup App" }
                            link(rel = "stylesheet", href = "/static/admin.css", type = "text/css")
                        }
                        body {
                            div("center") {
                                div("card") {
                                    h2 { +"Setup App" }
                                    p("hint") { +"Invalid credentials." }
                                    a(href = "/setup-app", classes = "btn") { +"Try again" }
                                }
                            }
                        }
                    }
                    return@post
                }

                call.sessions.set(session)
                call.respondRedirect("/setup-app/download")
            }

            get("/setup-app/logout") {
                call.sessions.clear<SetupAppSession>()
                call.respondRedirect("/setup-app")
            }

            /**
             * Shows the resolved current version + provides a download link.
             */
            get("/setup-app/download") {
                val raw = File(apkDir, "version.json").takeIf { it.exists() }?.readText(Charsets.UTF_8)
                val apkFileName = runCatching {
                    val el = Json.parseToJsonElement(raw ?: "{}")
                    val obj = el as? JsonObject
                    val url = (obj?.get("apkUrl") as? JsonPrimitive)?.content
                    url?.substringAfterLast('/')
                }.getOrNull()

                call.respondHtml {
                    head {
                        meta { charset = "utf-8" }
                        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
                        title { +"Download app" }
                        link(rel = "stylesheet", href = "/static/admin.css", type = "text/css")
                    }
                    body {
                        div("center") {
                            div("card") {
                                h2 { +"Download ShopManager" }
                                p("hint") { +"Download the latest APK and install it on this handset." }

                                if (apkFileName.isNullOrBlank()) {
                                    p { +"version.json is missing or does not contain apkUrl." }
                                } else {
                                    a(
                                        href = "/setup-app/apk/$apkFileName",
                                        classes = "btn primary",
                                    ) { +"Download APK" }
                                    p("hint") {
                                        +"File: $apkFileName"
                                    }
                                }

                                hr {}
                                a(href = "/setup-app/logout", classes = "btn") { +"Logout" }
                            }
                        }
                    }
                }
            }

            /**
             * Actual APK download, session-protected.
             */
            get("/setup-app/apk/{filename}") {
                val fileName = call.parameters["filename"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing filename")
                if (fileName.contains("..") || fileName.contains('\\') || fileName.contains('/')) {
                    return@get call.respond(HttpStatusCode.BadRequest, "Invalid filename")
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
