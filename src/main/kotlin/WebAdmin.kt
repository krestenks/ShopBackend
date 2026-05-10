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
                        NavItem("/twilio/setup", "Twilio setup", "📞"),
                        NavItem("/test-booking-link", "Booking link", "🔗"),
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
                        || path.startsWith("/api/book")
                        || path.startsWith("/api/booking/")
                        || path.startsWith("/api/shops")
                        || path.startsWith("/api/employees")
                        || path.startsWith("/api/services")
                        || path.startsWith("/api/timeslots")
                        || path.startsWith("/static/")

                if (!isPublic && call.sessions.get<AdminSession>() == null) {
                    call.respondRedirect("/login")
                    finish()
                }
            }

            get("/") {
                call.respondAdminPage(
                    titleText = "Dashboard",
                    subtitle = "Manage shops, staff and services",
                    activePath = "/",
                ) {
                    div("panel") {
                        p { +"Use the navigation to manage your data." }
                        ul {
                            li { +"Shops: address, manager, voice config and opening hours" }
                            li { +"Employees: assign shop + services" }
                            li { +"Services: prices and duration" }
                            li { +"Managers: admin + app logins" }
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
                val managers = db.getAllManagers()
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

                db.addManager(name, username, password, phone)
                call.respondRedirect("/managers")
            }

            get("/managers/edit") {
                val manager_id = call.request.queryParameters["id"]?.toIntOrNull()
                if (manager_id == null) {
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

            // POST route: update manager in DB
            post("/managers/edit") {
                val params = call.receiveParameters()
                val id = params["id"]?.toIntOrNull()
                val name = params["name"] ?: ""
                val phone = params["phone"] ?: ""
                val username = params["username"] ?: ""

                if (id != null) {
                    db.updateManager(id, name, phone, username)
                }
                call.respondRedirect("/managers")
            }

            get("/managers/delete") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                if (id != null) {
                    db.deleteManager(id)
                }
                call.respondRedirect("/managers")
            }


            // Code for shops
            get("/shops") {
                val shops = db.getAllShops()
                val managers = db.getAllManagers().associateBy { it.id }

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

                db.addShop(name, address, directions)
                call.respondRedirect("/shops")
            }

            get("/shops/delete") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                if (id != null) {
                    db.deleteShop(id)
                }
                call.respondRedirect("/shops")
            }

            get("/shops/edit") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respondRedirect("/shops")
                    return@get
                }
                val shop = db.getShopById(id) ?: return@get call.respondRedirect("/shops")
                val managers = db.getAllManagers()
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
                val employees = db.getAllEmployees()
                val shops = db.getAllShops()
                val services = db.getAllServices()
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

                db.addEmployee(name, phone)
                call.respondRedirect("/employees")
            }

            get("/employees/delete") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                if (id != null) {
                    db.deleteEmployee(id)
                }
                call.respondRedirect("/employees")
            }

            get("/employees/edit") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respondRedirect("/employees")
                    return@get
                }

                val employee = db.getEmployeeById(id)
                if (employee == null) {
                    call.respondRedirect("/employees")
                    return@get
                }

                val shops = db.getAllShops()
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

                if (id != null) {
                    db.updateEmployee(id, name, phone)
                    if (shopId != null) {
                        db.assignEmployeeToShop(id, shopId, exclusive = true)
                    } else {
                        db.removeEmployeeFromAllShops(id)
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
                val services = db.getAllServices()
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

                db.addService(name, price, duration)
                call.respondRedirect("/services")
            }

            get("/services/delete") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                if (id != null) {
                    db.deleteService(id)
                }
                call.respondRedirect("/services")
            }

            get("/services/edit") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                if (id == null) {
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

                if (id != null) {
                    db.updateService(id, name, price, duration)
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

        }
    }

    // Old header removed in favor of sidebar navigation via adminPage().
} 
