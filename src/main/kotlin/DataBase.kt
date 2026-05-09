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
data class Customer(
    val id: Int,
    val phone: String,
    val name: String,
    val status: String,
    val payment: Int,
    val language: Int,
    /** Name resolved from the CallApp directory (null if not yet screened or not found). */
    val callappName: String? = null,
)

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
    /** null/"auto" = follow opening hours schedule; "open" or "closed" forces state */
    val phoneOverride: String? = null,
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
    STALE,
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
    /** If the call was linked to a customer, this may contain the customer's name (if set). */
    val customerName: String? = null,
    /** Name resolved from CallApp directory, if screening has run for this phone. */
    val callappName: String? = null,
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

// ─── SMS message history ─────────────────────────────────────────────────────

@Serializable
data class SmsMessage(
    val id: Int,
    val shopId: Int,
    val customerId: Int?,
    /** For outbound: customer phone.  For inbound: sender phone. */
    val counterpartyPhone: String,
    val fromPhone: String,
    val toPhone: String,
    val body: String,
    /** "outbound" | "inbound" */
    val direction: String,
    /** "sent" | "queued" | "failed" | "received" */
    val status: String,
    val twilioMessageSid: String?,
    val errorMessage: String?,
    val createdAt: Long,
)

@Serializable
data class SmsConversationSummary(
    val counterpartyPhone: String,
    val customerId: Int?,
    val customerName: String?,
    /** Name resolved from CallApp directory for this counterparty's phone. */
    val callappName: String? = null,
    val lastBody: String,
    val lastDirection: String,
    val lastAt: Long,
    val unreadCount: Int,
)

@Serializable
data class SmsUnhandledNotification(
    val shopId: Int,
    val shopName: String,
    val counterpartyPhone: String,
    val customerId: Int?,
    val customerName: String?,
    val callappName: String?,
    val lastBody: String,
    val lastAt: Long,
    val unreadCount: Int,
)

// ─── CallApp screening result cache ─────────────────────────────────────────

