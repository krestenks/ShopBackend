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
- Platform admin can **switch** into an owner context to view/debug, but this is logged via
  `TenantContext.impersonatedByAdmin`.
- An owner admin logs in with their own credentials and sees only their domain.
- Mobile app JWT carries `ownerId`; every API call enforces it.
- Twilio inbound routes are already shop-scoped via `findShopIdByTwilioNumber`; the owner
  Twilio number will live in `owner_account`.

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
> They will be deleted in Step 10.

---

## Remaining steps

### Step 4 — `owner_account` table + owner authentication
**New table:**
```sql
CREATE TABLE IF NOT EXISTS owner_account (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id      INTEGER NOT NULL UNIQUE,
    username      TEXT    NOT NULL UNIQUE,
    password_hash TEXT    NOT NULL,
    twilio_number TEXT,           -- the Twilio number assigned to this owner
    twilio_account_sid  TEXT,     -- optional per-owner Twilio creds
    twilio_auth_token   TEXT,
    active        INTEGER NOT NULL DEFAULT 1,
    created_at    INTEGER NOT NULL
);
```
**DB methods to add:**
- `addOwnerAccount(ownerId, username, password, twilioNumber) → Int`
- `updateOwnerAccount(id, username, twilioNumber, active)`
- `updateOwnerAccountPassword(id, newPassword)`
- `authenticateOwnerAccount(username, password) → Pair<Int,Int>?` (ownerId, accountId)
- `getOwnerAccountByOwnerId(ownerId) → OwnerAccount?`

### Step 5 — Owner web admin session + scoped pages (`WebAdmin.kt`)
- Add `/owner-login` GET/POST (separate from `/login` platform-admin)
- Session key `ownerCtx: TenantContext` stored on successful owner login
- All existing shop/manager/employee/service pages get an owner-scoped version that
  filters by `session.ownerCtx.ownerId`
- Use `getShopsByOwner`, `addShopForOwner`, etc. instead of global methods
- Owner admin cannot see or edit other owners' data

### Step 6 — Platform-admin impersonation
- Platform admin `/admin/switch-owner?ownerId=X` → writes `adminImpersonatingOwnerContext`
  into session under a new key `impersonatedOwnerCtx`
- Platform admin then sees the owner's data (shops, managers, etc.) scoped to ownerId=X
- `/admin/exit-owner` → clears `impersonatedOwnerCtx`, returns to platform-admin view
- A banner is shown: "You are viewing as owner X [Exit]"
- Platform admin **cannot** set `app_account` passwords or Twilio credentials directly from
  impersonation view — only from the owner-management page

### Step 7 — JWT extended with `ownerId` (`JwtConfig.kt` + `MobileApi.kt`)
- Add `ownerId` claim to every JWT issued by `MobileApi /api/auth/login`
  - Manager login: `db.getOwnerIdForManager(managerId)`
  - Shop login:    `db.getOwnerIdForShop(shopId)`
- JWT validator extracts `ownerId` and builds a `TenantContext` per request
- Every mobile route that touches shop/manager/employee/service data calls
  `isShopOwnedBy(shopId, ctx.ownerId)` before proceeding → 403 if mismatch

### Step 8 — Twilio inbound routing update
- `findShopIdByTwilioNumber` already resolves shop from Twilio number (unchanged)
- Optionally: store per-owner Twilio fallback number in `owner_account`
- Twilio SMS/voice routes already produce a shop-scoped context — no change needed for
  inbound routing itself

### Step 9 — Platform-admin owner management UI
- New admin page `/admin/owners` — list of owners with edit/add buttons
- Add owner: name, slug, generate username/password for their web login
- Edit owner: name, slug, Twilio number, active flag
- Reset owner password button
- "Switch to owner" button → impersonation (Step 6)
- Platform admin **never** sees individual shop/customer/appointment data from this page

### Step 10 — DB indexes + NOT NULL migration
```sql
CREATE INDEX IF NOT EXISTS idx_shops_owner     ON shops(owner_id);
CREATE INDEX IF NOT EXISTS idx_managers_owner  ON managers(owner_id);
CREATE INDEX IF NOT EXISTS idx_employees_owner ON employees(owner_id);
CREATE INDEX IF NOT EXISTS idx_services_owner  ON services(owner_id);
CREATE INDEX IF NOT EXISTS idx_customers_owner ON customers(owner_id);
```
After verifying backfill is complete in production, change `owner_id` columns to
`NOT NULL DEFAULT 1` via a migration statement.

Remove global `getAllShops()`, `getAllManagers()`, `getAllEmployees()`, `getAllServices()`
(or keep for platform-admin only, with a clear comment).

---

## How to continue in a new chat

Paste this into your next message:

> "Please continue implementing the multi-tenant Owner feature on branch `feature/owners`.
> See `docs/owner-feature-progress.md` for full context.
> We completed Steps 1-3. Please start with Step 4 (`owner_account` table and
> owner authentication)."

---

## Key files changed so far

| File | What changed |
|------|-------------|
| `src/main/kotlin/DataBase.kt` | Steps 1 + 3: schema, backfill, scoped methods |
| `src/main/kotlin/TenantContext.kt` | Step 2: new file — tenant context model |
| `docs/owner-feature-progress.md` | This file |

## Files that need changes in future steps

| File | Steps |
|------|-------|
| `src/main/kotlin/DataBase.kt` | Step 4 (`owner_account`), Step 10 (indexes, NOT NULL) |
| `src/main/kotlin/WebAdmin.kt` | Steps 5, 6, 9 |
| `src/main/kotlin/JwtConfig.kt` | Step 7 |
| `src/main/kotlin/MobileApi.kt` | Step 7 |
| `src/main/kotlin/twilio/SmsRoutes.kt` | Step 8 (minor, if needed) |
| `src/main/kotlin/twilio/VoiceRoutes.kt` | Step 8 (minor, if needed) |
