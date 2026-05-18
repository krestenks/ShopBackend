import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.resources
import io.ktor.server.http.content.static
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.html.*
import org.mindrot.jbcrypt.BCrypt
import shared.components.BookingUI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class WebAdmin(private val db: DataBase) {

    data class AdminSession(val username: String)

    /** Session stored for an authenticated owner web-login. */
    data class OwnerSession(val ownerId: Int, val ownerName: String)

    /**
     * Set by the platform admin to "impersonate" a specific owner.
     * While this cookie is present, /admin/... pages filter data to the impersonated owner.
     * The owner admin login also creates this session so the same pages work for owners.
     */
    data class ImpersonationSession(val ownerId: Int, val ownerName: String)

    private data class NavItem(val href: String, val label: String, val icon: String)

    private fun HTML.adminPage(
        call: ApplicationCall,
        titleText: String,
        subtitle: String? = null,
        activePath: String? = null,
        bodyContent: FlowContent.() -> Unit,
    ) {
        head {
            meta { charset = "utf-8" }
            meta {
                name = "viewport"
                content = "width=device-width, initial-scale=1"
            }
            title { +titleText }
            link(rel = "stylesheet", href = "/static/admin.css", type = "text/css")
        }
        body {
            div("layout") {
                div("sidebar") {
                    div("brand") {
                        div {
                            div("brand-title") { +"ShopManager" }
                            div("brand-sub") { +"Admin" }
                        }
                    }

                    val nav = listOf(
                        NavItem("/", "Dashboard", "🏠"),
                        NavItem("/shops", "Shops", "🏪"),
                        NavItem("/employees", "Employees", "👥"),
                        NavItem("/services", "Services", "🧾"),
                        NavItem("/managers", "Managers", "🧑‍💼"),
                        NavItem("/appointments", "Appointments", "📅"),
                        NavItem("/customers", "Customers", "👤"),
                        NavItem("/reports", "Reports", "💰"),
                        NavItem("/twilio/setup", "Twilio setup", "📞"),
                        NavItem("/test-booking-link", "Booking link", "🔗"),
                        NavItem("/setup-app", "Install app", "📲"),
                        NavItem("/admin/owners", "Owners", "🏢"),
                    )

                    div("nav") {
                        for (item in nav) {
                            a(href = item.href, classes = if (activePath == item.href) "active" else null) {
                                span { +item.icon }
                                span { +item.label }
                            }
                        }
                        div("spacer") {}
                        a(href = "/logout") {
                            span { +"🚪" }
                            span { +"Logout" }
                        }
                    }
                }

                div("main") {
                    // ── Impersonation banner (shown on every admin page when active) ──
                    val impersonating = call.sessions.get<ImpersonationSession>()
                    if (impersonating != null) {
                        div(classes = "panel") {
                            style = "background:#fff3cd;border-left:4px solid #f0ad4e;margin-bottom:16px;padding:10px 16px;"
                            p {
                                span { style = "font-weight:bold"; +"🎭 Viewing as owner: ${impersonating.ownerName} (id=${impersonating.ownerId})" }
                                +"  "
                                a(href = "/admin/exit-owner", classes = "btn") { +"Exit impersonation" }
                                +" "
                                a(href = "/owner", classes = "btn") { +"Open Owner Portal →" }
                            }
                        }
                    }
                    div("page-header") {
                        div {
                            h1("page-title") { +titleText }
                            if (!subtitle.isNullOrBlank()) {
                                p("page-subtitle") { +subtitle }
                            }
                        }
                    }
                    bodyContent()
                }
            }
        }
    }

    private suspend fun ApplicationCall.respondAdminPage(
        titleText: String,
        subtitle: String? = null,
        activePath: String? = null,
        bodyContent: FlowContent.() -> Unit,
    ) {
        respondHtml {
            adminPage(
                call = this@respondAdminPage,
                titleText = titleText,
                subtitle = subtitle,
                activePath = activePath,
                bodyContent = bodyContent,
            )
        }
    }

    // ── Owner-portal HTML page wrapper (Step 13) ──────────────────────────────
    private fun HTML.ownerPage(
        session: OwnerSession,
        titleText: String,
        activePath: String? = null,
        bodyContent: FlowContent.() -> Unit,
    ) {
        head {
            meta { charset = "utf-8" }
            meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
            title { +"$titleText — ${session.ownerName}" }
            link(rel = "stylesheet", href = "/static/admin.css", type = "text/css")
        }
        body {
            div("layout") {
                div("sidebar") {
                    div("brand") {
                        div {
                            div("brand-title") { +session.ownerName }
                            div("brand-sub") { +"Owner Portal" }
                        }
                    }
                    val nav = listOf(
                        NavItem("/owner", "Dashboard", "🏠"),
                        NavItem("/owner/shops", "Shops", "🏪"),
                        NavItem("/owner/employees", "Employees", "👥"),
                        NavItem("/owner/services", "Services", "🧾"),
                        NavItem("/owner/managers", "Managers", "🧑‍💼"),
                    )
                    div("nav") {
                        for (item in nav) {
                            a(href = item.href, classes = if (activePath == item.href) "active" else null) {
                                span { +item.icon }; span { +item.label }
                            }
                        }
                        div("spacer") {}
                        a(href = "/owner-logout") { span { +"🚪" }; span { +"Logout" } }
                    }
                }
                div("main") {
                    div("page-header") {
                        div { h1("page-title") { +titleText } }
                    }
                    bodyContent()
                }
            }
        }
    }

    private suspend fun ApplicationCall.respondOwnerPage(
        session: OwnerSession,
        titleText: String,
        activePath: String? = null,
        bodyContent: FlowContent.() -> Unit,
    ) {
        respondHtml { ownerPage(session, titleText, activePath, bodyContent) }
    }

    /**
     * When the platform admin is impersonating an owner, returns that owner's id.
     * Null means the admin is viewing the global (unfiltered) platform view.
     */
    private fun ApplicationCall.impersonatedOwnerId(): Int? =
        sessions.get<ImpersonationSession>()?.ownerId

    fun setupRoutes(routing: Route) {
        routing {
            static("/static") {
                resources("static")
            }

            sharedBookingRoutes(db) // <- Include shared routes

            get("/login") {
                call.respondHtml {
                    head {
                        meta { charset = "utf-8" }
                        meta {
                            name = "viewport"
                            content = "width=device-width, initial-scale=1"
                        }
                        title { +"Admin Login" }
                        link(rel = "stylesheet", href = "/static/admin.css", type = "text/css")
                    }
                    body {
                        div("center") {
                            div("card") {
                                h2 { +"Admin Login" }
                                p("hint") { +"Sign in to manage shops, staff and services." }
                                form(action = "/login", method = FormMethod.post) {
                                    label { +"Username" }
                                    textInput { name = "username"; autoComplete = false }
                                    label { +"Password" }
                                    passwordInput { name = "password"; autoComplete = false }
                                    br()
                                    submitInput(classes = "btn primary") { value = "Login" }
                                }
                            }
                        }
                    }
                }
            }

            post("/login") {
                val params = call.receiveParameters()
                val username = params["username"] ?: ""
                val password = params["password"] ?: ""

                // Allow credentials to be overridden via environment variables so the password
                // can be changed on Upsun/Platform.sh without a code rebuild.
                // Fallback: hardcoded admin / 1234 (BCrypt hash).
                val envUser = System.getenv("ADMIN_USERNAME")?.trim()?.takeIf { it.isNotBlank() }
                val envPass = System.getenv("ADMIN_PASSWORD")?.trim()?.takeIf { it.isNotBlank() }

                val allowedAdminUsername = envUser ?: "admin"
                val expectedHash = "$2a$12\$bRyq/lqNzQbmYGAzS2V2qexIOd3es/8.URdwPmcamFTBGieqsodpW" // Hash of "1234"

                // If ADMIN_PASSWORD env var is set, compare directly (plain text).
                // Otherwise fall back to BCrypt check against the hardcoded hash.
                val passwordOk = if (envPass != null) {
                    password == envPass
                } else {
                    org.mindrot.jbcrypt.BCrypt.checkpw(password, expectedHash)
                }

                println("[WebAdmin/login] attempt username='$username' " +
                        "envUser=${envUser ?: "(none)"} envPassSet=${envPass != null} " +
                        "usernameMatch=${username == allowedAdminUsername} passwordOk=$passwordOk")

                if (username == allowedAdminUsername && passwordOk) {
                    println("[WebAdmin/login] SUCCESS username='$username'")
                    call.sessions.set(AdminSession(username))
                    call.respondRedirect("/")
                } else {
                    println("[WebAdmin/login] FAILED username='$username'")
                    call.respondHtml {
                        head {
                            meta { charset = "utf-8" }
                            meta {
                                name = "viewport"
                                content = "width=device-width, initial-scale=1"
                            }
                            title { +"Admin Login" }
                            link(rel = "stylesheet", href = "/static/admin.css", type = "text/css")
                        }
                        body {
                            div("center") {
                                div("card") {
                                    h2 { +"Admin Login" }
                                    p("hint") { +"Invalid credentials." }
                                    a(href = "/login", classes = "btn") { +"Try again" }
                                }
                            }
                        }
                    }
                }
            }

            get("/logout") {
                call.sessions.clear<AdminSession>()
                call.respondRedirect("/login")
            }

            intercept(ApplicationCallPipeline.Plugins) {
                val path = call.request.path()

                // Public customer booking endpoints must NOT require admin login.
                // Otherwise booking links sent by SMS (/api/book?token=...) redirect to /login.
                val isPublic = path == "/login"
                        || path == "/owner-login"
                        || path.startsWith("/api/book")
                        || path.startsWith("/api/booking/")
                        || path.startsWith("/api/shops")
                        || path.startsWith("/api/employees")
                        || path.startsWith("/api/services")
                        || path.startsWith("/api/timeslots")
                        || path.startsWith("/static/")

                // Owner-login portal pages are public; owner-portal pages require OwnerSession.
                val isOwnerPortal = path.startsWith("/owner/") || path == "/owner"

                if (!isPublic) {
                    if (isOwnerPortal) {
                        if (call.sessions.get<OwnerSession>() == null) {
                            call.respondRedirect("/owner-login")
                            finish()
                        }
                    } else if (call.sessions.get<AdminSession>() == null) {
                        call.respondRedirect("/login")
                        finish()
                    }
                }
            }

            get("/") {
                val owners = db.getAllOwners()
                call.respondAdminPage(
                    titleText = "Dashboard",
                    subtitle = "Platform-admin view — manage all data and tenants",
                    activePath = "/",
                ) {
                    div("grid-2") {
                        div("panel") {
                            h3 { +"📋 Platform admin" }
                            p { +"Use the navigation to manage global data." }
                            ul {
                                li { +"Shops: address, manager, voice config and opening hours" }
                                li { +"Employees: assign shop + services" }
                                li { +"Services: prices and duration" }
                                li { +"Managers: admin + app logins" }
                            }
                        }
                        div("panel") {
                            h3 { +"🏢 Owners (${owners.size} tenant${if (owners.size != 1) "s" else ""})" }
                            p { +"Each owner is an isolated shop-chain tenant with their own logins." }
                            if (owners.isEmpty()) {
                                p("hint") { +"No owners yet. Create one below." }
                            } else {
                                table {
                                    thead { tr { th { +"Name" }; th { +"Active" }; th { +"Login" }; th { +"Actions" } } }
                                    tbody {
                                        for (o in owners) {
                                            val acct = db.getOwnerAccountByOwnerId(o.id)
                                            tr {
                                                td { +o.name }
                                                td { span("badge ${if (o.active) "ok" else "warn"}") { +(if (o.active) "Yes" else "No") } }
                                                td {
                                                    if (acct != null) span("badge ok") { +acct.username }
                                                    else span("badge warn") { +"No login" }
                                                }
                                                td {
                                                    div("actions") {
                                                        a(href = "/admin/owners/edit?id=${o.id}", classes = "btn") { +"Edit" }
                                                        a(href = "/admin/switch-owner/${o.id}", classes = "btn") { +"View as owner" }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            br()
                            div("actions") {
                                a(href = "/admin/owners", classes = "btn primary") { +"🏢 Manage owners" }
                                +" "
                                a(href = "/owner-login", classes = "btn") { +"Owner Portal login →" }
                            }
                        }
                    }
                }
            }

            get("/twilio/setup") {
                val shops = db.getAllShops()
                val baseUrl = PublicBaseUrl.fromCall(call).trimEnd('/')
                val voiceWebhook = "$baseUrl/api/twilio/voice/welcome"

                call.respondAdminPage(
                    titleText = "Twilio setup",
                    subtitle = "Configure your Twilio number webhooks",
                    activePath = "/twilio/setup",
                ) {
                    div("panel") {
                        p {
                            +"For each shop Twilio number, configure in Twilio Console: Phone Numbers → (number) → Voice & Fax → 'A call comes in' → Webhook (POST)."
                        }
                        p {
                            +"Webhook URL to paste: "
                            code { +voiceWebhook }
                        }
                        p("hint") {
                            +"This endpoint returns TwiML that plays the welcome message (press 1 = SMS booking link, press 2 = forward to operator when open)."
                        }
                    }

                    div("panel") {
                        h3 { +"Shop readiness" }
                        table {
                            thead {
                                tr {
                                    th { +"Shop" }
                                    th { +"Shop Twilio #" }
                                    th { +"Manager (operator)" }
                                    th { +"Business name" }
                                    th { +"Welcome" }
                                    th { +"Opening hours" }
                                    th { +"Actions" }
                                }
                            }
                            tbody {
                                for (s in shops) {
                                    val vc = db.getShopVoiceConfig(s.id)
                                    db.ensureDefaultShopOpeningHours(s.id)
                                    val ohCount = db.getShopOpeningHours(s.id).size
                                    val welcomeOk = vc.welcomeOpenMessage.isNotBlank() && vc.welcomeClosedMessage.isNotBlank()
                                    tr {
                                        td { +s.name }
                                        td { +(vc.twilioNumber ?: "(missing)") }
                                        td {
                                            val managerPhone = db.getManagerPhoneForShop(s.id)
                                            +(managerPhone ?: "(no manager)")
                                        }
                                        td { +(vc.businessName ?: "(not set)") }
                                        td {
                                            span("badge ${if (welcomeOk) "ok" else "warn"}") {
                                                +(if (welcomeOk) "OK" else "Missing")
                                            }
                                        }
                                        td { +"$ohCount/7" }
                                        td {
                                            a(href = "/shops/edit?id=${s.id}", classes = "btn") { +"Edit shop" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            get("/managers") {
                val impOwnerId = call.impersonatedOwnerId()
                val managers = if (impOwnerId != null) db.getManagersByOwner(impOwnerId) else db.getAllManagers()
                call.respondAdminPage(
                    titleText = "Managers",
                    subtitle = "Admin users and mobile app credentials",
                    activePath = "/managers",
                ) {
                    div("grid-2") {
                        div("panel") {
                            h3 { +"All managers" }
                            table {
                                thead {
                                    tr {
                                        th { +"ID" }
                                        th { +"Name" }
                                        th { +"Phone" }
                                        th { +"Username" }
                                        th { +"Actions" }
                                    }
                                }
                                tbody {
                                    for (manager in managers) {
                                        tr {
                                            td { +manager.id.toString() }
                                            td { +manager.name }
                                            td { +manager.phone.toString() }
                                            td { +manager.username }
                                            td {
                                                div("actions") {
                                                    a(href = "/managers/edit?id=${manager.id}", classes = "btn") { +"Edit" }
                                                    a(href = "/managers/delete?id=${manager.id}", classes = "btn danger") {
                                                        onClick = "return confirm('Delete manager ${manager.name}?')"
                                                        +"Delete"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        div("panel") {
                            h3 { +"Add new manager" }
                            form(action = "/managers/add", method = FormMethod.post) {
                                label { +"Full name" }
                                textInput { name = "name"; placeholder = "Jane Doe" }

                                label { +"Username" }
                                textInput { name = "username"; placeholder = "manager01" }

                                label { +"Password" }
                                passwordInput { name = "password"; placeholder = "Password" }

                                label { +"Phone (optional)" }
                                textInput { name = "phone"; placeholder = "+45 12 34 56 78" }

                                br()
                                submitInput(classes = "btn primary") { value = "Add manager" }
                            }
                        }
                    }
                }
            }


            post("/managers/add") {
                val params = call.receiveParameters()
                val name = params["name"] ?: ""
                val username = params["username"] ?: ""
                val password = params["password"] ?: ""
                val phone = params["phone"]
                val impOwnerId = call.impersonatedOwnerId()
                if (impOwnerId != null) {
                    db.addManagerForOwner(impOwnerId, name, username, password, phone)
                } else {
                    db.addManager(name, username, password, phone)
                }
                call.respondRedirect("/managers")
            }

            get("/managers/edit") {
                val manager_id = call.request.queryParameters["id"]?.toIntOrNull()
                if (manager_id == null) {
                    call.respondRedirect("/managers")
                    return@get
                }
                val impOwnerId = call.impersonatedOwnerId()
                if (impOwnerId != null && !db.isManagerOwnedBy(manager_id, impOwnerId)) {
                    call.respondRedirect("/managers")
                    return@get
                }
                val manager = db.getManagerById(manager_id)
                if (manager == null) {
                    call.respondRedirect("/managers")
                    return@get
                }
                call.respondAdminPage(
                    titleText = "Edit manager",
                    subtitle = manager.name,
                    activePath = "/managers",
                ) {
                    div("grid-2") {
                        div("panel") {
                            h3 { +"Details" }
                            form(action = "/managers/edit", method = FormMethod.post) {
                                hiddenInput { name = "id"; value = manager.id.toString() }

                                label { +"Full name" }
                                textInput {
                                    name = "name"
                                    id = "name"
                                    value = manager.name
                                    placeholder = "Manager's full name"
                                }

                                label { +"Phone number" }
                                textInput {
                                    name = "phone"
                                    id = "phone"
                                    value = manager.phone.toString()
                                    placeholder = "e.g., +45 1234 5678"
                                }

                                label { +"Username (for login)" }
                                textInput {
                                    name = "username"
                                    id = "username"
                                    value = manager.username
                                    placeholder = "e.g., manager01"
                                }

                                br()
                                submitInput(classes = "btn primary") { value = "Save changes" }
                            }
                        }

                        div("panel") {
                            h3 { +"📱 Mobile App Login" }
                            val appUser = db.getManagerAppAccountUsername(manager_id!!)
                            if (appUser != null) { p { +"App username: "; b { +appUser } } }
                            else { p { em { +"No app login set yet." } } }
                            form(action = "/managers/app-login", method = FormMethod.post) {
                                hiddenInput { name = "id"; value = manager_id.toString() }
                                label { +"App username" }
                                textInput { name = "app_username"; value = appUser ?: ""; placeholder = "App username" }
                                label { +"New password" }
                                passwordInput { name = "app_password"; placeholder = "New password (required to change)" }
                                br()
                                submitInput(classes = "btn primary") { value = "Save App Login" }
                            }
                            if (appUser != null) {
                                form(action = "/managers/app-login/remove", method = FormMethod.post) {
                                    hiddenInput { name = "id"; value = manager_id.toString() }
                                    br()
                                    submitInput(classes = "btn danger") { value = "Remove App Login" }
                                }
                                form(action = "/managers/force-logout", method = FormMethod.post) {
                                    hiddenInput { name = "id"; value = manager_id.toString() }
                                    submitInput(classes = "btn danger") {
                                        value = "🚨 Force Logout Phone"
                                        attributes["onclick"] = "return confirm('Force logout the manager phone? The current session will be revoked immediately.')"
                                    }
                                }
                            }
                        }
                    }
                }
            }

            post("/managers/app-login") {
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull()
                val u = params["app_username"]?.trim().orEmpty()
                val p = params["app_password"].orEmpty()
                if (id != null && u.isNotBlank() && p.isNotBlank()) db.setManagerAppAccount(id, u, p)
                call.respondRedirect("/managers/edit?id=$id")
            }

            post("/managers/app-login/remove") {
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull()
                if (id != null) db.removeManagerAppAccount(id)
                call.respondRedirect("/managers/edit?id=$id")
            }

            post("/managers/force-logout") {
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull()
                if (id != null) {
                    db.bumpAppAccountTokenVersion("manager", id)
                    println("[WebAdmin/ForceLogout] Force-logged out manager $id")
                }
                call.respondRedirect("/managers/edit?id=$id")
            }

            // POST route: update manager in DB
            post("/managers/edit") {
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull()
                val name = params["name"] ?: ""
                val phone = params["phone"] ?: ""
                val username = params["username"] ?: ""
                val impOwnerId = call.impersonatedOwnerId()
                if (id != null) {
                    if (impOwnerId == null || db.isManagerOwnedBy(id, impOwnerId)) {
                        db.updateManager(id, name, phone, username)
                    }
                }
                call.respondRedirect("/managers")
            }

            get("/managers/delete") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                val impOwnerId = call.impersonatedOwnerId()
                if (id != null) {
                    if (impOwnerId == null || db.isManagerOwnedBy(id, impOwnerId)) {
                        db.deleteManager(id)
                    }
                }
                call.respondRedirect("/managers")
            }


            // Code for shops
            get("/shops") {
                val impOwnerId = call.impersonatedOwnerId()
                val shops = if (impOwnerId != null) db.getShopsByOwner(impOwnerId) else db.getAllShops()
                val managers = (if (impOwnerId != null) db.getManagersByOwner(impOwnerId) else db.getAllManagers()).associateBy { it.id }

                call.respondAdminPage(
                    titleText = "Shops",
                    subtitle = "Shop details, managers, employees and voice config",
                    activePath = "/shops",
                ) {
                    div("panel") {
                        div("actions") {
                            a(href = "/shops/add", classes = "btn primary") { +"Add new shop" }
                        }
                        table {
                            thead {
                                tr {
                                    th { +"ID" }
                                    th { +"Name" }
                                    th { +"Address" }
                                    th { +"Manager" }
                                    th { +"Employees" }
                                    th { +"Actions" }
                                }
                            }
                            tbody {
                                for (shop in shops) {
                                    val shopEmployees = db.getEmployeesForShop(shop.id)
                                    tr {
                                        td { +shop.id.toString() }
                                        td { +shop.name }
                                        td { +(shop.address ?: "") }
                                        td {
                                            val mgr = shop.managerId?.let { managers[it] }
                                            +(mgr?.name ?: "Unassigned")
                                        }
                                        td {
                                            if (shopEmployees.isEmpty()) {
                                                span("badge") { +"No employees" }
                                            } else {
                                                ul {
                                                    shopEmployees.forEach {
                                                        li { +"${it.name} (${it.phone ?: "-"})" }
                                                    }
                                                }
                                            }
                                        }
                                        td {
                                            div("actions") {
                                                a(href = "/shops/edit?id=${shop.id}", classes = "btn") { +"Edit" }
                                                a(href = "/shops/delete?id=${shop.id}", classes = "btn danger") {
                                                    onClick = "return confirm('Delete shop ${shop.name}?')"
                                                    +"Delete"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            get("/shops/add") {
                call.respondAdminPage(
                    titleText = "Add shop",
                    subtitle = "Create a new shop",
                    activePath = "/shops",
                ) {
                    div("panel") {
                        form(action = "/shops/add", method = FormMethod.post) {
                            label { +"Shop name" }
                            p("hint") { +"Shown in the app and used in confirmations." }
                            textInput { name = "name"; placeholder = "Shop Name" }
                            label { +"Address" }
                            p("hint") { +"Will be included in booking confirmation SMS." }
                            textInput { name = "address"; placeholder = "Address" }
                            label { +"Directions" }
                            p("hint") { +"Arrival instructions (door code, parking, floor, etc.). Will be included in booking confirmation SMS." }
                            textArea { name = "directions"; placeholder = "Directions" }
                            br()
                            submitInput(classes = "btn primary") { value = "Add shop" }
                        }
                    }
                }
            }

            post("/shops/add") {
                val params = call.receiveParameters()
                val name = params["name"] ?: ""
                val address = params["address"] ?: ""
                val directions = params["directions"] ?: ""
                val impOwnerId = call.impersonatedOwnerId()
                if (impOwnerId != null) {
                    db.addShopForOwner(impOwnerId, name, address, directions)
                } else {
                    db.addShop(name, address, directions)
                }
                call.respondRedirect("/shops")
            }

            get("/shops/delete") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                val impOwnerId = call.impersonatedOwnerId()
                if (id != null) {
                    if (impOwnerId == null || db.isShopOwnedBy(id, impOwnerId)) {
                        db.deleteShop(id)
                    }
                }
                call.respondRedirect("/shops")
            }

            get("/shops/edit") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respondRedirect("/shops")
                    return@get
                }
                val impOwnerId = call.impersonatedOwnerId()
                if (impOwnerId != null && !db.isShopOwnedBy(id, impOwnerId)) {
                    return@get call.respondRedirect("/shops")
                }
                val shop = db.getShopById(id) ?: return@get call.respondRedirect("/shops")
                val managers = if (impOwnerId != null) db.getManagersByOwner(impOwnerId) else db.getAllManagers()
                val voiceConfig = db.getShopVoiceConfig(id)
                db.ensureDefaultShopOpeningHours(id)
                val openingHours = db.getShopOpeningHours(id).associateBy { it.dayOfWeek }

                fun fmt(min: Int): String {
                    val h = min / 60
                    val m = min % 60
                    return "%02d:%02d".format(h, m)
                }

                call.respondAdminPage(
                    titleText = "Edit shop",
                    subtitle = shop.name,
                    activePath = "/shops",
                ) {
                    div("panel") {
                        form(action = "/shops/edit", method = FormMethod.post) {
                            hiddenInput { name = "id"; value = shop.id.toString() }
                            label { +"Shop name" }
                            p("hint") { +"Shown in the app and used in confirmations." }
                            textInput { name = "name"; value = shop.name }
                            br()
                            label { +"Address" }
                            p("hint") { +"Will be included in booking confirmation SMS." }
                            textInput { name = "address"; value = shop.address ?: "" }
                            br()
                            label { +"Directions" }
                            p("hint") { +"Arrival instructions (door code, parking, floor, etc.). Will be included in booking confirmation SMS." }
                            textArea { name = "directions"; +(shop.directions ?: "") }
                            br()
                            label { +"Assign Manager: " }
                            select {
                                name = "managerId"
                                option { value = ""; +"Unassigned" }
                                for (mgr in managers) {
                                    option {
                                        value = mgr.id.toString()
                                        if (mgr.id == shop.managerId) selected = true
                                        +mgr.name
                                    }
                                }
                            }

                            hr()
                            h3 { +"Twilio Voice / Welcome message" }

                            label { +"Shop Twilio number (E.164):" }
                            br()
                            textInput {
                                name = "twilio_number"
                                value = voiceConfig.twilioNumber ?: ""
                                placeholder = "+4512345678"
                            }
                            br(); br()

                            label { +"Operator phone (E.164) for call forwarding:" }
                            br()
                            p { em { +"Operator: derived automatically from the shop's assigned manager phone." } }
                            // business_name
                            label { +"Business name (spoken in whisper)" }
                            textInput {
                                name = "business_name"
                                value = voiceConfig.businessName ?: ""
                                placeholder = "My Salon"
                            }
                            br(); br()

                            label { +"Welcome message (OPEN):" }
                            br()
                            textArea {
                                name = "welcome_open_message"
                                style = "width: 100%; height: 80px;"
                                +voiceConfig.welcomeOpenMessage
                            }
                            br(); br()

                            label { +"Welcome message (CLOSED):" }
                            br()
                            textArea {
                                name = "welcome_closed_message"
                                style = "width: 100%; height: 80px;"
                                +voiceConfig.welcomeClosedMessage
                            }
                            br(); br()

                            label { +"Price list SMS footer (appended after generated price list):" }
                            br()
                            p("hint") { +"Text appended at the end when the manager taps 'Send price list' in the messaging interface. Leave blank for no footer." }
                            textArea {
                                name = "sms_price_list_footer"
                                style = "width: 100%; height: 60px;"
                                +(voiceConfig.smsPriceListFooter ?: "")
                            }

                            hr()
                            h3 { +"Data retention (per shop)" }
                            p("hint") {
                                +"Communication history (SMS + calls) and customer profiles are automatically deleted after the configured number of days when there is no new activity."
                            }
                            label { +"Communication history — keep for (days)" }
                            br()
                            numberInput {
                                name = "communication_retention_days"
                                value = voiceConfig.communicationRetentionDays.toString()
                                min = "1"; max = "365"; step = "1"
                            }
                            p("hint") { +"Default: 5 days. SMS messages and ended calls older than this are deleted from the database." }
                            br()
                            label { +"Customer profile — keep for (days without booking/contact)" }
                            br()
                            numberInput {
                                name = "customer_retention_days"
                                value = voiceConfig.customerRetentionDays.toString()
                                min = "1"; max = "3650"; step = "1"
                            }
                            p("hint") { +"Default: 90 days (≈3 months). Customer records with no appointment, SMS or call within this window are deleted." }

                            hr()
                            h3 { +"Opening hours" }

                            table {
                                fun row(dow: Int, labelText: String) {
                                    val row = openingHours[dow]
                                    tr {
                                        td { +labelText }
                                        td {
                                            checkBoxInput {
                                                name = "oh_${dow}_closed"
                                                if (row?.closed == true) checked = true
                                            }
                                            +" Closed"
                                        }
                                        td {
                                            +"Open: "
                                            textInput {
                                                name = "oh_${dow}_open"
                                                value = fmt(row?.openMinute ?: (9 * 60))
                                                placeholder = "09:00"
                                            }
                                        }
                                        td {
                                            +"Close: "
                                            textInput {
                                                name = "oh_${dow}_close"
                                                value = fmt(row?.closeMinute ?: (17 * 60))
                                                placeholder = "17:00"
                                            }
                                        }
                                    }
                                }
                                row(1, "Mon")
                                row(2, "Tue")
                                row(3, "Wed")
                                row(4, "Thu")
                                row(5, "Fri")
                                row(6, "Sat")
                                row(7, "Sun")
                            }

                            br()
                            submitInput(classes = "btn primary") { value = "Save Changes" }
                        }

                        hr()
                        h3 { +"📱 Mobile App Login (in-shop use)" }
                        val shopAppUser = db.getShopAppAccountUsername(id)
                        if (shopAppUser != null) { p { +"App username: "; b { +shopAppUser } } }
                        else { p { em { +"No in-shop app login set yet." } } }
                        form(action = "/shops/app-login", method = FormMethod.post) {
                            hiddenInput { name = "id"; value = id.toString() }
                            textInput { name = "app_username"; value = shopAppUser ?: ""; placeholder = "App username" }
                            +" "
                            passwordInput { name = "app_password"; placeholder = "New password (required to change)" }
                            +" "
                            submitInput(classes = "btn primary") { value = "Save App Login" }
                        }
                        if (shopAppUser != null) {
                            form(action = "/shops/app-login/remove", method = FormMethod.post) {
                                hiddenInput { name = "id"; value = id.toString() }
                                submitInput(classes = "btn danger") { value = "Remove App Login" }
                            }
                            form(action = "/shops/force-logout", method = FormMethod.post) {
                                hiddenInput { name = "id"; value = id.toString() }
                                submitInput(classes = "btn danger") {
                                    value = "🚨 Force Logout Phone"
                                    attributes["onclick"] = "return confirm('Force logout the shop phone? The current session will be revoked immediately.')"
                                }
                            }
                        }
                    }
                }
            }

            post("/shops/app-login") {
                val params = call.receiveParameters()
                val sid = params["id"]?.toIntOrNull()
                val u = params["app_username"]?.trim().orEmpty()
                val p = params["app_password"].orEmpty()
                if (sid != null && u.isNotBlank() && p.isNotBlank()) db.setShopAppAccount(sid, u, p)
                call.respondRedirect("/shops/edit?id=$sid")
            }

            post("/shops/app-login/remove") {
                val params = call.receiveParameters()
                val sid = params["id"]?.toIntOrNull()
                if (sid != null) db.removeShopAppAccount(sid)
                call.respondRedirect("/shops/edit?id=$sid")
            }

            post("/shops/force-logout") {
                val params = call.receiveParameters()
                val sid = params["id"]?.toIntOrNull()
                if (sid != null) {
                    db.bumpAppAccountTokenVersion("shop", sid)
                    println("[WebAdmin/ForceLogout] Force-logged out shop $sid")
                }
                call.respondRedirect("/shops/edit?id=$sid")
            }

            post("/shops/edit") {
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull()
                val name = params["name"] ?: ""
                val address = params["address"] ?: ""
                val directions = params["directions"] ?: ""
                val managerId = params["managerId"]?.toIntOrNull()

                if (id != null) {
                    db.updateShop(id, name, address, directions, managerId)

                    // Voice config — operator_phone removed; operator comes from manager phone
                    val voice = ShopVoiceConfig(
                        shopId = id,
                        twilioNumber = params["twilio_number"]?.trim()?.takeIf { it.isNotBlank() },
                        businessName = params["business_name"]?.trim()?.takeIf { it.isNotBlank() },
                        temporaryOperatorClosed = params["temporary_operator_closed"] == "on",
                        temporaryOperatorClosedMessage = params["temporary_operator_closed_message"]?.trim().orEmpty()
                            .ifBlank { ShopVoiceConfig(id).temporaryOperatorClosedMessage },
                        welcomeOpenMessage = params["welcome_open_message"]?.trim().orEmpty().ifBlank { ShopVoiceConfig(id).welcomeOpenMessage },
                        welcomeClosedMessage = params["welcome_closed_message"]?.trim().orEmpty().ifBlank { ShopVoiceConfig(id).welcomeClosedMessage },
                        communicationRetentionDays = params["communication_retention_days"]?.toIntOrNull()?.coerceAtLeast(1) ?: 5,
                        customerRetentionDays = params["customer_retention_days"]?.toIntOrNull()?.coerceAtLeast(1) ?: 90,
                        smsPriceListFooter = params["sms_price_list_footer"]?.trim()?.takeIf { it.isNotBlank() },
                    )
                    db.upsertShopVoiceConfig(voice)

                    // Opening hours
                    fun parseMin(s: String?): Int? {
                        if (s.isNullOrBlank()) return null
                        val parts = s.trim().split(":")
                        if (parts.size != 2) return null
                        val h = parts[0].toIntOrNull() ?: return null
                        val m = parts[1].toIntOrNull() ?: return null
                        if (h !in 0..23 || m !in 0..59) return null
                        return h * 60 + m
                    }

                    val rows = (1..7).map { dow ->
                        val closed = params["oh_${dow}_closed"] != null
                        val openMin = parseMin(params["oh_${dow}_open"]) ?: 9 * 60
                        val closeMin = parseMin(params["oh_${dow}_close"]) ?: 17 * 60
                        ShopOpeningHours(
                            shopId = id,
                            dayOfWeek = dow,
                            openMinute = openMin,
                            closeMinute = closeMin,
                            closed = closed,
                        )
                    }
                    db.upsertShopOpeningHours(rows)
                }
                call.respondRedirect("/shops")
            }


            // Employees
            get("/employees") {
                val impOwnerId = call.impersonatedOwnerId()
                val employees = if (impOwnerId != null) db.getEmployeesByOwner(impOwnerId) else db.getAllEmployees()
                val shops = if (impOwnerId != null) db.getShopsByOwner(impOwnerId) else db.getAllShops()
                val services = if (impOwnerId != null) db.getServicesByOwner(impOwnerId) else db.getAllServices()
                val employeeShopMap = employees.associateWith { emp -> db.getShopIdForEmployee(emp.id) }
                val shopsById = shops.associateBy { it.id }
                val employeeServicesMap = employees.associateWith { emp -> db.getServicesForEmployee(emp.id) }

                call.respondAdminPage(
                    titleText = "Employees",
                    subtitle = "Assign employees to a shop and services",
                    activePath = "/employees",
                ) {
                    div("grid-2") {
                        div("panel") {
                            h3 { +"All employees" }
                            table {
                                thead {
                                    tr {
                                        listOf("ID", "Name", "Phone", "Shop", "Services", "Actions").forEach {
                                            th { +it }
                                        }
                                    }
                                }
                                tbody {
                                    for (emp in employees) {
                                        tr {
                                            td { +emp.id.toString() }
                                            td { +emp.name }
                                            td { +(emp.phone ?: "") }

                                            // Shop
                                            td {
                                                val shopId = employeeShopMap[emp]
                                                val shopName = shopId?.let { shopsById[it]?.name } ?: "None"
                                                +shopName
                                            }

                                            // Services
                                            td {
                                                val empServices = employeeServicesMap[emp].orEmpty()
                                                if (empServices.isEmpty()) {
                                                    span("badge") { +"No services" }
                                                } else {
                                                    ul {
                                                        for (srv in empServices.take(4)) {
                                                            li { +"${srv.name} (${srv.price} kr)" }
                                                        }
                                                        if (empServices.size > 4) {
                                                            li { span("badge") { +"+${empServices.size - 4} more" } }
                                                        }
                                                    }
                                                }
                                            }

                                            // Actions
                                            td {
                                                div("actions") {
                                                    a(href = "/employees/edit?id=${emp.id}", classes = "btn") { +"Edit" }
                                                    a(href = "/employees/delete?id=${emp.id}", classes = "btn danger") {
                                                        onClick = "return confirm('Delete employee ${emp.name}?')"
                                                        +"Delete"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        div("panel") {
                            h3 { +"Add new employee" }
                            form(action = "/employees/add", method = FormMethod.post) {
                                label { +"Full name" }
                                textInput { name = "name"; placeholder = "Full Name" }
                                label { +"Phone (optional)" }
                                textInput { name = "phone"; placeholder = "+45 12 34 56 78" }
                                br()
                                submitInput(classes = "btn primary") { value = "Add employee" }
                            }
                        }
                    }
                }
            }

            post("/employees/add") {
                val params = call.receiveParameters()
                val name = params["name"] ?: ""
                val phone = params["phone"]
                val impOwnerId = call.impersonatedOwnerId()
                if (impOwnerId != null) {
                    db.addEmployeeForOwner(impOwnerId, name, phone)
                } else {
                    db.addEmployee(name, phone)
                }
                call.respondRedirect("/employees")
            }

            get("/employees/delete") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                val impOwnerId = call.impersonatedOwnerId()
                if (id != null) {
                    val isOwned = impOwnerId == null || db.getEmployeesByOwner(impOwnerId).any { it.id == id }
                    if (isOwned) db.deleteEmployee(id)
                }
                call.respondRedirect("/employees")
            }

            get("/employees/edit") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respondRedirect("/employees")
                    return@get
                }

                val impOwnerId = call.impersonatedOwnerId()
                if (impOwnerId != null && !db.getEmployeesByOwner(impOwnerId).any { it.id == id }) {
                    call.respondRedirect("/employees")
                    return@get
                }

                val employee = db.getEmployeeById(id)
                if (employee == null) {
                    call.respondRedirect("/employees")
                    return@get
                }

                val shops = if (impOwnerId != null) db.getShopsByOwner(impOwnerId) else db.getAllShops()
                val currentShopId = db.getShopIdForEmployee(id)

                val allServices = db.getAllServices()
                val employeeServices = db.getServicesForEmployee(id).associateBy { it.id }

                call.respondAdminPage(
                    titleText = "Edit employee",
                    subtitle = employee.name,
                    activePath = "/employees",
                ) {
                    div("grid-2") {
                        div("panel") {
                            h3 { +"Details" }
                            form(action = "/employees/edit", method = FormMethod.post) {
                                hiddenInput { name = "id"; value = employee.id.toString() }
                                label { +"Name" }
                                textInput {
                                    name = "name"
                                    value = employee.name
                                }
                                label { +"Phone" }
                                textInput {
                                    name = "phone"
                                    value = employee.phone ?: ""
                                }
                                label { +"Shop" }
                                select {
                                    name = "shopId"
                                    option { value = ""; +"-- No Shop --" }
                                    for (shop in shops) {
                                        option {
                                            value = shop.id.toString()
                                            if (shop.id == currentShopId) selected = true
                                            +shop.name
                                        }
                                    }
                                }
                                br()
                                submitInput(classes = "btn primary") { value = "Save changes" }
                            }
                        }

                        div("panel") {
                            h3 { +"Services" }
                            p("hint") { +"Assign services this employee can perform." }

                            // Assign
                            form(action = "/employees/services/assign", method = FormMethod.post) {
                                hiddenInput { name = "employee_id"; value = employee.id.toString() }
                                label { +"Add service" }
                                select {
                                    name = "service_id"
                                    for (svc in allServices) {
                                        option {
                                            value = svc.id.toString()
                                            +"${svc.name} (${svc.price} kr)"
                                        }
                                    }
                                }
                                br()
                                submitInput(classes = "btn") { value = "Assign" }
                            }

                            hr()
                            if (employeeServices.isEmpty()) {
                                span("badge") { +"No services assigned" }
                            } else {
                                table {
                                    thead {
                                        tr {
                                            th { +"Service" }
                                            th { +"Price" }
                                            th { +"Actions" }
                                        }
                                    }
                                    tbody {
                                        for (svc in employeeServices.values) {
                                            tr {
                                                td { +svc.name }
                                                td { +"${svc.price} kr" }
                                                td {
                                                    div("actions") {
                                                        a(
                                                            href = "/employees/services/unassign?employee_id=${employee.id}&service_id=${svc.id}",
                                                            classes = "btn danger",
                                                        ) { +"Unassign" }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }


            post("/employees/edit") {
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull()
                val name = params["name"] ?: ""
                val phone = params["phone"]
                val shopId = params["shopId"]?.toIntOrNull()
                val impOwnerId = call.impersonatedOwnerId()

                if (id != null) {
                    val isOwned = impOwnerId == null || db.getEmployeesByOwner(impOwnerId).any { it.id == id }
                    if (isOwned) {
                        db.updateEmployee(id, name, phone)
                        val validShopId = if (impOwnerId != null && shopId != null && !db.isShopOwnedBy(shopId, impOwnerId)) null else shopId
                        if (validShopId != null) {
                            db.assignEmployeeToShop(id, validShopId, exclusive = true)
                        } else {
                            db.removeEmployeeFromAllShops(id)
                        }
                    }
                }

                call.respondRedirect("/employees")
            }

            // Service assignment (moved from /assign-services page)
            post("/employees/services/assign") {
                val params = call.receiveParameters()
                val empId = params["employee_id"]?.toIntOrNull()
                val svcId = params["service_id"]?.toIntOrNull()
                if (empId != null && svcId != null) {
                    db.assignServiceToEmployee(empId, svcId)
                }
                call.respondRedirect("/employees/edit?id=$empId")
            }

            get("/employees/services/unassign") {
                val empId = call.request.queryParameters["employee_id"]?.toIntOrNull()
                val svcId = call.request.queryParameters["service_id"]?.toIntOrNull()
                if (empId != null && svcId != null) {
                    db.removeServiceFromEmployee(empId, svcId)
                }
                call.respondRedirect("/employees/edit?id=$empId")
            }

            // Services
            get("/services") {
                val impOwnerId = call.impersonatedOwnerId()
                val services = if (impOwnerId != null) db.getServicesByOwner(impOwnerId) else db.getAllServices()
                call.respondAdminPage(
                    titleText = "Services",
                    subtitle = "Manage service catalog (price + duration)",
                    activePath = "/services",
                ) {
                    div("grid-2") {
                        div("panel") {
                            h3 { +"All services" }
                            table {
                                thead {
                                    tr {
                                        th { +"ID" }
                                        th { +"Name" }
                                        th { +"Price" }
                                        th { +"Duration" }
                                        th { +"Actions" }
                                    }
                                }
                                tbody {
                                    for (service in services) {
                                        tr {
                                            td { +service.id.toString() }
                                            td { +service.name }
                                            td { +"${"%.2f".format(service.price)} kr" }
                                            td { +"${service.duration} min" }
                                            td {
                                                div("actions") {
                                                    a(href = "/services/edit?id=${service.id}", classes = "btn") { +"Edit" }
                                                    a(href = "/services/delete?id=${service.id}", classes = "btn danger") {
                                                        onClick = "return confirm('Delete service ${service.name}?')"
                                                        +"Delete"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        div("panel") {
                            h3 { +"Add new service" }
                            form(action = "/services/add", method = FormMethod.post) {
                                label { +"Name" }
                                textInput { name = "name"; placeholder = "Service Name" }
                                label { +"Price (kr)" }
                                numberInput {
                                    name = "price"
                                    placeholder = "Price"
                                    step = "0.01"
                                }
                                label { +"Duration (minutes)" }
                                numberInput {
                                    name = "duration"
                                    placeholder = "Duration"
                                    step = "5"
                                }
                                br()
                                submitInput(classes = "btn primary") { value = "Add service" }
                            }
                        }
                    }
                }
            }

            post("/services/add") {
                val params = call.receiveParameters()
                val name = params["name"] ?: ""
                val price = params["price"]?.toDoubleOrNull() ?: 0.0
                val duration = params["duration"]?.toIntOrNull() ?: 0
                val impOwnerId = call.impersonatedOwnerId()
                if (impOwnerId != null) {
                    db.addServiceForOwner(impOwnerId, name, price, duration)
                } else {
                    db.addService(name, price, duration)
                }
                call.respondRedirect("/services")
            }

            get("/services/delete") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                val impOwnerId = call.impersonatedOwnerId()
                if (id != null) {
                    val isOwned = impOwnerId == null || db.getServicesByOwner(impOwnerId).any { it.id == id }
                    if (isOwned) db.deleteService(id)
                }
                call.respondRedirect("/services")
            }

            get("/services/edit") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respondRedirect("/services")
                    return@get
                }
                val impOwnerId = call.impersonatedOwnerId()
                if (impOwnerId != null && !db.getServicesByOwner(impOwnerId).any { it.id == id }) {
                    call.respondRedirect("/services")
                    return@get
                }
                val service = db.getServiceById(id)
                if (service == null) {
                    call.respondRedirect("/services")
                    return@get
                }
                call.respondAdminPage(
                    titleText = "Edit service",
                    subtitle = service.name,
                    activePath = "/services",
                ) {
                    div("panel") {
                        form(action = "/services/edit", method = FormMethod.post) {
                            hiddenInput { name = "id"; value = service.id.toString() }
                            label { +"Name" }
                            textInput { name = "name"; value = service.name }
                            label { +"Price (kr)" }
                            numberInput {
                                name = "price"
                                value = "%.2f".format(service.price)
                                step = "0.01"
                            }
                            label { +"Duration (min)" }
                            numberInput {
                                name = "duration"
                                value = "%d".format(service.duration)
                                step = "1"
                            }
                            br()
                            submitInput(classes = "btn primary") { value = "Save changes" }
                        }
                    }
                }
            }

            post("/services/edit") {
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull()
                val name = params["name"] ?: ""
                val price = params["price"]?.toDoubleOrNull() ?: 0.0
                val duration = params["duration"]?.toInt() ?: 0
                val impOwnerId = call.impersonatedOwnerId()
                if (id != null) {
                    val isOwned = impOwnerId == null || db.getServicesByOwner(impOwnerId).any { it.id == id }
                    if (isOwned) db.updateService(id, name, price, duration)
                }
                call.respondRedirect("/services")
            }

            get("/assign-services") {
                val employees = db.getAllEmployees()
                val services = db.getAllServices()
                val assignments = db.getAllEmployeeServiceRelations()

                call.respondAdminPage(
                    titleText = "Assign services",
                    subtitle = "(Deprecated) Use Employees → Edit to manage assignments.",
                    activePath = "/employees",
                ) {
                    div("panel") {
                        p("hint") { +"This page is kept for backward compatibility. Please use Employees → Edit." }
                        div("actions") {
                            a(href = "/employees", classes = "btn primary") { +"Go to Employees" }
                        }
                    }

                    // Still show something useful here for now
                    div("panel") {
                        h3 { +"Quick assign" }
                        form(action = "/assign-services/add", method = FormMethod.post) {
                            label { +"Employee" }
                            select {
                                name = "employeeId"
                                for (emp in employees) {
                                    option { value = emp.id.toString(); +emp.name }
                                }
                            }
                            label { +"Service" }
                            select {
                                name = "serviceId"
                                for (svc in services) {
                                    option { value = svc.id.toString(); +svc.name }
                                }
                            }
                            br()
                            submitInput(classes = "btn") { value = "Assign" }
                        }

                        hr()
                        h3 { +"Current assignments" }
                        val grouped = assignments.groupBy { it.first }
                        if (grouped.isEmpty()) {
                            span("badge") { +"No assignments" }
                        } else {
                            for ((employee, svcs) in grouped) {
                                div("panel") {
                                    h3 { +"${employee.name} (${employee.phone})" }
                                    ul {
                                        for ((_, service) in svcs) {
                                            li {
                                                +"${service.name} (${service.price} kr)"
                                                +" "
                                                a(
                                                    href = "/unassign-service?employee_id=${employee.id}&service_id=${service.id}",
                                                    classes = "btn danger",
                                                ) { +"Unassign" }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            post("/assign-services/add") {
                val params = call.receiveParameters()
                val empId = params["employeeId"]?.toIntOrNull()
                val svcId = params["serviceId"]?.toIntOrNull()
                if (empId != null && svcId != null) {
                    db.assignServiceToEmployee(empId, svcId)
                }
                call.respondRedirect("/assign-services")
            }

            get("/unassign-service") {
                val empId = call.request.queryParameters["employee_id"]?.toIntOrNull()
                val svcId = call.request.queryParameters["service_id"]?.toIntOrNull()
                if (empId != null && svcId != null) {
                    db.removeServiceFromEmployee(empId, svcId)
                }
                call.respondRedirect("/assign-services")
            }

            // Appointments
            // Show all appointments
            get("/appointments") {
                val appointments = db.getAllAppointments()
                val employees = db.getAllEmployees().associateBy { it.id }
                val shops = db.getAllShops().associateBy { it.id }

                call.respondAdminPage(
                    titleText = "Appointments",
                    subtitle = "Read-only overview (bookings are created via public booking flow)",
                    activePath = "/appointments",
                ) {
                    div("panel") {
                        table {
                            thead {
                                tr {
                                    th { +"ID" }
                                    th { +"Employee" }
                                    th { +"Shop" }
                                    th { +"Date/Time" }
                                    th { +"Duration" }
                                    th { +"Price" }
                                    th { +"Services" }
                                }
                            }
                            tbody {
                                for (appt in appointments) {
                                    val services = db.getServicesForAppointment(appt.id)
                                    tr {
                                        td { +"${appt.id}" }
                                        td { +employees[appt.employeeId]?.name.orEmpty() }
                                        td { +shops[appt.shopId]?.name.orEmpty() }
                                        td { +java.time.Instant.ofEpochMilli(appt.dateTime).toString() }
                                        td { +"${appt.duration} min" }
                                        td { +"${"%.2f".format(appt.price)} DKK" }
                                        td {
                                            if (services.isEmpty()) {
                                                span("badge") { +"–" }
                                            } else {
                                                ul {
                                                    services.forEach { s ->
                                                        li { +"${s.name} (${s.price} DKK)" }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Show form to create a new appointment
            get("/appointments/add") {
                call.respondText(
                    """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Book Appointment</title></head>
                    <body>
                    ${BookingUI.getFormHtml(shopId = 1, customerId = 1)}
                    </body>
                    </html>
                    """.trimIndent(),
                    ContentType.Text.Html
                )
            }



            get("/test-booking-link") {
                val shops = db.getAllShops()

                call.respondAdminPage(
                    titleText = "Booking link tools",
                    subtitle = "Generate a booking link (or SMS text) for a phone number",
                    activePath = "/test-booking-link",
                ) {
                    div("grid-2") {
                        div("panel") {
                            h3 { +"Generate booking link" }
                            form(action = "/test-booking-link", method = FormMethod.post) {
                                label { +"Shop" }
                                select {
                                    id = "shopSelect"
                                    name = "shop_id"
                                    required = true
                                    for (s in shops) {
                                        option { value = s.id.toString(); +s.name }
                                    }
                                }
                                label { +"Phone" }
                                textInput {
                                    name = "phone"
                                    required = true
                                    placeholder = "+45 12 34 56 78"
                                }
                                br()
                                submitInput(classes = "btn primary") { value = "Generate link" }
                            }
                        }

                        div("panel") {
                            h3 { +"Generate SMS text" }
                            p("hint") { +"Uses /api/booking/create to generate the message body." }
                            form(action = "/api/booking/create", method = FormMethod.post) {
                                label { +"Shop" }
                                select {
                                    id = "shopSelect2"
                                    name = "shop_id"
                                    required = true
                                    for (s in shops) {
                                        option { value = s.id.toString(); +s.name }
                                    }
                                }
                                label { +"Phone" }
                                textInput {
                                    name = "phone"
                                    required = true
                                    placeholder = "+45 12 34 56 78"
                                }
                                br()
                                submitInput(classes = "btn") { value = "Generate SMS text" }
                            }
                        }
                    }
                }
            }

            post("/test-booking-link") {
                    val params = call.receiveParameters()
                    val shopId = params["shop_id"]?.toIntOrNull()
                    val phone = params["phone"]

                    if (shopId == null || phone == null) {
                        return@post call.respond(HttpStatusCode.BadRequest, "Missing shop_id or phone")
                    }

                    val customerId = db.ensureCustomerByPhone(phone) // implement if needed
                    val booking_token = db.generateBookingToken( customerId, shopId, phone)

                    val baseUrl = PublicBaseUrl.fromCall(call)
                    val fullUrl = "$baseUrl/api/book?token=$booking_token"

                    call.respondText(
                        """<html><body>Generated link: <a href="$fullUrl">$fullUrl</a></body></html>""",
                        ContentType.Text.Html
                    )
                }

            // ── Customers ──────────────────────────────────────────────────────
            // List page with optional search
            get("/customers") {
                val search = call.request.queryParameters["q"]?.trim()
                val customers = db.getAllCustomers(search)

                call.respondAdminPage(
                    titleText = "Customers",
                    subtitle = "Browse and verify customer records. 'New' = auto-created on first call/booking. Blacklist is per-shop.",
                    activePath = "/customers",
                ) {
                    div("panel") {
                        // Search bar
                        form(action = "/customers", method = FormMethod.get) {
                            div("actions") {
                                textInput {
                                    name = "q"
                                    value = search ?: ""
                                    placeholder = "Search by name or phone…"
                                    style = "min-width:260px"
                                }
                                submitInput(classes = "btn") { value = "Search" }
                                if (!search.isNullOrBlank()) {
                                    a(href = "/customers", classes = "btn") { +"Clear" }
                                }
                            }
                        }

                        if (customers.isEmpty()) {
                            p("hint") { +"No customers found${if (!search.isNullOrBlank()) " for \"$search\"" else ""}." }
                        } else {
                            p("hint") { +"${customers.size} customer(s)${if (!search.isNullOrBlank()) " matching \"$search\"" else ""}." }
                            table {
                                thead {
                                    tr {
                                        th { +"ID" }
                                        th { +"Name" }
                                        th { +"Phone" }
                                        th { +"CallApp" }
                                        th { +"CRM status" }
                                        th { +"Blacklisted" }
                                        th { +"Bookings" }
                                        th { +"Actions" }
                                    }
                                }
                                tbody {
                                    for (c in customers) {
                                        val bookingCount = db.getAppointmentCountForCustomer(c.id)
                                        val blacklistShops = db.getBlacklistShopsForPhone(c.phone)
                                        tr {
                                            td { +c.id.toString() }
                                            td { +(c.name.takeIf { it.isNotBlank() && it != "NoName" } ?: "—") }
                                            td { +(c.phone.takeIf { it.isNotBlank() } ?: "—") }
                                            td {
                                                if (!c.callappName.isNullOrBlank()) {
                                                    span("badge ok") { +"📇 ${c.callappName}" }
                                                } else {
                                                    span { style = "color:#aaa"; +"—" }
                                                }
                                            }
                                            td {
                                                // "New" = auto-created stub; any other value = manually set CRM label
                                                span("badge ${if (c.status == "New") "warn" else "ok"}") {
                                                    +(c.status.ifBlank { "—" })
                                                }
                                            }
                                            td {
                                                if (blacklistShops.isEmpty()) {
                                                    span("badge ok") { +"No" }
                                                } else {
                                                    span("badge danger") {
                                                        +"Yes — ${blacklistShops.joinToString(", ") { it.second }}"
                                                    }
                                                }
                                            }
                                            td { +bookingCount.toString() }
                                            td {
                                                div("actions") {
                                                    a(href = "/customers/edit?id=${c.id}", classes = "btn") { +"Edit" }
                                                    a(href = "/customers/delete?id=${c.id}", classes = "btn danger") {
                                                        onClick = "return confirm('Delete customer ${c.id} (${c.phone})? This cannot be undone.')"
                                                        +"Delete"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Edit customer: all fields editable + blacklist info + delete
            get("/customers/edit") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@get call.respondRedirect("/customers")
                val customer = db.getCustomerById(id)
                    ?: return@get call.respondRedirect("/customers")
                val bookingCount = db.getAppointmentCountForCustomer(id)
                val recentAppts = db.getAppointmentsForCustomer(id).take(10)
                val blacklistShops = db.getBlacklistShopsForPhone(customer.phone)

                call.respondAdminPage(
                    titleText = "Edit customer",
                    subtitle = "ID ${customer.id} · ${customer.phone}",
                    activePath = "/customers",
                ) {
                    div("grid-2") {
                        div("panel") {
                            h3 { +"Customer details" }
                            form(action = "/customers/edit", method = FormMethod.post) {
                                hiddenInput { name = "id"; value = customer.id.toString() }

                                label { +"Name" }
                                textInput {
                                    name = "name"
                                    value = customer.name.takeIf { it != "NoName" } ?: ""
                                    placeholder = "Customer name"
                                }

                                label { +"Phone (E.164)" }
                                textInput {
                                    name = "phone"
                                    value = customer.phone
                                    placeholder = "+4512345678"
                                }

                                label { +"CRM status" }
                                p("hint") { +"'New' means auto-created from an incoming call or booking. You can change this to any label (e.g. VIP, Regular)." }
                                textInput {
                                    name = "status"
                                    value = customer.status
                                    placeholder = "New"
                                }

                                label { +"Payment method" }
                                select {
                                    name = "payment"
                                    listOf(
                                        "0" to "— Unknown",
                                        "1" to "Cash",
                                        "2" to "Card",
                                        "3" to "MobilePay",
                                        "4" to "PayPal",
                                        "5" to "Revolut",
                                    ).forEach { (v, lbl) ->
                                        option {
                                            value = v
                                            selected = (customer.payment.toString() == v)
                                            +lbl
                                        }
                                    }
                                }

                                label { +"Preferred language" }
                                select {
                                    name = "language"
                                    listOf(
                                        "0" to "— Unknown",
                                        "1" to "English",
                                        "2" to "Danish",
                                    ).forEach { (v, lbl) ->
                                        option {
                                            value = v
                                            selected = (customer.language.toString() == v)
                                            +lbl
                                        }
                                    }
                                }

                                br()
                                submitInput(classes = "btn primary") { value = "Save changes" }
                                +" "
                                a(href = "/customers", classes = "btn") { +"Cancel" }
                            }

                            hr()
                            h3 { +"Blacklist status" }
                            if (blacklistShops.isEmpty()) {
                                p { span("badge ok") { +"Not blacklisted in any shop" } }
                            } else {
                                p { span("badge danger") { +"Blacklisted in: ${blacklistShops.joinToString(", ") { it.second }}" } }
                                p("hint") { +"To remove from blacklist, go to the relevant shop's Blacklist page in the mobile app, or use the Blacklist API." }
                            }

                            hr()
                            h3 { +"Danger zone" }
                            form(action = "/customers/delete", method = FormMethod.post) {
                                hiddenInput { name = "id"; value = customer.id.toString() }
                                p("hint") { +"Deletes this customer record. Appointment history is retained for auditing." }
                                submitInput(classes = "btn danger") {
                                    value = "Delete customer"
                                    onClick = "return confirm('Delete customer ${customer.id} (${customer.phone})? This cannot be undone.')"
                                }
                            }
                        }

                        div("panel") {
                            // ── CallApp directory info ─────────────────────────
                            val screening = db.getCustomerCallAppScreening(customer.id)
                            h3 { +"📇 CallApp directory" }
                            if (screening == null) {
                                p("hint") { +"Not yet screened — screening runs in the background every 6 hours." }
                            } else if (!screening.found) {
                                p {
                                    span("badge warn") { +"Not found in directory" }
                                    +" (failures: ${screening.failureCount})"
                                }
                                if (!screening.apiMessage.isNullOrBlank()) {
                                    p("hint") { +"API message: ${screening.apiMessage}" }
                                }
                            } else {
                                p {
                                    span("badge ok") { +"📇 ${screening.name ?: "—"}" }
                                    if (screening.priority != null) {
                                        +" · Priority ${screening.priority}"
                                    }
                                }
                                if (screening.screenedAt > 0) {
                                    val checkedFmt = java.time.Instant.ofEpochMilli(screening.screenedAt)
                                        .atZone(java.time.ZoneId.of("Europe/Copenhagen"))
                                        .format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"))
                                    p("hint") { +"Last checked: $checkedFmt" }
                                }
                            }

                            hr()
                            div("actions") {
                                a(href = "/customers/comms?id=${customer.id}", classes = "btn") {
                                    +"📨 Communication history (SMS + calls)"
                                }
                            }
                            hr()
                            h3 { +"Appointment history ($bookingCount total)" }
                            if (recentAppts.isEmpty()) {
                                p("hint") { +"No bookings found." }
                            } else {
                                table {
                                    thead {
                                        tr {
                                            th { +"Date" }
                                            th { +"Shop" }
                                            th { +"Services" }
                                            th { +"Price" }
                                        }
                                    }
                                    tbody {
                                        val shops = db.getAllShops().associateBy { it.id }
                                        for (appt in recentAppts) {
                                            val dt = java.time.Instant.ofEpochMilli(appt.dateTime)
                                                .atZone(java.time.ZoneId.of("Europe/Copenhagen"))
                                                .format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"))
                                            tr {
                                                td { +dt }
                                                td { +(shops[appt.shopId]?.name ?: "#${appt.shopId}") }
                                                td {
                                                    if (appt.services.isEmpty()) {
                                                        span("badge") { +"–" }
                                                    } else {
                                                        +appt.services.joinToString(", ") { it.name }
                                                    }
                                                }
                                                td { +"${"%.2f".format(appt.price)} DKK" }
                                            }
                                        }
                                        if (bookingCount > 10) {
                                            tr {
                                                td {
                                                    colSpan = "4"
                                                    em { +"… and ${bookingCount - 10} more" }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Customer communication history page ───────────────────────────
            get("/customers/comms") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@get call.respondRedirect("/customers")
                val customer = db.getCustomerById(id)
                    ?: return@get call.respondRedirect("/customers")

                val smsList   = db.getSmsMessagesForCustomer(customer.id, customer.phone, limit = 200)
                val callsList = db.getVoiceCallsForCustomer(customer.id, customer.phone, limit = 200)
                val shopsMap  = db.getAllShops().associateBy { it.id }

                fun fmtTs(epochMs: Long): String = java.time.Instant.ofEpochMilli(epochMs)
                    .atZone(java.time.ZoneId.of("Europe/Copenhagen"))
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"))

                call.respondAdminPage(
                    titleText = "Communication history",
                    subtitle = "${customer.name.takeIf { it != "NoName" } ?: "—"} · ${customer.phone}",
                    activePath = "/customers",
                ) {
                    div("panel") {
                        div("actions") {
                            a(href = "/customers/edit?id=$id", classes = "btn") { +"← Back to customer" }
                        }

                        h3 { +"📨 SMS messages (${smsList.size})" }
                        if (smsList.isEmpty()) {
                            p("hint") { +"No SMS messages found." }
                        } else {
                            table {
                                thead {
                                    tr {
                                        th { +"Date" }; th { +"Shop" }; th { +"Dir" }
                                        th { +"From" }; th { +"To" }; th { +"Status" }; th { +"Body" }
                                    }
                                }
                                tbody {
                                    for (m in smsList) {
                                        tr {
                                            td { +fmtTs(m.createdAt) }
                                            td { +(shopsMap[m.shopId]?.name ?: "#${m.shopId}") }
                                            td {
                                                span("badge ${if (m.direction == "inbound") "ok" else ""}") {
                                                    +(if (m.direction == "inbound") "↙ IN" else "↗ OUT")
                                                }
                                            }
                                            td { +m.fromPhone }
                                            td { +m.toPhone }
                                            td { +m.status }
                                            td { style = "max-width:320px;word-break:break-word;"; +m.body }
                                        }
                                    }
                                }
                            }
                        }

                        hr()
                        h3 { +"📞 Call log (${callsList.size})" }
                        if (callsList.isEmpty()) {
                            p("hint") { +"No calls found." }
                        } else {
                            table {
                                thead {
                                    tr {
                                        th { +"Date" }; th { +"Shop" }; th { +"From" }; th { +"To" }
                                        th { +"State" }; th { +"Outcome" }; th { +"Booking" }
                                    }
                                }
                                tbody {
                                    for (c in callsList) {
                                        tr {
                                            td { +fmtTs(c.startedAt) }
                                            td { +(shopsMap[c.shopId]?.name ?: "#${c.shopId}") }
                                            td { +c.fromPhone }
                                            td { +c.toPhone }
                                            td { +c.state }
                                            td { +c.outcome }
                                            td {
                                                if (c.linkedBookingId != null) span("badge ok") { +"#${c.linkedBookingId}" }
                                                else span { style = "color:#aaa"; +"—" }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        hr()
                        h3 { +"⚠️ Danger zone" }
                        p("hint") { +"Permanently deletes communication records for this customer. Active calls are never deleted." }
                        div("actions") {
                            form(action = "/customers/comms/clear-sms", method = FormMethod.post) {
                                hiddenInput { name = "id"; value = id.toString() }
                                submitInput(classes = "btn danger") {
                                    value = "🗑 Clear SMS history (${smsList.size} messages)"
                                    onClick = "return confirm('Delete all ${smsList.size} SMS messages for this customer? This cannot be undone.')"
                                }
                            }
                            +" "
                            form(action = "/customers/comms/clear-calls", method = FormMethod.post) {
                                hiddenInput { name = "id"; value = id.toString() }
                                submitInput(classes = "btn danger") {
                                    value = "🗑 Clear call history (${callsList.size} calls)"
                                    onClick = "return confirm('Delete all ended calls for this customer? Active calls are kept. This cannot be undone.')"
                                }
                            }
                        }
                    }
                }
            }

            post("/customers/comms/clear-sms") {
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull()
                    ?: return@post call.respondRedirect("/customers")
                val customer = db.getCustomerById(id)
                    ?: return@post call.respondRedirect("/customers")
                val deleted = db.clearSmsForCustomer(customer.id, customer.phone)
                println("[Admin] Cleared $deleted SMS messages for customer ${customer.id} (${customer.phone})")
                call.respondRedirect("/customers/comms?id=$id")
            }

            post("/customers/comms/clear-calls") {
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull()
                    ?: return@post call.respondRedirect("/customers")
                val customer = db.getCustomerById(id)
                    ?: return@post call.respondRedirect("/customers")
                val deleted = db.clearCallsForCustomer(customer.id, customer.phone)
                println("[Admin] Cleared $deleted call records for customer ${customer.id} (${customer.phone})")
                call.respondRedirect("/customers/comms?id=$id")
            }

            post("/customers/edit") {
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull()
                    ?: return@post call.respondRedirect("/customers")
                val name    = params["name"]?.trim().orEmpty()
                val phone   = params["phone"]?.trim().orEmpty()
                val status  = params["status"]?.trim().orEmpty().ifBlank { "New" }
                val payment  = params["payment"]?.toIntOrNull() ?: 0
                val language = params["language"]?.toIntOrNull() ?: 0
                db.updateCustomerFull(id, name, phone, status, payment, language)
                call.respondRedirect("/customers")
            }

            // GET confirm-delete (from list "Delete" button with onclick confirm)
            get("/customers/delete") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@get call.respondRedirect("/customers")
                db.deleteCustomer(id)
                call.respondRedirect("/customers")
            }

            // POST delete (from edit page danger zone)
            post("/customers/delete") {
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull()
                    ?: return@post call.respondRedirect("/customers")
                db.deleteCustomer(id)
                call.respondRedirect("/customers")
            }

            // =================================================================
            // Step 5 — Owner web-login portal  (/owner-login, /owner, /owner-logout)
            // =================================================================

            get("/owner-login") {
                call.respondHtml {
                    head {
                        meta { charset = "utf-8" }
                        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
                        title { +"Owner Login" }
                        link(rel = "stylesheet", href = "/static/admin.css", type = "text/css")
                    }
                    body {
                        div("center") {
                            div("card") {
                                h2 { +"Owner Login" }
                                p("hint") { +"Sign in to manage your shops, staff and services." }
                                form(action = "/owner-login", method = FormMethod.post) {
                                    label { +"Username" }
                                    textInput { name = "username"; autoComplete = false }
                                    label { +"Password" }
                                    passwordInput { name = "password"; autoComplete = false }
                                    br()
                                    submitInput(classes = "btn primary") { value = "Sign in" }
                                }
                            }
                        }
                    }
                }
            }

            post("/owner-login") {
                val params = call.receiveParameters()
                val username = params["username"]?.trim() ?: ""
                val password = params["password"] ?: ""
                val result = db.authenticateOwnerAccount(username, password)
                if (result != null) {
                    val (ownerId, _) = result
                    val owner = db.getOwnerById(ownerId)
                    val ownerName = owner?.name ?: "Owner"
                    call.sessions.set(OwnerSession(ownerId = ownerId, ownerName = ownerName))
                    println("[WebAdmin/owner-login] SUCCESS ownerId=$ownerId name='$ownerName'")
                    call.respondRedirect("/owner")
                } else {
                    println("[WebAdmin/owner-login] FAILED username='$username'")
                    call.respondHtml {
                        head {
                            meta { charset = "utf-8" }
                            meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
                            title { +"Owner Login" }
                            link(rel = "stylesheet", href = "/static/admin.css", type = "text/css")
                        }
                        body {
                            div("center") {
                                div("card") {
                                    h2 { +"Owner Login" }
                                    p("hint") { +"Invalid credentials." }
                                    a(href = "/owner-login", classes = "btn") { +"Try again" }
                                }
                            }
                        }
                    }
                }
            }

            get("/owner-logout") {
                call.sessions.clear<OwnerSession>()
                call.respondRedirect("/owner-login")
            }

            get("/owner") {
                val session = call.sessions.get<OwnerSession>()!!
                val shops = db.getShopsByOwner(session.ownerId)
                val managers = db.getManagersByOwner(session.ownerId)
                val employees = db.getEmployeesByOwner(session.ownerId)
                val services = db.getServicesByOwner(session.ownerId)
                call.respondOwnerPage(session, "Dashboard", "/owner") {
                    div("grid-2") {
                        div("panel") {
                            h3 { +"🏪 Shops (${shops.size})" }
                            if (shops.isEmpty()) {
                                p("hint") { +"No shops yet." }
                            } else {
                                ul {
                                    for (s in shops) { li { +s.name } }
                                }
                            }
                            div("actions") {
                                a(href = "/owner/shops", classes = "btn primary") { +"Manage shops →" }
                            }
                        }
                        div("panel") {
                            h3 { +"👥 Employees (${employees.size})" }
                            if (employees.isEmpty()) {
                                p("hint") { +"No employees yet." }
                            } else {
                                ul {
                                    for (e in employees) { li { +e.name } }
                                }
                            }
                            div("actions") {
                                a(href = "/owner/employees", classes = "btn primary") { +"Manage employees →" }
                            }
                        }
                        div("panel") {
                            h3 { +"🧾 Services (${services.size})" }
                            if (services.isEmpty()) {
                                p("hint") { +"No services yet." }
                            } else {
                                ul {
                                    for (s in services) { li { +"${s.name} — ${"%.0f".format(s.price)} kr / ${s.duration} min" } }
                                }
                            }
                            div("actions") {
                                a(href = "/owner/services", classes = "btn primary") { +"Manage services →" }
                            }
                        }
                        div("panel") {
                            h3 { +"🧑‍💼 Managers (${managers.size})" }
                            if (managers.isEmpty()) {
                                p("hint") { +"No managers yet." }
                            } else {
                                ul {
                                    for (m in managers) { li { +m.name } }
                                }
                            }
                            div("actions") {
                                a(href = "/owner/managers", classes = "btn primary") { +"Manage managers →" }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // Step 9 — Platform-admin owner management  (/admin/owners)
            // Step 6 — Impersonation: /admin/switch-owner/{id}, /admin/exit-owner
            // =================================================================

            get("/admin/owners") {
                val owners = db.getAllOwners()
                val impersonating = call.sessions.get<ImpersonationSession>()
                call.respondAdminPage(
                    titleText = "Owners",
                    subtitle = "Manage SaaS tenants (owners)",
                    activePath = "/admin/owners",
                ) {
                    if (impersonating != null) {
                        div("panel") {
                            p {
                                span("badge warn") { +"Impersonating: ${impersonating.ownerName} (id=${impersonating.ownerId})" }
                                +" "
                                a(href = "/admin/exit-owner", classes = "btn") { +"Exit impersonation" }
                            }
                        }
                    }
                    div("grid-2") {
                        div("panel") {
                            h3 { +"All owners" }
                            table {
                                thead {
                                    tr {
                                        th { +"ID" }; th { +"Name" }; th { +"Slug" }; th { +"Active" }
                                        th { +"Login" }; th { +"Actions" }
                                    }
                                }
                                tbody {
                                    for (owner in owners) {
                                        val account = db.getOwnerAccountByOwnerId(owner.id)
                                        tr {
                                            td { +owner.id.toString() }
                                            td { +owner.name }
                                            td { +(owner.slug ?: "—") }
                                            td { span("badge ${if (owner.active) "ok" else "warn"}") { +(if (owner.active) "Yes" else "No") } }
                                            td {
                                                if (account != null) span("badge ok") { +account.username }
                                                else span("badge warn") { +"No login" }
                                            }
                                            td {
                                                div("actions") {
                                                    a(href = "/admin/owners/edit?id=${owner.id}", classes = "btn") { +"Edit" }
                                                    a(href = "/admin/switch-owner/${owner.id}", classes = "btn") { +"Impersonate" }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        div("panel") {
                            h3 { +"Add owner" }
                            form(action = "/admin/owners/add", method = FormMethod.post) {
                                label { +"Owner name" }
                                textInput { name = "name"; placeholder = "Acme Salons" }
                                label { +"Slug (optional, URL-friendly)" }
                                textInput { name = "slug"; placeholder = "acme-salons" }
                                label { +"Login username" }
                                textInput { name = "username"; placeholder = "acme-admin" }
                                label { +"Password" }
                                passwordInput { name = "password"; placeholder = "Secure password" }
                                br()
                                submitInput(classes = "btn primary") { value = "Create owner" }
                            }
                        }
                    }
                }
            }

            post("/admin/owners/add") {
                val params = call.receiveParameters()
                val name     = params["name"]?.trim().orEmpty()
                val slug     = params["slug"]?.trim()?.takeIf { it.isNotBlank() }
                val username = params["username"]?.trim().orEmpty()
                val password = params["password"].orEmpty()
                if (name.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                    val ownerId = db.addOwner(name, slug)
                    if (ownerId > 0) db.addOwnerAccount(ownerId, username, password)
                }
                call.respondRedirect("/admin/owners")
            }

            get("/admin/owners/edit") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@get call.respondRedirect("/admin/owners")
                val owner = db.getOwnerById(id)
                    ?: return@get call.respondRedirect("/admin/owners")
                val account = db.getOwnerAccountByOwnerId(id)
                call.respondAdminPage(
                    titleText = "Edit owner — ${owner.name}",
                    activePath = "/admin/owners",
                ) {
                    div("grid-2") {
                        div("panel") {
                            h3 { +"Owner details" }
                            form(action = "/admin/owners/edit", method = FormMethod.post) {
                                hiddenInput { name = "id"; value = id.toString() }
                                label { +"Name" }
                                textInput { name = "name"; value = owner.name }
                                label { +"Slug" }
                                textInput { name = "slug"; value = owner.slug ?: "" }
                                label { +"Active" }
                                checkBoxInput { name = "active"; checked = owner.active }
                                br()
                                submitInput(classes = "btn primary") { value = "Save" }
                            }
                        }
                        div("panel") {
                            h3 { +"Owner login" }
                            if (account != null) {
                                p { +"Current username: "; b { +account.username } }
                            } else {
                                p { em { +"No login set." } }
                            }
                            form(action = "/admin/owners/set-login", method = FormMethod.post) {
                                hiddenInput { name = "id"; value = id.toString() }
                                label { +"Username" }
                                textInput { name = "username"; value = account?.username ?: "" }
                                label { +"Password (leave blank to keep existing)" }
                                passwordInput { name = "password"; placeholder = "New password" }
                                br()
                                submitInput(classes = "btn primary") { value = "Save login" }
                            }
                        }
                    }
                }
            }

            post("/admin/owners/edit") {
                val params = call.receiveParameters()
                val id     = params["id"]?.toIntOrNull() ?: return@post call.respondRedirect("/admin/owners")
                val name   = params["name"]?.trim().orEmpty()
                val slug   = params["slug"]?.trim()?.takeIf { it.isNotBlank() }
                val active = params["active"] == "on"
                if (name.isNotBlank()) db.updateOwner(id, name, slug, active)
                call.respondRedirect("/admin/owners")
            }

            post("/admin/owners/set-login") {
                val params = call.receiveParameters()
                val id       = params["id"]?.toIntOrNull() ?: return@post call.respondRedirect("/admin/owners")
                val username = params["username"]?.trim().orEmpty()
                val password = params["password"].orEmpty()
                val existing = db.getOwnerAccountByOwnerId(id)
                when {
                    existing == null && username.isNotBlank() && password.isNotBlank() ->
                        db.addOwnerAccount(id, username, password)
                    existing != null && username.isNotBlank() -> {
                        db.updateOwnerAccount(existing.id, username)
                        if (password.isNotBlank()) db.updateOwnerAccountPassword(existing.id, password)
                    }
                }
                call.respondRedirect("/admin/owners/edit?id=$id")
            }

            get("/admin/switch-owner/{ownerId}") {
                val ownerId = call.parameters["ownerId"]?.toIntOrNull()
                    ?: return@get call.respondRedirect("/admin/owners")
                val owner = db.getOwnerById(ownerId)
                    ?: return@get call.respondRedirect("/admin/owners")
                call.sessions.set(ImpersonationSession(ownerId = owner.id, ownerName = owner.name))
                println("[WebAdmin] Admin impersonating ownerId=${owner.id} name='${owner.name}'")
                call.respondRedirect("/admin/owners")
            }

            get("/admin/exit-owner") {
                call.sessions.clear<ImpersonationSession>()
                call.respondRedirect("/admin/owners")
            }

            // =================================================================
            // Step 13 — Owner-scoped portal CRUD pages  (/owner/...)
            // All routes below require OwnerSession (enforced by the intercept above).
            // =================================================================

            // ── Owner / Shops ────────────────────────────────────────────────
            get("/owner/shops") {
                val session = call.sessions.get<OwnerSession>()!!
                val shops = db.getShopsByOwner(session.ownerId)
                val managers = db.getManagersByOwner(session.ownerId).associateBy { it.id }
                call.respondOwnerPage(session, "Shops", "/owner/shops") {
                    div("panel") {
                        div("actions") {
                            a(href = "/owner/shops/add", classes = "btn primary") { +"Add new shop" }
                        }
                        table {
                            thead { tr { th { +"ID" }; th { +"Name" }; th { +"Address" }; th { +"Manager" }; th { +"Actions" } } }
                            tbody {
                                for (s in shops) {
                                    tr {
                                        td { +s.id.toString() }
                                        td { +s.name }
                                        td { +(s.address ?: "—") }
                                        td { +(managers[s.managerId]?.name ?: "Unassigned") }
                                        td {
                                            div("actions") {
                                                a(href = "/owner/shops/edit?id=${s.id}", classes = "btn") { +"Edit" }
                                                a(href = "/owner/shops/delete?id=${s.id}", classes = "btn danger") {
                                                    onClick = "return confirm('Delete shop?')"
                                                    +"Delete"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            get("/owner/shops/add") {
                val session = call.sessions.get<OwnerSession>()!!
                val managers = db.getManagersByOwner(session.ownerId)
                call.respondOwnerPage(session, "Add shop", "/owner/shops") {
                    div("panel") {
                        form(action = "/owner/shops/add", method = FormMethod.post) {
                            label { +"Shop name" }; textInput { name = "name"; placeholder = "My Shop" }
                            label { +"Address" }; textInput { name = "address"; placeholder = "Street, City" }
                            label { +"Directions" }; textArea { name = "directions"; placeholder = "Arrival instructions" }
                            label { +"Manager" }
                            select {
                                name = "managerId"
                                option { value = ""; +"Unassigned" }
                                for (m in managers) { option { value = m.id.toString(); +m.name } }
                            }
                            br()
                            submitInput(classes = "btn primary") { value = "Add shop" }
                        }
                    }
                }
            }

            post("/owner/shops/add") {
                val session = call.sessions.get<OwnerSession>()!!
                val params = call.receiveParameters()
                val name = params["name"]?.trim().orEmpty()
                val address = params["address"]?.trim().orEmpty()
                val directions = params["directions"]?.trim().orEmpty()
                val managerId = params["managerId"]?.toIntOrNull()
                if (name.isNotBlank()) {
                    val shopId = db.addShopForOwner(session.ownerId, name, address.ifBlank { null }, directions.ifBlank { null })
                    if (shopId > 0 && managerId != null && db.isManagerOwnedBy(managerId, session.ownerId)) {
                        db.updateShop(shopId, name, address.ifBlank { null }, directions.ifBlank { null }, managerId)
                    }
                }
                call.respondRedirect("/owner/shops")
            }

            get("/owner/shops/edit") {
                val session = call.sessions.get<OwnerSession>()!!
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@get call.respondRedirect("/owner/shops")
                if (!db.isShopOwnedBy(id, session.ownerId)) return@get call.respondRedirect("/owner/shops")
                val shop = db.getShopById(id) ?: return@get call.respondRedirect("/owner/shops")
                val managers = db.getManagersByOwner(session.ownerId)
                call.respondOwnerPage(session, "Edit shop — ${shop.name}", "/owner/shops") {
                    div("panel") {
                        form(action = "/owner/shops/edit", method = FormMethod.post) {
                            hiddenInput { name = "id"; value = id.toString() }
                            label { +"Shop name" }; textInput { name = "name"; value = shop.name }
                            label { +"Address" }; textInput { name = "address"; value = shop.address ?: "" }
                            label { +"Directions" }; textArea { name = "directions"; +(shop.directions ?: "") }
                            label { +"Manager" }
                            select {
                                name = "managerId"
                                option { value = ""; +"Unassigned" }
                                for (m in managers) { option { value = m.id.toString(); if (m.id == shop.managerId) selected = true; +m.name } }
                            }
                            br()
                            submitInput(classes = "btn primary") { value = "Save changes" }
                        }
                    }
                }
            }

            post("/owner/shops/edit") {
                val session = call.sessions.get<OwnerSession>()!!
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull() ?: return@post call.respondRedirect("/owner/shops")
                if (!db.isShopOwnedBy(id, session.ownerId)) return@post call.respondRedirect("/owner/shops")
                val name = params["name"]?.trim().orEmpty()
                val address = params["address"]?.trim().orEmpty()
                val directions = params["directions"]?.trim().orEmpty()
                val managerId = params["managerId"]?.toIntOrNull()
                    ?.takeIf { db.isManagerOwnedBy(it, session.ownerId) }
                if (name.isNotBlank()) db.updateShop(id, name, address.ifBlank { null }, directions.ifBlank { null }, managerId)
                call.respondRedirect("/owner/shops")
            }

            get("/owner/shops/delete") {
                val session = call.sessions.get<OwnerSession>()!!
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@get call.respondRedirect("/owner/shops")
                if (db.isShopOwnedBy(id, session.ownerId)) db.deleteShop(id)
                call.respondRedirect("/owner/shops")
            }

            // ── Owner / Employees ────────────────────────────────────────────
            get("/owner/employees") {
                val session = call.sessions.get<OwnerSession>()!!
                val employees = db.getEmployeesByOwner(session.ownerId)
                call.respondOwnerPage(session, "Employees", "/owner/employees") {
                    div("grid-2") {
                        div("panel") {
                            h3 { +"All employees" }
                            table {
                                thead { tr { th { +"ID" }; th { +"Name" }; th { +"Phone" }; th { +"Actions" } } }
                                tbody {
                                    for (e in employees) {
                                        tr {
                                            td { +e.id.toString() }; td { +e.name }; td { +(e.phone ?: "—") }
                                            td {
                                                div("actions") {
                                                    a(href = "/owner/employees/edit?id=${e.id}", classes = "btn") { +"Edit" }
                                                    a(href = "/owner/employees/delete?id=${e.id}", classes = "btn danger") {
                                                        onClick = "return confirm('Delete employee?')"
                                                        +"Delete"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        div("panel") {
                            h3 { +"Add employee" }
                            form(action = "/owner/employees/add", method = FormMethod.post) {
                                label { +"Name" }; textInput { name = "name"; placeholder = "Full name" }
                                label { +"Phone (optional)" }; textInput { name = "phone"; placeholder = "+45 12 34 56 78" }
                                br()
                                submitInput(classes = "btn primary") { value = "Add employee" }
                            }
                        }
                    }
                }
            }

            post("/owner/employees/add") {
                val session = call.sessions.get<OwnerSession>()!!
                val params = call.receiveParameters()
                val name = params["name"]?.trim().orEmpty()
                val phone = params["phone"]?.trim().takeIf { !it.isNullOrBlank() }
                if (name.isNotBlank()) db.addEmployeeForOwner(session.ownerId, name, phone)
                call.respondRedirect("/owner/employees")
            }

            get("/owner/employees/edit") {
                val session = call.sessions.get<OwnerSession>()!!
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@get call.respondRedirect("/owner/employees")
                val emp = db.getEmployeeById(id) ?: return@get call.respondRedirect("/owner/employees")
                // Verify ownership via owner_id column
                val isOwned = db.getEmployeesByOwner(session.ownerId).any { it.id == id }
                if (!isOwned) return@get call.respondRedirect("/owner/employees")
                val shops = db.getShopsByOwner(session.ownerId)
                val currentShopId = db.getShopIdForEmployee(id)
                call.respondOwnerPage(session, "Edit employee — ${emp.name}", "/owner/employees") {
                    div("panel") {
                        form(action = "/owner/employees/edit", method = FormMethod.post) {
                            hiddenInput { name = "id"; value = id.toString() }
                            label { +"Name" }; textInput { name = "name"; value = emp.name }
                            label { +"Phone" }; textInput { name = "phone"; value = emp.phone ?: "" }
                            label { +"Assigned shop" }
                            select {
                                name = "shopId"
                                option { value = ""; +"— No shop —" }
                                for (s in shops) { option { value = s.id.toString(); if (s.id == currentShopId) selected = true; +s.name } }
                            }
                            br()
                            submitInput(classes = "btn primary") { value = "Save" }
                        }
                    }
                }
            }

            post("/owner/employees/edit") {
                val session = call.sessions.get<OwnerSession>()!!
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull() ?: return@post call.respondRedirect("/owner/employees")
                val isOwned = db.getEmployeesByOwner(session.ownerId).any { it.id == id }
                if (!isOwned) return@post call.respondRedirect("/owner/employees")
                val name = params["name"]?.trim().orEmpty()
                val phone = params["phone"]?.trim().takeIf { !it.isNullOrBlank() }
                val shopId = params["shopId"]?.toIntOrNull()
                    ?.takeIf { db.isShopOwnedBy(it, session.ownerId) }
                if (name.isNotBlank()) db.updateEmployee(id, name, phone)
                if (shopId != null) db.assignEmployeeToShop(id, shopId, exclusive = true)
                else db.removeEmployeeFromAllShops(id)
                call.respondRedirect("/owner/employees")
            }

            get("/owner/employees/delete") {
                val session = call.sessions.get<OwnerSession>()!!
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@get call.respondRedirect("/owner/employees")
                val isOwned = db.getEmployeesByOwner(session.ownerId).any { it.id == id }
                if (isOwned) db.deleteEmployee(id)
                call.respondRedirect("/owner/employees")
            }

            // ── Owner / Services ─────────────────────────────────────────────
            get("/owner/services") {
                val session = call.sessions.get<OwnerSession>()!!
                val services = db.getServicesByOwner(session.ownerId)
                call.respondOwnerPage(session, "Services", "/owner/services") {
                    div("grid-2") {
                        div("panel") {
                            h3 { +"All services" }
                            table {
                                thead { tr { th { +"ID" }; th { +"Name" }; th { +"Price" }; th { +"Duration" }; th { +"Actions" } } }
                                tbody {
                                    for (s in services) {
                                        tr {
                                            td { +s.id.toString() }; td { +s.name }
                                            td { +"${"%.2f".format(s.price)} kr" }; td { +"${s.duration} min" }
                                            td {
                                                div("actions") {
                                                    a(href = "/owner/services/edit?id=${s.id}", classes = "btn") { +"Edit" }
                                                    a(href = "/owner/services/delete?id=${s.id}", classes = "btn danger") {
                                                        onClick = "return confirm('Delete service?')"
                                                        +"Delete"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        div("panel") {
                            h3 { +"Add service" }
                            form(action = "/owner/services/add", method = FormMethod.post) {
                                label { +"Name" }; textInput { name = "name"; placeholder = "Massage" }
                                label { +"Price (kr)" }; numberInput { name = "price"; step = "0.01"; placeholder = "0.00" }
                                label { +"Duration (min)" }; numberInput { name = "duration"; step = "5"; placeholder = "60" }
                                br()
                                submitInput(classes = "btn primary") { value = "Add service" }
                            }
                        }
                    }
                }
            }

            post("/owner/services/add") {
                val session = call.sessions.get<OwnerSession>()!!
                val params = call.receiveParameters()
                val name = params["name"]?.trim().orEmpty()
                val price = params["price"]?.toDoubleOrNull() ?: 0.0
                val duration = params["duration"]?.toIntOrNull() ?: 0
                if (name.isNotBlank()) db.addServiceForOwner(session.ownerId, name, price, duration)
                call.respondRedirect("/owner/services")
            }

            get("/owner/services/edit") {
                val session = call.sessions.get<OwnerSession>()!!
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@get call.respondRedirect("/owner/services")
                val svc = db.getServiceById(id) ?: return@get call.respondRedirect("/owner/services")
                val isOwned = db.getServicesByOwner(session.ownerId).any { it.id == id }
                if (!isOwned) return@get call.respondRedirect("/owner/services")
                call.respondOwnerPage(session, "Edit service — ${svc.name}", "/owner/services") {
                    div("panel") {
                        form(action = "/owner/services/edit", method = FormMethod.post) {
                            hiddenInput { name = "id"; value = id.toString() }
                            label { +"Name" }; textInput { name = "name"; value = svc.name }
                            label { +"Price (kr)" }; numberInput { name = "price"; value = "%.2f".format(svc.price); step = "0.01" }
                            label { +"Duration (min)" }; numberInput { name = "duration"; value = "${svc.duration}"; step = "1" }
                            br()
                            submitInput(classes = "btn primary") { value = "Save" }
                        }
                    }
                }
            }

            post("/owner/services/edit") {
                val session = call.sessions.get<OwnerSession>()!!
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull() ?: return@post call.respondRedirect("/owner/services")
                val isOwned = db.getServicesByOwner(session.ownerId).any { it.id == id }
                if (!isOwned) return@post call.respondRedirect("/owner/services")
                val name = params["name"]?.trim().orEmpty()
                val price = params["price"]?.toDoubleOrNull() ?: 0.0
                val duration = params["duration"]?.toIntOrNull() ?: 0
                if (name.isNotBlank()) db.updateService(id, name, price, duration)
                call.respondRedirect("/owner/services")
            }

            get("/owner/services/delete") {
                val session = call.sessions.get<OwnerSession>()!!
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@get call.respondRedirect("/owner/services")
                val isOwned = db.getServicesByOwner(session.ownerId).any { it.id == id }
                if (isOwned) db.deleteService(id)
                call.respondRedirect("/owner/services")
            }

            // ── Owner / Managers ─────────────────────────────────────────────
            get("/owner/managers") {
                val session = call.sessions.get<OwnerSession>()!!
                val managers = db.getManagersByOwner(session.ownerId)
                call.respondOwnerPage(session, "Managers", "/owner/managers") {
                    div("grid-2") {
                        div("panel") {
                            h3 { +"All managers" }
                            table {
                                thead { tr { th { +"ID" }; th { +"Name" }; th { +"Username" }; th { +"Phone" }; th { +"Actions" } } }
                                tbody {
                                    for (m in managers) {
                                        tr {
                                            td { +m.id.toString() }; td { +m.name }; td { +m.username }; td { +(m.phone ?: "—") }
                                            td {
                                                div("actions") {
                                                    a(href = "/owner/managers/edit?id=${m.id}", classes = "btn") { +"Edit" }
                                                    a(href = "/owner/managers/delete?id=${m.id}", classes = "btn danger") {
                                                        onClick = "return confirm('Delete manager?')"
                                                        +"Delete"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        div("panel") {
                            h3 { +"Add manager" }
                            form(action = "/owner/managers/add", method = FormMethod.post) {
                                label { +"Name" }; textInput { name = "name"; placeholder = "Jane Doe" }
                                label { +"Username" }; textInput { name = "username"; placeholder = "manager01" }
                                label { +"Password" }; passwordInput { name = "password" }
                                label { +"Phone (optional)" }; textInput { name = "phone"; placeholder = "+45 12 34 56 78" }
                                br()
                                submitInput(classes = "btn primary") { value = "Add manager" }
                            }
                        }
                    }
                }
            }

            post("/owner/managers/add") {
                val session = call.sessions.get<OwnerSession>()!!
                val params = call.receiveParameters()
                val name = params["name"]?.trim().orEmpty()
                val username = params["username"]?.trim().orEmpty()
                val password = params["password"].orEmpty()
                val phone = params["phone"]?.trim().takeIf { !it.isNullOrBlank() }
                if (name.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                    db.addManagerForOwner(session.ownerId, name, username, password, phone)
                }
                call.respondRedirect("/owner/managers")
            }

            get("/owner/managers/edit") {
                val session = call.sessions.get<OwnerSession>()!!
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@get call.respondRedirect("/owner/managers")
                if (!db.isManagerOwnedBy(id, session.ownerId)) return@get call.respondRedirect("/owner/managers")
                val mgr = db.getManagerById(id) ?: return@get call.respondRedirect("/owner/managers")
                call.respondOwnerPage(session, "Edit manager — ${mgr.name}", "/owner/managers") {
                    div("grid-2") {
                        div("panel") {
                            h3 { +"Details" }
                            form(action = "/owner/managers/edit", method = FormMethod.post) {
                                hiddenInput { name = "id"; value = id.toString() }
                                label { +"Name" }; textInput { name = "name"; value = mgr.name }
                                label { +"Username" }; textInput { name = "username"; value = mgr.username }
                                label { +"Phone" }; textInput { name = "phone"; value = mgr.phone ?: "" }
                                br()
                                submitInput(classes = "btn primary") { value = "Save" }
                            }
                        }
                        div("panel") {
                            h3 { +"Reset password" }
                            form(action = "/owner/managers/reset-password", method = FormMethod.post) {
                                hiddenInput { name = "id"; value = id.toString() }
                                label { +"New password" }; passwordInput { name = "password"; placeholder = "New password" }
                                br()
                                submitInput(classes = "btn danger") { value = "Reset password" }
                            }
                        }
                    }
                }
            }

            post("/owner/managers/edit") {
                val session = call.sessions.get<OwnerSession>()!!
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull() ?: return@post call.respondRedirect("/owner/managers")
                if (!db.isManagerOwnedBy(id, session.ownerId)) return@post call.respondRedirect("/owner/managers")
                val name = params["name"]?.trim().orEmpty()
                val username = params["username"]?.trim().orEmpty()
                val phone = params["phone"]?.trim().orEmpty()
                if (name.isNotBlank() && username.isNotBlank()) db.updateManager(id, name, phone, username)
                call.respondRedirect("/owner/managers")
            }

            post("/owner/managers/reset-password") {
                val session = call.sessions.get<OwnerSession>()!!
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull() ?: return@post call.respondRedirect("/owner/managers")
                if (!db.isManagerOwnedBy(id, session.ownerId)) return@post call.respondRedirect("/owner/managers")
                val password = params["password"].orEmpty()
                if (password.isNotBlank()) db.updateManagerPassword(id, password)
                call.respondRedirect("/owner/managers/edit?id=$id")
            }

            get("/owner/managers/delete") {
                val session = call.sessions.get<OwnerSession>()!!
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                    ?: return@get call.respondRedirect("/owner/managers")
                if (db.isManagerOwnedBy(id, session.ownerId)) db.deleteManager(id)
                call.respondRedirect("/owner/managers")
            }

        }
    }
}
