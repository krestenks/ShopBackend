import kotlinx.html.currentTimeMillis
import org.mindrot.jbcrypt.BCrypt
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.String

@Serializable
data class SimpleEmployee(val id: Int, val name: String)
@Serializable
data class SimpleService(val id: Int, val name: String, val duration: Int)
@Serializable
data class Manager(val id: Int = 0, val name: String, val username: String, val passwordHash: String, val phone: String?)
@Serializable
data class Employee(val id: Int = 0, val name: String, val phone: String?)
@Serializable
data class Shop(val id: Int, val name: String, val address: String?, val directions: String?, val managerId: Int)
@Serializable
data class Service(val id: Int, val name: String, val price: Double, val duration: Int)
@Serializable
data class TimeSlot(val start: Long, val end: Long)
@Serializable
data class Customer(val id: Int, val phone: String, val name: String, val status: String, val payment: Int, val language: Int)

data class BookingTokenInfo(val shopId: Int, val customerId: Int, val token: String)
data class Appointment(
    val id: Int,
    val employeeId: Int,
    val shopId: Int,
    val dateTime: Long,
    val duration: Int,
    val price: Double
)
@Serializable
data class AppointmentWithServices(
    val id: Int,
    val employeeId: Int,
    val shopId: Int,
    val dateTime: Long,
    val duration: Int,
    val price: Double,
    val services: List<Service>,
    val employee: Employee?,
    val customer: Customer?
)



@Serializable
data class ShopList(val shops: List<Shop>)

@Serializable
data class EmployeeList(val employees: List<Employee>)

@Serializable
data class ServiceList(val services: List<Service>)


class DataBase(dbFileName: String = "ShopManager.db") {
    private val dbFile = File(dbFileName)
    private val connection: Connection

    init {
        Class.forName("org.sqlite.JDBC")
        val dbUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        connection = DriverManager.getConnection(dbUrl)

        if (dbFile.createNewFile()) {
            println("Database created at: ${dbFile.absolutePath}")
        } else {
            println("Using existing database.")
        }

        createTables()
    }

    private fun createTables() {
        val sqlStatements = listOf(
            """
            CREATE TABLE IF NOT EXISTS booking_links (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                token TEXT NOT NULL UNIQUE,
                phone TEXT NOT NULL,
                customer_id INTEGER NOT NULL,
                shop_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                used INTEGER NOT NULL DEFAULT 0
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS customers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT NOT NULL,
                name TEXT,
                status TEXT,
                payment INTEGER,
                language INTEGER
            );
            ""","""
            CREATE TABLE IF NOT EXISTS appointments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                employee_id INTEGER NOT NULL,
                shop_id INTEGER NOT NULL,
                date_time INTEGER NOT NULL,
                duration INTEGER NOT NULL,
                price REAL NOT NULL,
                customer_id INTEGER NOT NULL
            );
            ""","""
            CREATE TABLE IF NOT EXISTS appointment_services (
                appointment_id INTEGER PRIMARY KEY AUTOINCREMENT,
                service_id INTEGER NOT NULL
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS managers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                username TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                phone TEXT
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS employees (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                phone TEXT
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS shops (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                address TEXT,
                directions TEXT,
                manager_id INTEGER
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS employee_shop (
                employee_id INTEGER,
                shop_id INTEGER,
                PRIMARY KEY (employee_id, shop_id)
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS services (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                price REAL,
                duration INTEGER
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS employee_specialty (
                employee_id INTEGER,
                specialty_id INTEGER,
                PRIMARY KEY (employee_id, specialty_id)
            );
            """
        )

        for (sql in sqlStatements) {
            connection.createStatement().use { stmt -> stmt.execute(sql) }
        }
        println("All tables ready.")
    }

    // === DAO methods ===

    fun authenticateShop(username: String, password: String): Shop? {
        val stmt = connection.prepareStatement("SELECT * FROM shops WHERE username = ?")
        stmt.setString(1, username)
        val rs = stmt.executeQuery()

        if (rs.next()) {
            val hash = rs.getString("password_hash")
            println("Authenticate shop with pwd:$password, hash: $hash")
            if (BCrypt.checkpw(password, hash)) {
                println("Shop verified")
                return Shop(
                    id = rs.getInt("id"),
                    name = rs.getString("name"),
                    address = rs.getString("address"),
                    directions = rs.getString("directions"),
                    managerId = rs.getInt("manager_id")
                )
            }
        }

        rs.close()
        stmt.close()
        return null
    }

