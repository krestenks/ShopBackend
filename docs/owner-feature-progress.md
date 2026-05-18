# Multi-Tenant Owner Feature — Implementation Progress

> Branch: `feature/owners`
> Goal: Introduce an **Owner** concept so the backend can be sold as a service to different
> shop-chain owners, with full data isolation between domains.

---

## Architecture summary

```
Platform Admin  (one global user, manages owners, sets Twilio numbers)
      │
      ▼
   Owners table  (one row per paying shop-chain customer)
      │
      ├── owner_account  (web-login credentials + optional Twilio creds)
      │
      ├── Shops        owner_id FK
      ├── Managers     owner_id FK
      ├── Employees    owner_id FK
      ├── Services     owner_id FK
      ├── Customers    owner_id FK
      └── AppAccount   owner_id FK
```

**Key design rules:**
- `ownerId` is always resolved server-side from the session/JWT — never from a client param.
- Platform admin (web login) can create owners and set their credentials/Twilio number.
- Platform admin can **switch** into an owner context to view/debug (ImpersonationSession).
- An owner admin logs in with their own credentials and sees only their domain.
- Mobile app JWT carries `ownerId`; every API call can enforce it.
- Twilio inbound routes are already shop-scoped via `findShopIdByTwilioNumber`.

---

## Completed steps

### Step 1 — Owner foundation (`DataBase.kt`) ✅ commit `951649f`
- `Owner` data class (`id`, `name`, `slug`, `active`, `createdAt`)
- `owners` table (`CREATE TABLE IF NOT EXISTS`)
- Nullable `owner_id` columns added to: `shops`, `managers`, `employees`, `services`,
  `customers`, `app_account`
- `ensureDefaultOwnerAndBackfill()` — creates default owner id=1 on first run, backfills
  all existing rows → **zero data loss** for existing deployments
- `getAllOwners`, `getOwnerById`, `addOwner`, `updateOwner`
- `getOwnerIdForShop`, `getOwnerIdForManager` resolver helpers

### Step 2 — TenantContext model (`TenantContext.kt`) ✅ commit `0f0a8d8`
- `TenantContext` data class:
  - `ownerId: Int` (0 = `PLATFORM_ADMIN_OWNER_ID`)
  - `role: String` — `"platform_admin" | "owner_admin" | "manager" | "shop"`
  - `userId: Int?` — entity id for the role
  - `impersonatedByAdmin: String?` — audit field, non-null when platform admin switched in
- Computed properties: `isPlatformAdmin`, `isOwnerAdmin`, `isManager`, `isShop`,
  `hasOwner`, `isImpersonated`
- Factory functions:
  - `platformAdminContext()` — for existing web-admin session
  - `ownerAdminContext(ownerId, ownerAccountId)` — direct owner login
  - `adminImpersonatingOwnerContext(ownerId, adminUsername)` — admin switch
  - `resolveManagerContext(managerId, db)` — derives context from DB for mobile JWT
  - `resolveShopContext(shopId, db)` — same for shop JWT

### Step 3 — Owner-scoped DB methods (`DataBase.kt`) ✅ commit `9759c0c`
**Scoped reads:**
- `getShopsByOwner(ownerId)`
- `getManagersByOwner(ownerId)`
- `getEmployeesByOwner(ownerId)`
- `getServicesByOwner(ownerId)`

**Scoped inserts (stamp `owner_id` on every new row):**
- `addShopForOwner(ownerId, name, address, directions) → Int`
- `addManagerForOwner(ownerId, name, username, password, phone) → Int`
- `addEmployeeForOwner(ownerId, name, phone) → Int`
- `addServiceForOwner(ownerId, name, price, duration) → Int`

**Authorization guards:**
- `isShopOwnedBy(shopId, ownerId): Boolean`
- `isManagerOwnedBy(managerId, ownerId): Boolean`

> Global `getAllXxx()` methods are **kept** (not yet removed).
> They will be deleted in Step 11.

