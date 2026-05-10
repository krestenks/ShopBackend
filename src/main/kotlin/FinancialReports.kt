import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.html.*
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import com.lowagie.text.*
import com.lowagie.text.Font
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ─── Period helpers ───────────────────────────────────────────────────────────

private val DK = ZoneId.of("Europe/Copenhagen")
private val LOCALE_EN = Locale.ENGLISH

/**
 * Parse the `date` query param or default to today in Copenhagen time.
 */
private fun parseDateOrToday(dateStr: String?): LocalDate {
    return try {
        if (dateStr.isNullOrBlank()) LocalDate.now(DK)
        else LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (_: Exception) {
        LocalDate.now(DK)
    }
}

private fun epochMs(ld: LocalDate): Long = ld.atStartOfDay(DK).toInstant().toEpochMilli()

// ─── Bucket helpers ───────────────────────────────────────────────────────────

private fun dayBucketKey(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(DK).toLocalDate()
        .format(DateTimeFormatter.ISO_LOCAL_DATE)   // "yyyy-MM-dd"

private fun monthBucketKey(epochMs: Long): String {
    val dt = Instant.ofEpochMilli(epochMs).atZone(DK)
    return "%04d-%02d".format(dt.year, dt.monthValue)  // "yyyy-MM"
}

private fun dailyBuckets(from: LocalDate, to: LocalDate): List<Pair<String, String>> {
    val list = mutableListOf<Pair<String, String>>()
    var d = from
    val dayFmt  = DateTimeFormatter.ofPattern("dd/MM")
    while (!d.isAfter(to)) {
        val key   = d.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val label = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, LOCALE_EN)} ${d.format(dayFmt)}"
        list += key to label
        d = d.plusDays(1)
    }
    return list
}

private fun monthlyBuckets(year: Int): List<Pair<String, String>> =
    (1..12).map { m ->
        val key   = "%04d-%02d".format(year, m)
        val label = Month.of(m).getDisplayName(TextStyle.FULL, LOCALE_EN)
        key to label
    }

// ─── Period range calculation ─────────────────────────────────────────────────

private data class Period(
    val periodType: String,           // "week" | "month" | "year"
    val ref: LocalDate,               // reference date
    val from: LocalDate,
    val to: LocalDate,
    val title: String,                // human description for report header
)

private fun buildPeriod(periodType: String, refDate: LocalDate): Period {
    return when (periodType) {
        "week" -> {
            val mon = refDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val sun = mon.plusDays(6)
            val wkFmt = DateTimeFormatter.ofPattern("dd MMM", LOCALE_EN)
            Period("week", refDate, mon, sun,
                "Week ${mon.format(wkFmt)} – ${sun.format(wkFmt)} ${sun.year}")
        }
        "month" -> {
            val first = refDate.withDayOfMonth(1)
            val last  = first.plusMonths(1).minusDays(1)
            val mFmt  = DateTimeFormatter.ofPattern("MMMM yyyy", LOCALE_EN)
            Period("month", refDate, first, last, refDate.format(mFmt))
        }
        else -> {  // "year"
            val first = LocalDate.of(refDate.year, 1, 1)
            val last  = LocalDate.of(refDate.year, 12, 31)
            Period("year", refDate, first, last, refDate.year.toString())
        }
    }
}

private fun prevRef(p: Period): LocalDate = when (p.periodType) {
    "week"  -> p.ref.minusWeeks(1)
    "month" -> p.ref.minusMonths(1)
    else    -> p.ref.minusYears(1)
}

private fun nextRef(p: Period): LocalDate = when (p.periodType) {
    "week"  -> p.ref.plusWeeks(1)
    "month" -> p.ref.plusMonths(1)
    else    -> p.ref.plusYears(1)
}

// ─── Formatting helpers ───────────────────────────────────────────────────────

private fun fmt(v: Double): String = if (v == 0.0) "–" else "%.2f".format(v)
private fun fmtNum(v: Double): String = "%.2f".format(v)

// ─── Excel export ─────────────────────────────────────────────────────────────

