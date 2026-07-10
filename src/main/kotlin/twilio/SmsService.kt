package twilio

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.util.Base64

/**
 * Base URL (through the `/2010-04-01` API version) for Twilio's REST API.
 *
 * Defaults to the **Ireland region, Dublin edge** endpoint `api.dublin.ie1.twilio.com`, because all
 * of our numbers live in that Twilio Region. Sending from an IE1 number via a different region's
 * host fails with error 21663 ("'From' phone number routing configuration is incorrect"). Override
 * with `TWILIO_API_HOST` (e.g. `api.twilio.com` for the global/US endpoint, or a different edge).
 *
 * NOTE: the older region-only host `api.ie1.twilio.com` was retired on 2026-04-28 — use the
 * edge-location form (`api.<edge>.ie1.twilio.com`). Webhooks are unaffected (they point at our backend).
 */
internal fun twilioApiBase(): String {
    val host = System.getenv("TWILIO_API_HOST")?.trim()?.takeIf { it.isNotBlank() } ?: "api.dublin.ie1.twilio.com"
    return "https://$host/2010-04-01"
}

/**
 * Minimal Twilio SMS sender using Twilio's REST API directly (no Twilio SDK).
 */
class TwilioSmsService(
    private val accountSid: String,
    private val authToken: String,
) {
    private val client = HttpClient(CIO)

    data class SmsResult(
        val success: Boolean,
        val status: Int,
        val body: String,
        val messageSid: String? = null,
        val errorMessage: String? = null,
    )

    suspend fun sendSms(fromNumberE164: String, toNumberE164: String, bodyText: String): SmsResult {
        return try {
            if (accountSid.isBlank() || authToken.isBlank()) {
                return SmsResult(false, 500, "Twilio not configured (missing TWILIO_ACCOUNT_SID/TWILIO_AUTH_TOKEN)")
            }
            if (fromNumberE164.isBlank()) return SmsResult(false, 400, "Missing From number")
            if (toNumberE164.isBlank()) return SmsResult(false, 400, "Missing To number")

            val url = "${twilioApiBase()}/Accounts/$accountSid/Messages.json"
            val basic = Base64.getEncoder().encodeToString("$accountSid:$authToken".toByteArray(Charsets.UTF_8))

            val resp: HttpResponse = client.post(url) {
                header(HttpHeaders.Authorization, "Basic $basic")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    listOf(
                        "To" to toNumberE164,
                        "From" to fromNumberE164,
                        "Body" to bodyText,
                    ).formUrlEncode()
                )
            }

            val body = resp.bodyAsText()
            val ok   = resp.status.value in 200..299
            // Extract SID from Twilio JSON response  e.g. {"sid":"SMxxx",...}
            val sid  = if (ok) Regex(""""sid"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) else null
            val errMsg = if (!ok) body.take(200) else null
            SmsResult(ok, resp.status.value, body, messageSid = sid, errorMessage = errMsg)
        } catch (e: Exception) {
            SmsResult(false, 500, "Exception sending SMS: ${e.message}", errorMessage = e.message)
        }
    }
}
