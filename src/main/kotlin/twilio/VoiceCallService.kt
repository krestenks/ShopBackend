package twilio

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.util.Base64

/**
 * Minimal Twilio Voice integration using Twilio's REST API directly (no Twilio SDK).
 *
 * Flow:
 * 1) Backend hits Twilio Calls API to start an outbound call to the customer.
 * 2) Twilio fetches TwiML from our /api/twilio/voice/ready endpoint.
 * 3) TwiML uses <Say> to read out a message (TTS).
 */
class TwilioVoiceCallService(
    private val accountSid: String,
    private val authToken: String,
    private val fromNumber: String,
    private val publicBaseUrl: String,
) {

    private val client = HttpClient(CIO)

    data class CallResult(
        val success: Boolean,
        val status: Int,
        val body: String,
    )

    /**
     * Initiates a real two-way call by:
     * 1. Calling the manager (operator) phone FROM the Twilio number.
     * 2. When the manager answers, TwiML dials the customer and bridges them.
     * The customer sees the shop's Twilio number as the caller ID.
     */
    suspend fun directCallCustomer(
        managerPhoneE164: String,
        customerPhoneE164: String,
    ): CallResult {
        if (accountSid.isBlank() || authToken.isBlank() || fromNumber.isBlank()) {
            return CallResult(false, 500, "Twilio not configured (missing TWILIO_* env vars)")
        }

        val url = "https://api.twilio.com/2010-04-01/Accounts/$accountSid/Calls.json"

        // When the manager answers we serve TwiML that dials the customer.
        val bridgeUrl = buildString {
            append(publicBaseUrl.trimEnd('/'))
            append("/api/twilio/voice/bridge")
            append("?to=")
            append(java.net.URLEncoder.encode(customerPhoneE164, Charsets.UTF_8))
        }

        val basic = Base64.getEncoder().encodeToString("$accountSid:$authToken".toByteArray(Charsets.UTF_8))

        val resp: HttpResponse = client.post(url) {
            header(HttpHeaders.Authorization, "Basic $basic")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    "To"   to managerPhoneE164,
                    "From" to fromNumber,
                    "Url"  to bridgeUrl,
                ).formUrlEncode()
            )
        }

        val body = resp.bodyAsText()
        return CallResult(resp.status.value in 200..299, resp.status.value, body)
    }

    suspend fun callCustomer(toPhoneE164: String, message: String, appointmentId: Int? = null): CallResult {
        if (accountSid.isBlank() || authToken.isBlank() || fromNumber.isBlank()) {
            return CallResult(false, 500, "Twilio not configured (missing TWILIO_* env vars)")
        }

        val url = "https://api.twilio.com/2010-04-01/Accounts/$accountSid/Calls.json"

        val callbackUrl = buildString {
            append(publicBaseUrl.trimEnd('/'))
            append("/api/twilio/voice/ready")
            // Message is passed through query params; keep it short-ish.
            append("?msg=")
            append(java.net.URLEncoder.encode(message, Charsets.UTF_8))
            if (appointmentId != null) {
                append("&appointment_id=")
                append(appointmentId)
            }
        }

        // Status callback lets us reliably mark calls inactive when Twilio considers them finished.
        // Twilio will POST CallSid + CallStatus etc.
        val statusCallbackUrl = publicBaseUrl.trimEnd('/') + "/api/twilio/voice/status"

        // Basic Auth: AccountSID:AuthToken
        val basic = Base64.getEncoder().encodeToString("$accountSid:$authToken".toByteArray(Charsets.UTF_8))

        val resp: HttpResponse = client.post(url) {
            header(HttpHeaders.Authorization, "Basic $basic")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    "To" to toPhoneE164,
                    "From" to fromNumber,
                    "Url" to callbackUrl,
                    "StatusCallback" to statusCallbackUrl,
                    "StatusCallbackMethod" to "POST",
                    "StatusCallbackEvent" to "initiated ringing answered completed",
                ).formUrlEncode()
            )
        }

        val body = resp.bodyAsText()
        return CallResult(resp.status.value in 200..299, resp.status.value, body)
    }
}