private fun buildXlsx(report: FinancialReport, period: Period): ByteArray {
    val wb   = XSSFWorkbook()
    val sheet = wb.createSheet(period.periodType.replaceFirstChar { it.uppercase() })

    // --- Header styles ---
    val headerStyle = wb.createCellStyle().apply {
        fillForegroundColor = IndexedColors.DARK_BLUE.index
        fillPattern = FillPatternType.SOLID_FOREGROUND
        val font = wb.createFont().also { it.bold = true; it.color = IndexedColors.WHITE.index }
        setFont(font)
        alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
    }
    val totalStyle = wb.createCellStyle().apply {
        val font = wb.createFont().also { it.bold = true }
        setFont(font)
        alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT
    }
    val numStyle = wb.createCellStyle().apply {
        dataFormat = wb.creationHelper.createDataFormat().getFormat("#,##0.00")
    }
    val numTotalStyle = wb.createCellStyle().apply {
        val font = wb.createFont().also { it.bold = true }
        setFont(font)
        dataFormat = wb.creationHelper.createDataFormat().getFormat("#,##0.00")
    }

    // Row 0: report title
    sheet.createRow(0).createCell(0).setCellValue("${report.shop.name} — ${period.title}")

    // Row 1: blank
    sheet.createRow(1)

    // Row 2: column headers
    val hdr = sheet.createRow(2)
    var col = 0
    hdr.createCell(col++).apply { setCellValue("Period"); cellStyle = headerStyle }
    for (emp in report.employees) {
        hdr.createCell(col++).apply { setCellValue(emp.name); cellStyle = headerStyle }
    }
    hdr.createCell(col++).apply { setCellValue("Shop total"); cellStyle = headerStyle }

    // Data rows
    var rowIdx = 3
    for (row in report.rows) {
        val xr = sheet.createRow(rowIdx++)
        col = 0
        xr.createCell(col++).setCellValue(row.label)
        for (emp in report.employees) {
            xr.createCell(col++).apply {
                setCellValue(row.earnings[emp.id] ?: 0.0)
                cellStyle = numStyle
            }
        }
        xr.createCell(col++).apply { setCellValue(row.total); cellStyle = numTotalStyle }
    }

    // Totals row
    val totRow = sheet.createRow(rowIdx)
    col = 0
    totRow.createCell(col++).apply { setCellValue("TOTAL"); cellStyle = totalStyle }
    for (emp in report.employees) {
        totRow.createCell(col++).apply {
            setCellValue(report.employeeTotals[emp.id] ?: 0.0)
            cellStyle = numTotalStyle
        }
    }
    totRow.createCell(col).apply { setCellValue(report.grandTotal); cellStyle = numTotalStyle }

    // Auto-size columns
    for (c in 0..col) sheet.autoSizeColumn(c)

    val out = ByteArrayOutputStream()
    wb.write(out)
    wb.close()
    return out.toByteArray()
}

// ─── PDF export ───────────────────────────────────────────────────────────────

