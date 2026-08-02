package controlplane

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One-time, short-lived APK download tokens for onboarding new phones.
 *
 * A not-yet-onboarded phone can't reach anything on the tailnet, so the APK is served from the
 * public download listener (see [DownloadRoutes]) at a single-use URL minted per add-phone.
 */
object DownloadStore {
    private data class Tok(val expiresAt: Long, @Volatile var used: Boolean = false)

    private val tokens = ConcurrentHashMap<String, Tok>()

    /** Mints a single-use token valid for [ttlMs]. */
    fun create(ttlMs: Long = 60 * 60 * 1000L): String {
        val t = UUID.randomUUID().toString().replace("-", "")
        tokens[t] = Tok(System.currentTimeMillis() + ttlMs)
        // opportunistic cleanup of expired entries
        val now = System.currentTimeMillis()
        tokens.entries.removeIf { it.value.expiresAt < now }
        return t
    }

    /** Marks the token used if currently valid; returns true only on the first valid call. */
    fun consume(token: String): Boolean {
        val t = tokens[token] ?: return false
        synchronized(t) {
            if (t.used || System.currentTimeMillis() > t.expiresAt) return false
            t.used = true
            return true
        }
    }
}

/**
 * PUBLIC listener routes — the only thing exposed to the internet from the control plane.
 * Serves nothing but the one-time APK download (+ a health check). No admin API here.
 */
class DownloadRoutes(
    private val apkFile: File,
    private val apkFilename: String,
) {
    fun install(r: Route) = r.route("") {
        get("/health") { call.respondText("ok") }

        get("/dl/{token}") {
            val token = call.parameters["token"].orEmpty()
            if (!DownloadStore.consume(token)) {
                call.respond(HttpStatusCode.Gone, "This install link has expired or was already used. Ask for a new one.")
                return@get
            }
            if (!apkFile.exists() || !apkFile.isFile) {
                call.respond(HttpStatusCode.NotFound, "App package not available on the server.")
                return@get
            }
            call.response.headers.append(HttpHeaders.ContentType, "application/vnd.android.package-archive")
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$apkFilename\"")
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondFile(apkFile)
        }
    }
}