### Step 4 — `owner_account` table + owner authentication ✅
**New data class:** `OwnerAccount` (id, ownerId, username, passwordHash, twilioNumber,
twilioAccountSid, twilioAuthToken, active, createdAt)

**New table** created in `createTables()`:
```sql
CREATE TABLE IF NOT EXISTS owner_account (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id            INTEGER NOT NULL UNIQUE,
    username            TEXT    NOT NULL UNIQUE,
    password_hash       TEXT    NOT NULL,
    twilio_number       TEXT,
    twilio_account_sid  TEXT,
    twilio_auth_token   TEXT,
    active              INTEGER NOT NULL DEFAULT 1,
    created_at          INTEGER NOT NULL
)
```

**DB methods added:**
- `addOwnerAccount(ownerId, username, password, ...) → Int`
- `updateOwnerAccount(id, username, twilioNumber?, ..., active)`
- `updateOwnerAccountPassword(id, newPassword)`
- `authenticateOwnerAccount(username, password) → Pair<Int,Int>?` (ownerId, accountId)
- `getOwnerAccountByOwnerId(ownerId) → OwnerAccount?`

### Step 5 — Owner web-login portal (`WebAdmin.kt`) ✅
- New session classes: `OwnerSession(ownerId, ownerName)`,
  `ImpersonationSession(ownerId, ownerName)`
- Registered in `ShopBackend.kt`: `OWNER_SESSION`, `IMPERSONATION_SESSION` cookies
- `GET /owner-login` + `POST /owner-login` — authenticates via `authenticateOwnerAccount`
- `GET /owner-logout` — clears `OwnerSession`
- `GET /owner` — owner dashboard page (scoped stats: shops, managers, employees, services)
- Intercept updated: `/owner/...` paths require `OwnerSession`, redirect to `/owner-login`
  if missing; other paths still require `AdminSession` as before

### Step 6 — Platform-admin impersonation (`WebAdmin.kt`) ✅
- `GET /admin/switch-owner/{ownerId}` — sets `ImpersonationSession` cookie, redirects to
  `/admin/owners` with impersonation banner visible
- `GET /admin/exit-owner` — clears `ImpersonationSession`, returns to normal admin view
- `/admin/owners` page shows banner when impersonating: "Viewing as owner X [Exit]"

### Step 7 — JWT extended with `ownerId` (`JwtConfig.kt` + `MobileApi.kt`) ✅
- `JwtConfig.generateToken(userId, role, tokenVersion, ownerId)` — new `ownerId` parameter
  (default 0 = no filter during migration)
- `ownerId` claim embedded in every issued JWT via `withClaim("ownerId", ownerId)`
- Mobile login (`/api/mobile/login`) resolves `ownerId` from DB for all 3 auth paths:
  - app_account → `getOwnerIdForManager` / `getOwnerIdForShop`
  - manager fallback → `getOwnerIdForManager`
  - shop fallback → `getOwnerIdForShop`
- `LoginInfo` data class extended with `ownerId: Int = 0`

### Step 8 — Twilio inbound routing ✅ (no change needed)
- `findShopIdByTwilioNumber` already resolves shop from Twilio number
- Twilio SMS/voice routes are already shop-scoped — no update required

### Step 9 — Platform-admin owner management UI (`WebAdmin.kt`) ✅
- `GET /admin/owners` — lists all owners with id, name, slug, active status, login username,
  and action buttons (Edit / Impersonate)
- "Add owner" form on the same page: name, slug, username, password → calls `addOwner` +
  `addOwnerAccount` in one form submit
- `GET /admin/owners/edit?id=X` — two-panel edit page: owner details + login credentials
- `POST /admin/owners/edit` — saves name, slug, active
- `POST /admin/owners/set-login` — creates or updates `owner_account` row; updates password
  if provided
- `/admin/owners` nav item can be added to the sidebar manually if desired