private fun buildPdf(report: FinancialReport, period: Period): ByteArray {
    val out  = ByteArrayOutputStream()
    val doc  = Document(PageSize.A4.rotate())
    PdfWriter.getInstance(doc, out)
    doc.open()

    // Title
    val titleFont = Font(Font.HELVETICA, 14f, Font.BOLD)
    val subFont   = Font(Font.HELVETICA, 11f, Font.NORMAL)
    val boldFont  = Font(Font.HELVETICA, 9f, Font.BOLD)
    val normFont  = Font(Font.HELVETICA, 9f, Font.NORMAL)

    doc.add(Paragraph("${report.shop.name} — Financial Report", titleFont))
    doc.add(Paragraph(period.title, subFont))
    doc.add(Paragraph(" "))

    val colCount = 1 + report.employees.size + 1  // period + employees + total

    val table = PdfPTable(colCount).apply {
        widthPercentage = 100f
        // proportional column widths: period col wider
        val widths = FloatArray(colCount) { 1f }.also { it[0] = 1.8f }
        setWidths(widths)
    }

    fun headerCell(text: String) = PdfPCell(Phrase(text, boldFont)).apply {
        backgroundColor = java.awt.Color(33, 62, 122)
        borderColor = java.awt.Color(200, 200, 200)
        setPadding(4f)
        horizontalAlignment = Element.ALIGN_CENTER
        // text color white handled via phrase font color below
    }.also {
        it.phrase = Phrase(text, Font(Font.HELVETICA, 9f, Font.BOLD, java.awt.Color.WHITE))
    }

    fun dataCell(text: String, bold: Boolean = false, align: Int = Element.ALIGN_RIGHT) =
        PdfPCell(Phrase(text, if (bold) boldFont else normFont)).apply {
            borderColor = java.awt.Color(200, 200, 200)
            setPadding(3f)
            horizontalAlignment = align
        }

    // Header row
    table.addCell(headerCell("Period"))
    for (emp in report.employees) table.addCell(headerCell(emp.name))
    table.addCell(headerCell("Shop Total"))

    // Data rows
    var shade = false
    val bg1 = java.awt.Color(245, 245, 252)
    val bg2 = java.awt.Color.WHITE
    for (row in report.rows) {
        val bg = if (shade) bg1 else bg2
        shade = !shade
        fun dc(text: String, bold: Boolean = false) =
            dataCell(text, bold).also { it.backgroundColor = bg }
        table.addCell(dc(row.label, bold = false).also { it.horizontalAlignment = Element.ALIGN_LEFT })
        for (emp in report.employees) table.addCell(dc(fmtNum(row.earnings[emp.id] ?: 0.0)))
        table.addCell(dc(fmtNum(row.total), bold = true))
    }

    // Totals row
    val totalBg = java.awt.Color(220, 230, 255)
    fun tc(text: String) = dataCell(text, bold = true).also { it.backgroundColor = totalBg }
    table.addCell(tc("TOTAL").also { it.horizontalAlignment = Element.ALIGN_LEFT })
    for (emp in report.employees) table.addCell(tc(fmtNum(report.employeeTotals[emp.id] ?: 0.0)))
    table.addCell(tc(fmtNum(report.grandTotal)))

    doc.add(table)
    doc.add(Paragraph(" "))
    doc.add(Paragraph("Generated: ${java.time.LocalDateTime.now(DK).format(
        DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"))} (Europe/Copenhagen)", normFont))

    doc.close()
    return out.toByteArray()
}

// ─── Route installer ──────────────────────────────────────────────────────────

