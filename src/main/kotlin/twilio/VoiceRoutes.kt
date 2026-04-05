package twilio

import DataBase
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import PublicBaseUrl

/**
 * Twilio Voice endpoints.
 *
 * - /api/twilio/voice/ready : TwiML that reads out a message.
 * - /api/twilio/voice/welcome : Incoming call webhook (TTS + gather)
 * - /api/twilio/voice/menu : Handle keypad input (press 1 => send booking link by SMS)
 */
fun Route.twilioVoiceRoutes(db: DataBase) {
    // db kept to mirror other twilio routes and to allow future lookups/logging.

    val smsService = TwilioSmsService(
        accountSid = System.getenv("TWILIO_ACCOUNT_SID") ?: "",
        authToken = System.getenv("TWILIO_AUTH_TOKEN") ?: "",
    )

    fun twiml(xmlInsideResponse: String): String =
        """<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response>$xmlInsideResponse</Response>"""

    fun isShopOpenNow(shopId: Int): Boolean {
        // Uses system timezone (server) for now. If we need per-shop timezone later, we can add it.
        val now = java.time.ZonedDateTime.now()
        val dow = now.dayOfWeek.value // 1=Mon..7=Sun
        db.ensureDefaultShopOpeningHours(shopId)
        val row = db.getShopOpeningHours(shopId).firstOrNull { it.dayOfWeek == dow } ?: return true
        if (row.closed) return false
        val minutes = now.hour * 60 + now.minute
        return minutes in row.openMinute until row.closeMinute
    }

    /**
     * Twilio will request this URL when the outbound call is connected.
     * We return TwiML with a TTS message.
     *
     * Security note: Twilio signature validation is not implemented here.
     * Consider validating X-Twilio-Signature in production.
     */
    route("/api/twilio/voice/ready") {
        get {
            val message = call.request.queryParameters["msg"]?.take(400)
                ?: "Hello. You can come to the door now."

            call.respondText(
                twiml("<Say voice=\"alice\">${escapeForXml(message)}</Say>"),
                ContentType.Text.Xml,
            )
        }
        post {
            // Twilio sometimes POSTs when configured for webhooks.
            val params = call.receiveParameters()
            val message = (params["msg"] ?: call.request.queryParameters["msg"])?.take(400)
                ?: "Hello. You can come to the door now."

            call.respondText(
                twiml("<Say voice=\"alice\">${escapeForXml(message)}</Say>"),
                ContentType.Text.Xml,
            )
        }
    }

    /**
     * Incoming call webhook.
     *
     * Twilio will POST this endpoint when a call comes in.
     * We speak a welcome message (open/closed) and offer keypad menu.
     */
    route("/api/twilio/voice/welcome") {
        post {
            val params = call.receiveParameters()
            val to = params["To"] ?: ""

            val shopId = db.findShopIdByTwilioNumber(to) ?: 1
            val voice = db.getShopVoiceConfig(shopId)

            val open = isShopOpenNow(shopId)
            val welcome = if (open) voice.welcomeOpenMessage else voice.welcomeClosedMessage

            val gatherAction = "${PublicBaseUrl.fromCall(call)}/api/twilio/voice/menu"
            val xml = """
                <Say voice=\"alice\">${escapeForXml(welcome)}</Say>
                <Gather numDigits=\"1\" action=\"${escapeForXml(gatherAction)}\" method=\"POST\">
                  <Say voice=\"alice\">Press 1 to receive a booking link by SMS. Press 2 to talk to the operator.</Say>
                </Gather>
                <Say voice=\"alice\">Goodbye.</Say>
            """.trimIndent()

            call.respondText(twiml(xml), ContentType.Text.Xml)
        }
        get {
            // convenience for manual browser testing
            val to = call.request.queryParameters["To"] ?: ""
            val shopId = db.findShopIdByTwilioNumber(to) ?: 1
            val voice = db.getShopVoiceConfig(shopId)
            val open = isShopOpenNow(shopId)
            val welcome = if (open) voice.welcomeOpenMessage else voice.welcomeClosedMessage

            val gatherAction = "${PublicBaseUrl.fromCall(call)}/api/twilio/voice/menu"
            val xml = """
                <Say voice=\"alice\">${escapeForXml(welcome)}</Say>
                <Gather numDigits=\"1\" action=\"${escapeForXml(gatherAction)}\" method=\"POST\">
                  <Say voice=\"alice\">Press 1 to receive a booking link by SMS. Press 2 to talk to the operator.</Say>
                </Gather>
                <Say voice=\"alice\">Goodbye.</Say>
            """.trimIndent()

            call.respondText(twiml(xml), ContentType.Text.Xml)
        }
    }

    /**
     * Handles keypad input from /welcome <Gather>.
     *
     * If Digits==1: generate a booking link for that shop and SMS it to the caller.
     */
    route("/api/twilio/voice/menu") {
        post {
            val params = call.receiveParameters()
            val digits = params["Digits"]?.trim()
            val from = params["From"]?.trim().orEmpty()
            val to = params["To"]?.trim().orEmpty()

            val shopId = db.findShopIdByTwilioNumber(to) ?: 1
            val voice = db.getShopVoiceConfig(shopId)
            val fromNumber = voice.twilioNumber?.takeIf { it.isNotBlank() }
                ?: (System.getenv("TWILIO_FROM_NUMBER") ?: "")

            if (from.isBlank()) {
                call.respondText(
                    twiml("<Say voice=\"alice\">Sorry, we could not read your number.</Say>"),
                    ContentType.Text.Xml
                )
                return@post
            }

            if (digits == "2") {
                val operator = voice.operatorPhone?.trim().orEmpty()
                if (!isShopOpenNow(shopId)) {
                    call.respondText(
                        twiml("<Say voice=\"alice\">Sorry, we are currently closed.</Say>"),
                        ContentType.Text.Xml
                    )
                    return@post
                }
                if (operator.isBlank()) {
                    call.respondText(
                        twiml("<Say voice=\"alice\">Sorry, call forwarding is not available.</Say>"),
                        ContentType.Text.Xml
                    )
                    return@post
                }

                // Safe default: show shop number as callerId to the operator.
                val callerId = fromNumber
                val xml = """
                    <Say voice=\"alice\">Please wait while we connect you.</Say>
                    <Dial callerId=\"${escapeForXml(callerId)}\"><Number>${escapeForXml(operator)}</Number></Dial>
                """.trimIndent()
                call.respondText(twiml(xml), ContentType.Text.Xml)
                return@post
            }

            if (digits != "1") {
                call.respondText(
                    twiml("<Say voice=\"alice\">Sorry, I did not understand.</Say>"),
                    ContentType.Text.Xml
                )
                return@post
            }

            // Ensure customer and generate token
            val customerId = db.ensureCustomerByPhone(from)
            val token = db.generateBookingToken(customerId, shopId, from)
            val bookingUrl = "${PublicBaseUrl.fromCall(call)}/api/book?token=$token"

            val smsText = "Booking link: $bookingUrl"
            val sms = smsService.sendSms(
                fromNumberE164 = fromNumber,
                toNumberE164 = from,
                bodyText = smsText,
            )

            if (!sms.success) {
                println("[TwilioSMS] Failed: status=${sms.status} body=${sms.body}")
                call.respondText(
                    twiml("<Say voice=\"alice\">Sorry, we could not send the SMS right now.</Say>"),
                    ContentType.Text.Xml
                )
                return@post
            }

            call.respondText(
                twiml("<Say voice=\"alice\">We have sent you a booking link by SMS. Goodbye.</Say>"),
                ContentType.Text.Xml
            )
        }
    }
}

internal fun escapeForXml(input: String): String {
    return buildString {
        for (c in input) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
