/**
 * TenantContext.kt
 *
 * Carries the resolved owner domain for a given request or session.
 * Every authenticated action (web admin, mobile JWT, Twilio inbound) should
 * derive a TenantContext before touching domain data so that isolation is
 * enforced consistently.
 *
 * Design rules:
 * - ownerId must always come from the server-side auth resolution, never from a
 *   client-supplied request parameter.
 * - Platform-admin sessions that have not explicitly switched into an owner
 *   context should carry PLATFORM_ADMIN_OWNER_ID (0).
 * - impersonatedByAdmin is set when a platform admin explicitly entered an owner
 *   context via the impersonation/switch mechanism.
 */

// ─── Sentinel value ──────────────────────────────────────────────────────────

/** Owner-id value that signals "platform admin context — no owner selected yet". */
const val PLATFORM_ADMIN_OWNER_ID = 0

// ─── Context model ───────────────────────────────────────────────────────────

/**
 * Immutable snapshot of the resolved tenant context for a single request / session.
 *
 * @param ownerId           The resolved owner id.  0 = platform admin context.
 * @param role              "platform_admin" | "owner_admin" | "manager" | "shop"
 * @param userId            The primary entity id for the role:
 *                          - owner_admin  → owner account id
 *                          - manager      → manager id
 *                          - shop         → shop id
 *                          - platform_admin → null (no user entity)
 * @param impersonatedByAdmin  Set to the platform-admin username when this context
 *                             was entered via explicit owner switch / impersonation.
 *                             Null for direct owner / manager / shop sessions.
 */
data class TenantContext(
    val ownerId: Int,
    val role: String,
    val userId: Int? = null,
    val impersonatedByAdmin: String? = null,
) {
    val isPlatformAdmin: Boolean get() = role == "platform_admin"
    val isOwnerAdmin: Boolean   get() = role == "owner_admin"
    val isManager: Boolean      get() = role == "manager"
    val isShop: Boolean         get() = role == "shop"
    val isImpersonated: Boolean get() = impersonatedByAdmin != null

    /** True if this context has a real owner (i.e. not the platform-admin placeholder). */
    val hasOwner: Boolean get() = ownerId != PLATFORM_ADMIN_OWNER_ID
}

// ─── Resolution helpers ───────────────────────────────────────────────────────

/**
 * Resolves a [TenantContext] for a manager login.
 *
 * Returns null if the manager has no owner_id set (should not happen after
 * Step 1 backfill, but handled gracefully).
 */
fun resolveManagerContext(managerId: Int, db: DataBase): TenantContext? {
    val ownerId = db.getOwnerIdForManager(managerId) ?: return null
    return TenantContext(
        ownerId = ownerId,
        role    = "manager",
        userId  = managerId,
    )
}

/**
 * Resolves a [TenantContext] for a shop login.
 *
 * Returns null if the shop has no owner_id set.
 */
fun resolveShopContext(shopId: Int, db: DataBase): TenantContext? {
    val ownerId = db.getOwnerIdForShop(shopId) ?: return null
    return TenantContext(
        ownerId = ownerId,
        role    = "shop",
        userId  = shopId,
    )
}

/**
 * Returns a platform-admin context (no owner selected).
 * Used for the existing web-admin session before an owner switch is performed.
 */
fun platformAdminContext(): TenantContext =
    TenantContext(
        ownerId = PLATFORM_ADMIN_OWNER_ID,
        role    = "platform_admin",
        userId  = null,
    )

/**
 * Returns a platform-admin-impersonating-owner context.
 * Used when the platform admin has explicitly switched into a specific owner domain.
 *
 * @param adminUsername  The platform-admin username performing the switch (for audit).
 */
fun adminImpersonatingOwnerContext(ownerId: Int, adminUsername: String): TenantContext =
    TenantContext(
        ownerId             = ownerId,
        role                = "owner_admin",
        userId              = null,
        impersonatedByAdmin = adminUsername,
    )

/**
 * Returns an owner-admin context for a directly logged-in owner.
 *
 * @param ownerAccountId  The id in the owner_account table.
 */
fun ownerAdminContext(ownerId: Int, ownerAccountId: Int): TenantContext =
    TenantContext(
        ownerId = ownerId,
        role    = "owner_admin",
        userId  = ownerAccountId,
    )
