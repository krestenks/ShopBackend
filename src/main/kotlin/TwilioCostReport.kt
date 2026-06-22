import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.net.URL
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ─── Data models ─────────────────────────────────────────────────────────────

data class TwilioDayRow(
    val date: LocalDate,          // yyyy-MM-dd
    val callsCount: Int,
    val callsCost: Double,        // USD
    val smsCount: Int,
    val smsCost: Double,          // USD
) {
    val total: Double get() = callsCost + smsCost
}

data class TwilioCostData(
    val rows: List<TwilioDayRow>,
    val totalCallsCount: Int,
    val totalCallsCost: Double,
    val totalSmsCount: Int,
    val totalSmsCost: Double,
    val grandTotal: Double,
    val priceUnit: String,        // e.g. "usd"
    val accountSid: String,
)

// ─── Live exchange rate ───────────────────────────────────────────────────────

/**
 * Fetches the current USD → DKK exchange rate from open.er-api.com (free, no key needed).
 * Returns the rate, or 7.0 as a safe fallback if the fetch fails.
 */
fun fetchUsdToDkk(): Double {
    return try {
        val url  = "https://open.er-api.com/v6/latest/USD"
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8_000
        conn.readTimeout    = 8_000
        if (conn.responseCode != 200) return 7.0
        val body = conn.inputStream.bufferedReader().readText()
        val root = Json.parseToJsonElement(body).jsonObject
        root["rates"]?.jsonObject?.get("DKK")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 7.0
    } catch (_: Exception) { 7.0 }
}

// ─── Twilio Usage Records API fetch ──────────────────────────────────────────

/**
 * Fetch daily usage records from Twilio for a given month.
 * Calls the Usage Records Daily sub-resource twice (calls + sms) and joins by date.
 *
 * @param accountSid   Twilio Account SID
 * @param authToken    Twilio Auth Token
 * @param yearMonth    The month to query
 */
