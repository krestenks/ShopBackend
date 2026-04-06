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

@Serializable
data class ShopVoiceConfig(
    val shopId: Int,
    val twilioNumber: String? = null,
    val operatorPhone: String? = null,
    val welcomeOpenMessage: String = "Hello and welcome. We are open.",
    val welcomeClosedMessage: String = "Hello and welcome. We are currently closed.",
    val temporaryOperatorClosed: Boolean = false,
    val temporaryOperatorClosedMessage: String = "Our phones are temporarily unavailable. Please try again in 30 minutes.",
    val businessName: String? = null,
)

// ─── Voice call log ──────────────────────────────────────────────────────────

/** Current state of a tracked inbound call (mirrors Phone flow spec state machine). */
enum class VoiceCallState {
    INCOMING_CALL,
    REJECTED_BLACKLISTED,
    IDENTIFY_CUSTOMER,
    CHECK_OPENING_HOURS,
    CHECK_TEMP_OPERATOR_CLOSURE,
    UNKNOWN_CUSTOMER_ROUTE,
    KNOWN_CUSTOMER_MENU,
    KNOWN_CUSTOMER_SMS_BOOKING,
    KNOWN_CUSTOMER_OPERATOR_ROUTE,
    OPERATOR_BUSY,
    OPERATOR_WHISPER,
    BRIDGED_TO_OPERATOR,
    CLOSED_MESSAGE,
    TEMPORARY_CLOSED_MESSAGE,
    TERMINATED,
}

enum class VoiceCallOutcome {
    BLACKLIST_REJECTED,
    CLOSED_HOURS,
    TEMP_OPERATOR_CLOSED,
    OPERATOR_BUSY,
    SMS_SENT,
    OPERATOR_BRIDGED,
    OPERATOR_DECLINED,
    INVALID_MENU_MAX_RETRIES,
    SYSTEM_ERROR,
    IN_PROGRESS,
}

@Serializable
data class VoiceCallRecord(
    val id: Int,
    val shopId: Int,
    val twilioCallSid: String,
    val fromPhone: String,
    val toPhone: String,
    val customerType: String,          // "unknown" | "known"
    val customerId: Int?,
    val state: String,
    val outcome: String,
    val isActive: Boolean,
    val startedAt: Long,
    val endedAt: Long?,
    val linkedBookingId: Int?,
    val operatorPhone: String?,        // operator target for this call (cross-shop busy detection)
)

@Serializable
data class VoiceCallEvent(
    val id: Int,
    val callId: Int,
    val state: String,
    val note: String?,
    val createdAt: Long,
)

// ─── Blacklist ───────────────────────────────────────────────────────────────

@Serializable
data class BlacklistEntry(
    val id: Int,
    val shopId: Int,
    val phoneE164: String,
    val reason: String?,
    val createdAt: Long,
    val active: Boolean,
)

