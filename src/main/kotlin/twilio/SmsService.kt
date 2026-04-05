package twilio

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.util.Base64

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
    )

    suspend fun sendSms(fromNumberE164: String, toNumberE164: String, bodyText: String): SmsResult {
        if (accountSid.isBlank() || authToken.isBlank()) {
            return SmsResult(false, 500, "Twilio not configured (missing TWILIO_ACCOUNT_SID/TWILIO_AUTH_TOKEN)")
        }
        if (fromNumberE164.isBlank()) return SmsResult(false, 400, "Missing From number")
        if (toNumberE164.isBlank()) return SmsResult(false, 400, "Missing To number")

        val url = "https://api.twilio.com/2010-04-01/Accounts/$accountSid/Messages.json"
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
        return SmsResult(resp.status.value in 200..299, resp.status.value, body)
    }
}
