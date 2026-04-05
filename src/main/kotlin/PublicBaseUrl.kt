/**
 * Central place for deciding which public URL the backend should use when it needs to generate a URL
 * that the outside world can open (booking links, Twilio callbacks, etc.).
 */
import io.ktor.server.application.*
import io.ktor.server.request.*

object PublicBaseUrl {

    /**
     * Preferred order:
     * 1) PUBLIC_BASE_URL   (generic, used by Twilio voice)
     * 2) PUBLIC_BOOKING_URL (legacy name used for booking links)
     * 3) localhost fallback
     */
    fun getFromEnvOrNull(): String? {
        val base = System.getenv("PUBLIC_BASE_URL")
            ?: System.getenv("PUBLIC_BOOKING_URL")
        return base?.trimEnd('/')?.takeIf { it.isNotBlank() }
    }

    fun get(): String {
        return getFromEnvOrNull() ?: "http://localhost:9091"
    }

    /**
     * Derive the public base URL for the *current request*.
     *
     * Useful on platforms behind a reverse proxy (Upsun/Platform.sh, Cloudflare, etc.), where the
     * external hostname is provided via X-Forwarded-* headers.
     */
    fun fromCall(call: ApplicationCall): String {
        getFromEnvOrNull()?.let { return it }

        val headers = call.request.headers
        val forwardedProto = headers["X-Forwarded-Proto"]?.split(',')?.firstOrNull()?.trim()
        val forwardedHost = headers["X-Forwarded-Host"]?.split(',')?.firstOrNull()?.trim()
        val host = forwardedHost ?: headers["Host"]?.split(',')?.firstOrNull()?.trim()

        // Prefer X-Forwarded-Proto (set by reverse proxies). If missing, assume http.
        // (We avoid call.request.origin here since it isn't available in all Ktor setups in this project.)
        val proto = forwardedProto ?: "http"

        if (!host.isNullOrBlank()) {
            return "${proto}://${host}".trimEnd('/')
        }

        // last resort
        return "http://localhost:9091"
    }
}