fun fetchTwilioCosts(accountSid: String, authToken: String, yearMonth: YearMonth): TwilioCostData {
    val startDate = yearMonth.atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
    val endDate   = yearMonth.atEndOfMonth().format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun fetchCategory(category: String): Map<LocalDate, Pair<Int, Double>> {
        val urlStr = "https://api.twilio.com/2010-04-01/Accounts/$accountSid/Usage/Records/Daily.json" +
                "?Category=$category&StartDate=$startDate&EndDate=$endDate&PageSize=100"
        val credentials = Base64.getEncoder().encodeToString("$accountSid:$authToken".toByteArray())
        val connection = URL(urlStr).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Basic $credentials")
        connection.connectTimeout = 10_000
        connection.readTimeout    = 10_000

        val responseCode = connection.responseCode
        if (responseCode != 200) {
            val err = connection.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
            throw RuntimeException("Twilio API error ($category): $err")
        }

        val body    = connection.inputStream.bufferedReader().readText()
        val root    = Json.parseToJsonElement(body).jsonObject
        val records = root["usage_records"]?.jsonArray ?: return emptyMap()

        val result = mutableMapOf<LocalDate, Pair<Int, Double>>()
        for (rec in records) {
            val obj     = rec.jsonObject
            val dateStr = obj["start_date"]?.jsonPrimitive?.content ?: continue
            val date    = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
            val count   = obj["count"]?.jsonPrimitive?.content?.toIntOrNull()    ?: 0
            val price   = obj["price"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            result[date] = count to price
        }
        return result
    }

    val callsData = try { fetchCategory("calls") } catch (_: Exception) { emptyMap() }
    val smsData   = try { fetchCategory("sms")   } catch (_: Exception) { emptyMap() }

    // Build one row per day in the month (including days with no usage)
    val rows = mutableListOf<TwilioDayRow>()
    var d = yearMonth.atDay(1)
    val lastDay = yearMonth.atEndOfMonth()
    while (!d.isAfter(lastDay)) {
        val (callsCount, callsCost) = callsData[d] ?: (0 to 0.0)
        val (smsCount,   smsCost  ) = smsData[d]   ?: (0 to 0.0)
        rows += TwilioDayRow(d, callsCount, callsCost, smsCount, smsCost)
        d = d.plusDays(1)
    }

    return TwilioCostData(
        rows            = rows,
        totalCallsCount = rows.sumOf { it.callsCount },
        totalCallsCost  = rows.sumOf { it.callsCost },
        totalSmsCount   = rows.sumOf { it.smsCount },
        totalSmsCost    = rows.sumOf { it.smsCost },
        grandTotal      = rows.sumOf { it.total },
        priceUnit       = "USD",
        accountSid      = accountSid,
    )
}

// ─── Per-number cost fetch (Calls + Messages list APIs) ──────────────────────

/**
 * Fetches daily costs for a single Twilio phone number by querying the Calls and
 * Messages list resources with a phone-number filter.  This is the only way to get
 * truly per-shop numbers because the Usage Records Daily API returns account-wide totals.
 *
 * Paginates automatically using Twilio's next_page_uri.
 * Dates are converted to Copenhagen local time so day-boundaries match business hours.
 */
fun fetchTwilioCostsByNumber(
    accountSid: String,
    authToken: String,
    yearMonth: YearMonth,
    twilioNumber: String,
): TwilioCostData {
    val credentials  = Base64.getEncoder().encodeToString("$accountSid:$authToken".toByteArray())
    val startDateStr = yearMonth.atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
    val endDateStr   = yearMonth.atEndOfMonth().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val copenhagenTz = java.time.ZoneId.of("Europe/Copenhagen")

    // Twilio timestamps come in RFC-2822 format, e.g. "Thu, 29 Jun 2023 12:00:00 +0000"
    val rfc2822 = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)

    fun parseTwilioDate(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        return try {
            ZonedDateTime.parse(s.trim(), rfc2822).withZoneSameInstant(copenhagenTz).toLocalDate()
        } catch (_: Exception) { null }
    }

    data class DayAccum(var count: Int = 0, var cost: Double = 0.0)

    /**
     * Fetches all pages from a Calls or Messages list endpoint filtered by a phone number.
     * Keys in the returned map are Copenhagen-local dates.
     */
    fun fetchPages(
        resource: String,     // "Calls" or "Messages"
        dateGe: String,       // e.g. "StartTime>="
        dateLe: String,       // e.g. "StartTime<="
        filterKey: String,    // "To" or "From"
        filterVal: String,
        arrayKey: String,     // "calls" or "messages"
        dateJsonKey: String,  // "start_time" or "date_sent"
    ): Map<LocalDate, DayAccum> {
        val accum = mutableMapOf<LocalDate, DayAccum>()

        // Build initial URL with URL-encoded param names so ">=" is transmitted correctly
        fun buildInitialUrl(): String {
            val params = listOf(
                filterKey to filterVal,
                dateGe    to startDateStr,
                dateLe    to endDateStr,
                "PageSize" to "1000",
            )
            val qs = params.joinToString("&") { (k, v) ->
                "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }
            return "https://api.twilio.com/2010-04-01/Accounts/$accountSid/$resource.json?$qs"
        }

        var url: String? = buildInitialUrl()
        while (url != null) {
            val conn = URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Basic $credentials")
            conn.connectTimeout = 15_000
            conn.readTimeout    = 15_000

            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                throw RuntimeException("Twilio $resource API error (${filterKey}=$filterVal): $err")
            }

            val root  = Json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
            val items = root[arrayKey]?.jsonArray ?: break

            for (item in items) {
                val obj   = item.jsonObject
                val date  = parseTwilioDate(obj[dateJsonKey]?.jsonPrimitive?.content) ?: continue
                // Twilio prices are negative (cost to you); take abs value
                val price = obj["price"]?.jsonPrimitive?.content
                    ?.toDoubleOrNull()?.let { kotlin.math.abs(it) } ?: 0.0
                val day   = accum.getOrPut(date) { DayAccum() }
                day.count++
                day.cost += price
            }

            // Follow pagination
            url = root["next_page_uri"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { "https://api.twilio.com$it" }
        }
        return accum
    }

    // Fetch both directions (inbound + outbound) for calls and SMS
    val callsTo   = try { fetchPages("Calls",    "StartTime>=", "StartTime<=", "To",   twilioNumber, "calls",    "start_time") } catch (_: Exception) { emptyMap() }
    val callsFrom = try { fetchPages("Calls",    "StartTime>=", "StartTime<=", "From", twilioNumber, "calls",    "start_time") } catch (_: Exception) { emptyMap() }
    val smsTo     = try { fetchPages("Messages", "DateSent>=",  "DateSent<=",  "To",   twilioNumber, "messages", "date_sent")  } catch (_: Exception) { emptyMap() }
    val smsFrom   = try { fetchPages("Messages", "DateSent>=",  "DateSent<=",  "From", twilioNumber, "messages", "date_sent")  } catch (_: Exception) { emptyMap() }

    val rows = mutableListOf<TwilioDayRow>()
    var d = yearMonth.atDay(1)
    val lastDay = yearMonth.atEndOfMonth()
    while (!d.isAfter(lastDay)) {
        val callCount = (callsTo[d]?.count ?: 0) + (callsFrom[d]?.count ?: 0)
        val callCost  = (callsTo[d]?.cost  ?: 0.0) + (callsFrom[d]?.cost ?: 0.0)
        val smsCount  = (smsTo[d]?.count   ?: 0) + (smsFrom[d]?.count   ?: 0)
        val smsCost   = (smsTo[d]?.cost    ?: 0.0) + (smsFrom[d]?.cost  ?: 0.0)
        rows += TwilioDayRow(d, callCount, callCost, smsCount, smsCost)
        d = d.plusDays(1)
    }

    return TwilioCostData(
        rows            = rows,
        totalCallsCount = rows.sumOf { it.callsCount },
        totalCallsCost  = rows.sumOf { it.callsCost },
        totalSmsCount   = rows.sumOf { it.smsCount },
        totalSmsCost    = rows.sumOf { it.smsCost },
        grandTotal      = rows.sumOf { it.total },
        priceUnit       = "USD",
        accountSid      = accountSid,
    )
}

// ─── Formatting helpers ───────────────────────────────────────────────────────

private val DK_TC = java.time.ZoneId.of("Europe/Copenhagen")
private val EN_TC = Locale.ENGLISH

private fun fmtCost(v: Double): String = if (v == 0.0) "–" else "$%.4f".format(v)
private fun fmtCount(v: Int): String   = if (v == 0) "–" else v.toString()

private fun dayLabel(d: LocalDate): String {
    val dow = d.dayOfWeek.getDisplayName(TextStyle.SHORT, EN_TC)
    return "$dow ${d.format(DateTimeFormatter.ofPattern("dd/MM"))}"
}

private fun monthTitle(ym: YearMonth): String =
    ym.month.getDisplayName(TextStyle.FULL, EN_TC) + " " + ym.year

// ─── JWT validation (reuse same logic as FinancialReports) ───────────────────

private fun validateTwilioReportToken(tokenParam: String?): Pair<Int, String>? {
    if (tokenParam.isNullOrBlank()) return null
    val raw = tokenParam.removePrefix("Bearer ").trim()
    if (raw.isBlank()) return null
    return try {
        val jwt = JWT.require(Algorithm.HMAC256("very-secret"))
            .withAudience("mobile")
            .withIssuer("shop-manager")
            .acceptExpiresAt(365L * 24 * 60 * 60)
            .build()
            .verify(raw)
        val userId = jwt.getClaim("userId").asInt() ?: return null
        val role   = jwt.getClaim("role").asString()  ?: return null
        userId to role
    } catch (_: Exception) { null }
}

// ─── Currency helpers ─────────────────────────────────────────────────────────

/** All display info for a chosen currency. */
data class CurrencyDisplay(
    val symbol: String,    // e.g. "$" or "kr"
    val label: String,     // e.g. "USD" or "DKK"
    val rate: Double,      // multiply USD amounts by this
)

private fun fmtMoney(usd: Double, cur: CurrencyDisplay): String {
    val v = usd * cur.rate
    return if (v == 0.0) "–"
    else if (cur.label == "DKK") "%.2f kr".format(v)
    else "$%.4f".format(v)
}

// ─── Shared HTML rendering helper ────────────────────────────────────────────

private fun HtmlBlockTag.renderCostTable(data: TwilioCostData, cur: CurrencyDisplay) {
    div {
        style = "overflow-x: auto;"
        table(classes = "cost-table") {
            thead {
                tr {
                    th { +"Date" }
                    th { +"Calls" }
                    th { +"Calls (${cur.label})" }
                    th { +"SMS" }
                    th { +"SMS (${cur.label})" }
                    th { +"Total (${cur.label})" }
                }
            }
            tbody {
                for (row in data.rows) {
                    if (row.callsCount == 0 && row.smsCount == 0) continue
                    tr {
                        td { +dayLabel(row.date) }
                        td { +fmtCount(row.callsCount) }
                        td { +fmtMoney(row.callsCost, cur) }
                        td { +fmtCount(row.smsCount) }
                        td { +fmtMoney(row.smsCost, cur) }
                        td { +fmtMoney(row.total, cur) }
                    }
                }
            }
            tfoot {
                tr(classes = "totals-row") {
                    td { +"TOTAL" }
                    td { +data.totalCallsCount.toString() }
                    td { +fmtMoney(data.totalCallsCost, cur) }
                    td { +data.totalSmsCount.toString() }
                    td { +fmtMoney(data.totalSmsCost, cur) }
                    td { +fmtMoney(data.grandTotal, cur) }
                }
            }
        }
    }
}

// ─── Web-admin route ─────────────────────────────────────────────────────────

fun Route.twilioCostReportRoutes(db: DataBase) {

    get("/reports/twilio-costs") {
        val now           = java.time.YearMonth.now(DK_TC)
        val yearParam     = call.request.queryParameters["year"]?.toIntOrNull()     ?: now.year
        val monParam      = call.request.queryParameters["month"]?.toIntOrNull()    ?: now.monthValue
        val currencyParam = call.request.queryParameters["currency"]?.lowercase()  ?: "usd"
        val shopIdParam   = call.request.queryParameters["shop_id"]                 // null/"all" = all shops
        val ym            = YearMonth.of(yearParam, monParam)
        val prevYm        = ym.minusMonths(1)
        val nextYm        = ym.plusMonths(1)

        // Load all shops for the filter dropdown
        val allShops  = db.getAllShops()
        val shopIdInt = shopIdParam?.toIntOrNull()
        val selectedShop = allShops.find { it.id == shopIdInt }

        // Resolve Twilio credentials based on selected shop (or global fallback)
        val (accountSid, authToken) = if (selectedShop != null) {
            val ownerId = db.getOwnerIdForShop(selectedShop.id)
            val acct    = ownerId?.let { db.getOwnerAccountByOwnerId(it) }
            val sid     = acct?.twilioAccountSid?.takeIf { it.isNotBlank() }
                ?: System.getenv("TWILIO_ACCOUNT_SID")?.takeIf { it.isNotBlank() } ?: ""
            val tok     = acct?.twilioAuthToken?.takeIf { it.isNotBlank() }
                ?: System.getenv("TWILIO_AUTH_TOKEN")?.takeIf { it.isNotBlank() } ?: ""
            sid to tok
        } else {
            val sid = System.getenv("TWILIO_ACCOUNT_SID")?.takeIf { it.isNotBlank() }
                ?: db.getAllOwners().firstNotNullOfOrNull { db.getOwnerAccountByOwnerId(it.id)?.twilioAccountSid } ?: ""
            val tok = System.getenv("TWILIO_AUTH_TOKEN")?.takeIf { it.isNotBlank() }
                ?: db.getAllOwners().firstNotNullOfOrNull { db.getOwnerAccountByOwnerId(it.id)?.twilioAuthToken } ?: ""
            sid to tok
        }

        // Fetch cost data: per-number API for a specific shop, account-wide for All Shops
        val shopTwilioNumber: String? = selectedShop?.let {
            db.getShopVoiceConfig(it.id).twilioNumber?.takeIf { n -> n.isNotBlank() }
        }
        val (costData, fetchError) = when {
            accountSid.isBlank() || authToken.isBlank() ->
                null to "No Twilio credentials configured (set TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN env vars)"
            selectedShop != null && shopTwilioNumber == null ->
                null to "Shop '${selectedShop.name}' has no Twilio number configured — add one in Twilio Setup"
            selectedShop != null ->
                try { fetchTwilioCostsByNumber(accountSid, authToken, ym, shopTwilioNumber!!) to null }
                catch (e: Exception) { null to e.message }
            else ->
                try { fetchTwilioCosts(accountSid, authToken, ym) to null }
                catch (e: Exception) { null to e.message }
        }

        // Currency display
        val cur = if (currencyParam == "dkk") {
            val rate = fetchUsdToDkk()
            CurrencyDisplay("kr", "DKK", rate)
        } else {
            CurrencyDisplay("$", "USD", 1.0)
        }

        val shopQs   = if (shopIdInt != null) "&shop_id=$shopIdInt" else ""
        fun pageUrl(y: Int, m: Int) =
            "/reports/twilio-costs?year=$y&month=$m&currency=$currencyParam$shopQs"
        fun curUrl(c: String) =
            "/reports/twilio-costs?year=$yearParam&month=$monParam&currency=$c$shopQs"

        val nav = listOf(
            "/" to ("Dashboard" to "🏠"), "/shops" to ("Shops" to "🏪"),
            "/employees" to ("Employees" to "👥"), "/services" to ("Services" to "🧾"),
            "/managers" to ("Managers" to "🧑‍💼"), "/appointments" to ("Appointments" to "📅"),
            "/customers" to ("Customers" to "👤"), "/reports" to ("Reports" to "💰"),
            "/reports/twilio-costs" to ("Twilio Costs" to "📡"),
            "/twilio/setup" to ("Twilio setup" to "📞"),
        )

        call.respondHtml {
            head {
                meta { charset = "utf-8" }
                meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
                title { +"Twilio Cost Report" }
                link(rel = "stylesheet", href = "/static/admin.css", type = "text/css")
                style {
                    unsafe {
                        +"""
                        .cost-table { width: 100%; border-collapse: collapse; font-size: 0.9em; }
                        .cost-table th { background: #1e2a5e; color: #fff; padding: 8px 12px; text-align: right; }
                        .cost-table th:first-child { text-align: left; }
                        .cost-table td { padding: 7px 12px; text-align: right; border-bottom: 1px solid rgba(255,255,255,0.06); }
                        .cost-table td:first-child { text-align: left; font-weight: 500; }
                        .cost-table tr:nth-child(even) td { background: rgba(255,255,255,0.025); }
                        .cost-table tr.totals-row td { font-weight: 700; background: rgba(124,92,255,0.12); border-top: 2px solid rgba(124,92,255,0.4); }
                        .period-nav { display:flex; align-items:center; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
                        .period-nav a.btn { min-width: 36px; text-align: center; }
                        .period-title { font-size: 1.1em; font-weight: 600; flex: 1; }
                        .filter-bar { display:flex; gap:10px; align-items:center; margin-bottom: 16px; flex-wrap: wrap; }
                        .filter-bar label { font-size: 0.8em; color: rgba(255,255,255,0.55); }
                        .filter-bar select { background:#1c1c2e; color:#e8e8f0; border:1px solid rgba(255,255,255,0.15);
                            border-radius:6px; padding:6px 10px; font-size:0.9em; }
                        .cur-toggle { display:flex; gap:6px; margin-bottom: 16px; }
                        .cur-toggle a { padding: 6px 14px; border-radius: 6px; text-decoration:none; font-size: 0.85em; border: 1px solid rgba(255,255,255,0.2); color:#ccc; }
                        .cur-toggle a.active { background: #4f46e5; color: #fff; border-color: #4f46e5; }
                        .summary-cards { display:flex; gap: 16px; margin-bottom: 20px; flex-wrap: wrap; }
                        .summary-card { background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.1);
                            border-radius: 8px; padding: 14px 20px; min-width: 160px; }
                        .summary-card .card-label { font-size: 0.75em; color: rgba(255,255,255,0.55); text-transform: uppercase; }
                        .summary-card .card-value { font-size: 1.4em; font-weight: 700; color: #a78bfa; margin-top: 4px; }
                        .error-box { background: rgba(220,50,50,0.15); border: 1px solid rgba(220,50,50,0.4);
                            border-radius: 8px; padding: 14px 18px; color: #f87171; margin-bottom: 16px; }
                        """.trimIndent()
                    }
                }
            }
            body {
                div("layout") {
                    div("sidebar") {
                        div("brand") {
                            div { div("brand-title") { +"ShopManager" }; div("brand-sub") { +"Admin" } }
                        }
                        div("nav") {
                            for ((href, labelIcon) in nav) {
                                val (label, icon) = labelIcon
                                a(href = href, classes = if (href == "/reports/twilio-costs") "active" else null) {
                                    span { +icon }; span { +label }
                                }
                            }
                            div("spacer") {}
                            a(href = "/logout") { span { +"🚪" }; span { +"Logout" } }
                        }
                    }

                    div("main") {
                        div("page-header") {
                            div {
                                h1("page-title") { +"📡 Twilio Cost Report" }
                                p("page-subtitle") { +"Daily call and SMS costs from your Twilio account" }
                            }
                        }

                        // Shop filter
                        div("filter-bar") {
                            label { +"Shop:" }
                            select {
                                attributes["onchange"] = "location.href='/reports/twilio-costs?year=$yearParam&month=$monParam&currency=$currencyParam&shop_id='+this.value"
                                option {
                                    value = "all"
                                    if (shopIdInt == null) selected = true
                                    +"All Shops"
                                }
                                for (s in allShops) {
                                    option {
                                        value = s.id.toString()
                                        if (s.id == shopIdInt) selected = true
                                        +s.name
                                    }
                                }
                            }
                        }

                        // Currency toggle
                        div("cur-toggle") {
                            a(href = curUrl("usd"), classes = if (currencyParam == "usd") "active" else null) { +"USD $" }
                            a(href = curUrl("dkk"), classes = if (currencyParam == "dkk") "active" else null) { +"DKK kr" }
                        }

                        // Month navigation
                        div("period-nav") {
                            a(href = pageUrl(prevYm.year, prevYm.monthValue), classes = "btn") { +"‹ Prev" }
                            span("period-title") { +monthTitle(ym) }
                            a(href = pageUrl(nextYm.year, nextYm.monthValue), classes = "btn") { +"Next ›" }
                        }

                        if (fetchError != null) {
                            div("error-box") { +"⚠ Could not fetch Twilio data: $fetchError" }
                        } else if (costData != null) {
                            // Summary cards
                            div("summary-cards") {
                                div("summary-card") {
                                    div("card-label") { +"Grand Total (${cur.label})" }
                                    div("card-value") { +fmtMoney(costData.grandTotal, cur) }
                                }
                                div("summary-card") {
                                    div("card-label") { +"Calls" }
                                    div("card-value") { +"${costData.totalCallsCount}" }
                                }
                                div("summary-card") {
                                    div("card-label") { +"Calls (${cur.label})" }
                                    div("card-value") { +fmtMoney(costData.totalCallsCost, cur) }
                                }
                                div("summary-card") {
                                    div("card-label") { +"SMS" }
                                    div("card-value") { +"${costData.totalSmsCount}" }
                                }
                                div("summary-card") {
                                    div("card-label") { +"SMS (${cur.label})" }
                                    div("card-value") { +fmtMoney(costData.totalSmsCost, cur) }
                                }
                            }

                            div("panel") {
                                val activeRows = costData.rows.count { it.callsCount > 0 || it.smsCount > 0 }
                                if (activeRows == 0) {
                                    p("hint") { +"No Twilio usage recorded for this month." }
                                } else {
                                    renderCostTable(costData, cur)
                                }
                            }

                            p("hint") {
                                style = "margin-top: 10px; font-size: 0.8em; color: rgba(255,255,255,0.35);"
                                val shopNote = selectedShop?.let { " · Shop: ${it.name}" } ?: " · All Shops"
                                val rateNote = if (cur.label == "DKK") " · Rate: 1 USD = ${"%.4f".format(cur.rate)} DKK (live)" else ""
                                +"Account SID: ${costData.accountSid.take(12)}… · Source: Twilio Usage Records API$shopNote$rateNote"
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Mobile (JWT token-in-query-param) route ─────────────────────────────────

fun Route.mobileTwilioCostReportRoutes(db: DataBase) {

    get("/api/mobile/manager/twilio-costs") {
        val tokenParam = call.request.queryParameters["token"]
        val principal  = validateTwilioReportToken(tokenParam)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid or missing token"); return@get
        }
        val (userId, role) = principal
        val managerShops = if (role == "manager") db.getShopsForManager(userId) else emptyList()

        val now           = java.time.YearMonth.now(DK_TC)
        val yearParam     = call.request.queryParameters["year"]?.toIntOrNull()     ?: now.year
        val monParam      = call.request.queryParameters["month"]?.toIntOrNull()    ?: now.monthValue
        val currencyParam = call.request.queryParameters["currency"]?.lowercase()  ?: "usd"
        val shopIdParam   = call.request.queryParameters["shop_id"]                 // null/"all" = all shops
        val ym            = YearMonth.of(yearParam, monParam)
        val prevYm        = ym.minusMonths(1)
        val nextYm        = ym.plusMonths(1)

        // Determine selected shop (restricted to manager's shops)
        val shopIdInt    = shopIdParam?.toIntOrNull()
        val selectedShop = managerShops.find { it.id == shopIdInt }

        // Resolve credentials based on selected shop or global fallback
        val (accountSid, authToken) = if (selectedShop != null) {
            val ownerId = db.getOwnerIdForShop(selectedShop.id)
            val acct    = ownerId?.let { db.getOwnerAccountByOwnerId(it) }
            val sid     = acct?.twilioAccountSid?.takeIf { it.isNotBlank() }
                ?: System.getenv("TWILIO_ACCOUNT_SID")?.takeIf { it.isNotBlank() } ?: ""
            val tok     = acct?.twilioAuthToken?.takeIf { it.isNotBlank() }
                ?: System.getenv("TWILIO_AUTH_TOKEN")?.takeIf { it.isNotBlank() } ?: ""
            sid to tok
        } else {
            val sid = System.getenv("TWILIO_ACCOUNT_SID")?.takeIf { it.isNotBlank() }
                ?: managerShops.firstNotNullOfOrNull { shop ->
                    db.getOwnerIdForShop(shop.id)?.let { db.getOwnerAccountByOwnerId(it)?.twilioAccountSid }
                }
                ?: db.getAllOwners().firstNotNullOfOrNull { db.getOwnerAccountByOwnerId(it.id)?.twilioAccountSid } ?: ""
            val tok = System.getenv("TWILIO_AUTH_TOKEN")?.takeIf { it.isNotBlank() }
                ?: managerShops.firstNotNullOfOrNull { shop ->
                    db.getOwnerIdForShop(shop.id)?.let { db.getOwnerAccountByOwnerId(it)?.twilioAuthToken }
                }
                ?: db.getAllOwners().firstNotNullOfOrNull { db.getOwnerAccountByOwnerId(it.id)?.twilioAuthToken } ?: ""
            sid to tok
        }

        // Fetch cost data: per-number API for a specific shop, account-wide for All Shops
        val shopTwilioNumber: String? = selectedShop?.let {
            db.getShopVoiceConfig(it.id).twilioNumber?.takeIf { n -> n.isNotBlank() }
        }
        val (costData, fetchError) = when {
            accountSid.isBlank() || authToken.isBlank() ->
                null to "No Twilio credentials configured"
            selectedShop != null && shopTwilioNumber == null ->
                null to "Shop '${selectedShop.name}' has no Twilio number configured"
            selectedShop != null ->
                try { fetchTwilioCostsByNumber(accountSid, authToken, ym, shopTwilioNumber!!) to null }
                catch (e: Exception) { null to e.message }
            else ->
                try { fetchTwilioCosts(accountSid, authToken, ym) to null }
                catch (e: Exception) { null to e.message }
        }

        // Currency display
        val cur = if (currencyParam == "dkk") {
            val rate = fetchUsdToDkk()
            CurrencyDisplay("kr", "DKK", rate)
        } else {
            CurrencyDisplay("$", "USD", 1.0)
        }

        val shopQs = if (shopIdInt != null) "&shop_id=$shopIdInt" else ""
        fun navUrl(y: Int, m: Int) =
            "/api/mobile/manager/twilio-costs?token=$tokenParam&year=$y&month=$m&currency=$currencyParam$shopQs"
        fun toggleUrl(c: String) =
            "/api/mobile/manager/twilio-costs?token=$tokenParam&year=$yearParam&month=$monParam&currency=$c$shopQs"
        fun shopUrl(sid: String) =
            "/api/mobile/manager/twilio-costs?token=$tokenParam&year=$yearParam&month=$monParam&currency=$currencyParam&shop_id=$sid"

        call.respondHtml {
            head {
                meta { charset = "utf-8" }
                meta { name = "viewport"; content = "width=device-width, initial-scale=1, maximum-scale=1" }
                title { +"📡 Twilio Costs" }
                style {
                    unsafe {
                        +"""
                        * { box-sizing: border-box; margin: 0; padding: 0; }
                        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                               background: #13131f; color: #e8e8f0; font-size: 15px; }
                        .toolbar { background: #1e2a5e; padding: 14px 16px; font-size: 18px; font-weight: 700; }
                        .container { padding: 14px 12px; }
                        .filter-select { width: 100%; background: #1c1c2e; color: #e8e8f0;
                            border: 1px solid #4f46e5; border-radius: 8px; padding: 10px 12px;
                            font-size: 15px; margin-bottom: 12px; }
                        .cur-toggle { display:flex; gap:6px; margin-bottom: 12px; }
                        .cur-toggle a { flex:1; text-align:center; padding: 8px 0; border-radius: 8px;
                            text-decoration:none; font-size: 0.9em; border: 1px solid #4f46e5; color: #a78bfa; }
                        .cur-toggle a.active { background: #4f46e5; color: #fff; }
                        .period-nav { display: flex; align-items: center; gap: 8px; margin: 0 0 14px; }
                        .period-nav a { flex: 0 0 auto; }
                        .period-title { flex: 1; text-align: center; font-weight: 600; font-size: 1em; }
                        .btn { display: inline-block; background: #4f46e5; color: #fff;
                            border: none; border-radius: 8px; padding: 10px 18px;
                            font-size: 14px; text-decoration: none; cursor: pointer; }
                        .btn-outline { background: transparent; border: 1px solid #4f46e5; color: #a78bfa; }
                        .summary-cards { display: flex; gap: 10px; overflow-x: auto; margin-bottom: 14px; padding-bottom: 4px; }
                        .summary-card { flex: 0 0 auto; background: #1c1c2e; border: 1px solid #333;
                            border-radius: 10px; padding: 12px 16px; min-width: 120px; }
                        .card-label { font-size: 0.7em; color: #888; text-transform: uppercase; margin-bottom: 4px; }
                        .card-value { font-size: 1.1em; font-weight: 700; color: #a78bfa; }
                        table { width: 100%; border-collapse: collapse; font-size: 12px; }
                        th { background: #1e2a5e; color: #fff; padding: 8px 8px; text-align: right; white-space: nowrap; }
                        th:first-child { text-align: left; }
                        td { padding: 7px 8px; text-align: right; border-bottom: 1px solid #222; white-space: nowrap; }
                        td:first-child { text-align: left; font-weight: 500; }
                        tr:nth-child(even) td { background: rgba(255,255,255,0.03); }
                        tr.totals-row td { font-weight: 700; background: rgba(124,92,255,0.12);
                            border-top: 2px solid rgba(124,92,255,0.4); }
                        .wrap { overflow-x: auto; }
                        .hint { color: #888; font-size: 0.75em; padding: 10px 0; }
                        .error-box { background: rgba(220,50,50,0.15); border: 1px solid rgba(220,50,50,0.35);
                            border-radius: 8px; padding: 12px 14px; color: #f87171; margin-bottom: 12px; font-size: 0.85em; }
                        """.trimIndent()
                    }
                }
            }
            body {
                div("toolbar") { +"📡 Twilio Costs" }
                div("container") {
                    // Shop filter (only show if manager has more than one shop)
                    if (managerShops.size > 1) {
                        select(classes = "filter-select") {
                            attributes["onchange"] = "location.href=this.value"
                            option {
                                value = shopUrl("all")
                                if (shopIdInt == null) selected = true
                                +"All Shops"
                            }
                            for (s in managerShops) {
                                option {
                                    value = shopUrl(s.id.toString())
                                    if (s.id == shopIdInt) selected = true
                                    +s.name
                                }
                            }
                        }
                    }

                    // Currency toggle
                    div("cur-toggle") {
                        a(href = toggleUrl("usd"), classes = if (currencyParam == "usd") "active" else null) { +"USD $" }
                        a(href = toggleUrl("dkk"), classes = if (currencyParam == "dkk") "active" else null) { +"DKK kr" }
                    }

                    // Month navigation
                    div("period-nav") {
                        a(href = navUrl(prevYm.year, prevYm.monthValue), classes = "btn btn-outline") { +"‹" }
                        span("period-title") { +monthTitle(ym) }
                        a(href = navUrl(nextYm.year, nextYm.monthValue), classes = "btn btn-outline") { +"›" }
                    }

                    if (fetchError != null) {
                        div("error-box") { +"⚠ $fetchError" }
                    } else if (costData != null) {
                        // Summary cards
                        div("summary-cards") {
                            div("summary-card") {
                                div("card-label") { +"Total ${cur.label}" }
                                div("card-value") { +fmtMoney(costData.grandTotal, cur) }
                            }
                            div("summary-card") {
                                div("card-label") { +"Calls" }
                                div("card-value") { +"${costData.totalCallsCount}" }
                            }
                            div("summary-card") {
                                div("card-label") { +"Calls ${cur.label}" }
                                div("card-value") { +fmtMoney(costData.totalCallsCost, cur) }
                            }
                            div("summary-card") {
                                div("card-label") { +"SMS" }
                                div("card-value") { +"${costData.totalSmsCount}" }
                            }
                            div("summary-card") {
                                div("card-label") { +"SMS ${cur.label}" }
                                div("card-value") { +fmtMoney(costData.totalSmsCost, cur) }
                            }
                        }

                        val activeRows = costData.rows.count { it.callsCount > 0 || it.smsCount > 0 }
                        if (activeRows == 0) {
                            p("hint") { +"No Twilio usage recorded for this month." }
                        } else {
                            div("wrap") {
                                table {
                                    thead {
                                        tr {
                                            th { +"Date" }
                                            th { +"Calls" }
                                            th { +"Calls ${cur.label}" }
                                            th { +"SMS" }
                                            th { +"SMS ${cur.label}" }
                                            th { +"Total ${cur.label}" }
                                        }
                                    }
                                    tbody {
                                        for (row in costData.rows) {
                                            if (row.callsCount == 0 && row.smsCount == 0) continue
                                            tr {
                                                td { +dayLabel(row.date) }
                                                td { +fmtCount(row.callsCount) }
                                                td { +fmtMoney(row.callsCost, cur) }
                                                td { +fmtCount(row.smsCount) }
                                                td { +fmtMoney(row.smsCost, cur) }
                                                td { +fmtMoney(row.total, cur) }
                                            }
                                        }
                                    }
                                    tfoot {
                                        tr(classes = "totals-row") {
                                            td { +"TOTAL" }
                                            td { +costData.totalCallsCount.toString() }
                                            td { +fmtMoney(costData.totalCallsCost, cur) }
                                            td { +costData.totalSmsCount.toString() }
                                            td { +fmtMoney(costData.totalSmsCost, cur) }
                                            td { +fmtMoney(costData.grandTotal, cur) }
                                        }
                                    }
                                }
                            }
                        }

                        val shopNote = selectedShop?.let { " · ${it.name}" } ?: ""
                        val rateNote = if (cur.label == "DKK") " · 1 USD = ${"%.4f".format(cur.rate)} DKK" else ""
                        p("hint") { +"Twilio Usage Records API$shopNote$rateNote" }
                    }
                }
            }
        }
    }
}
