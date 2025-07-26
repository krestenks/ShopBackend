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
                        .build()
                )
                validate { credential ->
                    val userId = credential.payload.getClaim("userId").asInt()
                    val role = credential.payload.getClaim("role").asString()
                    println("JWT validated: userId=$userId, role=$role")

                    if (userId != null) JWTPrincipal(credential.payload) else null
                }
            }
        }
    }

    fun generateToken(userId: Int, role: String): String {
        val expiresAt = System.currentTimeMillis() + 1000 * 60 * 60 * 24 * 30 // 30 days
        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("userId", userId)
            .withClaim("role", role)
            .withExpiresAt(Date(expiresAt))
            .sign(Algorithm.HMAC256(secret))
    }
}
