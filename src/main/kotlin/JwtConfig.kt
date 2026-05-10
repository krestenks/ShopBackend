import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.*
import java.util.Date

object JwtConfig {
    private const val secret = "very-secret"
    private const val issuer = "shop-manager"
    private const val audience = "mobile"
    private const val realm = "Access to mobile API"

    fun install(application: Application) {
        application.install(Authentication) {
            jwt("jwt") {
                realm = JwtConfig.realm
                verifier(
                    JWT
                        .require(Algorithm.HMAC256(secret))
                        .withAudience(audience)
                        .withIssuer(issuer)
                        // Accept tokens that expired up to 1 year ago so that long-lived
                        // sessions keep working between app restarts.  New tokens are issued
                        // with a 365-day lifetime so this only matters for very old sessions.
                        .acceptExpiresAt(365L * 24 * 60 * 60)   // leeway in seconds
                        .build()
                )
                validate { credential ->
                    val userId = credential.payload.getClaim("userId").asInt()
                    // Silent on success — only auth failures are logged (see MobileApi.authenticateManager).
                    if (userId != null) JWTPrincipal(credential.payload) else null
                }
            }
        }
    }

    fun generateToken(userId: Int, role: String): String {
        val expiresAt = System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 // 365 days
        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("userId", userId)
            .withClaim("role", role)
            .withExpiresAt(Date(expiresAt))
            .sign(Algorithm.HMAC256(secret))
    }
}