fun Route.financialReportRoutes(db: DataBase) {

    // Helper to load + validate query params common to all report endpoints
    fun resolveReportParams(call: ApplicationCall): Triple<Int, String, LocalDate>? {
        val shopId     = call.request.queryParameters["shop_id"]?.toIntOrNull() ?: return null
        val periodType = call.request.queryParameters["period"]?.lowercase()
            ?.takeIf { it in listOf("week", "month", "year") } ?: "week"
        val refDate    = parseDateOrToday(call.request.queryParameters["date"])
        return Triple(shopId, periodType, refDate)
    }

    fun resolveReport(call: ApplicationCall): Pair<FinancialReport, Period>? {
        val (shopId, periodType, refDate) = resolveReportParams(call) ?: return null
        val period = buildPeriod(periodType, refDate)
        val bucketLabels = when (periodType) {
            "year"  -> monthlyBuckets(refDate.year)
            else    -> dailyBuckets(period.from, period.to)
        }
        val bucketFn: (Long) -> String = when (periodType) {
            "year"  -> ::monthBucketKey
            else    -> ::dayBucketKey
        }
        val report = db.buildFinancialReport(
            shopId       = shopId,
            fromMs       = epochMs(period.from),
            toMs         = epochMs(period.to.plusDays(1)) - 1,
            bucketLabels = bucketLabels,
            bucketFn     = bucketFn,
        )
        return report to period
    }

    // ── HTML report page ──────────────────────────────────────────────────────

    get("/reports") {
        val shops = db.getAllShops()
        val firstShopId = shops.firstOrNull()?.id

        // Determine selected shop and period
        val shopId     = call.request.queryParameters["shop_id"]?.toIntOrNull() ?: firstShopId ?: 0
        val periodType = call.request.queryParameters["period"]?.lowercase()
            ?.takeIf { it in listOf("week", "month", "year") } ?: "week"
        val refDate    = parseDateOrToday(call.request.queryParameters["date"])
        val period     = buildPeriod(periodType, refDate)

        val selectedShop = shops.find { it.id == shopId }

        val report: FinancialReport? = if (selectedShop != null) {
            val bucketLabels = when (periodType) {
                "year"  -> monthlyBuckets(refDate.year)
                else    -> dailyBuckets(period.from, period.to)
            }
            val bucketFn: (Long) -> String = when (periodType) {
                "year"  -> ::monthBucketKey
                else    -> ::dayBucketKey
            }
            try {
                db.buildFinancialReport(
                    shopId       = shopId,
                    fromMs       = epochMs(period.from),
                    toMs         = epochMs(period.to.plusDays(1)) - 1,
                    bucketLabels = bucketLabels,
                    bucketFn     = bucketFn,
                )
            } catch (e: Exception) { null }
        } else null

        val isoDate      = refDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val prevIsoDate  = prevRef(period).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val nextIsoDate  = nextRef(period).format(DateTimeFormatter.ISO_LOCAL_DATE)

        fun exportUrl(fmt: String) =
            "/reports/export.$fmt?shop_id=$shopId&period=$periodType&date=$isoDate"

        call.respondHtml {
            head {
                meta { charset = "utf-8" }
                meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
                title { +"Financial Reports" }
                link(rel = "stylesheet", href = "/static/admin.css", type = "text/css")
                style {
                    unsafe {
                        +"""
                        .report-table { width: 100%; border-collapse: collapse; font-size: 0.9em; }
                        .report-table th { background: #1e2a5e; color: #fff; padding: 8px 12px; text-align: right; }
                        .report-table th:first-child { text-align: left; }
                        .report-table td { padding: 7px 12px; text-align: right; border-bottom: 1px solid rgba(255,255,255,0.06); }
                        .report-table td:first-child { text-align: left; font-weight: 500; }
                        .report-table tr:nth-child(even) td { background: rgba(255,255,255,0.025); }
                        .report-table tr.totals-row td { font-weight: 700; background: rgba(124,92,255,0.12); border-top: 2px solid rgba(124,92,255,0.4); }
                        .report-table .zero { color: rgba(255,255,255,0.25); }
                        .period-nav { display:flex; align-items:center; gap: 10px; margin-bottom: 16px; }
                        .period-nav a.btn { min-width: 36px; text-align: center; }
                        .period-title { font-size: 1.1em; font-weight: 600; flex: 1; }
                        .summary-cards { display:flex; gap: 16px; margin-bottom: 20px; flex-wrap: wrap; }
                        .summary-card { background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.1);
                            border-radius: 8px; padding: 14px 20px; min-width: 160px; }
                        .summary-card .card-label { font-size: 0.75em; color: rgba(255,255,255,0.55); text-transform: uppercase; }
                        .summary-card .card-value { font-size: 1.4em; font-weight: 700; color: #a78bfa; margin-top: 4px; }
                        .export-row { display:flex; gap:10px; margin-bottom: 18px; }
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
                        val nav = listOf(
                            "/", "/shops", "/employees", "/services", "/managers",
                            "/appointments", "/customers", "/reports", "/twilio/setup",
                        )
                        val navLabels = mapOf(
                            "/" to ("Dashboard" to "🏠"), "/shops" to ("Shops" to "🏪"),
                            "/employees" to ("Employees" to "👥"), "/services" to ("Services" to "🧾"),
                            "/managers" to ("Managers" to "🧑‍💼"), "/appointments" to ("Appointments" to "📅"),
                            "/customers" to ("Customers" to "👤"), "/reports" to ("Reports" to "💰"),
                            "/twilio/setup" to ("Twilio setup" to "📞"),
                        )
                        div("nav") {
                            for (href in nav) {
                                val (label, icon) = navLabels[href] ?: continue
                                a(href = href, classes = if (href == "/reports") "active" else null) {
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
                                h1("page-title") { +"💰 Financial Reports" }
                                p("page-subtitle") { +"Earnings per employee and shop totals" }
                            }
                        }

                        // ── Filter form ──────────────────────────────────────
                        div("panel") {
                            form(action = "/reports", method = FormMethod.get) {
                                div("grid-2") {
                                    div {
                                        label { +"Shop" }
                                        select {
                                            name = "shop_id"
                                            for (s in shops) {
                                                option {
                                                    value = s.id.toString()
                                                    if (s.id == shopId) selected = true
                                                    +s.name
                                                }
                                            }
                                        }
                                    }
                                    div {
                                        label { +"Period type" }
                                        select {
                                            name = "period"
                                            listOf("week" to "Weekly", "month" to "Monthly", "year" to "Yearly")
                                                .forEach { (v, lbl) ->
                                                    option {
                                                        value = v
                                                        if (v == periodType) selected = true
                                                        +lbl
                                                    }
                                                }
                                        }
                                    }
                                }
                                br()
                                label { +"Reference date" }
                                textInput {
                                    type = InputType.date
                                    name = "date"
                                    value = isoDate
                                }
                                +" "
                                submitInput(classes = "btn primary") { value = "Show report" }
                            }
                        }

                        if (report != null) {
                            // ── Period navigation ────────────────────────────
                            div("period-nav") {
                                a(href = "/reports?shop_id=$shopId&period=$periodType&date=$prevIsoDate",
                                    classes = "btn") { +"‹ Prev" }
                                span("period-title") { +period.title }
                                a(href = "/reports?shop_id=$shopId&period=$periodType&date=$nextIsoDate",
                                    classes = "btn") { +"Next ›" }
                            }

                            // ── Summary cards ────────────────────────────────
                            div("summary-cards") {
                                div("summary-card") {
                                    div("card-label") { +"Shop total" }
                                    div("card-value") { +"${fmtNum(report.grandTotal)} kr" }
                                }
                                for (emp in report.employees) {
                                    div("summary-card") {
                                        div("card-label") { +emp.name }
                                        div("card-value") { +"${fmtNum(report.employeeTotals[emp.id] ?: 0.0)} kr" }
                                    }
                                }
                            }

                            // ── Export buttons ───────────────────────────────
                            div("export-row") {
                                a(href = exportUrl("xlsx"), classes = "btn") { +"⬇ Export .xlsx" }
                                a(href = exportUrl("pdf"), classes = "btn") { +"⬇ Export PDF" }
                            }

                            // ── Report table ─────────────────────────────────
                            div("panel") {
                                if (report.employees.isEmpty()) {
                                    p("hint") { +"No appointments recorded for this shop in the selected period." }
                                } else {
                                    div {
                                        style = "overflow-x: auto;"
                                        table(classes = "report-table") {
                                            thead {
                                                tr {
                                                    th { +"Period" }
                                                    for (emp in report.employees) th { +emp.name }
                                                    th { +"Shop Total" }
                                                }
                                            }
                                            tbody {
                                                for (row in report.rows) {
                                                    tr {
                                                        td { +row.label }
                                                        for (emp in report.employees) {
                                                            val v = row.earnings[emp.id] ?: 0.0
                                                            td(classes = if (v == 0.0) "zero" else null) {
                                                                +fmt(v)
                                                            }
                                                        }
                                                        td(classes = if (row.total == 0.0) "zero" else null) {
                                                            +fmt(row.total)
                                                        }
                                                    }
                                                }
                                            }
                                            tfoot {
                                                tr(classes = "totals-row") {
                                                    td { +"TOTAL" }
                                                    for (emp in report.employees) {
                                                        td { +fmtNum(report.employeeTotals[emp.id] ?: 0.0) }
                                                    }
                                                    td { +fmtNum(report.grandTotal) }
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
    }

    // ── Excel export ──────────────────────────────────────────────────────────

    get("/reports/export.xlsx") {
        val (report, period) = resolveReport(call) ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing or invalid parameters"); return@get
        }
        val bytes = buildXlsx(report, period)
        val filename = "report_${report.shop.name.replace(" ", "_")}_${period.periodType}_${period.ref}.xlsx"
        call.response.header(HttpHeaders.ContentDisposition,
            "attachment; filename=\"$filename\"")
        call.respondBytes(bytes, ContentType.parse(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    }

    // ── PDF export ────────────────────────────────────────────────────────────

    get("/reports/export.pdf") {
        val (report, period) = resolveReport(call) ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing or invalid parameters"); return@get
        }
        val bytes = buildPdf(report, period)
        val filename = "report_${report.shop.name.replace(" ", "_")}_${period.periodType}_${period.ref}.pdf"
        call.response.header(HttpHeaders.ContentDisposition,
            "attachment; filename=\"$filename\"")
        call.respondBytes(bytes, ContentType.Application.Pdf)
    }
}

// ─── Mobile (JWT token-in-query-param) report routes ─────────────────────────
// Used by the manager phone app WebView which cannot inject Authorization headers
// on every link click.  The raw JWT is passed as ?token=<jwt> and validated here.

private fun validateMobileToken(tokenParam: String?): Pair<Int, String>? {
    if (tokenParam.isNullOrBlank()) return null
    return try {
        val jwt = JWT.require(Algorithm.HMAC256("very-secret"))
            .withAudience("mobile")
            .withIssuer("shop-manager")
            .build()
            .verify(tokenParam)
        val userId = jwt.getClaim("userId").asInt() ?: return null
        val role   = jwt.getClaim("role").asString()  ?: return null
        userId to role
    } catch (_: Exception) { null }
}

fun Route.mobileFinancialReportRoutes(db: DataBase) {

    fun resolveManagerShops(call: ApplicationCall): List<Shop>? {
        val token = call.request.queryParameters["token"]
        val (userId, role) = validateMobileToken(token) ?: return null
        return if (role == "manager") db.getShopsForManager(userId) else null
    }

    fun resolveReportMobile(call: ApplicationCall): Pair<FinancialReport, Period>? {
        val shopId     = call.request.queryParameters["shop_id"]?.toIntOrNull() ?: return null
        val periodType = call.request.queryParameters["period"]?.lowercase()
            ?.takeIf { it in listOf("week", "month", "year") } ?: "week"
        val refDate    = parseDateOrToday(call.request.queryParameters["date"])
        val period     = buildPeriod(periodType, refDate)
        val bucketLabels = if (periodType == "year") monthlyBuckets(refDate.year)
                           else dailyBuckets(period.from, period.to)
        val bucketFn: (Long) -> String = if (periodType == "year") ::monthBucketKey else ::dayBucketKey
        val report = db.buildFinancialReport(
            shopId       = shopId,
            fromMs       = epochMs(period.from),
            toMs         = epochMs(period.to.plusDays(1)) - 1,
            bucketLabels = bucketLabels,
            bucketFn     = bucketFn,
        )
        return report to period
    }

    // ── Mobile HTML page ──────────────────────────────────────────────────────
    get("/api/mobile/manager/reports") {
        val token = call.request.queryParameters["token"] ?: ""
        val managerShops = resolveManagerShops(call)
        if (managerShops == null) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid or missing token"); return@get
        }

        val firstShopId = managerShops.firstOrNull()?.id
        val shopId      = call.request.queryParameters["shop_id"]?.toIntOrNull() ?: firstShopId ?: 0
        val periodType  = call.request.queryParameters["period"]?.lowercase()
            ?.takeIf { it in listOf("week", "month", "year") } ?: "week"
        val refDate     = parseDateOrToday(call.request.queryParameters["date"])
        val period      = buildPeriod(periodType, refDate)

        val allowedShopIds = managerShops.map { it.id }.toSet()
        val selectedShop   = managerShops.find { it.id == shopId }

        val report: FinancialReport? = if (selectedShop != null && shopId in allowedShopIds) {
            val bucketLabels = if (periodType == "year") monthlyBuckets(refDate.year)
                               else dailyBuckets(period.from, period.to)
            val bucketFn: (Long) -> String =
                if (periodType == "year") ::monthBucketKey else ::dayBucketKey
            try {
                db.buildFinancialReport(
                    shopId       = shopId,
                    fromMs       = epochMs(period.from),
                    toMs         = epochMs(period.to.plusDays(1)) - 1,
                    bucketLabels = bucketLabels,
                    bucketFn     = bucketFn,
                )
            } catch (_: Exception) { null }
        } else null

        val isoDate     = refDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val prevIsoDate = prevRef(period).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val nextIsoDate = nextRef(period).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val baseParams  = "token=$token"

        fun mobileUrl(extra: String) =
            "/api/mobile/manager/reports?$baseParams&shop_id=$shopId&period=$periodType&date=$extra"
        fun exportUrl(fmt: String) =
            "/api/mobile/manager/reports/export.$fmt?$baseParams&shop_id=$shopId&period=$periodType&date=$isoDate"

        call.respondHtml {
            head {
                meta { charset = "utf-8" }
                meta { name = "viewport"; content = "width=device-width, initial-scale=1, maximum-scale=1" }
                title { +"💰 Reports" }
                style {
                    unsafe {
                        +"""
                        * { box-sizing: border-box; margin: 0; padding: 0; }
                        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                               background: #13131f; color: #e8e8f0; font-size: 15px; }
                        .toolbar { background: #1e2a5e; padding: 14px 16px; font-size: 18px; font-weight: 700; }
                        .container { padding: 14px 12px; }
                        label { display: block; font-size: 0.75em; color: #aaa; margin-bottom: 4px; text-transform: uppercase; }
                        select, input[type=date] { width: 100%; background: #1c1c2e; color: #e8e8f0;
                            border: 1px solid #333; border-radius: 8px; padding: 10px 12px;
                            font-size: 15px; margin-bottom: 12px; }
                        .btn { display: inline-block; background: #4f46e5; color: #fff;
                            border: none; border-radius: 8px; padding: 10px 18px;
                            font-size: 14px; text-decoration: none; cursor: pointer; }
                        .btn-row { display: flex; gap: 8px; flex-wrap: wrap; margin: 10px 0 14px; }
                        .btn-outline { background: transparent; border: 1px solid #4f46e5; color: #a78bfa; }
                        .period-nav { display: flex; align-items: center; gap: 8px; margin: 10px 0 14px; }
                        .period-nav a { flex: 0 0 auto; }
                        .period-title { flex: 1; text-align: center; font-weight: 600; font-size: 1em; }
                        .summary-cards { display: flex; gap: 10px; overflow-x: auto; margin-bottom: 14px; padding-bottom: 4px; }
                        .summary-card { flex: 0 0 auto; background: #1c1c2e; border: 1px solid #333;
                            border-radius: 10px; padding: 12px 16px; min-width: 130px; }
                        .card-label { font-size: 0.7em; color: #888; text-transform: uppercase; margin-bottom: 4px; }
                        .card-value { font-size: 1.2em; font-weight: 700; color: #a78bfa; }
                        table { width: 100%; border-collapse: collapse; font-size: 13px; }
                        th { background: #1e2a5e; color: #fff; padding: 8px 10px; text-align: right; }
                        th:first-child { text-align: left; }
                        td { padding: 7px 10px; text-align: right; border-bottom: 1px solid #222; }
                        td:first-child { text-align: left; font-weight: 500; }
                        tr:nth-child(even) td { background: rgba(255,255,255,0.03); }
                        tr.totals-row td { font-weight: 700; background: rgba(124,92,255,0.12);
                            border-top: 2px solid rgba(124,92,255,0.4); }
                        .zero { color: #444; }
                        .wrap { overflow-x: auto; }
                        .hint { color: #888; font-size: 0.85em; padding: 12px 0; }
                        """.trimIndent()
                    }
                }
            }
            body {
                div("toolbar") { +"💰 Financial Reports" }
                div("container") {
                    // Filter form
                    form(action = "/api/mobile/manager/reports", method = FormMethod.get) {
                        hiddenInput { name = "token"; value = token }

                        label { +"Shop" }
                        select {
                            name = "shop_id"
                            for (s in managerShops) {
                                option {
                                    value = s.id.toString()
                                    if (s.id == shopId) selected = true
                                    +s.name
                                }
                            }
                        }

                        label { +"Period" }
                        select {
                            name = "period"
                            listOf("week" to "Weekly", "month" to "Monthly", "year" to "Yearly")
                                .forEach { (v, lbl) ->
                                    option {
                                        value = v
                                        if (v == periodType) selected = true
                                        +lbl
                                    }
                                }
                        }

                        label { +"Reference date" }
                        textInput {
                            type = InputType.date
                            name = "date"
                            value = isoDate
                        }

                        submitInput(classes = "btn") { value = "Show report" }
                    }

                    if (report != null) {
                        // Period navigation
                        div("period-nav") {
                            a(href = mobileUrl(prevIsoDate), classes = "btn btn-outline") { +"‹" }
                            span("period-title") { +period.title }
                            a(href = mobileUrl(nextIsoDate), classes = "btn btn-outline") { +"›" }
                        }

                        // Summary cards
                        div("summary-cards") {
                            div("summary-card") {
                                div("card-label") { +"Shop total" }
                                div("card-value") { +"${fmtNum(report.grandTotal)} kr" }
                            }
                            for (emp in report.employees) {
                                div("summary-card") {
                                    div("card-label") { +emp.name }
                                    div("card-value") { +"${fmtNum(report.employeeTotals[emp.id] ?: 0.0)} kr" }
                                }
                            }
                        }

                        // Export buttons
                        div("btn-row") {
                            a(href = exportUrl("pdf"), classes = "btn btn-outline") { +"⬇ PDF" }
                            a(href = exportUrl("xlsx"), classes = "btn btn-outline") { +"⬇ Excel" }
                        }

                        if (report.employees.isEmpty()) {
                            p("hint") { +"No appointments in this period." }
                        } else {
                            div("wrap") {
                                table {
                                    thead {
                                        tr {
                                            th { +"Period" }
                                            for (emp in report.employees) th { +emp.name.take(10) }
                                            th { +"Total" }
                                        }
                                    }
                                    tbody {
                                        for (row in report.rows) {
                                            tr {
                                                td { +row.label }
                                                for (emp in report.employees) {
                                                    val v = row.earnings[emp.id] ?: 0.0
                                                    td(classes = if (v == 0.0) "zero" else null) { +fmt(v) }
                                                }
                                                td(classes = if (row.total == 0.0) "zero" else null) { +fmt(row.total) }
                                            }
                                        }
                                    }
                                    tfoot {
                                        tr(classes = "totals-row") {
                                            td { +"TOTAL" }
                                            for (emp in report.employees) {
                                                td { +fmtNum(report.employeeTotals[emp.id] ?: 0.0) }
                                            }
                                            td { +fmtNum(report.grandTotal) }
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

    // ── Mobile xlsx export ────────────────────────────────────────────────────
    get("/api/mobile/manager/reports/export.xlsx") {
        if (validateMobileToken(call.request.queryParameters["token"]) == null) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid token"); return@get
        }
        val (report, period) = resolveReportMobile(call) ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing parameters"); return@get
        }
        val bytes = buildXlsx(report, period)
        val filename = "report_${report.shop.name.replace(" ", "_")}_${period.periodType}_${period.ref}.xlsx"
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
        call.respondBytes(bytes, ContentType.parse(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    }

    // ── Mobile PDF export ─────────────────────────────────────────────────────
    get("/api/mobile/manager/reports/export.pdf") {
        if (validateMobileToken(call.request.queryParameters["token"]) == null) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid token"); return@get
        }
        val (report, period) = resolveReportMobile(call) ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing parameters"); return@get
        }
        val bytes = buildPdf(report, period)
        val filename = "report_${report.shop.name.replace(" ", "_")}_${period.periodType}_${period.ref}.pdf"
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
        call.respondBytes(bytes, ContentType.Application.Pdf)
    }
}
