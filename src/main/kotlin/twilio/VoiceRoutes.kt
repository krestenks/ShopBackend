package twilio

import DataBase
import VoiceCallOutcome
import VoiceCallState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import PublicBaseUrl

/**
 * Twilio Voice endpoints — implements the Phone flow spec.
 *
 * Inbound call state machine:
 *   IncomingCall
 *     → (blacklisted)          RejectedBlacklisted → Terminated
 *     → (known customer)       KnownCustomerMenu (DTMF, up to 3 attempts)
 *         digit 1              → KnownCustomerSmsBooking → Terminated
 *         digit 2 + open       → OperatorWhisper → BridgedToOperator / Terminated
 *         digit 2 + closed     → ClosedMessage → Terminated
 *         digit 2 + tempClosed → TemporaryClosedMessage → Terminated
 *         no input × 3         → Terminated
 *     → (unknown customer)
 *         + open + !tempClosed → OperatorWhisper → BridgedToOperator / Terminated
 *         + open + tempClosed  → TemporaryClosedMessage → Terminated
 *         + closed             → ClosedMessage → Terminated
 *
 * Operator whisper: customer hears ringing via answerOnBridge="true";
 * operator hears whisper and must press 1 to accept.
 */
fun Route.twilioVoiceRoutes(db: DataBase) {

    val smsService = TwilioSmsService(
        accountSid = System.getenv("TWILIO_ACCOUNT_SID") ?: "",
        authToken = System.getenv("TWILIO_AUTH_TOKEN") ?: "",
    )

    fun twiml(xmlInsideResponse: String): String =
        """<?xml version="1.0" encoding="UTF-8"?><Response>$xmlInsideResponse</Response>"""

    // Customers often need a moment after answering before audio starts.
    // Twilio recommendation: add a short pause before the first <Say> to avoid clipping.
    val customerTtsLeadInPause = "<Pause length=\"3\"/>"

    // Use this for TwiML that is played to the *customer* (not operator whisper/accept).
    fun customerTwiml(xmlInsideResponse: String): String = twiml(customerTtsLeadInPause + xmlInsideResponse)

    /**
     * Effective open/closed check, respecting the per-shop phone override.
     *
     * override:
     *  - "open"   -> always open
     *  - "closed" -> always closed
     *  - null/"auto" -> use opening-hours schedule
     */
    fun isShopOpenEffectiveNow(shopId: Int): Boolean {
        val vc = db.getShopVoiceConfig(shopId)
        val mode = vc.phoneOverride?.trim()?.lowercase()
        return when (mode) {
            "open" -> true
            "closed" -> false
            else -> db.isShopOpenByScheduleNow(shopId)
        }
    }

    // ── Outbound "bridge" TwiML — manager answers, Twilio dials customer ─────
    // Called by Twilio after the manager picks up a direct-call-customer call.
    //
    // Query params set by us (never clashing with Twilio's own POST fields):
    //   ?customerPhone={E164}   — number to dial after manager answers
    //   &callerId={E164}        — shop Twilio number shown to customer
    //
    // Twilio will ALSO POST its own fields (To, From, CallSid, etc.) but since
    // we now read from queryParameters only (for our custom params), there is
    // no clash.  The legacy "to" param is kept as a fallback for old requests.

    route("/api/twilio/voice/bridge") {
        get {
            // Always read from query string — never from POST body — to avoid Twilio's
            // own "To" POST field shadowing our custom "customerPhone" / "to" param.
            val qp          = call.request.queryParameters
            val customerPhone = qp["customerPhone"]?.trim().orEmpty()
                .ifBlank { qp["to"]?.trim().orEmpty() }  // legacy fallback
            val callerId    = qp["callerId"]?.trim()

            println("[Bridge/GET] customerPhone=$customerPhone callerId=$callerId twilioTo=${qp["To"]} twilioFrom=${qp["From"]}")

            if (customerPhone.isBlank()) {
                call.respondText(twiml("<Hangup/>"), ContentType.Text.Xml)
                return@get
            }
            val dialXml = buildString {
                append("<Dial")
                if (!callerId.isNullOrBlank()) append(" callerId=\"${escapeForXml(callerId)}\"")
                append("><Number>${escapeForXml(customerPhone)}</Number></Dial>")
            }
            println("[Bridge/GET] ✅ dialling customer=$customerPhone  TwiML=$dialXml")
            call.respondText(twiml(dialXml), ContentType.Text.Xml)
        }
        post {
            // Twilio POSTs its own To/From/CallSid etc. to this URL.
            // We intentionally ignore the POST body for our custom params and read only
            // from the query string, which is unambiguous and under our full control.
            val qp          = call.request.queryParameters
            val customerPhone = qp["customerPhone"]?.trim().orEmpty()
                .ifBlank { qp["to"]?.trim().orEmpty() }  // legacy fallback
            val callerId    = qp["callerId"]?.trim()

            // Read Twilio's own fields for logging only
            val twilioParams = runCatching { call.receiveParameters() }.getOrNull()
            println("[Bridge/POST] customerPhone=$customerPhone callerId=$callerId  twilioTo=${twilioParams?.get("To")} twilioFrom=${twilioParams?.get("From")}")

            if (customerPhone.isBlank()) {
                call.respondText(twiml("<Hangup/>"), ContentType.Text.Xml)
                return@post
            }
            val dialXml = buildString {
                append("<Dial")
                if (!callerId.isNullOrBlank()) append(" callerId=\"${escapeForXml(callerId)}\"")
                append("><Number>${escapeForXml(customerPhone)}</Number></Dial>")
            }
            println("[Bridge/POST] ✅ dialling customer=$customerPhone  TwiML=$dialXml")
            call.respondText(twiml(dialXml), ContentType.Text.Xml)
        }
    }

    // ── Outbound "ready" TwiML (unchanged) ───────────────────────────────────

    route("/api/twilio/voice/ready") {
        get {
            val message = call.request.queryParameters["msg"]?.take(400)
                ?: "Hello. You can come to the door now."

            val msgEsc = escapeForXml(message)
            val say = "<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">$msgEsc</Say>"
            val xml = customerTtsLeadInPause +
                    say + "<Pause length=\"2\"/>" +
                    say + "<Pause length=\"2\"/>" +
                    say + "<Hangup/>"
            call.respondText(
                twiml(xml),
                ContentType.Text.Xml,
            )
        }
        post {
            val params = call.receiveParameters()
            val message = (params["msg"] ?: call.request.queryParameters["msg"])?.take(400)
                ?: "Hello. You can come to the door now."

            val msgEsc = escapeForXml(message)
            val say = "<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">$msgEsc</Say>"
            val xml = customerTtsLeadInPause +
                    say + "<Pause length=\"2\"/>" +
                    say + "<Pause length=\"2\"/>" +
                    say + "<Hangup/>"
            call.respondText(
                twiml(xml),
                ContentType.Text.Xml,
            )
        }
    }

    // ── Inbound call entry: /api/twilio/voice/welcome ────────────────────────

    suspend fun handleWelcome(call: ApplicationCall) {
        val params = try { call.receiveParameters() } catch (_: Exception) { Parameters.Empty }
        val to   = (params["To"]       ?: call.request.queryParameters["To"]       ?: "").trim()
        val from = (params["From"]     ?: call.request.queryParameters["From"]     ?: "").trim()
        val sid  = (params["CallSid"]  ?: call.request.queryParameters["CallSid"]  ?: "unknown-${System.currentTimeMillis()}").trim()

        val shopId = db.findShopIdByTwilioNumber(to)
        if (shopId == null) {
            println("[VoiceRoutes/welcome] No shop found for Twilio number '$to' — hanging up")
            call.respondText(twiml("<Hangup/>"), ContentType.Text.Xml)
            return
        }
        val voice  = db.getShopVoiceConfig(shopId)
        val base   = PublicBaseUrl.fromCall(call)

        // Robustness: if Twilio callbacks were missed, we may have stuck active calls.
        // Clear anything older than 5 minutes for this shop before creating a new call record.
        runCatching {
            val terminated = db.terminateActiveCalls(
                shopId = shopId,
                olderThanMs = 5 * 60 * 1000L,
                note = "new_inbound_call_cleanup",
            )
            if (terminated > 0) println("[VoiceRoutes/welcome] terminated $terminated stale active calls for shopId=$shopId")
        }.onFailure { e ->
            println("[VoiceRoutes/welcome] cleanup error: ${e.message}")
        }

        // Extra robustness: if the SAME caller calls again and the previous call never got marked inactive
        // (common if they hang up during a Gather/menu), terminate any active calls from this number.
        runCatching {
            val killed = db.terminateActiveCallsFromPhone(
                shopId = shopId,
                fromPhone = from,
                note = "new_inbound_call_same_caller_cleanup",
            )
            if (killed > 0) println("[VoiceRoutes/welcome] terminated $killed active calls from caller=$from shopId=$shopId")
        }.onFailure { e ->
            println("[VoiceRoutes/welcome] same-caller cleanup error: ${e.message}")
        }

        // ── Persist call immediately so app can see it ────────────────────────
        val callId = db.createInboundCallLog(shopId, sid, from, to)
        db.updateCallState(callId, VoiceCallState.INCOMING_CALL)

        // ── 1. Blacklist check ────────────────────────────────────────────────
        if (from.isNotBlank() && db.isPhoneBlacklisted(shopId, from)) {
            db.updateCallState(callId, VoiceCallState.REJECTED_BLACKLISTED, "caller=$from")
            db.terminateCall(callId, VoiceCallOutcome.BLACKLIST_REJECTED)
            call.respondText(twiml("<Reject/>"), ContentType.Text.Xml)
            return
        }

        // ── 2. Identify customer ─────────────────────────────────────────────
        db.updateCallState(callId, VoiceCallState.IDENTIFY_CUSTOMER)
        val existingCustomerId: Int? = if (from.isNotBlank()) db.getCustomerIdByPhone(from) else null
        val isKnownCustomer = existingCustomerId != null

        // Auto-create a "New" customer row for unknown callers so the manager app can always
        // navigate to a customer detail page and whitelist/blacklist the number.
        val customerId: Int? = existingCustomerId ?: run {
            if (from.isBlank()) null
            else try {
                db.insertNewCustomer(from).also { newId ->
                    println("[VoiceRoutes/welcome] Auto-created customer id=$newId for new caller phone=$from")
                }
            } catch (e: Exception) {
                println("[VoiceRoutes/welcome] Failed to auto-create customer for phone=$from: ${e.message}")
                null
            }
        }

        db.updateCallCustomer(callId, customerId, if (isKnownCustomer) "known" else "unknown")

        val isOpen      = isShopOpenEffectiveNow(shopId)
        val tempClosed  = voice.temporaryOperatorClosed
        val bizName     = voice.businessName ?: "the shop"

        if (!isKnownCustomer) {
            // ── Unknown caller ────────────────────────────────────────────────
            // Unknown customers hear NO automated messages at any point.
            // Shop closed or any unavailable condition → silent rejection (no audio).
            // Open shop → pure ringing until the operator picks up (answerOnBridge=true, no <Say> before <Dial>).
            db.updateCallState(callId, VoiceCallState.UNKNOWN_CUSTOMER_ROUTE)

            if (!isOpen) {
                // Reject before the call is answered so the caller just hears a "not available" signal.
                db.updateCallState(callId, VoiceCallState.CLOSED_MESSAGE)
                db.terminateCall(callId, VoiceCallOutcome.CLOSED_HOURS)
                call.respondText(twiml("<Reject/>"), ContentType.Text.Xml)
                return
            }

            if (tempClosed) {
                db.updateCallState(callId, VoiceCallState.TEMPORARY_CLOSED_MESSAGE)
                db.terminateCall(callId, VoiceCallOutcome.TEMP_OPERATOR_CLOSED)
                call.respondText(twiml("<Reject/>"), ContentType.Text.Xml)
                return
            }

            // Transfer to operator with whisper — operator = shop manager's phone
            val operator = db.getManagerPhoneForShop(shopId)?.trim().orEmpty()
            val fromNumber = voice.twilioNumber?.takeIf { it.isNotBlank() }
                ?: (System.getenv("TWILIO_FROM_NUMBER") ?: "")
            println("[VoiceRoutes/welcome] resolvedOperator='$operator' shopId=$shopId")
            if (operator.isBlank()) {
                db.terminateCall(callId, VoiceCallOutcome.OPERATOR_DECLINED)
                call.respondText(twiml("<Hangup/>"), ContentType.Text.Xml)
                return
            }
            // ── Operator busy check (operator-phone scoped, cross-shop) ──────
            if (db.isOperatorBusy(operator)) {
                db.updateCallState(callId, VoiceCallState.OPERATOR_BUSY, "operator=$operator")
                db.terminateCall(callId, VoiceCallOutcome.OPERATOR_BUSY)
                call.respondText(twiml("<Hangup/>"), ContentType.Text.Xml)
                return
            }
            db.setCallOperatorPhone(callId, operator)
            db.updateCallState(callId, VoiceCallState.OPERATOR_WHISPER)
            // Use the original caller's number as callerId so the manager sees who is calling,
            // not the shop Twilio number. Fall back to Twilio number only if from is blank.
            val callerId = from.takeIf { it.isNotBlank() } ?: fromNumber.takeIf { it.isNotBlank() }
            val whisperUrl = "$base/api/twilio/voice/operator-whisper" +
                    "?callId=$callId&customerType=new&bizName=${java.net.URLEncoder.encode(bizName, Charsets.UTF_8)}"
            // Pass customerType to dial-status so it knows whether to speak or stay silent
            val dialAction = "$base/api/twilio/voice/dial-status?callId=$callId&customerType=new"
            println("[VoiceRoutes/welcome] DIAL callId=$callId shop=$shopId" +
                " operator='$operator' callerId='$callerId' (original caller=$from)" +
                " whisperUrl=$whisperUrl dialAction=$dialAction")
            // No <Say> before <Dial> — unknown caller hears only ringing while operator decides.
            val xml = """
                <Dial answerOnBridge="true"${callerId?.let { " callerId=\"${escapeForXml(it)}\"" } ?: ""} action="${escapeForXml(dialAction)}">
                  <Number url="${escapeForXml(whisperUrl)}">${escapeForXml(operator)}</Number>
                </Dial>
            """.trimIndent()
            println("[VoiceRoutes/welcome] TwiML sent:\n${twiml(xml)}")
            call.respondText(twiml(xml), ContentType.Text.Xml)
            return
        }

        // ── Known caller: show DTMF menu ──────────────────────────────────────
        db.updateCallState(callId, VoiceCallState.KNOWN_CUSTOMER_MENU)
        // IMPORTANT: we do not pass isOpen/tempClosed as query params because the manager may
        // change phone status live while the customer is in the menu.
        val closedForPhones = (!isOpen) || tempClosed
        val menuAction = "$base/api/twilio/voice/menu?callId=$callId&attempt=1&shopId=$shopId&closed=$closedForPhones"
        val welcomeMsg = if (isOpen) voice.welcomeOpenMessage else voice.welcomeClosedMessage

        val menuPrompt = if (closedForPhones) {
            "The phone is currently closed but you can perform a booking by SMS link. Press 1 to receive a link."
        } else {
            "Press 1 to receive a booking link by SMS. Press 2 to book by phone with an operator."
        }

        val xml = """
            <Say voice="Polly.Amy-Neural" language="en-GB">${escapeForXml(welcomeMsg)}</Say>
            <Gather numDigits="1" timeout="15" action="${escapeForXml(menuAction)}" method="POST">
              <Say voice="Polly.Amy-Neural" language="en-GB">${escapeForXml(menuPrompt)}</Say>
            </Gather>
            <Redirect method="POST">${escapeForXml(menuAction)}</Redirect>
        """.trimIndent()
        call.respondText(customerTwiml(xml), ContentType.Text.Xml)
    }

    route("/api/twilio/voice/welcome") {
        post { handleWelcome(call) }
        get  { handleWelcome(call) }
    }
    route("/api/twilio/voice/welcome/") {
        post { handleWelcome(call) }
    }

    // ── Menu handler ─────────────────────────────────────────────────────────

    route("/api/twilio/voice/menu") {
        post {
            val params  = call.receiveParameters()
            val digits  = (params["Digits"] ?: call.request.queryParameters["Digits"])?.trim()
            val from    = (params["From"]   ?: call.request.queryParameters["From"]   ?: "").trim()
            val to      = (params["To"]     ?: call.request.queryParameters["To"]     ?: "").trim()
            val callId  = call.request.queryParameters["callId"]?.toIntOrNull() ?: -1
            val attempt = call.request.queryParameters["attempt"]?.toIntOrNull() ?: 1
            val shopId: Int = call.request.queryParameters["shopId"]?.toIntOrNull()
                ?: db.findShopIdByTwilioNumber(to)
                ?: run {
                    println("[VoiceRoutes/menu] No shopId param and no shop found for Twilio number '$to' — hanging up")
                    call.respondText(
                        """<?xml version="1.0" encoding="UTF-8"?><Response><Hangup/></Response>""",
                        ContentType.Text.Xml,
                    )
                    return@post
                }
            val closedMenu = call.request.queryParameters["closed"]?.trim()?.lowercase() == "true"

            val voice    = db.getShopVoiceConfig(shopId)
            val bizName  = voice.businessName ?: "the shop"
            val base     = PublicBaseUrl.fromCall(call)
            val fromNumber = voice.twilioNumber?.takeIf { it.isNotBlank() }
                ?: (System.getenv("TWILIO_FROM_NUMBER") ?: "")

            // Re-evaluate open/closed status LIVE (manager may override at any time)
            val isOpen = isShopOpenEffectiveNow(shopId)
            val tempClosed = voice.temporaryOperatorClosed

            fun menuAgain(nextAttempt: Int): String {
                val menuAction = "$base/api/twilio/voice/menu" +
                        "?callId=$callId&attempt=$nextAttempt&shopId=$shopId&closed=$closedMenu"
                val prompt = if (closedMenu) {
                    "The phone is currently closed but you can perform a booking by SMS link. Press 1 to receive a link."
                } else {
                    "Press 1 to receive a booking link by SMS. Press 2 to book by phone with an operator."
                }
                return """
                    <Gather numDigits="1" timeout="15" action="${escapeForXml(menuAction)}" method="POST">
                      <Say voice="Polly.Amy-Neural" language="en-GB">${escapeForXml(prompt)}</Say>
                    </Gather>
                    <Redirect method="POST">${escapeForXml(menuAction)}</Redirect>
                """.trimIndent()
            }

            when (digits) {
                "1" -> {
                    // SMS booking link
                    if (callId > 0) db.updateCallState(callId, VoiceCallState.KNOWN_CUSTOMER_SMS_BOOKING)
                    if (from.isBlank()) {
                        if (callId > 0) db.terminateCall(callId, VoiceCallOutcome.SYSTEM_ERROR)
                        call.respondText(
                            customerTwiml("<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">Sorry, we could not read your number.</Say>"),
                            ContentType.Text.Xml,
                        )
                        return@post
                    }
                    // Block SMS booking for customers with "New" status (voice only until whitelisted)
                    val customerStatus = db.getCustomerIdByPhone(from)?.let { db.getCustomerById(it)?.status }
                    if (customerStatus == "New") {
                        if (callId > 0) db.terminateCall(callId, VoiceCallOutcome.CLOSED_HOURS)
                        call.respondText(
                            customerTwiml("<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">SMS booking is not available for your account. Please call again and press 2 to speak with an operator.</Say>"),
                            ContentType.Text.Xml,
                        )
                        return@post
                    }
                    val customerId = db.ensureCustomerByPhone(from)
                    val token      = db.generateBookingToken(customerId, shopId, from)
                    val bookingUrl = "$base/api/book?token=$token"
                    val sms = smsService.sendSms(
                        fromNumberE164 = fromNumber,
                        toNumberE164   = from,
                        bodyText       = "Booking link: $bookingUrl",
                    )
                    if (!sms.success) {
                        println("[TwilioSMS] Failed: status=${sms.status} body=${sms.body}")
                        if (callId > 0) db.terminateCall(callId, VoiceCallOutcome.SYSTEM_ERROR)
                        call.respondText(
                            customerTwiml("<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">Sorry, we could not send the SMS right now. Please try again later.</Say>"),
                            ContentType.Text.Xml,
                        )
                        return@post
                    }
                    if (callId > 0) db.terminateCall(callId, VoiceCallOutcome.SMS_SENT)
                    call.respondText(
                        customerTwiml("<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">We have sent you a booking link by SMS. Goodbye.</Say>"),
                        ContentType.Text.Xml,
                    )
                }

                "2" -> {
                    if (callId > 0) db.updateCallState(callId, VoiceCallState.KNOWN_CUSTOMER_OPERATOR_ROUTE)
                    if (closedMenu) {
                        // Closed menu: do not offer operator route.
                        if (callId > 0) db.terminateCall(callId, VoiceCallOutcome.CLOSED_HOURS)
                        call.respondText(
                            customerTwiml("<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">${escapeForXml(voice.welcomeClosedMessage)}</Say>"),
                            ContentType.Text.Xml,
                        )
                        return@post
                    }
                    if (!isOpen) {
                        if (callId > 0) db.terminateCall(callId, VoiceCallOutcome.CLOSED_HOURS)
                        call.respondText(
                            customerTwiml("<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">${escapeForXml(voice.welcomeClosedMessage)}</Say>"),
                            ContentType.Text.Xml,
                        )
                        return@post
                    }
                    if (tempClosed) {
                        if (callId > 0) db.terminateCall(callId, VoiceCallOutcome.TEMP_OPERATOR_CLOSED)
                        call.respondText(
                            customerTwiml("<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">${escapeForXml(voice.temporaryOperatorClosedMessage)}</Say>"),
                            ContentType.Text.Xml,
                        )
                        return@post
                    }
                    // Operator = shop manager's phone number
                    val operator = db.getManagerPhoneForShop(shopId)?.trim().orEmpty()
                    println("[VoiceRoutes/menu digit=2] resolvedOperator='$operator' shopId=$shopId")
                    if (operator.isBlank()) {
                        if (callId > 0) db.terminateCall(callId, VoiceCallOutcome.OPERATOR_DECLINED)
                        call.respondText(
                            customerTwiml("<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">Sorry, call forwarding is not available.</Say>"),
                            ContentType.Text.Xml,
                        )
                        return@post
                    }
                    // ── Operator busy check (operator-phone scoped, cross-shop) ──
                    if (db.isOperatorBusy(operator)) {
                        if (callId > 0) {
                            db.updateCallState(callId, VoiceCallState.OPERATOR_BUSY, "operator=$operator")
                            db.terminateCall(callId, VoiceCallOutcome.OPERATOR_BUSY)
                        }
                        call.respondText(
                            customerTwiml("<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">Our operator is currently busy. Please call again in 5 minutes.</Say>"),
                            ContentType.Text.Xml,
                        )
                        return@post
                    }
                    // Bridge with whisper
                    if (callId > 0) {
                        db.setCallOperatorPhone(callId, operator)
                        db.updateCallState(callId, VoiceCallState.OPERATOR_WHISPER)
                    }
                    // Use the original caller's number as callerId so the manager sees who is calling,
                    // not the shop Twilio number. Fall back to Twilio number only if from is blank.
                    val callerId = from.takeIf { it.isNotBlank() } ?: fromNumber.takeIf { it.isNotBlank() }
                    val whisperUrl = "$base/api/twilio/voice/operator-whisper" +
                            "?callId=$callId&customerType=existing&bizName=${java.net.URLEncoder.encode(bizName, Charsets.UTF_8)}"
                    val dialAction = "$base/api/twilio/voice/dial-status?callId=$callId"
                    println("[VoiceRoutes/menu digit=2] DIAL callId=$callId shop=$shopId" +
                        " operator='$operator' callerId='$callerId' (original caller=$from)" +
                        " whisperUrl=$whisperUrl dialAction=$dialAction")
                    val xml = """
                        <Say voice="Polly.Amy-Neural" language="en-GB">Please hold while we connect you to the operator.</Say>
                        <Dial answerOnBridge="true"${callerId?.let { " callerId=\"${escapeForXml(it)}\"" } ?: ""} action="${escapeForXml(dialAction)}">
                          <Number url="${escapeForXml(whisperUrl)}">${escapeForXml(operator)}</Number>
                        </Dial>
                    """.trimIndent()
                    println("[VoiceRoutes/menu digit=2] TwiML sent:\n${twiml(xml)}")
                    call.respondText(customerTwiml(xml), ContentType.Text.Xml)
                }

                else -> {
                    // No / invalid input
                    if (attempt >= 3) {
                        if (callId > 0) db.terminateCall(callId, VoiceCallOutcome.INVALID_MENU_MAX_RETRIES)
                        call.respondText(
                            customerTwiml("<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">We did not receive a valid selection. Goodbye.</Say>"),
                            ContentType.Text.Xml,
                        )
                    } else {
                        call.respondText(customerTwiml(menuAgain(attempt + 1)), ContentType.Text.Xml)
                    }
                }
            }
        }
    }

    // ── Operator whisper: played only to operator before bridge ──────────────

    route("/api/twilio/voice/operator-whisper") {
        post {
            val params = call.receiveParameters()
            val childCallSid = params["CallSid"]
            handleOperatorWhisper(call, db, childCallSid)
        }
        get {
            val childCallSid = call.request.queryParameters["CallSid"]
            handleOperatorWhisper(call, db, childCallSid)
        }
    }

    // ── App-accept: operator-leg is redirected here when manager taps 🤝 in app ──
    route("/api/twilio/voice/auto-accept") {
        post {
            val callId = call.request.queryParameters["callId"]?.toIntOrNull() ?: -1
            if (callId > 0) db.updateCallState(callId, VoiceCallState.BRIDGED_TO_OPERATOR)
            call.respondText(twiml(""), ContentType.Text.Xml)
        }
        get {
            val callId = call.request.queryParameters["callId"]?.toIntOrNull() ?: -1
            if (callId > 0) db.updateCallState(callId, VoiceCallState.BRIDGED_TO_OPERATOR)
            call.respondText(twiml(""), ContentType.Text.Xml)
        }
    }

    // ── Operator accept: operator must press 1 to accept ─────────────────────

    route("/api/twilio/voice/operator-accept") {
        post {
            val params       = call.receiveParameters()
            val digits       = (params["Digits"] ?: call.request.queryParameters["Digits"])?.trim()
            val callId       = call.request.queryParameters["callId"]?.toIntOrNull() ?: -1

            if (digits == "1") {
                // Accepted: return empty TwiML so Twilio bridges the call
                if (callId > 0) db.updateCallState(callId, VoiceCallState.BRIDGED_TO_OPERATOR)
                call.respondText(twiml(""), ContentType.Text.Xml)
            } else {
                if (callId > 0) db.terminateCall(callId, VoiceCallOutcome.OPERATOR_DECLINED)
                call.respondText(
                    twiml("<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">No operator is currently available. Please try again later.</Say>"),
                    ContentType.Text.Xml,
                )
            }
        }
        get {
            // Support Twilio occasionally using GET
            val digits  = call.request.queryParameters["Digits"]?.trim()
            val callId  = call.request.queryParameters["callId"]?.toIntOrNull() ?: -1
            if (digits == "1") {
                if (callId > 0) db.updateCallState(callId, VoiceCallState.BRIDGED_TO_OPERATOR)
                call.respondText(twiml(""), ContentType.Text.Xml)
            } else {
                if (callId > 0) db.terminateCall(callId, VoiceCallOutcome.OPERATOR_DECLINED)
                call.respondText(
                    twiml("<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">No operator is currently available. Please try again later.</Say>"),
                    ContentType.Text.Xml,
                )
            }
        }
    }

    // ── Dial status callback: closes call log when <Dial> completes ──────────

    route("/api/twilio/voice/dial-status") {
        post {
            val params       = call.receiveParameters()
            val dialStatus   = params["DialCallStatus"]?.trim() ?: "unknown"
            val callId       = call.request.queryParameters["callId"]?.toIntOrNull() ?: -1
            // customerType=new means the call was an unknown/new customer — no spoken feedback for them.
            val customerType = call.request.queryParameters["customerType"]?.trim() ?: "existing"
            val isUnknownCaller = customerType == "new"
            val dialSid     = params["DialCallSid"]?.trim() ?: ""
            val dialTo      = params["To"]?.trim() ?: ""
            val callSid     = params["CallSid"]?.trim() ?: ""
            println("[VoiceRoutes/dial-status] callId=$callId DialCallStatus=$dialStatus customerType=$customerType" +
                " DialCallSid=$dialSid To=$dialTo CallSid=$callSid")
            // Log all params for full diagnostic
            params.entries().forEach { (k, vals) ->
                println("  param  $k=${vals.joinToString()}")
            }
            if (callId > 0) {
                val outcome = when (dialStatus) {
                    "completed"  -> VoiceCallOutcome.OPERATOR_BRIDGED
                    "no-answer",
                    "busy",
                    "failed",
                    "canceled"   -> VoiceCallOutcome.OPERATOR_DECLINED
                    else         -> VoiceCallOutcome.OPERATOR_DECLINED
                }
                db.terminateCall(callId, outcome)
            }
            if (isUnknownCaller) {
                // Unknown callers hear nothing — just hang up silently.
                call.respondText(twiml("<Hangup/>"), ContentType.Text.Xml)
            } else {
                // Known customers hear a meaningful spoken message.
                val responseXml = when (dialStatus) {
                    "completed" -> "<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">Thank you. Goodbye.</Say>"
                    "no-answer" -> "<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">The operator did not answer. Please try again in a few minutes.</Say>"
                    "busy"      -> "<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">The operator is currently busy. Please try again in a few minutes.</Say>"
                    "failed"    -> "<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">Sorry, we could not reach the operator. Please try again later.</Say>"
                    "canceled"  -> "<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">The call was cancelled. Goodbye.</Say>"
                    else        -> "<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">Goodbye.</Say>"
                }
                call.respondText(customerTwiml(responseXml), ContentType.Text.Xml)
            }
        }
        get {
            // Twilio sometimes hits action URLs as GET
            val dialStatus   = call.request.queryParameters["DialCallStatus"]?.trim() ?: "unknown"
            val callId       = call.request.queryParameters["callId"]?.toIntOrNull() ?: -1
            val customerType = call.request.queryParameters["customerType"]?.trim() ?: "existing"
            val isUnknownCaller = customerType == "new"
            println("[VoiceRoutes/dial-status GET] callId=$callId DialCallStatus=$dialStatus customerType=$customerType")
            if (callId > 0) {
                val outcome = when (dialStatus) {
                    "completed"  -> VoiceCallOutcome.OPERATOR_BRIDGED
                    else         -> VoiceCallOutcome.OPERATOR_DECLINED
                }
                db.terminateCall(callId, outcome)
            }
            if (isUnknownCaller) {
                call.respondText(twiml("<Hangup/>"), ContentType.Text.Xml)
            } else {
                call.respondText(customerTwiml("<Say voice=\"Polly.Amy-Neural\" language=\"en-GB\">Goodbye.</Say>"), ContentType.Text.Xml)
            }
        }
    }

    // ── Twilio status callback (optional): close stale call rows ─────────────
    // Configure in Twilio Console → Phone Number → Voice → Status Callback URL

    route("/api/twilio/voice/status") {
        post {
            val params      = call.receiveParameters()
            val callSid     = params["CallSid"]?.trim() ?: return@post call.respond(HttpStatusCode.OK)
            val callStatus  = params["CallStatus"]?.trim() ?: ""
            val terminalStatuses = setOf("completed", "busy", "no-answer", "failed", "canceled")
            if (callStatus in terminalStatuses) {
                val record = db.getCallByTwilioSid(callSid)
                if (record != null && record.isActive) {
                    db.terminateCall(record.id, VoiceCallOutcome.OPERATOR_BRIDGED)
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// ── Operator whisper logic (shared between POST and GET) ─────────────────────

private suspend fun handleOperatorWhisper(call: ApplicationCall, db: DataBase, childCallSid: String? = null) {
    val callId       = call.request.queryParameters["callId"]?.toIntOrNull() ?: -1
    val customerType = call.request.queryParameters["customerType"] ?: "new"
    val bizName      = call.request.queryParameters["bizName"]
        ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        ?: "the shop"

    // Store the operator-leg (child) CallSid so the app's 🤝 button can redirect it.
    if (callId > 0 && !childCallSid.isNullOrBlank()) {
        runCatching { db.setCallOperatorLegSid(callId, childCallSid) }
    }

    val base = PublicBaseUrl.fromCall(call)

    // Both existing and new customers: play whisper, then bridge automatically.
    //
    // Twilio bridges the operator to the customer as soon as the whisper TwiML ends
    // (because the parent <Dial> uses answerOnBridge="true").
    //
    // The app's 🤝 button fires WHILE the whisper is playing (operatorLegSid is set),
    // and serves as a UI shortcut to the customer-detail / booking screen — it no longer
    // needs to make a Twilio REST API call because the bridge is already in progress.
    //
    // If the operator wants to DECLINE, they hang up their physical phone before
    // the whisper finishes, which triggers dial-status with no-answer/canceled.
    if (callId > 0) runCatching { db.updateCallState(callId, VoiceCallState.BRIDGED_TO_OPERATOR) }

    val whisperText = if (customerType == "existing") {
        "Incoming call for ${escapeForXml(bizName)}. Existing customer."
    } else {
        "Incoming call for ${escapeForXml(bizName)}. New customer. Intake required."
    }

    call.respondText(
        """<?xml version="1.0" encoding="UTF-8"?><Response><Say voice="Polly.Amy-Neural" language="en-GB">$whisperText</Say></Response>""",
        ContentType.Text.Xml,
    )
}

internal fun escapeForXml(input: String): String {
    return buildString {
        for (c in input) {
            when (c) {
                '&'  -> append("&amp;")
                '<'  -> append("&lt;")
                '>'  -> append("&gt;")
                '"'  -> append("&quot;")
                '\''  -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
