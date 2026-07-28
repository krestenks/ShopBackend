package telephony

/**
 * Push-wake abstraction. On an incoming call the routing layer calls [wakeManagers]
 * so on-duty managers' apps come to the foreground and (re)register SIP in time to
 * ring — the piece that lets us stop relying on a permanently-running foreground
 * service on the phones.
 *
 * The real sender (Firebase Cloud Messaging HTTP v1) needs a service-account JSON and
 * an OAuth2 access token per request; that is deferred (needs the Firebase console).
 * Until it's configured, [NoopPushService] just logs, so the rest of the pipeline —
 * token registration, duty filtering, the call/inbound hook — is exercised end to end.
 */
interface PushService {
    /** Fire-and-forget wake for the given device tokens. Must never throw. */
    suspend fun wakeManagers(tokens: List<String>, callId: Long, shopId: Int, fromPhone: String)

    companion object {
        /**
         * Picks an implementation from the environment. Real FCM is wired only when the
         * credentials are present; otherwise the no-op logger is returned.
         */
        fun fromEnv(): PushService {
            val credsPath = System.getenv("FCM_SERVICE_ACCOUNT_JSON")?.trim().orEmpty()
            return if (credsPath.isNotBlank()) {
                // TODO(FCM): construct FcmHttpV1PushService(credsPath) once the Firebase
                //   project + service account exist. Falling back to logging for now so a
                //   half-configured server never crashes the call path.
                println("[Push] FCM_SERVICE_ACCOUNT_JSON set but the HTTP v1 sender is not yet implemented — using no-op logger.")
                NoopPushService
            } else {
                NoopPushService
            }
        }
    }
}

object NoopPushService : PushService {
    override suspend fun wakeManagers(tokens: List<String>, callId: Long, shopId: Int, fromPhone: String) {
        if (tokens.isNotEmpty()) {
            println("[Push] (noop) would wake ${tokens.size} device(s) for call=$callId shop=$shopId from=$fromPhone")
        }
    }
}
