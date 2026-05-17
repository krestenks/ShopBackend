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

    /**
     * Installs JWT authentication with an optional token-version revocation check.
     *
     * When [db] is provided the `validate` block reads [DataBase.getAppAccountTokenVersion]
     * and rejects any token whose embedded `tokenVersion` claim no longer matches the DB value.
     * Bumping the DB version (via [DataBase.bumpAppAccountTokenVersion]) therefore instantly
     * invalidates every currently-issued JWT for that account — enabling force-logout.
     *
     * Fallback-authenticated accounts (manager/shop rows that have no `app_account` row yet)
     * always return version 0 from the DB, and their tokens are issued with version 0, so they
     * continue to work transparently until a force-logout is triggered.
     */
    fun install(application: Application, db: DataBase? = null) {
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
                        ?: return@validate null

                    // ── Token-version revocation check ────────────────────────
                    if (db != null) {
                        val role = credential.payload.getClaim("role")?.asString()
                        if (role != null) {
                            val jwtVersion = credential.payload.getClaim("tokenVersion")
                                ?.let { if (!it.isNull) it.asInt() else 0 } ?: 0
                            val storedVersion = try {
                                db.getAppAccountTokenVersion(role, userId)
                            } catch (e: Exception) {
                                println("[JwtConfig] DB error during version check for $role id=$userId: ${e.message}")
                                0 // fail-open on DB error so a transient error doesn't lock everyone out
                            }
                            if (jwtVersion != storedVersion) {
                                println("[JwtConfig] REVOKED token for $role id=$userId " +
                                        "(jwt=$jwtVersion db=$storedVersion) — force-logout in effect")
                                return@validate null   // → 401 Unauthorized
                            }
                        }
                    }

                    JWTPrincipal(credential.payload)
                }
            }
        }
    }

    /**
     * Issues a signed JWT.
     *
     * [tokenVersion] should match the current [DataBase.getAppAccountTokenVersion] for the
     * account so the first request after a force-logout correctly fails validation.
     * Defaults to 0 for backwards-compatible / fallback-authenticated tokens.
     */
    fun generateToken(userId: Int, role: String, tokenVersion: Int = 0): String {
        val expiresAt = System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 // 365 days
        return JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("userId", userId)
            .withClaim("role", role)
            .withClaim("tokenVersion", tokenVersion)
            .withExpiresAt(Date(expiresAt))
            .sign(Algorithm.HMAC256(secret))
    }
}
