package asterisk

/**
 * All Asterisk-related settings, sourced from environment variables.
 *
 * The backend and Asterisk run on the same machine (the phone server), so the
 * defaults point at localhost. Secrets have no defaults on purpose.
 *
 *   ASTERISK_ENABLED           "true" switches TelephonyService to Asterisk and starts AMI
 *   ASTERISK_AMI_HOST          default 127.0.0.1
 *   ASTERISK_AMI_PORT          default 5038
 *   ASTERISK_AMI_USERNAME      default kotlinbackend
 *   ASTERISK_AMI_SECRET        required when enabled
 *   ASTERISK_ARI_BASE_URL      default http://127.0.0.1:8088
 *   ASTERISK_ARI_USERNAME      default kotlinbackend
 *   ASTERISK_ARI_PASSWORD      required when enabled
 *   ASTERISK_CONFIG_PATH       default /etc/asterisk (where the generated .conf files go)
 *   ASTERISK_BACKEND_BASE_URL  default http://127.0.0.1:8080 (URL the dialplan CURLs back to)
 *   ASTERISK_INTERNAL_SECRET   required when enabled — shared secret for the
 *                              /api/internal/telephony/... endpoints
 */
data class AsteriskConfig(
    val enabled: Boolean,
    val amiHost: String,
    val amiPort: Int,
    val amiUsername: String,
    val amiSecret: String,
    val ariBaseUrl: String,
    val ariUsername: String,
    val ariPassword: String,
    val configPath: String,
    val backendBaseUrl: String,
    val internalSecret: String,
) {
    companion object {
        fun fromEnv(): AsteriskConfig = AsteriskConfig(
            enabled = System.getenv("ASTERISK_ENABLED")?.equals("true", ignoreCase = true) == true,
            amiHost = env("ASTERISK_AMI_HOST", "127.0.0.1"),
            amiPort = System.getenv("ASTERISK_AMI_PORT")?.toIntOrNull() ?: 5038,
            amiUsername = env("ASTERISK_AMI_USERNAME", "kotlinbackend"),
            amiSecret = env("ASTERISK_AMI_SECRET", ""),
            ariBaseUrl = env("ASTERISK_ARI_BASE_URL", "http://127.0.0.1:8088").trimEnd('/'),
            ariUsername = env("ASTERISK_ARI_USERNAME", "kotlinbackend"),
            ariPassword = env("ASTERISK_ARI_PASSWORD", ""),
            configPath = env("ASTERISK_CONFIG_PATH", "/etc/asterisk"),
            backendBaseUrl = env("ASTERISK_BACKEND_BASE_URL", "http://127.0.0.1:8080").trimEnd('/'),
            internalSecret = env("ASTERISK_INTERNAL_SECRET", ""),
        )

        private fun env(name: String, default: String): String =
            System.getenv(name)?.trim()?.takeIf { it.isNotBlank() } ?: default
    }

    /** Trunk/device name used in quectel.conf and Dial() strings, e.g. "shop3". */
    fun trunkName(shopId: Int) = "shop$shopId"

    /** PJSIP endpoint id the manager app registers as, e.g. "shop3-manager". */
    fun endpointId(shopId: Int) = "shop$shopId-manager"

    /** Dialplan context for calls/SMS arriving on a shop's GSM trunk. */
    fun inboundContext(shopId: Int) = "from-gsm-shop$shopId"

    /** Dialplan context the shop's SIP endpoint dials out from. */
    fun outboundContext(shopId: Int) = "from-sip-shop$shopId"
}
