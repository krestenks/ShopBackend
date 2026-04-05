package twilio

import DataBase
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Twilio Voice endpoints.
 *
 * - /api/twilio/voice/ready : TwiML that reads out a message.
 */
fun Route.twilioVoiceRoutes(@Suppress("UNUSED_PARAMETER") db: DataBase) {
    // db kept to mirror other twilio routes and to allow future lookups/logging.
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
                """<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Say voice=\"alice\">${escapeForXml(message)}</Say></Response>""",
                ContentType.Text.Xml,
            )
        }
        post {
            // Twilio sometimes POSTs when configured for webhooks.
            val params = call.receiveParameters()
            val message = (params["msg"] ?: call.request.queryParameters["msg"])?.take(400)
                ?: "Hello. You can come to the door now."

            call.respondText(
                """<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Say voice=\"alice\">${escapeForXml(message)}</Say></Response>""",
                ContentType.Text.Xml,
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
