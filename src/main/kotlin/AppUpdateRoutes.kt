import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Self-hosted Android update endpoints (authenticated).
 *
 * - GET /api/app/version.json
 * - GET /api/app/download/{filename}
 *
 * Files are loaded from [apkDir] (default: data/apk).
 */
fun Route.appUpdateRoutes(
    apkDir: File = File("data/apk"),
) {
    @Serializable
    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val required: Boolean,
        val releaseNotes: String? = null,
        val minSupportedVersionCode: Long? = null,
    )

    fun safeJoin(dir: File, fileName: String): File? {
        if (fileName.contains("..") || fileName.contains('\\') || fileName.contains('/')) return null
        val f = File(dir, fileName)
        val canonDir = dir.canonicalFile
        val canonFile = f.canonicalFile
        return if (canonFile.path.startsWith(canonDir.path)) canonFile else null
    }

    authenticate("jwt") {
        /**
         * Serves the current update manifest.
         *
         * NOTE: This reads a file so you can update it without redeploying the backend.
         */
        get("/api/app/version.json") {
            val f = File(apkDir, "version.json")
            if (!f.exists()) {
                call.respond(HttpStatusCode.NotFound, "Missing version.json")
                return@get
            }

            // Parse to validate shape (defensive). Then re-encode by Ktor JSON.
            val raw = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
                ?: return@get call.respond(HttpStatusCode.InternalServerError, "Could not read version.json")

            val info = runCatching {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString(UpdateInfo.serializer(), raw)
            }.getOrElse { ex ->
                call.respond(HttpStatusCode.InternalServerError, "Invalid version.json: ${ex.message}")
                return@get
            }

            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
            call.respond(info)
        }

        /**
         * Streams the given APK.
         *
         * Prefer versioned names (immutable caching) like booking-1.0.3.apk.
         */
        get("/api/app/download/{filename}") {
            val fileName = call.parameters["filename"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing filename")

            val f = safeJoin(apkDir, fileName)
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid filename")

            if (!f.exists() || !f.isFile) {
                call.respond(HttpStatusCode.NotFound, "Not found")
                return@get
            }

            // If you decide to use a /booking-latest.apk name, consider no-cache.
            // For versioned files, immutable caching is fine.
            val cacheControl = if (fileName.contains("latest", ignoreCase = true)) {
                "no-cache"
            } else {
                "public, max-age=31536000, immutable"
            }

            call.response.headers.append(HttpHeaders.CacheControl, cacheControl)
            // Ensure correct MIME type even if default file-type mapping is missing.
            call.response.header(HttpHeaders.ContentType, "application/vnd.android.package-archive")
            call.respondFile(f)
        }
    }
}