    fun authenticateManager(username: String, password: String): Manager? {
        val stmt = connection.prepareStatement("SELECT * FROM managers WHERE username = ?")
        stmt.setString(1, username)
        val rs = stmt.executeQuery()

        if (rs.next()) {
            val hash = rs.getString("password_hash")
            println("Authenticate manager with pwd:$password, hash: $hash")
            if (BCrypt.checkpw(password, hash)) {
                println("Manager verified")
                return Manager(
                    id = rs.getInt("id"),
                    name = rs.getString("name"),
                    username = rs.getString("username"),
                    passwordHash = rs.getString("password_hash"),
                    phone = rs.getString("phone")
                )
            }
        }

        rs.close()
        stmt.close()
        return null
    }


    // Booking

    fun getBookingTokenInfo(token: String): BookingTokenInfo? {
        val sql = """
        SELECT shop_id, customer_id, created_at, used
        FROM booking_links
        WHERE token = ?
    """.trimIndent()

        val now = Instant.now().epochSecond

        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, token)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                val shopId = rs.getInt("shop_id")
                val customerId = rs.getInt("customer_id")
                val createdAt = rs.getLong("created_at")
                val used = rs.getInt("used")

                if ((used == 0) && (createdAt > (System.currentTimeMillis()-3600000)) )
                {
                    return BookingTokenInfo(shopId, customerId, token)
                }
                else
                {
                    println("Booking used or too old: $token, $used, $createdAt")
                }
            }
        }

        return null
    }

    fun markBookingTokenUsed(token: String): Boolean {
        val sql = "UPDATE booking_links SET used = 1 WHERE token = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, token)
            val updatedRows = stmt.executeUpdate()
            return updatedRows > 0
        }
    }

    fun deleteOldBookingTokens(olderThanMillis: Long = 3600_000) {
        val cutoff = System.currentTimeMillis() - olderThanMillis

        val sql = """
        DELETE FROM booking_links
        WHERE used = 1 OR created_at < ?
    """.trimIndent()

        connection.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, cutoff)
            val deleted = stmt.executeUpdate()
            println("Deleted $deleted old or used booking tokens")
        }
    }

    fun generateBookingToken(customerId: Int, shopId: Int, phone: String): String {
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        val createdAt = System.currentTimeMillis()

        val stmt = connection.prepareStatement(
            "INSERT INTO booking_links (token, phone, customer_id, shop_id, created_at) VALUES (?, ?, ?, ?, ?)"
        )
        stmt.setString(1, token)
        stmt.setString(2, phone)
        stmt.setInt(3, customerId)
        stmt.setInt(4, shopId)
        stmt.setLong(5, createdAt)
        stmt.executeUpdate()
        stmt.close()

        return token
    }

    fun getCustomerById(id: Int): Customer? {
        val stmt = connection.prepareStatement(
            "SELECT id, phone, name, status, payment, language FROM customers WHERE id = ?"
        )
        stmt.setInt(1, id)
        val rs = stmt.executeQuery()

        val customer = if (rs.next()) {
            Customer(
                id = rs.getInt("id"),
                phone = rs.getString("phone"),
                name = rs.getString("name"),
                status = rs.getString("status"),
                payment = rs.getInt("payment"),
                language = rs.getInt("language")
            )
        } else {
            null
        }

        rs.close()
        stmt.close()
        return customer
    }


    fun getCustomerIdByPhone(phone: String): Int? {
        val stmt = connection.prepareStatement("SELECT id FROM customers WHERE phone = ?")
        stmt.setString(1, phone)
        val rs = stmt.executeQuery()

        val id = if (rs.next()) rs.getInt("id") else null

        rs.close()
        stmt.close()
        return id
    }

    fun insertNewCustomer(phone: String): Int {
        val insertStmt = connection.prepareStatement("INSERT INTO customers (phone, status) VALUES (?, 'New')")
        insertStmt.setString(1, phone)
        insertStmt.executeUpdate()
        insertStmt.close()

        // Retrieve last inserted row ID (works in SQLite)
        val idStmt = connection.createStatement()
        val rs = idStmt.executeQuery("SELECT last_insert_rowid()")
        val id = if (rs.next()) rs.getInt(1) else throw Exception("Failed to retrieve new customer ID")

        rs.close()
        idStmt.close()
        return id
    }

    fun ensureCustomerByPhone(phone: String): Int {
        return getCustomerIdByPhone(phone) ?: insertNewCustomer(phone)
    }




    // Add a new appointment with placeholder price (to be updated later)
    fun addAppointment(employeeId: Int, shopId: Int, customerId: Int, dateTime: Long, duration: Int): Int {
        val stmt = connection.prepareStatement(
            "INSERT INTO appointments (employee_id, shop_id, customer_id, date_time, duration, price) VALUES (?, ?, ?, ?, ?, ?)"
        )
        stmt.setInt(1, employeeId)
        stmt.setInt(2, shopId)
        stmt.setInt(3, customerId)
        stmt.setLong(4, dateTime)
        stmt.setInt(5, duration)
        stmt.setDouble(6, 0.0)
        stmt.executeUpdate()
        stmt.close()

// Retrieve last inserted row id
        val query = connection.prepareStatement("SELECT last_insert_rowid() AS id")
        val rs = query.executeQuery()
        val appointmentId = if (rs.next()) rs.getInt("id") else -1
        rs.close()
        query.close()

        return appointmentId
    }

    // Add services to an appointment and update total price
    fun addServicesToAppointment(appointmentId: Int, serviceIds: List<Int>) {
        val insertStmt = connection.prepareStatement(
            "INSERT INTO appointment_services (appointment_id, service_id) VALUES (?, ?)"
        )
        var totalPrice = 0.0

        for (serviceId in serviceIds) {
            insertStmt.setInt(1, appointmentId)
            insertStmt.setInt(2, serviceId)
            insertStmt.addBatch()

            // Look up service price
            val priceStmt = connection.prepareStatement("SELECT price FROM services WHERE id = ?")
            priceStmt.setInt(1, serviceId)
            val rs = priceStmt.executeQuery()
            if (rs.next()) {
                totalPrice += rs.getDouble("price")
            }
            rs.close()
            priceStmt.close()
        }

        insertStmt.executeBatch()
        insertStmt.close()

        // Update appointment price
        val updateStmt = connection.prepareStatement("UPDATE appointments SET price = ? WHERE id = ?")
        updateStmt.setDouble(1, totalPrice)
        updateStmt.setInt(2, appointmentId)
        updateStmt.executeUpdate()
        updateStmt.close()
    }

    fun isAppointmentOverlapping(employeeId: Int, startMillis: Long, durationMinutes: Int): Boolean {
        val endMillis = startMillis + durationMinutes * 60 * 1000L
        // Query to check if any existing appointment for the employee overlaps with [startMillis, endMillis)
        val sql = """
        SELECT COUNT(*) FROM appointments 
        WHERE employee_id = ? 
          AND date_time < ? 
          AND (date_time + duration * 60000) > ?
    """
        val stmt = connection.prepareStatement(sql)
        stmt.setInt(1, employeeId)
        stmt.setLong(2, endMillis)
        stmt.setLong(3, startMillis)
        val rs = stmt.executeQuery()
        val count = if (rs.next()) rs.getInt(1) else 0
        rs.close()
        stmt.close()
        return count > 0
    }


    // Fetch all appointments
    fun getAllAppointments(): List<Appointment> {
        val stmt = connection.prepareStatement("SELECT * FROM appointments")
        val rs = stmt.executeQuery()
        val appointments = mutableListOf<Appointment>()

        while (rs.next()) {
            appointments.add(
                Appointment(
                    id = rs.getInt("id"),
                    employeeId = rs.getInt("employee_id"),
                    shopId = rs.getInt("shop_id"),
                    dateTime = rs.getLong("date_time"),
                    duration = rs.getInt("duration"),
                    price = rs.getDouble("price")
                )
            )
        }

        rs.close()
        stmt.close()
        return appointments
    }

    fun getAppointmentById(id: Int): Appointment? {
        val stmt = connection.prepareStatement("SELECT * FROM appointments WHERE id = ?")
        stmt.setInt(1, id)
        val rs = stmt.executeQuery()

        val appointment = if (rs.next()) {
            Appointment(
                id = rs.getInt("id"),
                employeeId = rs.getInt("employee_id"),
                shopId = rs.getInt("shop_id"),
                dateTime = rs.getLong("date_time"),
                duration = rs.getInt("duration"),
                price = rs.getDouble("price")
            )
        } else {
            null
        }

        rs.close()
        stmt.close()
        return appointment
    }

    fun getAppointmentsForShop(shopId: Int): List<AppointmentWithServices> {
        val stmt = connection.prepareStatement("SELECT * FROM appointments WHERE shop_id = ?")
        stmt.setInt(1, shopId)
        val rs = stmt.executeQuery()

        val appointments = mutableListOf<AppointmentWithServices>()
        while (rs.next()) {
            val appointmentId = rs.getInt("id")
            val services = getServicesForAppointment(appointmentId)

            appointments.add(
                AppointmentWithServices(
                    id = appointmentId,
                    employeeId = rs.getInt("employee_id"),
                    shopId = rs.getInt("shop_id"),
                    dateTime = rs.getLong("date_time"),
                    duration = rs.getInt("duration"),
                    price = rs.getDouble("price"),
                    services = services,
                    customer = getCustomerById(rs.getInt("customer_id")),
                    employee = getEmployeeById(rs.getInt("employee_id"))
                )
            )
        }

        rs.close()
        stmt.close()
        return appointments
    }


    // Fetch services linked to an appointment
    fun getServicesForAppointment(appointmentId: Int): List<Service> {
        val stmt = connection.prepareStatement(
            """
        SELECT s.id, s.name, s.price, s.duration 
        FROM appointment_services aps
        JOIN services s ON aps.service_id = s.id
        WHERE aps.appointment_id = ?
        """.trimIndent()
        )
        stmt.setInt(1, appointmentId)
        val rs = stmt.executeQuery()

        val services = mutableListOf<Service>()
        while (rs.next()) {
            services.add(Service(rs.getInt("id"), rs.getString("name"), rs.getDouble("price"), rs.getInt("duration")))
        }

        rs.close()
        stmt.close()
        return services
    }


    fun getAllServices(): List<Service> {
        val services = mutableListOf<Service>()
        connection.prepareStatement("SELECT id, name, price, duration FROM services").use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                services.add(
                    Service(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getDouble("price"),
                        rs.getInt("duration")
                    )
                )
            }
        }
        return services
    }

    fun addService(name: String, price: Double, duration: Int) {
        connection.prepareStatement("INSERT INTO services (name, price, duration) VALUES (?, ?, ?)").use { stmt ->
            stmt.setString(1, name)
            stmt.setDouble(2, price)
            stmt.setInt(3, duration)
            stmt.executeUpdate()
        }
    }

    fun deleteService(id: Int) {
        connection.prepareStatement("DELETE FROM services WHERE id = ?").use { stmt ->
            stmt.setInt(1, id)
            stmt.executeUpdate()
        }
    }

    fun getServiceById(id: Int): Service? {
        connection.prepareStatement("SELECT id, name, price, duration FROM services WHERE id = ?").use { stmt ->
            stmt.setInt(1, id)
            val rs = stmt.executeQuery()
            return if (rs.next()) {
                Service(rs.getInt("id"), rs.getString("name"), rs.getDouble("price"), rs.getInt("duration"))
            } else null
        }
    }

    fun getServicesForEmployee(employeeId: Int): List<Service> {
        val services = mutableListOf<Service>()
        val stmt = connection.prepareStatement(
            """
        SELECT s.id, s.name, s.price, s.duration
        FROM services s
        JOIN employee_services es ON s.id = es.service_id
        WHERE es.employee_id = ?
        """
        )
        stmt.setInt(1, employeeId)
        val rs = stmt.executeQuery()
        while (rs.next()) {
            services.add(
                Service(
                    id = rs.getInt("id"),
                    name = rs.getString("name"),
                    price = rs.getDouble("price"),
                    duration = rs.getInt("duration")
                )
            )
        }
        rs.close()
        stmt.close()
        return services
    }

    fun updateService(id: Int, name: String, price: Double, duration: Int) {
        connection.prepareStatement("UPDATE services SET name = ?, price = ?, duration = ? WHERE id = ?").use { stmt ->
            stmt.setString(1, name)
            stmt.setDouble(2, price)
            stmt.setInt(3, duration)
            stmt.setInt(4, id)
            stmt.executeUpdate()
        }
    }

    fun getAllEmployees(): List<Employee> {
        val employees = mutableListOf<Employee>()
        connection.prepareStatement("SELECT id, name, phone FROM employees").use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                employees.add(
                    Employee(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("phone")
                    )
                )
            }
        }
        return employees
    }

    fun addEmployee(name: String, phone: String?) {
        connection.prepareStatement("INSERT INTO employees (name, phone) VALUES (?, ?)").use { stmt ->
            stmt.setString(1, name)
            stmt.setString(2, phone)
            stmt.executeUpdate()
        }
    }

    fun deleteEmployee(id: Int) {
        connection.prepareStatement("DELETE FROM employees WHERE id = ?").use { stmt ->
            stmt.setInt(1, id)
            stmt.executeUpdate()
        }
    }

    fun getEmployeeById(id: Int): Employee? {
        connection.prepareStatement("SELECT id, name, phone FROM employees WHERE id = ?").use { stmt ->
            stmt.setInt(1, id)
            val rs = stmt.executeQuery()
            return if (rs.next()) {
                Employee(rs.getInt("id"), rs.getString("name"), rs.getString("phone"))
            } else null
        }
    }

    fun getEmployeesForShop(shopId: Int): List<Employee> {
        val employees = mutableListOf<Employee>()
        val stmt = connection.prepareStatement(
            """
        SELECT e.id, e.name, e.phone 
        FROM employees e
        JOIN employee_shop es ON e.id = es.employee_id
        WHERE es.shop_id = ?
        """
        )
        stmt.setInt(1, shopId)
        val rs = stmt.executeQuery()
        while (rs.next()) {
            employees.add(
                Employee(
                    id = rs.getInt("id"),
                    name = rs.getString("name"),
                    phone = rs.getString("phone")
                )
            )
        }
        rs.close()
        stmt.close()
        return employees
    }


    fun updateEmployee(id: Int, name: String, phone: String?) {
        connection.prepareStatement("UPDATE employees SET name = ?, phone = ? WHERE id = ?").use { stmt ->
            stmt.setString(1, name)
            stmt.setString(2, phone)
            stmt.setInt(3, id)
            stmt.executeUpdate()
        }
    }

    fun isManagerOfShop(managerId: Int, shopId: Int): Boolean {
        val sql = "SELECT 1 FROM shops WHERE id = ? AND manager_id = ? LIMIT 1"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            stmt.setInt(2, managerId)
            val rs = stmt.executeQuery()
            val isAuthorized = rs.next()
            rs.close()
            return isAuthorized
        }
    }


    fun getAllManagers(): List<Manager> {
        val result = mutableListOf<Manager>()
        val stmt = connection.prepareStatement("SELECT * FROM managers")
        val rs = stmt.executeQuery()
        while (rs.next()) {
            result.add(
                Manager(
                    rs.getInt("id"),
                    rs.getString("name"),
                    rs.getString("username"),
                    rs.getString("password_hash"),
                    rs.getString("phone")
                )
            )
        }
        rs.close()
        stmt.close()
        return result
    }

    fun addManager(name: String, username: String, password: String, phone: String?) {
        val stmt = connection.prepareStatement("""
        INSERT INTO managers (name, username, password, phone)
        VALUES (?, ?, ?, ?)
    """)
        val hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
        stmt.setString(1, name)
        stmt.setString(2, username)
        stmt.setString(3, hashedPassword)
        stmt.setString(4, phone)
        stmt.executeUpdate()
        stmt.close()
        connection.close()
    }

    fun getManagerById(id: Int): Manager? {
        val stmt = connection.prepareStatement("SELECT id, name, phone, username FROM managers WHERE id = ?")
        stmt.setInt(1, id)
        val rs = stmt.executeQuery()
        return if (rs.next()) {
            Manager(
                id = rs.getInt("id"),
                name = rs.getString("name"),
                phone = rs.getString("phone"),
                passwordHash = "",
                username = rs.getString("username")
            )
        } else {
            null
        }
    }

    fun updateManager(id: Int, name: String, phone: String, username: String) {
        val stmt = connection.prepareStatement("UPDATE managers SET name = ?, phone = ?, username = ? WHERE id = ?")
        stmt.setString(1, name)
        stmt.setString(2, phone)
        stmt.setString(3, username)
        stmt.setInt(4, id)
        stmt.executeUpdate()
    }

    fun deleteManager(id: Int) {
        val stmt = connection.prepareStatement("DELETE FROM managers WHERE id = ?")
        stmt.setInt(1, id)
        stmt.executeUpdate()
        stmt.close()
    }

    private fun hash(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun addManager(manager: Manager): Int {
        val sql = "INSERT INTO managers (name, username, password_hash, phone) VALUES (?, ?, ?, ?)"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, manager.name)
            stmt.setString(2, manager.username)
            stmt.setString(3, manager.passwordHash)
            stmt.setString(4, manager.phone)
            stmt.executeUpdate()
            return stmt.generatedKeys?.let { if (it.next()) it.getInt(1) else -1 } ?: -1
        }
    }

    fun getManagers(): List<Manager> {
        val sql = "SELECT * FROM managers"
        connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery(sql)
            return buildList {
                while (rs.next()) {
                    add(
                        Manager(
                            id = rs.getInt("id"),
                            name = rs.getString("name"),
                            username = rs.getString("username"),
                            passwordHash = rs.getString("password_hash"),
                            phone = rs.getString("phone")
                        )
                    )
                }
            }
        }
    }

    fun getManagerByUsername(username: String): Manager? {
        val stmt = connection.prepareStatement("SELECT * FROM managers WHERE username = ?")
        stmt.setString(1, username)
        val rs = stmt.executeQuery()
        val manager = if (rs.next()) {
            Manager(
                id = rs.getInt("id"),
                name = rs.getString("name"),
                username = rs.getString("username"),
                passwordHash = rs.getString("password_hash"),
                phone = rs.getString("phone")
            )
        } else null
        rs.close()
        stmt.close()
        return manager
    }

    fun addEmployee(employee: Employee): Int {
        val sql = "INSERT INTO employees (name, phone) VALUES (?, ?)"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, employee.name)
            stmt.setString(2, employee.phone)
            stmt.executeUpdate()
            return stmt.generatedKeys?.let { if (it.next()) it.getInt(1) else -1 } ?: -1
        }
    }

    fun getEmployees(): List<Employee> {
        val sql = "SELECT * FROM employees"
        connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery(sql)
            return buildList {
                while (rs.next()) {
                    add(
                        Employee(
                            id = rs.getInt("id"),
                            name = rs.getString("name"),
                            phone = rs.getString("phone")
                        )
                    )
                }
            }
        }
    }

    fun removeEmployeeFromAllShops(employeeId: Int) {
        connection.prepareStatement("DELETE FROM employee_shop WHERE employee_id = ?").use {
            it.setInt(1, employeeId)
            it.executeUpdate()
        }
    }

    fun getShopIdForEmployee(employeeId: Int): Int? {
        connection.prepareStatement("SELECT shop_id FROM employee_shop WHERE employee_id = ?").use {
            it.setInt(1, employeeId)
            val rs = it.executeQuery()
            return if (rs.next()) rs.getInt("shop_id") else null
        }
    }

    fun getShopsForManager(managerId: Int): List<Shop> {
        println("getShopsForManager: $managerId")

        val stmt = connection.prepareStatement(
            "SELECT * FROM shops WHERE manager_id = ?"
        )
        stmt.setInt(1, managerId)
        val rs = stmt.executeQuery()

        val shops = mutableListOf<Shop>()
        while (rs.next()) {
            val shop = Shop(
                id = rs.getInt("id"),
                name = rs.getString("name"),
                address = rs.getString("address"),
                directions = rs.getString("directions"),
                managerId = rs.getInt("manager_id")
            )
            shops.add(shop)
        }

        println("Returning shops: $shops")

        rs.close()
        stmt.close()
        return shops
    }

    fun getAllShops(): List<Shop> {
        val shops = mutableListOf<Shop>()
        connection.prepareStatement("SELECT id, name, address, directions, manager_id FROM shops").use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                shops.add(
                    Shop(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("address"),
                        rs.getString("directions"),
                        managerId = rs.getInt("manager_id")
                    )
                )
            }
        }
        return shops
    }

    fun addShop(name: String, address: String?, directions: String?) {
        connection.prepareStatement("INSERT INTO shops (name, address, directions) VALUES (?, ?, ?)").use { stmt ->
            stmt.setString(1, name)
            stmt.setString(2, address)
            stmt.setString(3, directions)
            stmt.executeUpdate()
        }
    }

    fun deleteShop(id: Int) {
        connection.prepareStatement("DELETE FROM shops WHERE id = ?").use { stmt ->
            stmt.setInt(1, id)
            stmt.executeUpdate()
        }
    }

    fun getShopById(id: Int): Shop? {
        connection.prepareStatement("SELECT id, name, address, directions, manager_id FROM shops WHERE id = ?").use { stmt ->
            stmt.setInt(1, id)
            val rs = stmt.executeQuery()
            return if (rs.next()) {
                Shop(
                    rs.getInt("id"),
                    rs.getString("name"),
                    rs.getString("address"),
                    rs.getString("directions"),
                    managerId = rs.getInt("manager_id")
                )
            } else null
        }
    }

    fun updateShop(id: Int, name: String, address: String?, directions: String?, managerId: Int?) {
        val stmt = connection.prepareStatement("""
        UPDATE shops SET name = ?, address = ?, directions = ?, manager_id = ?
        WHERE id = ?
    """)
        stmt.setString(1, name)
        stmt.setString(2, address)
        stmt.setString(3, directions)
        if (managerId != null) stmt.setInt(4, managerId) else stmt.setNull(4, java.sql.Types.INTEGER)
        stmt.setInt(5, id)
        stmt.executeUpdate()
        stmt.close()
    }


    // Link manager <-> shop
    fun assignManagerToShop(managerId: Int, shopId: Int) {
        val sql = "INSERT OR IGNORE INTO manager_shop (manager_id, shop_id) VALUES (?, ?)"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, managerId)
            stmt.setInt(2, shopId)
            stmt.executeUpdate()
        }
    }

    // Link employee <-> shop
    fun assignEmployeeToShop(employeeId: Int, shopId: Int, exclusive: Boolean = false) {
        connection.prepareStatement("""
        ${if (exclusive) "DELETE FROM employee_shop WHERE employee_id = ?" else ""}
    """.trimIndent()).use { stmt ->
            if (exclusive) {
                stmt.setInt(1, employeeId)
                stmt.executeUpdate()
            }
        }

        connection.prepareStatement("""
        INSERT OR IGNORE INTO employee_shop (employee_id, shop_id) VALUES (?, ?)
    """).use { stmt ->
            stmt.setInt(1, employeeId)
            stmt.setInt(2, shopId)
            stmt.executeUpdate()
        }
    }

    fun getEmployeesPerShop(): List<Pair<Shop, Employee>> {
        val result = mutableListOf<Pair<Shop, Employee>>()
        val stmt = connection.prepareStatement("""
        SELECT s.id, s.name, s.address, s.directions,
               e.id as emp_id, e.name as emp_name, e.phone
        FROM shop_employee se
        JOIN shops s ON se.shop_id = s.id
        JOIN employees e ON se.employee_id = e.id
        ORDER BY s.id, e.id
    """.trimIndent())
        val rs = stmt.executeQuery()
        while (rs.next()) {
            val shop = Shop(
                id = rs.getInt("id"),
                name = rs.getString("name"),
                address = rs.getString("address"),
                directions = rs.getString("directions"),
                managerId = rs.getInt("manager_id")
            )
            val employee = Employee(
                id = rs.getInt("emp_id"),
                name = rs.getString("emp_name"),
                phone = rs.getString("phone")
            )
            result += shop to employee
        }
        return result
    }


    fun getAllEmployeeServiceRelations(): List<Pair<Employee, Service>> {
        val stmt = connection.prepareStatement("""
        SELECT e.id, e.name, e.phone, s.id, s.name, s.price, s.duration 
        FROM employee_services es
        JOIN employees e ON e.id = es.employee_id
        JOIN services s ON s.id = es.service_id
        ORDER BY e.id, s.id
    """)
        val rs = stmt.executeQuery()
        val result = mutableListOf<Pair<Employee, Service>>()
        while (rs.next()) {
            val emp = Employee(rs.getInt(1), rs.getString(2), rs.getString(3))
            val svc = Service(rs.getInt(4), rs.getString(5), rs.getDouble(6), rs.getInt(7))
            result.add(emp to svc)
        }
        rs.close()
        stmt.close()
        return result
    }

    fun assignServiceToEmployee(employeeId: Int, serviceId: Int) {
        val stmt = connection.prepareStatement("INSERT OR IGNORE INTO employee_services (employee_id, service_id) VALUES (?, ?)")
        stmt.setInt(1, employeeId)
        stmt.setInt(2, serviceId)
        stmt.executeUpdate()
        stmt.close()
    }

    fun removeServiceFromEmployee(employeeId: Int, serviceId: Int) {
        val stmt = connection.prepareStatement("DELETE FROM employee_services WHERE employee_id = ? AND service_id = ?")
        stmt.setInt(1, employeeId)
        stmt.setInt(2, serviceId)
        stmt.executeUpdate()
        stmt.close()
    }

    fun getEmployeesByShop(shopId: Int): List<Employee> {
        val stmt = connection.prepareStatement(
            "SELECT e.id, e.name, e.phone FROM employees e JOIN employee_shop es ON e.id = es.employee_id WHERE es.shop_id = ?"
        )
        stmt.setInt(1, shopId)
        val rs = stmt.executeQuery()
        val employees = mutableListOf<Employee>()
        while (rs.next()) {
            employees.add(Employee(rs.getInt("id"), rs.getString("name"), rs.getString("phone")))
        }
        rs.close()
        stmt.close()
        return employees
    }

    fun getTotalDurationForServices(serviceIds: List<Int>): Int {
        if (serviceIds.isEmpty()) return 0
        val inClause = serviceIds.joinToString(",")
        val stmt = connection.prepareStatement(
            "SELECT SUM(duration) as total FROM services WHERE id IN ($inClause)"
        )
        val rs = stmt.executeQuery()
        val total = if (rs.next()) rs.getInt("total") else 0
        rs.close()
        stmt.close()
        return total
    }

    fun getAvailableTimeSlots(employeeId: Int, shopId: Int, dateStr: String, duration: Int): List<String> {
        println("getAvailableTimeSlots called with:")
        println("  employeeId = $employeeId")
        println("  shopId = $shopId")
        println("  dateStr = $dateStr")
        println("  duration = $duration")

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val date = LocalDate.parse(dateStr, formatter)

        val defaultWorkStart = date.atTime(8, 0)
        val workEnd = date.atTime(23, 55)

        // Determine workStart depending on whether date is today
        val now = LocalDateTime.now()
        val workStart = if (date.isEqual(LocalDate.now())) {
            // Round now up to next 10-minute mark
            val minute = now.minute
            val roundedMinute = ((minute + 9) / 10) * 10
            val adjustedNow = now.withMinute(0).withSecond(0).withNano(0).plusMinutes(roundedMinute.toLong())
            if (adjustedNow.isAfter(defaultWorkStart)) adjustedNow else defaultWorkStart
        } else {
            defaultWorkStart
        }

        val intervalMinutes = 10

        // Get all existing appointments for the employee on that day
        val appointments = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        val startOfDaySeconds = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endOfDaySeconds = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val startOfDay = startOfDaySeconds * 1000  // Convert to milliseconds
        val endOfDay = endOfDaySeconds * 1000      // Convert to milliseconds

        println("  startOfDay epoch milliseconds = $startOfDay")
        println("  endOfDay epoch milliseconds = $endOfDay")

        val query = """
        SELECT date_time, duration FROM appointments
        WHERE employee_id = ? AND shop_id = ? AND date_time >= ? AND date_time < ?
    """

        connection.prepareStatement(query).use { stmt ->
            stmt.setInt(1, employeeId)
            stmt.setInt(2, shopId)
            stmt.setLong(3, startOfDay)
            stmt.setLong(4, endOfDay)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                val startEpoch = rs.getLong("date_time")
                val apptStart = Instant.ofEpochMilli(startEpoch)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
                val apptEnd = apptStart.plusMinutes(rs.getInt("duration").toLong())
                println("  Loaded appointment: $apptStart to $apptEnd")
                appointments.add(Pair(apptStart, apptEnd))
            }
        }

        // Generate slots
        val availableSlots = mutableListOf<String>()
        var slot = workStart
        val slotFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        while (slot.plusMinutes(duration.toLong()) <= workEnd) {
            val slotEnd = slot.plusMinutes(duration.toLong())
            val conflicts = appointments.any { (apptStart, apptEnd) ->
                !(slotEnd <= apptStart || slot >= apptEnd)
            }
            if (!conflicts) {
                availableSlots.add(slot.format(slotFormatter))
            }
            slot = slot.plusMinutes(intervalMinutes.toLong())
        }

        return availableSlots
    }







}