### Step 10 — DB indexes ✅
```sql
CREATE INDEX IF NOT EXISTS idx_shops_owner     ON shops(owner_id);
CREATE INDEX IF NOT EXISTS idx_managers_owner  ON managers(owner_id);
CREATE INDEX IF NOT EXISTS idx_employees_owner ON employees(owner_id);
CREATE INDEX IF NOT EXISTS idx_services_owner  ON services(owner_id);
CREATE INDEX IF NOT EXISTS idx_customers_owner ON customers(owner_id);
```
- `ensureOwnerIndexes()` method added to `DataBase.kt`
- Called at startup in `ShopBackend.main()` (idempotent — `CREATE INDEX IF NOT EXISTS`)

---

## Remaining steps

### Step 11 — Enforce NOT NULL + remove global getAllXxx()
- After verifying backfill is complete in production, change `owner_id` columns to
  `NOT NULL DEFAULT 1` via a migration statement.
- Remove (or restrict to platform-admin only) the global `getAllShops()`,
  `getAllManagers()`, `getAllEmployees()`, `getAllServices()` methods.
- Replace all call sites with owner-scoped variants where possible.

### Step 12 — Mobile API owner enforcement
- In mobile routes, extract `ownerId` from JWT: `principal.payload.getClaim("ownerId").asInt()`
- Before every shop/manager/employee operation, call `isShopOwnedBy(shopId, ownerId)` → 403
- Replace `loginInfo` helpers with full `TenantContext` to make enforcement consistent

### Step 13 — Owner-scoped admin pages (full self-service) ✅ Done
- Added `ownerPage(session, titleText, activePath, bodyContent)` HTML layout and
  `respondOwnerPage` helper in `WebAdmin.kt`
- Added full CRUD routes under `/owner/shops`, `/owner/employees`, `/owner/services`,
  `/owner/managers` — each with list, add (GET+POST), edit (GET+POST), delete (GET)
- All routes enforce `OwnerSession` and use owner-scoped DB methods (no cross-tenant leak)
- Owner can reset manager passwords via `POST /owner/managers/reset-password`
- Owner cannot see or edit other owners' data (guarded by `isShopOwnedBy`, `isManagerOwnedBy`, etc.)

---

## How to continue in a new chat

Paste this into your next message:

> "Please continue implementing the multi-tenant Owner feature on branch `feature/owners`.
> See `docs/owner-feature-progress.md` for full context.
> We completed Steps 1-10. Please start with Step 11 (NOT NULL migration +
> removing global getAllXxx() methods)."

---

## Key files changed so far

| File | What changed |
|------|-------------|
| `src/main/kotlin/DataBase.kt` | Steps 1, 3, 4, 10: schema, backfill, scoped methods, indexes |
| `src/main/kotlin/TenantContext.kt` | Step 2: new file — tenant context model |
| `src/main/kotlin/WebAdmin.kt` | Steps 5, 6, 9: owner login portal, impersonation, owners page |
| `src/main/kotlin/JwtConfig.kt` | Step 7: `ownerId` claim in JWT |
| `src/main/kotlin/MobileApi.kt` | Step 7: `ownerId` resolved and embedded in mobile tokens |
| `src/main/kotlin/ShopBackend.kt` | Steps 5 (session cookies), 10 (index startup call) |
| `docs/owner-feature-progress.md` | This file |

## Files that need changes in future steps

| File | Steps |
|------|-------|
| `src/main/kotlin/DataBase.kt` | Step 11 (NOT NULL migration, remove global getAllXxx) |
| `src/main/kotlin/WebAdmin.kt` | Step 13 (owner-scoped admin pages) |
| `src/main/kotlin/MobileApi.kt` | Step 12 (mobile API owner enforcement) |
| `src/main/kotlin/twilio/SmsRoutes.kt` | Step 12 (minor, if needed) |
| `src/main/kotlin/twilio/VoiceRoutes.kt` | Step 12 (minor, if needed) |