@Serializable
data class ShopOpeningHours(
    val shopId: Int,
    /** 1=Mon .. 7=Sun */
    val dayOfWeek: Int,
    /** Minutes since midnight, local shop time */
    val openMinute: Int,
    /** Minutes since midnight, local shop time */
    val closeMinute: Int,
    val closed: Boolean = false,
)


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
            CREATE TABLE IF NOT EXISTS employee_services (
                employee_id INTEGER,
                service_id INTEGER,
                PRIMARY KEY (employee_id, service_id)
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS shop_voice_config (
                shop_id INTEGER PRIMARY KEY,
                twilio_number TEXT,
                operator_phone TEXT,
                welcome_open_message TEXT,
                welcome_closed_message TEXT
            );
            """
            ,
            """
            CREATE TABLE IF NOT EXISTS shop_opening_hours (
                shop_id INTEGER NOT NULL,
                day_of_week INTEGER NOT NULL,
                open_minute INTEGER NOT NULL,
                close_minute INTEGER NOT NULL,
                closed INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (shop_id, day_of_week)
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS phone_blacklist (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                shop_id INTEGER NOT NULL,
                phone_e164 TEXT NOT NULL,
                reason TEXT,
                created_at INTEGER NOT NULL,
                active INTEGER NOT NULL DEFAULT 1,
                UNIQUE(shop_id, phone_e164)
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS voice_call (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                shop_id INTEGER NOT NULL,
                twilio_call_sid TEXT NOT NULL UNIQUE,
                from_phone TEXT NOT NULL,
                to_phone TEXT NOT NULL,
                customer_type TEXT NOT NULL DEFAULT 'unknown',
                customer_id INTEGER,
                state TEXT NOT NULL DEFAULT 'INCOMING_CALL',
                outcome TEXT NOT NULL DEFAULT 'IN_PROGRESS',
                is_active INTEGER NOT NULL DEFAULT 1,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                linked_booking_id INTEGER,
                operator_phone TEXT
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS voice_call_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                call_id INTEGER NOT NULL,
                state TEXT NOT NULL,
                note TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(call_id) REFERENCES voice_call(id)
            );
            """
        )

        for (sql in sqlStatements) {
            connection.createStatement().use { stmt -> stmt.execute(sql) }
        }

        // Migration: rename old table if it exists
        try {
            connection.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE employee_specialty RENAME TO employee_services")
                println("Migrated employee_specialty to employee_services")
            }
        } catch (e: Exception) {
            // Table already exists or other error - ignore
        }

        // Migrations: extend existing tables with new columns
        listOf(
            "ALTER TABLE shop_voice_config ADD COLUMN operator_phone TEXT",
            "ALTER TABLE shop_voice_config ADD COLUMN temporary_operator_closed INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE shop_voice_config ADD COLUMN temporary_operator_closed_message TEXT",
            "ALTER TABLE shop_voice_config ADD COLUMN business_name TEXT",
            "ALTER TABLE voice_call ADD COLUMN operator_phone TEXT",
        ).forEach { sql ->
            try { connection.createStatement().use { it.execute(sql) } } catch (_: Exception) {}
        }

        // Dedicated mobile app accounts (separate from web-admin login)
        connection.createStatement().use { it.execute("""
            CREATE TABLE IF NOT EXISTS app_account (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                ref_type     TEXT NOT NULL,          -- 'manager' | 'shop'
                ref_id       INTEGER NOT NULL,
                username     TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                active       INTEGER NOT NULL DEFAULT 1,
                UNIQUE(ref_type, ref_id)
            )
        """.trimIndent()) }

        println("All tables ready.")
    }

    // === Shop voice config ===

    fun getShopVoiceConfig(shopId: Int): ShopVoiceConfig {
        val sql = """
            SELECT shop_id, twilio_number, operator_phone, welcome_open_message, welcome_closed_message,
                   temporary_operator_closed, temporary_operator_closed_message, business_name
            FROM shop_voice_config WHERE shop_id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            val rs = stmt.executeQuery()
            return if (rs.next()) {
                ShopVoiceConfig(
                    shopId = rs.getInt("shop_id"),
                    twilioNumber = rs.getString("twilio_number"),
                    operatorPhone = rs.getString("operator_phone"),
                    welcomeOpenMessage = rs.getString("welcome_open_message") ?: ShopVoiceConfig(shopId).welcomeOpenMessage,
                    welcomeClosedMessage = rs.getString("welcome_closed_message") ?: ShopVoiceConfig(shopId).welcomeClosedMessage,
                    temporaryOperatorClosed = rs.getInt("temporary_operator_closed") != 0,
                    temporaryOperatorClosedMessage = rs.getString("temporary_operator_closed_message")
                        ?: ShopVoiceConfig(shopId).temporaryOperatorClosedMessage,
                    businessName = rs.getString("business_name"),
                )
            } else {
                ShopVoiceConfig(shopId)
            }
        }
    }

    fun upsertShopVoiceConfig(config: ShopVoiceConfig) {
        val sql = """
            INSERT INTO shop_voice_config (shop_id, twilio_number, operator_phone, welcome_open_message,
                welcome_closed_message, temporary_operator_closed, temporary_operator_closed_message, business_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(shop_id) DO UPDATE SET
                twilio_number = excluded.twilio_number,
                operator_phone = excluded.operator_phone,
                welcome_open_message = excluded.welcome_open_message,
                welcome_closed_message = excluded.welcome_closed_message,
                temporary_operator_closed = excluded.temporary_operator_closed,
                temporary_operator_closed_message = excluded.temporary_operator_closed_message,
                business_name = excluded.business_name
        """.trimIndent()

        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, config.shopId)
            stmt.setString(2, config.twilioNumber)
            stmt.setString(3, config.operatorPhone)
            stmt.setString(4, config.welcomeOpenMessage)
            stmt.setString(5, config.welcomeClosedMessage)
            stmt.setInt(6, if (config.temporaryOperatorClosed) 1 else 0)
            stmt.setString(7, config.temporaryOperatorClosedMessage)
            stmt.setString(8, config.businessName)
            stmt.executeUpdate()
        }
    }

    // =========================================================================
    // Phone blacklist
    // =========================================================================

    fun isPhoneBlacklisted(shopId: Int, phone: String): Boolean {
        val sql = "SELECT 1 FROM phone_blacklist WHERE shop_id = ? AND phone_e164 = ? AND active = 1 LIMIT 1"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            stmt.setString(2, phone.trim())
            val rs = stmt.executeQuery()
            return rs.next()
        }
    }

    fun addBlacklistEntry(shopId: Int, phone: String, reason: String?): Int {
        val sql = """
            INSERT INTO phone_blacklist (shop_id, phone_e164, reason, created_at, active)
            VALUES (?, ?, ?, ?, 1)
            ON CONFLICT(shop_id, phone_e164) DO UPDATE SET active = 1, reason = excluded.reason, created_at = excluded.created_at
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            stmt.setString(2, phone.trim())
            stmt.setString(3, reason)
            stmt.setLong(4, System.currentTimeMillis())
            stmt.executeUpdate()
        }
        connection.createStatement().use { s ->
            val rs = s.executeQuery("SELECT last_insert_rowid()")
            return if (rs.next()) rs.getInt(1) else -1
        }
    }

    fun removeBlacklistEntry(shopId: Int, phone: String): Boolean {
        val sql = "UPDATE phone_blacklist SET active = 0 WHERE shop_id = ? AND phone_e164 = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            stmt.setString(2, phone.trim())
            return stmt.executeUpdate() > 0
        }
    }

    fun removeBlacklistEntryById(entryId: Int): Boolean {
        val sql = "UPDATE phone_blacklist SET active = 0 WHERE id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, entryId)
            return stmt.executeUpdate() > 0
        }
    }

    fun listBlacklist(shopId: Int): List<BlacklistEntry> {
        val sql = "SELECT id, shop_id, phone_e164, reason, created_at, active FROM phone_blacklist WHERE shop_id = ? AND active = 1 ORDER BY created_at DESC"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            val rs = stmt.executeQuery()
            val result = mutableListOf<BlacklistEntry>()
            while (rs.next()) {
                result += BlacklistEntry(
                    id = rs.getInt("id"),
                    shopId = rs.getInt("shop_id"),
                    phoneE164 = rs.getString("phone_e164"),
                    reason = rs.getString("reason"),
                    createdAt = rs.getLong("created_at"),
                    active = rs.getInt("active") != 0,
                )
            }
            return result
        }
    }

    // =========================================================================
    // Voice call log
    // =========================================================================

    /**
     * Returns true if there is already an active call in an operator-active state
     * for the given operator phone number (across ALL shops).
     *
     * The check is scoped by operator_phone so a shared operator can't be double-booked
     * from two different shops simultaneously.
     */
    fun isOperatorBusy(operatorPhone: String): Boolean {
        val sql = """
            SELECT 1 FROM voice_call
            WHERE operator_phone = ?
              AND is_active = 1
              AND state IN ('OPERATOR_WHISPER', 'BRIDGED_TO_OPERATOR')
            LIMIT 1
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, operatorPhone.trim())
            val rs = stmt.executeQuery()
            return rs.next()
        }
    }

    /**
     * Set or update the operator_phone for an existing call record.
     * Called just before entering the operator-whisper/bridge path.
     */
    fun setCallOperatorPhone(callId: Int, operatorPhone: String) {
        connection.prepareStatement("UPDATE voice_call SET operator_phone = ? WHERE id = ?").use { stmt ->
            stmt.setString(1, operatorPhone.trim())
            stmt.setInt(2, callId)
            stmt.executeUpdate()
        }
    }

    /** Insert a new call log row immediately when the inbound call arrives. Returns the new row ID. */
    fun createInboundCallLog(shopId: Int, twilioCallSid: String, fromPhone: String, toPhone: String): Int {
        val sql = """
            INSERT INTO voice_call (shop_id, twilio_call_sid, from_phone, to_phone, state, outcome, is_active, started_at)
            VALUES (?, ?, ?, ?, 'INCOMING_CALL', 'IN_PROGRESS', 1, ?)
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            stmt.setString(2, twilioCallSid)
            stmt.setString(3, fromPhone)
            stmt.setString(4, toPhone)
            stmt.setLong(5, System.currentTimeMillis())
            stmt.executeUpdate()
        }
        connection.createStatement().use { s ->
            val rs = s.executeQuery("SELECT last_insert_rowid()")
            return if (rs.next()) rs.getInt(1) else -1
        }
    }

    fun updateCallState(callId: Int, state: VoiceCallState, note: String? = null) {
        connection.prepareStatement("UPDATE voice_call SET state = ? WHERE id = ?").use { stmt ->
            stmt.setString(1, state.name)
            stmt.setInt(2, callId)
            stmt.executeUpdate()
        }
        appendCallEvent(callId, state.name, note)
    }

    fun updateCallCustomer(callId: Int, customerId: Int?, customerType: String) {
        connection.prepareStatement("UPDATE voice_call SET customer_id = ?, customer_type = ? WHERE id = ?").use { stmt ->
            if (customerId != null) stmt.setInt(1, customerId) else stmt.setNull(1, java.sql.Types.INTEGER)
            stmt.setString(2, customerType)
            stmt.setInt(3, callId)
            stmt.executeUpdate()
        }
    }

    fun terminateCall(callId: Int, outcome: VoiceCallOutcome) {
        val now = System.currentTimeMillis()
        connection.prepareStatement(
            "UPDATE voice_call SET is_active = 0, ended_at = ?, state = 'TERMINATED', outcome = ? WHERE id = ?"
        ).use { stmt ->
            stmt.setLong(1, now)
            stmt.setString(2, outcome.name)
            stmt.setInt(3, callId)
            stmt.executeUpdate()
        }
        appendCallEvent(callId, VoiceCallState.TERMINATED.name, outcome.name)
    }

    fun linkBookingToCall(callId: Int, bookingId: Int) {
        connection.prepareStatement("UPDATE voice_call SET linked_booking_id = ? WHERE id = ?").use { stmt ->
            stmt.setInt(1, bookingId)
            stmt.setInt(2, callId)
            stmt.executeUpdate()
        }
        appendCallEvent(callId, "BOOKING_LINKED", "booking_id=$bookingId")
    }

    fun appendCallEvent(callId: Int, state: String, note: String? = null) {
        connection.prepareStatement(
            "INSERT INTO voice_call_event (call_id, state, note, created_at) VALUES (?, ?, ?, ?)"
        ).use { stmt ->
            stmt.setInt(1, callId)
            stmt.setString(2, state)
            stmt.setString(3, note)
            stmt.setLong(4, System.currentTimeMillis())
            stmt.executeUpdate()
        }
    }

    fun getActiveCallsForShop(shopId: Int): List<VoiceCallRecord> {
        val sql = """
            SELECT id, shop_id, twilio_call_sid, from_phone, to_phone, customer_type, customer_id,
                   state, outcome, is_active, started_at, ended_at, linked_booking_id, operator_phone
            FROM voice_call WHERE shop_id = ? AND is_active = 1 ORDER BY started_at DESC
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            return buildCallRecordList(stmt.executeQuery())
        }
    }

    fun getRecentCallsForShop(shopId: Int, limit: Int = 50): List<VoiceCallRecord> {
        val sql = """
            SELECT id, shop_id, twilio_call_sid, from_phone, to_phone, customer_type, customer_id,
                   state, outcome, is_active, started_at, ended_at, linked_booking_id, operator_phone
            FROM voice_call WHERE shop_id = ? ORDER BY started_at DESC LIMIT ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            stmt.setInt(2, limit)
            return buildCallRecordList(stmt.executeQuery())
        }
    }

    fun getCallById(callId: Int): VoiceCallRecord? {
        val sql = """
            SELECT id, shop_id, twilio_call_sid, from_phone, to_phone, customer_type, customer_id,
                   state, outcome, is_active, started_at, ended_at, linked_booking_id, operator_phone
            FROM voice_call WHERE id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, callId)
            return buildCallRecordList(stmt.executeQuery()).firstOrNull()
        }
    }

    fun getCallByTwilioSid(twilioCallSid: String): VoiceCallRecord? {
        val sql = """
            SELECT id, shop_id, twilio_call_sid, from_phone, to_phone, customer_type, customer_id,
                   state, outcome, is_active, started_at, ended_at, linked_booking_id, operator_phone
            FROM voice_call WHERE twilio_call_sid = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, twilioCallSid)
            return buildCallRecordList(stmt.executeQuery()).firstOrNull()
        }
    }

    fun getCallEvents(callId: Int): List<VoiceCallEvent> {
        val sql = "SELECT id, call_id, state, note, created_at FROM voice_call_event WHERE call_id = ? ORDER BY created_at ASC"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, callId)
            val rs = stmt.executeQuery()
            val result = mutableListOf<VoiceCallEvent>()
            while (rs.next()) {
                result += VoiceCallEvent(
                    id = rs.getInt("id"),
                    callId = rs.getInt("call_id"),
                    state = rs.getString("state"),
                    note = rs.getString("note"),
                    createdAt = rs.getLong("created_at"),
                )
            }
            return result
        }
    }

    private fun buildCallRecordList(rs: java.sql.ResultSet): List<VoiceCallRecord> {
        val result = mutableListOf<VoiceCallRecord>()
        while (rs.next()) {
            result += VoiceCallRecord(
                id = rs.getInt("id"),
                shopId = rs.getInt("shop_id"),
                twilioCallSid = rs.getString("twilio_call_sid"),
                fromPhone = rs.getString("from_phone"),
                toPhone = rs.getString("to_phone"),
                customerType = rs.getString("customer_type"),
                customerId = rs.getInt("customer_id").takeIf { !rs.wasNull() },
                state = rs.getString("state"),
                outcome = rs.getString("outcome"),
                isActive = rs.getInt("is_active") != 0,
                startedAt = rs.getLong("started_at"),
                endedAt = rs.getLong("ended_at").takeIf { !rs.wasNull() },
                linkedBookingId = rs.getInt("linked_booking_id").takeIf { !rs.wasNull() },
                operatorPhone = rs.getString("operator_phone"),
            )
        }
        return result
    }

    // === Shop opening hours ===

    fun getShopOpeningHours(shopId: Int): List<ShopOpeningHours> {
        val sql = "SELECT shop_id, day_of_week, open_minute, close_minute, closed FROM shop_opening_hours WHERE shop_id = ? ORDER BY day_of_week"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            val rs = stmt.executeQuery()
            val result = mutableListOf<ShopOpeningHours>()
            while (rs.next()) {
                result += ShopOpeningHours(
                    shopId = rs.getInt("shop_id"),
                    dayOfWeek = rs.getInt("day_of_week"),
                    openMinute = rs.getInt("open_minute"),
                    closeMinute = rs.getInt("close_minute"),
                    closed = rs.getInt("closed") != 0,
                )
            }
            return result
        }
    }

    fun ensureDefaultShopOpeningHours(shopId: Int) {
        // Only insert defaults if the shop has no rows yet.
        val existing = getShopOpeningHours(shopId)
        if (existing.isNotEmpty()) return

        // Default: Mon-Fri 09:00-17:00, Sat 10:00-14:00, Sun closed.
        val defaults = buildList {
            for (dow in 1..5) add(ShopOpeningHours(shopId, dow, 9 * 60, 17 * 60, closed = false))
            add(ShopOpeningHours(shopId, 6, 10 * 60, 14 * 60, closed = false))
            add(ShopOpeningHours(shopId, 7, 0, 0, closed = true))
        }
        upsertShopOpeningHours(defaults)
    }

    fun upsertShopOpeningHours(rows: List<ShopOpeningHours>) {
        if (rows.isEmpty()) return
        val sql = """
            INSERT INTO shop_opening_hours (shop_id, day_of_week, open_minute, close_minute, closed)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(shop_id, day_of_week) DO UPDATE SET
                open_minute = excluded.open_minute,
                close_minute = excluded.close_minute,
                closed = excluded.closed
        """.trimIndent()

        connection.prepareStatement(sql).use { stmt ->
            for (r in rows) {
                stmt.setInt(1, r.shopId)
                stmt.setInt(2, r.dayOfWeek)
                stmt.setInt(3, r.openMinute)
                stmt.setInt(4, r.closeMinute)
                stmt.setInt(5, if (r.closed) 1 else 0)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    /**
     * Returns the phone number of the manager assigned to the given shop.
     * Used as the operator contact number for call routing.
     */
    fun getManagerPhoneForShop(shopId: Int): String? {
        val sql = """
            SELECT m.phone
            FROM shops s
            JOIN managers m ON m.id = s.manager_id
            WHERE s.id = ?
            LIMIT 1
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.getString("phone")?.trim()?.takeIf { it.isNotBlank() } else null
        }
    }

    /**
     * Map an incoming Twilio call/SMS `To` number to a shop.
     *
     * We match against the per-shop configured Twilio number in [shop_voice_config].
     */
    fun findShopIdByTwilioNumber(twilioToNumber: String): Int? {
        val normalized = twilioToNumber.trim()
        if (normalized.isBlank()) return null

        val sql = """
            SELECT shop_id
            FROM shop_voice_config
            WHERE replace(replace(replace(twilio_number, ' ', ''), '-', ''), '(', '') =
                  replace(replace(replace(?, ' ', ''), '-', ''), '(', '')
            LIMIT 1
        """.trimIndent()

        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, normalized)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.getInt("shop_id") else null
        }
    }

    // === DAO methods ===

    fun authenticateShop(username: String, password: String): Shop? {
        // Some deployments may have a shops table without username/password_hash columns.
        // In that case, shop login is simply unavailable (manager login still works).
        val rs = try {
            val stmt = connection.prepareStatement("SELECT * FROM shops WHERE username = ?")
            stmt.setString(1, username)
            stmt.executeQuery()
        } catch (e: Exception) {
            println("authenticateShop: shops login not available (${e.message})")
            return null
        }

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
            "SELECT * FROM customers WHERE id = ?"
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
        val insertStmt = connection.prepareStatement("INSERT INTO customers (phone, status, name, payment, language) VALUES (?, 'New', 'NoName', 0, 0)")
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


    /**
     * Convenience helper for features that need to contact the customer (e.g. Twilio voice call).
     *
     * NOTE: The [Appointment] model currently does not expose customer_id, so this method returns
     * the richer [AppointmentWithServices] which includes the [customer] object.
     */
    fun getAppointmentWithServicesById(appointmentId: Int): AppointmentWithServices? {
        val stmt = connection.prepareStatement("SELECT * FROM appointments WHERE id = ?")
        stmt.setInt(1, appointmentId)
        val rs = stmt.executeQuery()

        val result = if (rs.next()) {
            val services = getServicesForAppointment(appointmentId)
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
        } else {
            null
        }

        rs.close()
        stmt.close()
        return result
    }

    fun getAppointmentsForShop(shopId: Int): List<AppointmentWithServices> {
        val stmt = connection.prepareStatement("SELECT * FROM appointments WHERE shop_id = ? ORDER BY date_time ASC")
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
        INSERT INTO managers (name, username, password_hash, phone)
        VALUES (?, ?, ?, ?)
    """)
        val hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
        stmt.setString(1, name)
        stmt.setString(2, username)
        stmt.setString(3, hashedPassword)
        stmt.setString(4, phone)
        stmt.executeUpdate()
        stmt.close()
        // Do NOT close the shared DB connection here.
        // Closing it will break all subsequent requests (e.g. /managers) with
        // java.sql.SQLException: database connection closed
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

    fun getAvailableTimeSlots(employeeId: Int, shopId: Int, dateStr: String, duration: Int): Array<String> {
        println("getAvailableTimeSlots called with:")
        println("  employeeId = $employeeId")
        println("  shopId = $shopId")
        println("  dateStr = $dateStr")
        println("  duration = $duration")

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val date = LocalDate.parse(dateStr, formatter)

        val defaultWorkStart = date.atTime(8, 0)
        val workEnd = date.atTime(23, 55)

        // Determine workStart depending on whether date is today (using shop's local timezone)
        val shopZoneForNow = java.time.ZoneId.of("Europe/Copenhagen")
        val now = LocalDateTime.now(shopZoneForNow)
        val workStart = if (date.isEqual(LocalDate.now(shopZoneForNow))) {
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
        val shopZone = java.time.ZoneId.of("Europe/Copenhagen")
        val startOfDaySeconds = date.atStartOfDay(shopZone).toEpochSecond()
        val endOfDaySeconds = date.plusDays(1).atStartOfDay(shopZone).toEpochSecond()

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
                    .atZone(java.time.ZoneId.of("Europe/Copenhagen"))
                    .toLocalDateTime()
                val apptEnd = apptStart.plusMinutes(rs.getInt("duration").toLong())
                println("  Loaded appointment: $apptStart to $apptEnd")
                appointments.add(Pair(apptStart, apptEnd))
            }
        }

        // Generate slots
        val slotFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val result = ArrayList<String>()
        var slot = workStart

        while (slot.plusMinutes(duration.toLong()) <= workEnd) {
            val slotEnd = slot.plusMinutes(duration.toLong())
            val conflicts = appointments.any { (apptStart, apptEnd) ->
                !(slotEnd <= apptStart || slot >= apptEnd)
            }
            if (!conflicts) {
                result.add(slot.format(slotFormatter))
            }
            slot = slot.plusMinutes(intervalMinutes.toLong())
        }

        return result.toTypedArray()
    }

    // Summarized slots to avoid returning large lists
    fun getAvailableTimeSlotsSummary(employeeId: Int, shopId: Int, dateStr: String, duration: Int): String {
        val slots = getAvailableTimeSlots(employeeId, shopId, dateStr, duration)
        val count = slots.size
        val firstSlot: String = if (count > 0) {
            val s: String = slots[0]
            s.replace(" ", "T")
        } else "none"
        val lastSlot: String = if (count > 0) {
            val s: String = slots[count - 1]
            s.replace(" ", "T")
        } else "none"
        val result = StringBuilder()
        result.append(count.toString())
        result.append(" slots from ")
        result.append(firstSlot)
        result.append(" to ")
        result.append(lastSlot)
        return result.toString()
    }

    // Find common available slots across multiple therapists
    fun findJointAvailability(employeeIds: List<Int>, shopId: Int, dateStr: String, duration: Int): String {
        if (employeeIds.isEmpty()) return "0 slots from none to none"
        
        // Get slots for first therapist as baseline
        val firstSlots = getAvailableTimeSlots(employeeIds[0], shopId, dateStr, duration).toMutableSet()
        
        // Intersect with other therapists
        for (i in 1 until employeeIds.size) {
            val otherSlots = getAvailableTimeSlots(employeeIds[i], shopId, dateStr, duration).toSet()
            firstSlots.retainAll(otherSlots)
            if (firstSlots.isEmpty()) break
        }
        
        val sortedSlots = firstSlots.sorted()
        val count = sortedSlots.size
        val first = if (count > 0) sortedSlots[0].replace(" ", "T") else "none"
        val last = if (count > 0) sortedSlots[count - 1].replace(" ", "T") else "none"
        return "$count slots from $first to $last"
    }

    // Complete JSON result for therapist availability (avoiding Kotlin/Java interop issues)
    fun getTherapistAvailabilityResult(therapistId: Int, dateStr: String, duration: Int, shopId: Int): String {
        val slots = getAvailableTimeSlots(therapistId, shopId, dateStr, duration)
        val count = slots.size
        val firstSlot = if (count > 0) {
            val s = slots[0]
            s.replace(" ", "T")
        } else "none"
        val lastSlot = if (count > 0) {
            val s = slots[count - 1]
            s.replace(" ", "T")
        } else "none"
        
        val sb = StringBuilder()
        sb.append("{\"therapistId\":\"")
        sb.append(therapistId.toString())
        sb.append("\",\"dateIso\":\"")
        sb.append(dateStr)
        sb.append("\",\"durationMinutes\":")
        sb.append(duration.toString())
        sb.append(",\"available\":\"")
        sb.append(count.toString())
        sb.append(" slots from ")
        sb.append(firstSlot)
        sb.append(" to ")
        sb.append(lastSlot)
        sb.append("\"}")
        return sb.toString()
    }

    // =========================================================================
    // Mobile app accounts  (app_account table)
    // =========================================================================

    /**
     * Create or overwrite the mobile app login for a manager.
     * Used from WebAdmin "App login" controls.
     */
    fun setManagerAppAccount(managerId: Int, username: String, password: String) {
        val hash = BCrypt.hashpw(password, BCrypt.gensalt())
        val sql = """
            INSERT INTO app_account (ref_type, ref_id, username, password_hash, active)
            VALUES ('manager', ?, ?, ?, 1)
            ON CONFLICT(ref_type, ref_id) DO UPDATE SET
                username = excluded.username,
                password_hash = excluded.password_hash,
                active = 1
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, managerId)
            stmt.setString(2, username.trim())
            stmt.setString(3, hash)
            stmt.executeUpdate()
        }
    }

    /**
     * Create or overwrite the mobile app login for a shop.
     */
    fun setShopAppAccount(shopId: Int, username: String, password: String) {
        val hash = BCrypt.hashpw(password, BCrypt.gensalt())
        val sql = """
            INSERT INTO app_account (ref_type, ref_id, username, password_hash, active)
            VALUES ('shop', ?, ?, ?, 1)
            ON CONFLICT(ref_type, ref_id) DO UPDATE SET
                username = excluded.username,
                password_hash = excluded.password_hash,
                active = 1
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            stmt.setString(2, username.trim())
            stmt.setString(3, hash)
            stmt.executeUpdate()
        }
    }

    /** Returns the stored username for a manager's app account, or null if not set. */
    fun getManagerAppAccountUsername(managerId: Int): String? {
        val sql = "SELECT username FROM app_account WHERE ref_type='manager' AND ref_id=? AND active=1 LIMIT 1"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, managerId)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.getString("username") else null
        }
    }

    /** Returns the stored username for a shop's app account, or null if not set. */
    fun getShopAppAccountUsername(shopId: Int): String? {
        val sql = "SELECT username FROM app_account WHERE ref_type='shop' AND ref_id=? AND active=1 LIMIT 1"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.getString("username") else null
        }
    }

    fun removeManagerAppAccount(managerId: Int) {
        connection.prepareStatement("UPDATE app_account SET active=0 WHERE ref_type='manager' AND ref_id=?").use { stmt ->
            stmt.setInt(1, managerId)
            stmt.executeUpdate()
        }
    }

    fun removeShopAppAccount(shopId: Int) {
        connection.prepareStatement("UPDATE app_account SET active=0 WHERE ref_type='shop' AND ref_id=?").use { stmt ->
            stmt.setInt(1, shopId)
            stmt.executeUpdate()
        }
    }

    /**
     * Authenticate against the dedicated app_account table.
     * Returns Pair(refType, refId) on success, null on failure.
     */
    fun authenticateAppAccount(username: String, password: String): Pair<String, Int>? {
        val sql = "SELECT ref_type, ref_id, password_hash FROM app_account WHERE username=? AND active=1 LIMIT 1"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, username.trim())
            val rs = stmt.executeQuery()
            if (rs.next()) {
                val hash = rs.getString("password_hash")
                if (BCrypt.checkpw(password, hash)) {
                    return Pair(rs.getString("ref_type"), rs.getInt("ref_id"))
                }
            }
        }
        return null
    }

    // Complete JSON result for joint availability
    fun getJointAvailabilityResult(therapistIds: List<Int>, shopId: Int, dateStr: String, duration: Int): String {
        val availability = findJointAvailability(therapistIds, shopId, dateStr, duration)
        
        val sb = StringBuilder()
        sb.append("{\"therapistIds\":[")
        for (i in therapistIds.indices) {
            if (i > 0) sb.append(",")
            sb.append("\"")
            sb.append(therapistIds[i].toString())
            sb.append("\"")
        }
        sb.append("],\"dateIso\":\"")
        sb.append(dateStr)
        sb.append("\",\"durationMinutes\":")
        sb.append(duration.toString())
        sb.append(",\"available\":\"")
        sb.append(availability)
        sb.append("\"}")
        return sb.toString()
    }







}
