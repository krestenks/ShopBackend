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
import shared.components.bookingForm
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class WebAdmin(private val db: DataBase) {

    data class AdminSession(val username: String)

    fun setupRoutes(routing: Routing) {
        routing {
            static("/static") {
                resources("static")
            }

            sharedBookingRoutes(db) // <- Include shared routes

            get("/login") {
                call.respondHtml {
                    body {
                        h2 { +"Admin Login" }
                        form(action = "/login", method = FormMethod.post) {
                            textInput { name = "username"; placeholder = "Username" }
                            br()
                            passwordInput { name = "password"; placeholder = "Password" }
                            br()
                            submitInput { value = "Login" }
                        }
                    }
                }
            }

            post("/login") {
                val params = call.receiveParameters()
                val username = params["username"] ?: ""
                val password = params["password"] ?: ""

                val allowedAdminUsername = "admin"
                val expectedHash = "\$2a\$12\$nQk81rAIlxMeoP.RcBZd9eaJ.wK2fanoVl7y0w18cNXipPZAZsf6G" // Hash of "1234"

                if (username == allowedAdminUsername && org.mindrot.jbcrypt.BCrypt.checkpw(password, expectedHash)) {
                    call.sessions.set(AdminSession(username))
                    call.respondRedirect("/")
                } else {
                    call.respondHtml {
                        body {
                            h3 { +"Invalid credentials." }
                            a("/login") { +"Try again" }
                        }
                    }
                }
            }

            get("/logout") {
                call.sessions.clear<AdminSession>()
                call.respondRedirect("/login")
            }

            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.path() != "/login" && call.sessions.get<AdminSession>() == null) {
                    call.respondRedirect("/login")
                    finish()
                }
            }

            get("/") {
                call.respondHtml {
                    body {
                        header()
                        h1 { +"ShopManager Admin" }
                        p { +"Use the interface to manage managers." }
                    }
                }
            }

            get("/managers") {
                val managers = db.getAllManagers()
                call.respondHtml {
                    body {
                        header()
                        h2 { +"Managers" }
                        table {
                            style = "border-collapse: collapse; width: 100%; text-align: center;"
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
                                            a(href = "/managers/edit?id=${manager.id}") { +"[Edit]" }
                                            +" "
                                            a(href = "/managers/delete?id=${manager.id}") { +"[Delete]" }
                                        }
                                    }
                                }
                            }
                        }
                        br()
                        a(href = "/managers/add") { +"Add New Manager" }
                    }
                }
            }


            get("/managers/add") {
                call.respondHtml {
                    body {
                        header()
                        h2 { +"Add New Manager" }
                        form(action = "/managers/add", method = FormMethod.post) {
                            textInput { name = "name"; placeholder = "Full Name" }
                            br()
                            textInput { name = "username"; placeholder = "Username" }
                            br()
                            passwordInput { name = "password"; placeholder = "Password" }
                            br()
                            textInput { name = "phone"; placeholder = "Phone (optional)" }
                            br()
                            submitInput { value = "Add Manager" }
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
                call.respondHtml {
                    body {
                        header()
                        h3 { +"Manager added." }
                        a("/") { +"Back to home" }
                    }
                }
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
                call.respondHtml {
                    body {
                        header()
                        h2 { +"Edit Manager" }
                        form(action = "/managers/edit", method = FormMethod.post) {
                            hiddenInput { name = "id"; value = manager.id.toString() }

                            label {
                                htmlFor = "name"
                                +"Full name:"
                            }
                            br()
                            textInput {
                                name = "name"
                                id = "name"
                                value = manager.name
                                placeholder = "Manager's full name"
                            }
                            br()
                            br()

                            label {
                                htmlFor = "phone"
                                +"Phone number:"
                            }
                            br()
                            textInput {
                                name = "phone"
                                id = "phone"
                                value = manager.phone.toString()
                                placeholder = "e.g., +45 1234 5678"
                            }
                            br()
                            br()

                            label {
                                htmlFor = "username"
                                +"Username (for login):"
                            }
                            br()
                            textInput {
                                name = "username"
                                id = "username"
                                value = manager.username
                                placeholder = "e.g., manager01"
                            }
                            br()
                            br()

                            submitInput { value = "Save Changes" }
                        }
                    }
                }
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

                call.respondHtml {
                    body {
                        header()
                        h2 { +"Shops Overview" }
                        table {
                            style = "border-collapse: collapse; width: 100%;"
                            tr {
                                th { +"ID" }
                                th { +"Name" }
                                th { +"Address" }
                                th { +"Manager" }
                                th { +"Employees" }
                                th { +"Actions" }
                            }
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
                                        ul {
                                            shopEmployees.forEach {
                                                li { +"${it.name} (${it.phone ?: "-"})" }
                                            }
                                        }
                                    }
                                    td {
                                        a(href = "/shops/edit?id=${shop.id}") { +"Edit" }; +" "
                                        a(href = "/shops/delete?id=${shop.id}") { +"Delete" }
                                    }
                                }
                            }
                        }
                        br()
                        a(href = "/shops/add") { +"Add New Shop" }
                    }
                }
            }

            get("/shops/add") {
                call.respondHtml {
                    body {
                        header()
                        h2 { +"Add New Shop" }
                        form(action = "/shops/add", method = FormMethod.post) {
                            textInput { name = "name"; placeholder = "Shop Name" }
                            br()
                            textInput { name = "address"; placeholder = "Address" }
                            br()
                            textArea { name = "directions"; placeholder = "Directions" }
                            br()
                            submitInput { value = "Add Shop" }
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

                call.respondHtml {
                    body {
                        header()
                        h2 { +"Edit Shop" }
                        form(action = "/shops/edit", method = FormMethod.post) {
                            hiddenInput { name = "id"; value = shop.id.toString() }
                            textInput { name = "name"; value = shop.name }
                            br()
                            textInput { name = "address"; value = shop.address ?: "" }
                            br()
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
                            br()
                            submitInput { value = "Save Changes" }
                        }
                    }
                }
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

                call.respondHtml {
                    body {
                        header()
                        h2 { +"Employees" }
                        table {
                            style = "border-collapse: collapse; width: 100%;"
                            thead {
                                tr {
                                    listOf("ID", "Name", "Phone", "Shop", "Services", "Actions").forEach {
                                        th {
                                            style = "border: 1px solid #ccc; padding: 8px;"
                                            +it
                                        }
                                    }
                                }
                            }
                            tbody {
                                for (emp in employees) {
                                    tr {
                                        td { style = "border: 1px solid #ccc; padding: 8px;"; +emp.id.toString() }
                                        td { style = "border: 1px solid #ccc; padding: 8px;"; +emp.name }
                                        td { style = "border: 1px solid #ccc; padding: 8px;"; +(emp.phone ?: "") }

                                        // Shop
                                        td {
                                            style = "border: 1px solid #ccc; padding: 8px;"
                                            val shopId = employeeShopMap[emp]
                                            val shopName = shopId?.let { shopsById[it]?.name } ?: "None"
                                            +shopName
                                        }

                                        // Services
                                        td {
                                            style = "border: 1px solid #ccc; padding: 8px;"
                                            val empServices = employeeServicesMap[emp].orEmpty()
                                            if (empServices.isEmpty()) {
                                                +"–"
                                            } else {
                                                ul {
                                                    for (srv in empServices) {
                                                        li { +"${srv.name} (${srv.price} kr)" }
                                                    }
                                                }
                                            }
                                        }

                                        // Actions
                                        td {
                                            style = "border: 1px solid #ccc; padding: 8px;"
                                            a(href = "/employees/edit?id=${emp.id}") { +"Edit" }
                                            +" | "
                                            a(href = "/employees/delete?id=${emp.id}") { +"Delete" }
                                        }
                                    }
                                }
                            }
                        }
                        br()
                        a(href = "/employees/add") { +"Add New Employee" }
                    }
                }
            }

            get("/employees/add") {
                call.respondHtml {
                    body {
                        header()
                        h2 { +"Add New Employee" }
                        form(action = "/employees/add", method = FormMethod.post) {
                            textInput { name = "name"; placeholder = "Full Name" }
                            br()
                            textInput { name = "phone"; placeholder = "Phone (optional)" }
                            br()
                            submitInput { value = "Add Employee" }
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

                call.respondHtml {
                    body {
                        header()
                        h2 { +"Edit Employee" }
                        form(action = "/employees/edit", method = FormMethod.post) {
                            hiddenInput { name = "id"; value = employee.id.toString() }
                            h5 { +"Name: " }
                            textInput {
                                name = "name"
                                value = employee.name
                            }
                            br()
                            h5 { +"Phone: " }
                            textInput {
                                name = "phone"
                                value = employee.phone ?: ""
                            }
                            br()
                            h5 { +"Shop: " }
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
                            submitInput { value = "Save Changes" }
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

            // Services
            get("/services") {
                val services = db.getAllServices()
                call.respondHtml {
                    body {
                        header()
                        h2 { +"Services" }
                        table {
                            style = "border-collapse: collapse; width: 100%; text-align: center;"
                            thead {
                                tr {
                                    th { +"ID" }
                                    th { +"Name" }
                                    th { +"Price (kr)" }
                                    th { +"Duration (min)" }
                                    th { +"Actions" }
                                }
                            }
                            tbody {
                                for (service in services) {
                                    tr {
                                        td { +service.id.toString() }
                                        td { +service.name }
                                        td { +"${"%.2f".format(service.price)}" }
                                        td { +service.duration.toString() }
                                        td {
                                            a(href = "/services/edit?id=${service.id}") { +"[Edit]" }
                                            +" "
                                            a(href = "/services/delete?id=${service.id}") { +"[Delete]" }
                                        }
                                    }
                                }
                            }
                        }
                        br()
                        a(href = "/services/add") { +"Add New Service" }
                    }
                }
            }


            get("/services/add") {
                call.respondHtml {
                    body {
                        header()
                        h2 { +"Add New Service" }
                        form(action = "/services/add", method = FormMethod.post) {
                            h5 { +"Name" }
                            textInput { name = "name"; placeholder = "Service Name" }
                            br()
                            h5 { +"Price" }
                            numberInput {
                                name = "price"
                                placeholder = "Price"
                                step = "0.01"
                            }
                            br()
                            h5 { +"Duration" }
                            numberInput {
                                name = "duration"
                                placeholder = "Duration"
                                step = "5"
                            }
                            br()
                            submitInput { value = "Add Service" }
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
                call.respondHtml {
                    body {
                        header()
                        h2 { +"Edit Service" }
                        form(action = "/services/edit", method = FormMethod.post) {
                            hiddenInput { name = "id"; value = service.id.toString() }
                            textInput { name = "name"; value = service.name }
                            br()
                            numberInput {
                                name = "price"
                                value = "%.2f".format(service.price)
                                step = "0.01"
                            }
                            br()
                            numberInput {
                                name = "duration"
                                value = "%d".format(service.duration)
                                step = "1"
                            }
                            submitInput { value = "Save Changes" }
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

                call.respondHtml {
                    body {
                        header()
                        h3 { +"Assign New Service" }
                        form(action = "/assign-services/add", method = FormMethod.post) {
                            select {
                                name = "employeeId"
                                for (emp in employees) {
                                    option { value = emp.id.toString(); +emp.name }
                                }
                            }
                            +" "
                            select {
                                name = "serviceId"
                                for (svc in services) {
                                    option { value = svc.id.toString(); +svc.name }
                                }
                            }
                            +" "
                            submitInput { value = "Assign" }
                        }

                        h3 { +"Current Assignments" }
                        ul {
                            val grouped = assignments.groupBy { it.first }

                            for ((employee, services) in grouped) {
                                div {
                                    style = "margin-bottom: 20px; padding: 10px; border-bottom: 1px solid #ccc;"
                                    h3 { +"${employee.name} (${employee.phone})" }
                                    ul {
                                        for ((_, service) in services) {
                                            li {
                                                +"${service.name} (${service.price} kr)"
                                                +" "
                                                a(href = "/unassign-service?employee_id=${employee.id}&service_id=${service.id}") { +"[Unassign]" }
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

            get("/assign-services/remove") {
                val empId = call.request.queryParameters["emp"]?.toIntOrNull()
                val svcId = call.request.queryParameters["svc"]?.toIntOrNull()
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

                call.respondHtml {
                    head { title("Appointments") }
                    body {
                        header()
                        h2 { +"Appointments" }
                        table {
                            style = "border-collapse: collapse; width: 100%; text-align: center;"
                            tr {
                                th { +"ID" }
                                th { +"Employee" }
                                th { +"Shop" }
                                th { +"Date/Time" }
                                th { +"Duration (min)" }
                                th { +"Price (DKK)" }
                                th { +"Services" }
                            }
                            for (appt in appointments) {
                                val services = db.getServicesForAppointment(appt.id)
                                tr {
                                    td { +"${appt.id}" }
                                    td { +employees[appt.employeeId]?.name.orEmpty() }
                                    td { +shops[appt.shopId]?.name.orEmpty() }
                                    td { +java.time.Instant.ofEpochMilli(appt.dateTime).toString() }
                                    td { +"${appt.duration}" }
                                    td { +"${"%.2f".format(appt.price)}" }
                                    td {
                                        ul {
                                            services.forEach { s ->
                                                li { +"${s.name} (${s.price} DKK)" }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        br()
                        a(href = "/appointments/add") { +"Add New Appointment" }
                    }
                }
            }

            // Show form to create a new appointment
            get("/appointments/add") {
                call.respondHtml {
                    body {
                        header()
                        bookingForm(customerId = 1) // or from call.parameters
                    }
                }
            }



            get("/test-booking-link") {
                val shops = db.getAllShops() // Replace with your actual method to get shops

                val optionsHtml = shops.joinToString("\n") { shop ->
                    """<option value="${shop.id}">${shop.name}</option>"""
                }

                call.respondText(
                    """
        <html>
          <body>
            <form action="/test-booking-link" method="post">
              <label for="shopSelect">Shop:</label>
              <select id="shopSelect" name="shop_id" required>
                $optionsHtml
              </select>
              <br/>
              Phone: <input name="phone" type="text" required />
              <br/>
              <button type="submit">Generate Link</button>
            </form>
            <br>
            <form action="/api/booking/create" method="post">
              <label for="shopSelect2">Shop:</label>
              <select id="shopSelect2" name="shop_id" required>
                $optionsHtml
              </select>
              <br/>
              Phone: <input name="phone" type="text" required />
              <br/>
              <button type="submit">Generate SMS text</button>
            </form>
          </body>
        </html>
        """.trimIndent(), ContentType.Text.Html
                )
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

                    val baseUrl = System.getenv("PUBLIC_BOOKING_URL") ?: "http://localhost:9091"
                    val fullUrl = "$baseUrl/api/book?token=$booking_token"

                    call.respondText(
                        """<html><body>Generated link: <a href="$fullUrl">$fullUrl</a></body></html>""",
                        ContentType.Text.Html
                    )
                }


        }
    }

    fun FlowContent.header() {
        div {
            link(rel = "stylesheet", href = "/static/admin.css", type = "text/css")
            a(href = "/") { +"Home" }; +" | "
            a(href = "/managers") { +"Managers" }; +" | "
            a(href = "/shops") { +"Shops" }; +" | "
            a(href = "/employees") { +"Employees" }; +" | "
            a(href = "/services") { +"Services" }; +" | "
            a(href = "/assign-services") { +"Assign Services" }; +" | "
            a(href = "/appointments") { +"Appointments" }; +" | "
            a(href = "/test-booking-link") { +"Booking link" }; +" | "
            a(href = "/logout") { +"Logout" }
        }
    }
} 
