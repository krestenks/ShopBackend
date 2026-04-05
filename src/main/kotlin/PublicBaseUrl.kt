/**
 * Central place for deciding which public URL the backend should use when it needs to generate a URL
 * that the outside world can open (booking links, Twilio callbacks, etc.).
 */
object PublicBaseUrl {

    /**
     * Preferred order:
     * 1) PUBLIC_BASE_URL   (generic, used by Twilio voice)
     * 2) PUBLIC_BOOKING_URL (legacy name used for booking links)
     * 3) localhost fallback
     */
    fun get(): String {
        val base = System.getenv("PUBLIC_BASE_URL")
            ?: System.getenv("PUBLIC_BOOKING_URL")
            ?: "http://localhost:9091"
        return base.trimEnd('/')
    }
}