@Serializable
data class CustomerCallAppScreening(
    val customerId: Int,
    /** true if CallApp found the number with a non-blank name */
    val found: Boolean,
    val name: String?,
    val priority: Int?,
    /** raw API status field */
    val apiStatus: Boolean,
    val apiMessage: String?,
    val apiTimestamp: Long?,
    val rawJson: String?,
    /** error message when the HTTP/network call itself failed (null on success) */
    val error: String?,
    /** epoch-ms when this screening result was stored */
    val screenedAt: Long,
    /** number of consecutive API failures since the last success (0 on success) */
    val failureCount: Int = 0,
    /**
     * epoch-ms before which we must not retry a failed lookup.
     * null = either a successful row or a brand-new row.
     */
    val nextRetryAt: Long? = null,
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

@Serializable
data class EmployeeAvailability(
    val employeeId: Int,
    val shopId: Int,
    val available: Boolean,
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
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                appointment_id INTEGER NOT NULL,
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
                welcome_closed_message TEXT,
                temporary_operator_closed INTEGER NOT NULL DEFAULT 0,
                temporary_operator_closed_message TEXT,
                business_name TEXT,
                phone_override TEXT
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
            """,
            """
            CREATE TABLE IF NOT EXISTS sms_message (
                id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                shop_id             INTEGER NOT NULL,
                customer_id         INTEGER,
                counterparty_phone  TEXT    NOT NULL,
                from_phone          TEXT    NOT NULL,
                to_phone            TEXT    NOT NULL,
                body                TEXT    NOT NULL DEFAULT '',
                direction           TEXT    NOT NULL DEFAULT 'outbound',
                status              TEXT    NOT NULL DEFAULT 'queued',
                twilio_message_sid  TEXT,
                error_message       TEXT,
                created_at          INTEGER NOT NULL
            );
            """
        )

        for (sql in sqlStatements) {
            connection.createStatement().use { stmt -> stmt.execute(sql) }
        }

        // Migration: appointment_services had a broken schema where `appointment_id` was the PRIMARY KEY,
        // which prevented multiple services being stored per appointment.
        // We recreate the table with an `id` PK and keep `appointment_id` as a normal FK-ish column.
        try {
            val cols = mutableListOf<String>()
            connection.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA table_info(appointment_services)")
                while (rs.next()) cols += rs.getString("name")
            }

            val needsFix = cols.contains("appointment_id") && !cols.contains("id")
            if (needsFix) {
                println("Migrating appointment_services schema...")
                connection.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS appointment_services_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            appointment_id INTEGER NOT NULL,
                            service_id INTEGER NOT NULL
                        );
                        """.trimIndent()
                    )
                    // Copy existing rows (best-effort)
                    stmt.execute(
                        """
                        INSERT INTO appointment_services_new (appointment_id, service_id)
                        SELECT appointment_id, service_id FROM appointment_services;
                        """.trimIndent()
                    )
                    stmt.execute("DROP TABLE appointment_services;")
                    stmt.execute("ALTER TABLE appointment_services_new RENAME TO appointment_services;")
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_appointment_services_appointment_id ON appointment_services(appointment_id);")
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_appointment_services_service_id ON appointment_services(service_id);")
                }
                println("appointment_services migration complete.")
            }
        } catch (e: Exception) {
            println("appointment_services migration skipped/failed: ${e.message}")
        }

        // Migration: add handled_at to sms_message (safe idempotent ALTER TABLE ADD COLUMN)
        try {
            connection.createStatement().use { stmt ->
                stmt.executeUpdate("ALTER TABLE sms_message ADD COLUMN handled_at INTEGER NULL")
            }
            println("[DataBase] Migration: added handled_at column to sms_message")
        } catch (_: Exception) {
            // Column already exists — safe to ignore
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
            "ALTER TABLE shop_voice_config ADD COLUMN phone_override TEXT",
            "ALTER TABLE voice_call ADD COLUMN operator_phone TEXT",
            // Employee availability per shop (manager controlled): 1=available, 0=unavailable
            "ALTER TABLE employee_shop ADD COLUMN available INTEGER NOT NULL DEFAULT 1",
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

        // CallApp screening result cache — one row per customer, refreshed every 30 days
        connection.createStatement().use { it.execute("""
            CREATE TABLE IF NOT EXISTS customer_callapp_screening (
                customer_id   INTEGER PRIMARY KEY,
                found         INTEGER NOT NULL DEFAULT 0,
                name          TEXT,
                priority      INTEGER,
                api_status    INTEGER NOT NULL DEFAULT 0,
                api_message   TEXT,
                api_timestamp INTEGER,
                raw_json      TEXT,
                error         TEXT,
                screened_at   INTEGER NOT NULL,
                failure_count INTEGER NOT NULL DEFAULT 0,
                next_retry_at INTEGER
            )
        """.trimIndent()) }

        // Migration: add retry-tracking columns to existing screening rows
        listOf(
            "ALTER TABLE customer_callapp_screening ADD COLUMN failure_count INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE customer_callapp_screening ADD COLUMN next_retry_at INTEGER",
        ).forEach { sql ->
            try { connection.createStatement().use { it.execute(sql) } } catch (_: Exception) {}
        }

        println("All tables ready.")
    }

    // === Shop voice config ===

    fun getShopVoiceConfig(shopId: Int): ShopVoiceConfig {
        val sql = """
            SELECT shop_id, twilio_number, operator_phone, welcome_open_message, welcome_closed_message,
                   temporary_operator_closed, temporary_operator_closed_message, business_name, phone_override
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
                    phoneOverride = rs.getString("phone_override"),
                )
            } else {
                ShopVoiceConfig(shopId)
            }
        }
    }

    fun upsertShopVoiceConfig(config: ShopVoiceConfig) {
        val sql = """
            INSERT INTO shop_voice_config (shop_id, twilio_number, operator_phone, welcome_open_message,
                welcome_closed_message, temporary_operator_closed, temporary_operator_closed_message, business_name, phone_override)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(shop_id) DO UPDATE SET
                twilio_number = excluded.twilio_number,
                operator_phone = excluded.operator_phone,
                welcome_open_message = excluded.welcome_open_message,
                welcome_closed_message = excluded.welcome_closed_message,
                temporary_operator_closed = excluded.temporary_operator_closed,
                temporary_operator_closed_message = excluded.temporary_operator_closed_message,
                business_name = excluded.business_name,
                phone_override = excluded.phone_override
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
            stmt.setString(9, config.phoneOverride)
            stmt.executeUpdate()
        }
    }

    /** Upsert only the phone override without overwriting other voice config fields. */
    fun upsertShopPhoneOverride(shopId: Int, phoneOverride: String?) {
        val sql = """
            INSERT INTO shop_voice_config (shop_id, phone_override)
            VALUES (?, ?)
            ON CONFLICT(shop_id) DO UPDATE SET
                phone_override = excluded.phone_override
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            stmt.setString(2, phoneOverride)
            stmt.executeUpdate()
        }
    }

    /** Opening-hours check only (ignores overrides). */
    fun isShopOpenByScheduleNow(shopId: Int, zoneId: ZoneId = ZoneId.of("Europe/Copenhagen")): Boolean {
        val now = ZonedDateTime.now(zoneId)
        val dow = now.dayOfWeek.value // 1=Mon..7=Sun
        ensureDefaultShopOpeningHours(shopId)
        val row = getShopOpeningHours(shopId).firstOrNull { it.dayOfWeek == dow } ?: return true
        if (row.closed) return false
        val minutes = now.hour * 60 + now.minute
        return minutes in row.openMinute until row.closeMinute
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

    fun terminateCall(callId: Int, outcome: VoiceCallOutcome, note: String? = null) {
        val now = System.currentTimeMillis()
        connection.prepareStatement(
            "UPDATE voice_call SET is_active = 0, ended_at = ?, state = 'TERMINATED', outcome = ? WHERE id = ?"
        ).use { stmt ->
            stmt.setLong(1, now)
            stmt.setString(2, outcome.name)
            stmt.setInt(3, callId)
            stmt.executeUpdate()
        }
        appendCallEvent(callId, VoiceCallState.TERMINATED.name, note ?: outcome.name)
    }

    /**
     * Terminates active calls that may be stuck as `is_active=1`.
     *
     * Used as a robustness measure on backend startup, on new inbound call, and in scheduled cleanup.
     */
    fun terminateActiveCalls(
        shopId: Int? = null,
        olderThanMs: Long? = null,
        outcome: VoiceCallOutcome = VoiceCallOutcome.STALE,
        note: String? = null,
    ): Int {
        val now = System.currentTimeMillis()
        val cutoff = olderThanMs?.let { now - it }

        val sql = buildString {
            append("SELECT id FROM voice_call WHERE is_active = 1")
            if (shopId != null) append(" AND shop_id = ?")
            if (cutoff != null) append(" AND started_at < ?")
        }

        val ids = mutableListOf<Int>()
        connection.prepareStatement(sql).use { stmt ->
            var idx = 1
            if (shopId != null) stmt.setInt(idx++, shopId)
            if (cutoff != null) stmt.setLong(idx++, cutoff)
            val rs = stmt.executeQuery()
            while (rs.next()) ids += rs.getInt("id")
        }

        if (ids.isEmpty()) return 0
        ids.forEach { id -> terminateCall(id, outcome, note = note) }
        return ids.size
    }

    /**
     * Terminates any currently active calls for the same shop and caller phone.
     *
     * This protects against Twilio callback gaps where a previous call never got marked inactive
     * (e.g. caller hung up while in menu / gather).
     */
    fun terminateActiveCallsFromPhone(
        shopId: Int,
        fromPhone: String,
        outcome: VoiceCallOutcome = VoiceCallOutcome.STALE,
        note: String? = null,
    ): Int {
        val phone = fromPhone.trim()
        if (phone.isBlank()) return 0

        val sql = "SELECT id FROM voice_call WHERE is_active = 1 AND shop_id = ? AND from_phone = ?"
        val ids = mutableListOf<Int>()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            stmt.setString(2, phone)
            val rs = stmt.executeQuery()
            while (rs.next()) ids += rs.getInt("id")
        }
        if (ids.isEmpty()) return 0
        ids.forEach { id -> terminateCall(id, outcome, note = note ?: "same_caller_cleanup from=$phone") }
        return ids.size
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

    /** Shared SELECT fragment used by all voice call queries. */
    private val CALL_SELECT = """
        SELECT vc.id, vc.shop_id, vc.twilio_call_sid, vc.from_phone, vc.to_phone,
               vc.customer_type, vc.customer_id,
               c.name  AS customer_name,
               cs.name AS callapp_name,
               vc.state, vc.outcome, vc.is_active, vc.started_at, vc.ended_at,
               vc.linked_booking_id, vc.operator_phone
        FROM voice_call vc
        LEFT JOIN customers c     ON c.id  = vc.customer_id
        -- Join screening via explicit customer_id first; fall back to a phone match for unknown callers
        LEFT JOIN customers cph   ON cph.phone = vc.from_phone
        LEFT JOIN customer_callapp_screening cs
                                  ON cs.customer_id = COALESCE(vc.customer_id, cph.id) AND cs.found = 1
    """.trimIndent()

    fun getActiveCallsForShop(shopId: Int): List<VoiceCallRecord> {
        val sql = "$CALL_SELECT WHERE vc.shop_id = ? AND vc.is_active = 1 ORDER BY vc.started_at DESC"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            return buildCallRecordList(stmt.executeQuery())
        }
    }

    fun getRecentCallsForShop(shopId: Int, limit: Int = 50): List<VoiceCallRecord> {
        val sql = "$CALL_SELECT WHERE vc.shop_id = ? ORDER BY vc.started_at DESC LIMIT ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            stmt.setInt(2, limit)
            return buildCallRecordList(stmt.executeQuery())
        }
    }

    fun getCallById(callId: Int): VoiceCallRecord? {
        val sql = "$CALL_SELECT WHERE vc.id = ?"
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, callId)
            return buildCallRecordList(stmt.executeQuery()).firstOrNull()
        }
    }

    fun getCallByTwilioSid(twilioCallSid: String): VoiceCallRecord? {
        val sql = "$CALL_SELECT WHERE vc.twilio_call_sid = ?"
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
            val customerName: String? = try {
                rs.getString("customer_name")?.trim()?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }

            val callappName: String? = try {
                rs.getString("callapp_name")?.trim()?.takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }

            result += VoiceCallRecord(
                id = rs.getInt("id"),
                shopId = rs.getInt("shop_id"),
                twilioCallSid = rs.getString("twilio_call_sid"),
                fromPhone = rs.getString("from_phone"),
                toPhone = rs.getString("to_phone"),
                customerType = rs.getString("customer_type"),
                customerId = rs.getInt("customer_id").takeIf { !rs.wasNull() },
                customerName = customerName,
                callappName = callappName,
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
        val sql = """
            SELECT c.id, c.phone, c.name, c.status, c.payment, c.language,
                   s.name AS callapp_name
            FROM customers c
            LEFT JOIN customer_callapp_screening s ON s.customer_id = c.id AND s.found = 1
            WHERE c.id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, id)
            val rs = stmt.executeQuery()
            if (!rs.next()) return null
            return Customer(
                id       = rs.getInt("id"),
                phone    = rs.getString("phone"),
                name     = rs.getString("name"),
                status   = rs.getString("status"),
                payment  = rs.getInt("payment"),
                language = rs.getInt("language"),
                callappName = rs.getString("callapp_name")?.trim()?.takeIf { it.isNotBlank() },
            )
        }
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

    fun updateCustomerName(customerId: Int, name: String) {
        connection.prepareStatement("UPDATE customers SET name = ? WHERE id = ?").use { stmt ->
            stmt.setString(1, name.trim())
            stmt.setInt(2, customerId)
            stmt.executeUpdate()
        }
    }

    /**
     * Update both name and phone for a customer. Used from the web admin customer edit page.
     */
    fun updateCustomer(customerId: Int, name: String, phone: String) {
        connection.prepareStatement("UPDATE customers SET name = ?, phone = ? WHERE id = ?").use { stmt ->
            stmt.setString(1, name.trim())
            stmt.setString(2, phone.trim())
            stmt.setInt(3, customerId)
            stmt.executeUpdate()
        }
    }

    /**
     * List all customers, optionally filtered by a name/phone search string.
     * Results are ordered newest first (by id desc).
     */
    fun getAllCustomers(search: String? = null): List<Customer> {
        val sql = if (search.isNullOrBlank()) {
            """
            SELECT c.id, c.phone, c.name, c.status, c.payment, c.language, s.name AS callapp_name
            FROM customers c
            LEFT JOIN customer_callapp_screening s ON s.customer_id = c.id AND s.found = 1
            ORDER BY c.id DESC
            """.trimIndent()
        } else {
            """
            SELECT c.id, c.phone, c.name, c.status, c.payment, c.language, s.name AS callapp_name
            FROM customers c
            LEFT JOIN customer_callapp_screening s ON s.customer_id = c.id AND s.found = 1
            WHERE c.phone LIKE ? OR c.name LIKE ?
            ORDER BY c.id DESC
            """.trimIndent()
        }
        connection.prepareStatement(sql).use { stmt ->
            if (!search.isNullOrBlank()) {
                val pattern = "%${search.trim()}%"
                stmt.setString(1, pattern)
                stmt.setString(2, pattern)
            }
            val rs = stmt.executeQuery()
            val customers = mutableListOf<Customer>()
            while (rs.next()) {
                customers += Customer(
                    id          = rs.getInt("id"),
                    phone       = rs.getString("phone") ?: "",
                    name        = rs.getString("name") ?: "",
                    status      = rs.getString("status") ?: "",
                    payment     = rs.getInt("payment"),
                    language    = rs.getInt("language"),
                    callappName = rs.getString("callapp_name")?.trim()?.takeIf { it.isNotBlank() },
                )
            }
            rs.close()
            return customers
        }
    }

    /**
     * Update name, phone and status for a customer. Used from the web admin customer edit page.
     */
    fun updateCustomerFull(id: Int, name: String, phone: String, status: String) {
        connection.prepareStatement("UPDATE customers SET name = ?, phone = ?, status = ? WHERE id = ?").use { stmt ->
            stmt.setString(1, name.trim())
            stmt.setString(2, phone.trim())
            stmt.setString(3, status.trim())
            stmt.setInt(4, id)
            stmt.executeUpdate()
        }
    }

    /**
     * Hard-delete a customer row. Also removes linked blacklist entries.
     * Does NOT delete appointments (those remain for historical records).
     */
    fun deleteCustomer(id: Int): Boolean {
        // Soft-remove any blacklist entries for this customer's phone (best-effort)
        getCustomerById(id)?.phone?.trim()?.takeIf { it.isNotBlank() }?.let { phone ->
            connection.prepareStatement("UPDATE phone_blacklist SET active = 0 WHERE phone_e164 = ?").use { stmt ->
                stmt.setString(1, phone)
                stmt.executeUpdate()
            }
        }
        connection.prepareStatement("DELETE FROM customers WHERE id = ?").use { stmt ->
            stmt.setInt(1, id)
            return stmt.executeUpdate() > 0
        }
    }

    /**
     * Returns a list of (shopId, shopName) pairs for every shop that has this phone blacklisted.
     * Empty list means the phone is not blacklisted anywhere.
     */
    fun getBlacklistShopsForPhone(phone: String): List<Pair<Int, String>> {
        if (phone.isBlank()) return emptyList()
        val sql = """
            SELECT pb.shop_id, COALESCE(s.name, 'Shop #' || pb.shop_id) AS shop_name
            FROM phone_blacklist pb
            LEFT JOIN shops s ON s.id = pb.shop_id
            WHERE pb.phone_e164 = ? AND pb.active = 1
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, phone.trim())
            val rs = stmt.executeQuery()
            val result = mutableListOf<Pair<Int, String>>()
            while (rs.next()) result += rs.getInt("shop_id") to rs.getString("shop_name")
            return result
        }
    }

    /**
     * Count the number of appointments for a given customer (across all shops).
     */
    fun getAppointmentCountForCustomer(customerId: Int): Int {
        connection.prepareStatement("SELECT COUNT(*) FROM appointments WHERE customer_id = ?").use { stmt ->
            stmt.setInt(1, customerId)
            val rs = stmt.executeQuery()
            val count = if (rs.next()) rs.getInt(1) else 0
            rs.close()
            return count
        }
    }

    fun getAppointmentsForCustomer(customerId: Int, shopId: Int? = null): List<AppointmentWithServices> {
        val sql = if (shopId != null) {
            "SELECT * FROM appointments WHERE customer_id = ? AND shop_id = ? ORDER BY date_time DESC"
        } else {
            "SELECT * FROM appointments WHERE customer_id = ? ORDER BY date_time DESC"
        }

        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, customerId)
            if (shopId != null) stmt.setInt(2, shopId)
            val rs = stmt.executeQuery()
            val appointments = mutableListOf<AppointmentWithServices>()
            while (rs.next()) {
                val appointmentId = rs.getInt("id")
                val services = getServicesForAppointment(appointmentId)
                appointments += AppointmentWithServices(
                    id = appointmentId,
                    employeeId = rs.getInt("employee_id"),
                    shopId = rs.getInt("shop_id"),
                    dateTime = rs.getLong("date_time"),
                    duration = rs.getInt("duration"),
                    price = rs.getDouble("price"),
                    services = services,
                    customer = getCustomerById(rs.getInt("customer_id")),
                    employee = getEmployeeById(rs.getInt("employee_id")),
                )
            }
            rs.close()
            return appointments
        }
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

    private fun <T> inTransaction(block: () -> T): T {
        val prevAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val res = block()
            connection.commit()
            return res
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = prevAutoCommit
        }
    }

    /**
     * Creates multiple appointments at the same start time. Each therapist (employee) may have
     * different services (and thus duration). Inserts are done in a DB transaction.
     */
    fun createAppointmentsSameTime(
        shopId: Int,
        customerId: Int,
        dateTimeMillis: Long,
        blocks: List<Pair<Int, List<Int>>>,
        overrideDurationMinutes: Int? = null,
    ): List<Int> {
        require(blocks.isNotEmpty()) { "No therapist blocks" }

        // Enforce availability upfront (fail fast)
        val unavailable = blocks.firstOrNull { (employeeId, _) -> !isEmployeeAvailable(shopId = shopId, employeeId = employeeId) }
        if (unavailable != null) {
            throw IllegalStateException("Employee unavailable for employeeId=${unavailable.first}")
        }

        return inTransaction {
            val appointmentIds = mutableListOf<Int>()

            // Therapists work in parallel starting at the same time.
            // The effective calendar block duration should therefore be the MAX duration across all therapists.
            // Otherwise a shorter therapist could be double-booked midway through a longer multi booking.
            val perTherapistDurations: List<Int> = blocks.map { (employeeId, serviceIds) ->
                require(employeeId > 0) { "Invalid employeeId" }
                require(serviceIds.isNotEmpty()) { "Missing services for employeeId=$employeeId" }
                val d = serviceIds.mapNotNull { getServiceById(it)?.duration }.sum()
                if (d <= 0) throw IllegalArgumentException("Invalid service durations for employeeId=$employeeId")
                d
            }
            val computedMaxDuration = perTherapistDurations.maxOrNull() ?: 0
            val maxDuration = maxOf(
                computedMaxDuration,
                overrideDurationMinutes?.takeIf { it > 0 } ?: 0,
            )
            if (maxDuration <= 0) {
                throw IllegalArgumentException("Invalid max duration")
            }

            for ((employeeId, serviceIds) in blocks) {
                // Enforce availability inside transaction too
                if (!isEmployeeAvailable(shopId = shopId, employeeId = employeeId)) {
                    throw IllegalStateException("Employee unavailable for employeeId=$employeeId")
                }
                // Check for overlapping appointments before booking (use maxDuration for the shared block)
                if (isAppointmentOverlapping(employeeId, dateTimeMillis, maxDuration)) {
                    throw IllegalStateException("Overlapping appointment for employeeId=$employeeId")
                }

                // Store appointment duration as the shared block duration (maxDuration)
                val appointmentId = addAppointment(employeeId, shopId, customerId, dateTimeMillis, maxDuration)
                if (appointmentId == -1) {
                    throw IllegalStateException("Failed to create appointment for employeeId=$employeeId")
                }

                addServicesToAppointment(appointmentId, serviceIds)
                appointmentIds += appointmentId
            }

            appointmentIds
        }
    }

    /**
     * Overrides the computed appointment price (e.g. for special offers).
     *
     * NOTE: This writes directly to `appointments.price`.
     */
    fun updateAppointmentPrice(appointmentId: Int, price: Double) {
        val updateStmt = connection.prepareStatement("UPDATE appointments SET price = ? WHERE id = ?")
        updateStmt.setDouble(1, price)
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

    /**
     * Returns only employees that are assigned to the shop AND currently marked available.
     *
     * Used for booking UIs so managers/customers don't see therapists that are off.
     */
    fun getAvailableEmployeesForShop(shopId: Int): List<Employee> {
        return try {
            val employees = mutableListOf<Employee>()
            val stmt = connection.prepareStatement(
                """
                SELECT e.id, e.name, e.phone
                FROM employees e
                JOIN employee_shop es ON e.id = es.employee_id
                WHERE es.shop_id = ? AND es.available = 1
                ORDER BY e.id
                """.trimIndent()
            )
            stmt.setInt(1, shopId)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                employees.add(
                    Employee(
                        id = rs.getInt("id"),
                        name = rs.getString("name"),
                        phone = rs.getString("phone"),
                    )
                )
            }
            rs.close()
            stmt.close()
            employees
        } catch (_: Exception) {
            // Older DB without column => treat all assigned employees as available
            getEmployeesForShop(shopId)
        }
    }

    // =========================================================================
    // Employee availability per shop (manager controlled)
    // =========================================================================

    /** Returns true if employee is available for the shop. Default = true if row/column missing. */
    fun isEmployeeAvailable(shopId: Int, employeeId: Int): Boolean {
        val sql = "SELECT available FROM employee_shop WHERE shop_id = ? AND employee_id = ? LIMIT 1"
        return try {
            connection.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, shopId)
                stmt.setInt(2, employeeId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    rs.getInt("available") != 0
                } else {
                    // Not assigned => treat as unavailable
                    false
                }
            }
        } catch (e: Exception) {
            // If column doesn't exist in older DBs, default to available to avoid breaking.
            true
        }
    }

    /** Sets availability for an employee in a shop. Returns false if no employee_shop row exists. */
    fun setEmployeeAvailable(shopId: Int, employeeId: Int, available: Boolean): Boolean {
        val sql = "UPDATE employee_shop SET available = ? WHERE shop_id = ? AND employee_id = ?"
        return connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, if (available) 1 else 0)
            stmt.setInt(2, shopId)
            stmt.setInt(3, employeeId)
            stmt.executeUpdate() > 0
        }
    }

    fun listEmployeeAvailabilityForShop(shopId: Int): List<EmployeeAvailability> {
        val sql = "SELECT employee_id, shop_id, available FROM employee_shop WHERE shop_id = ? ORDER BY employee_id"
        return try {
            connection.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, shopId)
                val rs = stmt.executeQuery()
                buildList {
                    while (rs.next()) {
                        add(
                            EmployeeAvailability(
                                employeeId = rs.getInt("employee_id"),
                                shopId = rs.getInt("shop_id"),
                                available = rs.getInt("available") != 0,
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Column missing, return all assigned employees as available
            getEmployeesForShop(shopId).map { emp ->
                EmployeeAvailability(employeeId = emp.id, shopId = shopId, available = true)
            }
        }
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

    fun deleteAppointment(id: Int): Boolean {
        connection.prepareStatement("DELETE FROM appointment_services WHERE appointment_id = ?").use { stmt ->
            stmt.setInt(1, id)
            stmt.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM appointments WHERE id = ?").use { stmt ->
            stmt.setInt(1, id)
            return stmt.executeUpdate() > 0
        }
    }

    fun getAvailableTimeSlots(employeeId: Int, shopId: Int, dateStr: String, duration: Int): Array<String> {
        // Manager-controlled availability toggle: if employee is marked unavailable for the shop, no slots.
        if (!isEmployeeAvailable(shopId = shopId, employeeId = employeeId)) {
            return emptyArray()
        }

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

    /**
     * Computes the intersection of available start-times across multiple therapists, where
     * each therapist can have a different duration.
     */
    fun getAvailableTimeSlotsMulti(shopId: Int, dateStr: String, blocks: List<Pair<Int, Int>>): Array<String> {
        if (blocks.isEmpty()) return emptyArray()

        val (firstEmployeeId, firstDuration) = blocks.first()
        var common = getAvailableTimeSlots(firstEmployeeId, shopId, dateStr, firstDuration).toMutableSet()

        for (i in 1 until blocks.size) {
            val (employeeId, duration) = blocks[i]
            val other = getAvailableTimeSlots(employeeId, shopId, dateStr, duration).toSet()
            common.retainAll(other)
            if (common.isEmpty()) break
        }

        return common.sorted().toTypedArray()
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

    // =========================================================================
    // SMS message history
    // =========================================================================

    /**
     * Inserts an SMS record (outbound or inbound) and returns the new row id.
     * [customerId] stays null until a booking is confirmed.
     */
    fun insertSmsMessage(
        shopId: Int,
        customerId: Int?,
        counterpartyPhone: String,
        fromPhone: String,
        toPhone: String,
        body: String,
        direction: String,
        status: String,
        twilioMessageSid: String? = null,
        errorMessage: String? = null,
    ): Int {
        val sql = """
            INSERT INTO sms_message
                (shop_id, customer_id, counterparty_phone, from_phone, to_phone,
                 body, direction, status, twilio_message_sid, error_message, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            if (customerId != null) stmt.setInt(2, customerId) else stmt.setNull(2, java.sql.Types.INTEGER)
            stmt.setString(3, counterpartyPhone.trim())
            stmt.setString(4, fromPhone.trim())
            stmt.setString(5, toPhone.trim())
            stmt.setString(6, body)
            stmt.setString(7, direction)
            stmt.setString(8, status)
            stmt.setString(9, twilioMessageSid)
            stmt.setString(10, errorMessage)
            stmt.setLong(11, System.currentTimeMillis())
            stmt.executeUpdate()
        }
        connection.createStatement().use { s ->
            val rs = s.executeQuery("SELECT last_insert_rowid()")
            return if (rs.next()) rs.getInt(1) else -1
        }
    }

    /** Full thread for a shop + counterparty phone, oldest first. */
    fun getSmsThread(shopId: Int, counterpartyPhone: String, limit: Int = 200): List<SmsMessage> {
        val sql = """
            SELECT id, shop_id, customer_id, counterparty_phone, from_phone, to_phone,
                   body, direction, status, twilio_message_sid, error_message, created_at
            FROM sms_message
            WHERE shop_id = ? AND counterparty_phone = ?
            ORDER BY created_at ASC
            LIMIT ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            stmt.setString(2, counterpartyPhone.trim())
            stmt.setInt(3, limit)
            return buildSmsMessageList(stmt.executeQuery())
        }
    }

    /** One summary row per counterparty phone, ordered by most-recent message first. */
    fun getSmsConversations(shopId: Int, limit: Int = 50): List<SmsConversationSummary> {
        val sql = """
            SELECT m.counterparty_phone,
                   m.customer_id,
                   c.name  AS customer_name,
                   cs.name AS callapp_name,
                   m.body AS last_body,
                   m.direction AS last_direction,
                   m.created_at AS last_at,
                   COALESCE((
                       SELECT COUNT(*) FROM sms_message u
                       WHERE u.shop_id = m.shop_id
                         AND u.counterparty_phone = m.counterparty_phone
                         AND u.direction = 'inbound'
                         AND u.handled_at IS NULL
                   ), 0) AS unread_count
            FROM sms_message m
            LEFT JOIN customers c     ON c.id    = m.customer_id
            -- Resolve CallApp name: prefer explicit customer_id; fall back to phone match
            LEFT JOIN customers cph   ON cph.phone = m.counterparty_phone
            LEFT JOIN customer_callapp_screening cs
                                      ON cs.customer_id = COALESCE(m.customer_id, cph.id) AND cs.found = 1
            WHERE m.shop_id = ?
              AND m.id = (
                  SELECT id FROM sms_message
                  WHERE shop_id = m.shop_id AND counterparty_phone = m.counterparty_phone
                  ORDER BY created_at DESC LIMIT 1
              )
            ORDER BY m.created_at DESC
            LIMIT ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, shopId)
            stmt.setInt(2, limit)
            val rs = stmt.executeQuery()
            val result = mutableListOf<SmsConversationSummary>()
            while (rs.next()) {
                result += SmsConversationSummary(
                    counterpartyPhone = rs.getString("counterparty_phone"),
                    customerId  = rs.getInt("customer_id").takeIf { !rs.wasNull() },
                    customerName = rs.getString("customer_name")?.trim()?.takeIf { it.isNotBlank() },
                    callappName = rs.getString("callapp_name")?.trim()?.takeIf { it.isNotBlank() },
                    lastBody    = rs.getString("last_body") ?: "",
                    lastDirection = rs.getString("last_direction") ?: "outbound",
                    lastAt      = rs.getLong("last_at"),
                    unreadCount = rs.getInt("unread_count"),
                )
            }
            return result
        }
    }

    /** Mark all unhandled inbound messages in a conversation as handled (read). */
    fun markSmsConversationHandled(shopId: Int, counterpartyPhone: String) {
        val sql = """
            UPDATE sms_message SET handled_at = ?
            WHERE shop_id = ? AND counterparty_phone = ?
              AND direction = 'inbound' AND handled_at IS NULL
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, System.currentTimeMillis())
            stmt.setInt(2, shopId)
            stmt.setString(3, counterpartyPhone.trim())
            stmt.executeUpdate()
        }
    }

    /** Total count of unhandled inbound SMS messages across a set of shops. */
    fun getUnhandledSmsCount(shopIds: List<Int>): Int {
        if (shopIds.isEmpty()) return 0
        val placeholders = shopIds.joinToString(",") { "?" }
        val sql = """
            SELECT COUNT(*) FROM sms_message
            WHERE shop_id IN ($placeholders)
              AND direction = 'inbound'
              AND handled_at IS NULL
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            shopIds.forEachIndexed { i, id -> stmt.setInt(i + 1, id) }
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.getInt(1) else 0
        }
    }

    /** One summary row per conversation across the given shops — ALL conversations, unread count may be 0. */
    fun getAllSmsConversationsAcrossShops(shopIds: List<Int>): List<SmsUnhandledNotification> {
        if (shopIds.isEmpty()) return emptyList()
        val placeholders = shopIds.joinToString(",") { "?" }
        val sql = """
            SELECT m.shop_id,
                   s.name   AS shop_name,
                   m.counterparty_phone,
                   m.customer_id,
                   c.name   AS customer_name,
                   cs.name  AS callapp_name,
                   m.body   AS last_body,
                   m.created_at AS last_at,
                   (SELECT COUNT(*) FROM sms_message u
                    WHERE u.shop_id = m.shop_id
                      AND u.counterparty_phone = m.counterparty_phone
                      AND u.direction = 'inbound'
                      AND u.handled_at IS NULL) AS unread_count
            FROM sms_message m
            JOIN shops s ON s.id = m.shop_id
            LEFT JOIN customers c   ON c.id = m.customer_id
            LEFT JOIN customers cph ON cph.phone = m.counterparty_phone
            LEFT JOIN customer_callapp_screening cs
                                    ON cs.customer_id = COALESCE(m.customer_id, cph.id) AND cs.found = 1
            WHERE m.shop_id IN ($placeholders)
              AND m.id = (
                  SELECT id FROM sms_message
                  WHERE shop_id = m.shop_id AND counterparty_phone = m.counterparty_phone
                  ORDER BY created_at DESC LIMIT 1
              )
            ORDER BY unread_count DESC, m.created_at DESC
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            shopIds.forEachIndexed { i, id -> stmt.setInt(i + 1, id) }
            val rs = stmt.executeQuery()
            val result = mutableListOf<SmsUnhandledNotification>()
            while (rs.next()) {
                result += SmsUnhandledNotification(
                    shopId      = rs.getInt("shop_id"),
                    shopName    = rs.getString("shop_name") ?: "",
                    counterpartyPhone = rs.getString("counterparty_phone"),
                    customerId  = rs.getInt("customer_id").takeIf { !rs.wasNull() },
                    customerName = rs.getString("customer_name")?.trim()?.takeIf { it.isNotBlank() },
                    callappName  = rs.getString("callapp_name")?.trim()?.takeIf { it.isNotBlank() },
                    lastBody    = rs.getString("last_body") ?: "",
                    lastAt      = rs.getLong("last_at"),
                    unreadCount = rs.getInt("unread_count"),
                )
            }
            return result
        }
    }

    /** One summary row per unhandled conversation, across the given shops. */
    fun getUnhandledSmsNotifications(shopIds: List<Int>): List<SmsUnhandledNotification> {
        if (shopIds.isEmpty()) return emptyList()
        val placeholders = shopIds.joinToString(",") { "?" }
        val sql = """
            SELECT m.shop_id,
                   s.name   AS shop_name,
                   m.counterparty_phone,
                   m.customer_id,
                   c.name   AS customer_name,
                   cs.name  AS callapp_name,
                   m.body   AS last_body,
                   m.created_at AS last_at,
                   (SELECT COUNT(*) FROM sms_message u
                    WHERE u.shop_id = m.shop_id
                      AND u.counterparty_phone = m.counterparty_phone
                      AND u.direction = 'inbound'
                      AND u.handled_at IS NULL) AS unread_count
            FROM sms_message m
            JOIN shops s ON s.id = m.shop_id
            LEFT JOIN customers c   ON c.id = m.customer_id
            LEFT JOIN customers cph ON cph.phone = m.counterparty_phone
            LEFT JOIN customer_callapp_screening cs
                                    ON cs.customer_id = COALESCE(m.customer_id, cph.id) AND cs.found = 1
            WHERE m.shop_id IN ($placeholders)
              AND m.id = (
                  SELECT id FROM sms_message
                  WHERE shop_id = m.shop_id AND counterparty_phone = m.counterparty_phone
                  ORDER BY created_at DESC LIMIT 1
              )
              AND EXISTS (
                  SELECT 1 FROM sms_message u
                  WHERE u.shop_id = m.shop_id
                    AND u.counterparty_phone = m.counterparty_phone
                    AND u.direction = 'inbound'
                    AND u.handled_at IS NULL
              )
            ORDER BY m.created_at DESC
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            shopIds.forEachIndexed { i, id -> stmt.setInt(i + 1, id) }
            val rs = stmt.executeQuery()
            val result = mutableListOf<SmsUnhandledNotification>()
            while (rs.next()) {
                result += SmsUnhandledNotification(
                    shopId      = rs.getInt("shop_id"),
                    shopName    = rs.getString("shop_name") ?: "",
                    counterpartyPhone = rs.getString("counterparty_phone"),
                    customerId  = rs.getInt("customer_id").takeIf { !rs.wasNull() },
                    customerName = rs.getString("customer_name")?.trim()?.takeIf { it.isNotBlank() },
                    callappName  = rs.getString("callapp_name")?.trim()?.takeIf { it.isNotBlank() },
                    lastBody    = rs.getString("last_body") ?: "",
                    lastAt      = rs.getLong("last_at"),
                    unreadCount = rs.getInt("unread_count"),
                )
            }
            return result
        }
    }

    /** Retroactively link existing sms_message rows to a customer once created. */
    fun linkSmsMessagesToCustomer(shopId: Int, phone: String, customerId: Int) {
        val sql = """
            UPDATE sms_message SET customer_id = ?
            WHERE shop_id = ? AND counterparty_phone = ? AND customer_id IS NULL
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, customerId)
            stmt.setInt(2, shopId)
            stmt.setString(3, phone.trim())
            stmt.executeUpdate()
        }
    }

    // =========================================================================
    // CallApp screening cache
    // =========================================================================

    /** Read the cached screening result for a customer, or null if not yet screened. */
    fun getCustomerCallAppScreening(customerId: Int): CustomerCallAppScreening? {
        val sql = """
            SELECT customer_id, found, name, priority, api_status, api_message,
                   api_timestamp, raw_json, error, screened_at,
                   COALESCE(failure_count, 0) AS failure_count,
                   next_retry_at
            FROM customer_callapp_screening WHERE customer_id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, customerId)
            val rs = stmt.executeQuery()
            if (!rs.next()) return null
            return CustomerCallAppScreening(
                customerId   = rs.getInt("customer_id"),
                found        = rs.getInt("found") != 0,
                name         = rs.getString("name"),
                priority     = rs.getInt("priority").takeIf { !rs.wasNull() },
                apiStatus    = rs.getInt("api_status") != 0,
                apiMessage   = rs.getString("api_message"),
                apiTimestamp = rs.getLong("api_timestamp").takeIf { !rs.wasNull() },
                rawJson      = rs.getString("raw_json"),
                error        = rs.getString("error"),
                screenedAt   = rs.getLong("screened_at"),
                failureCount = rs.getInt("failure_count"),
                nextRetryAt  = rs.getLong("next_retry_at").takeIf { !rs.wasNull() },
            )
        }
    }

    /**
     * Store a successful CallApp lookup result (insert or overwrite).
     * Resets failure_count and next_retry_at so the row is treated as healthy.
     */
    fun upsertCustomerCallAppScreening(
        customerId: Int,
        result: callapp.CallAppLookupResult,
        screenedAt: Long = System.currentTimeMillis(),
    ) {
        val sql = """
            INSERT INTO customer_callapp_screening
                (customer_id, found, name, priority, api_status, api_message, api_timestamp,
                 raw_json, error, screened_at, failure_count, next_retry_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, 0, NULL)
            ON CONFLICT(customer_id) DO UPDATE SET
                found         = excluded.found,
                name          = excluded.name,
                priority      = excluded.priority,
                api_status    = excluded.api_status,
                api_message   = excluded.api_message,
                api_timestamp = excluded.api_timestamp,
                raw_json      = excluded.raw_json,
                error         = NULL,
                screened_at   = excluded.screened_at,
                failure_count = 0,
                next_retry_at = NULL
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, customerId)
            stmt.setInt(2, if (result.found) 1 else 0)
            stmt.setString(3, result.name)
            if (result.priority != null) stmt.setInt(4, result.priority) else stmt.setNull(4, java.sql.Types.INTEGER)
            stmt.setInt(5, if (result.status) 1 else 0)
            stmt.setString(6, result.message)
            if (result.timestamp != null) stmt.setLong(7, result.timestamp) else stmt.setNull(7, java.sql.Types.INTEGER)
            stmt.setString(8, result.rawJson)
            stmt.setLong(9, screenedAt)
            stmt.executeUpdate()
        }
    }

    /**
     * Store a screening error (network timeout, HTTP error, etc.).
     *
     * Key robustness properties:
     * - Preserves any existing [name] from a previous successful lookup (does NOT erase it).
     * - Increments failure_count.
     * - Sets next_retry_at using exponential backoff: 1h → 2h → 4h → 8h → ... capped at 24h.
     */
    fun upsertCustomerCallAppScreeningError(
        customerId: Int,
        error: String,
        screenedAt: Long = System.currentTimeMillis(),
    ) {
        // Determine current failure count to compute backoff; default to 0 if no row yet.
        val currentFailures = getCustomerCallAppScreening(customerId)?.failureCount ?: 0
        val newFailures = currentFailures + 1
        // Backoff: 2^newFailures hours, capped at 24h
        val backoffMs = minOf(
            (1L shl newFailures) * 60L * 60L * 1000L,  // 2^n hours in ms
            24L * 60L * 60L * 1000L,                    // 24h cap
        )
        val nextRetryAt = screenedAt + backoffMs

        val sql = """
            INSERT INTO customer_callapp_screening
                (customer_id, found, name, priority, api_status, api_message, api_timestamp,
                 raw_json, error, screened_at, failure_count, next_retry_at)
            VALUES (?, 0, NULL, NULL, 0, NULL, NULL, NULL, ?, ?, ?, ?)
            ON CONFLICT(customer_id) DO UPDATE SET
                -- Intentionally NOT overwriting 'found', 'name', 'priority' — keep last good data.
                api_status    = 0,
                api_message   = NULL,
                api_timestamp = NULL,
                raw_json      = NULL,
                error         = excluded.error,
                screened_at   = excluded.screened_at,
                failure_count = excluded.failure_count,
                next_retry_at = excluded.next_retry_at
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, customerId)
            stmt.setString(2, error.take(500))
            stmt.setLong(3, screenedAt)
            stmt.setInt(4, newFailures)
            stmt.setLong(5, nextRetryAt)
            stmt.executeUpdate()
        }
    }

    /**
     * Returns customers that need a CallApp lookup:
     * - Never screened (no row).
     * - Successful row older than [maxAgeMs] (default 30 days).
     * - Failed row whose [next_retry_at] has passed (exponential backoff).
     *
     * Ordered ASC so oldest customers are processed first.
     */
    fun getCustomersNeedingCallAppScreening(
        maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,
        limit: Int = 50,
    ): List<Customer> {
        val now    = System.currentTimeMillis()
        val cutoff = now - maxAgeMs
        val sql = """
            SELECT c.id, c.phone, c.name, c.status, c.payment, c.language
            FROM customers c
            LEFT JOIN customer_callapp_screening s ON s.customer_id = c.id
            WHERE c.phone IS NOT NULL AND c.phone != ''
              AND (
                s.customer_id IS NULL                            -- never screened
                OR (s.error IS NULL     AND s.screened_at < ?)  -- stale success (>30 days)
                OR (s.error IS NOT NULL AND (s.next_retry_at IS NULL OR s.next_retry_at <= ?))  -- backoff elapsed
              )
            ORDER BY c.id ASC
            LIMIT ?
        """.trimIndent()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, cutoff)
            stmt.setLong(2, now)
            stmt.setInt(3, limit)
            val rs = stmt.executeQuery()
            val result = mutableListOf<Customer>()
            while (rs.next()) {
                result += Customer(
                    id       = rs.getInt("id"),
                    phone    = rs.getString("phone") ?: "",
                    name     = rs.getString("name") ?: "",
                    status   = rs.getString("status") ?: "",
                    payment  = rs.getInt("payment"),
                    language = rs.getInt("language"),
                )
            }
            return result
        }
    }

    private fun buildSmsMessageList(rs: java.sql.ResultSet): List<SmsMessage> {
        val result = mutableListOf<SmsMessage>()
        while (rs.next()) {
            result += SmsMessage(
                id = rs.getInt("id"),
                shopId = rs.getInt("shop_id"),
                customerId = rs.getInt("customer_id").takeIf { !rs.wasNull() },
                counterpartyPhone = rs.getString("counterparty_phone"),
                fromPhone = rs.getString("from_phone"),
                toPhone = rs.getString("to_phone"),
                body = rs.getString("body") ?: "",
                direction = rs.getString("direction"),
                status = rs.getString("status"),
                twilioMessageSid = rs.getString("twilio_message_sid"),
                errorMessage = rs.getString("error_message"),
                createdAt = rs.getLong("created_at"),
            )
        }
        return result
    }






}
