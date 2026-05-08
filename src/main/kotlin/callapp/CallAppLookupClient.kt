package callapp

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ─── Public exceptions ────────────────────────────────────────────────────────

class InvalidPhoneNumberException(message: String) : Exception(message)

// ─── Public result / config types ────────────────────────────────────────────

data class CallAppLookupResult(
    val found: Boolean,
    val name: String?,
    val priority: Int?,
    val status: Boolean,
    val message: String?,
    val timestamp: Long?,
    val rawJson: String?,
)

data class CallAppRapidApiConfig(
    val apiKey: String,
    val host: String    = "callapp.p.rapidapi.com",
    val baseUrl: String = "https://callapp.p.rapidapi.com",
    val timeoutMs: Long = 1_200,
)

// ─── Interface ────────────────────────────────────────────────────────────────

interface CallAppLookupClient {
    /**
     * Look up a Danish phone number.
     *
     * @param phoneNumber a Danish phone in any common format:
     *   "+4551941736", "+45 51 94 17 36", "004551941736", "4551941736"
     * @throws InvalidPhoneNumberException if the number cannot be validated as a Danish 8-digit number.
     * @throws Exception for network / timeout failures — callers decide fail-open or fail-closed.
     */
    suspend fun lookup(phoneNumber: String): CallAppLookupResult
}

// ─── Normalization / validation ───────────────────────────────────────────────

/**
 * Parses a Danish phone number into (countryCode, nationalNumber).
 * Only Danish numbers are supported: country code "45" + exactly 8 national digits.
 *
 * Accepted input examples:
 *   "+4551941736"  → ("45", "51941736")
 *   "+45 51 94 17 36"
 *   "004551941736"
 *   "4551941736"
 */
internal fun parseDanishPhone(raw: String): Pair<String, String> {
    // 1. Strip whitespace
    var s = raw.trim()
    // 2. Remove spaces, hyphens, parentheses
    s = s.replace(Regex("[\\s\\-()]"), "")
    // 3. Remove optional leading "+"
    s = s.removePrefix("+")
    // 4. Remove leading "00" prefix
    if (s.startsWith("00")) s = s.removePrefix("00")

    // 5. Validate Danish: must be exactly "45" + 8 digits = 10 digits total
    if (!s.matches(Regex("^45\\d{8}$"))) {
        throw InvalidPhoneNumberException(
            "Not a valid Danish phone number: '$raw' (normalized: '$s'). Expected 45 + 8 digits."
        )
    }

    return Pair("45", s.substring(2))
}

// ─── kotlinx.serialization DTOs ──────────────────────────────────────────────

@Serializable
internal data class CallAppApiResponseDto(
    val status: Boolean = false,
    val message: String? = null,
    val timestamp: Long? = null,
    val data: CallAppDataDto? = null,
)

@Serializable
internal data class CallAppDataDto(
    val name: String? = null,
    val priority: Int? = null,
)

private val responseJson = Json { ignoreUnknownKeys = true }

// ─── Implementation ───────────────────────────────────────────────────────────

class CallAppRapidApiClient(
    private val config: CallAppRapidApiConfig,
) : CallAppLookupClient {

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeoutMs
            connectTimeoutMillis = config.timeoutMs
            socketTimeoutMillis  = config.timeoutMs
        }
    }

    override suspend fun lookup(phoneNumber: String): CallAppLookupResult {
        val (code, number) = parseDanishPhone(phoneNumber)  // throws InvalidPhoneNumberException

        val response: HttpResponse = client.get("${config.baseUrl}/api/v1/search") {
            parameter("code",   code)
            parameter("number", number)
            header("X-RapidAPI-Key",  config.apiKey)   // never logged
            header("X-RapidAPI-Host", config.host)
        }

        val rawBody = response.bodyAsText()

        if (!response.status.isSuccess()) {
            return CallAppLookupResult(
                found    = false,
                name     = null,
                priority = null,
                status   = false,
                message  = "HTTP ${response.status.value}: ${response.status.description}",
                timestamp = null,
                rawJson  = rawBody,
            )
        }

        // Parse JSON — let malformed JSON exceptions bubble up so callers decide
        val dto = responseJson.decodeFromString(CallAppApiResponseDto.serializer(), rawBody)

        val resolvedName = dto.data?.name?.trim()?.takeIf { it.isNotBlank() }
        val found = dto.status && resolvedName != null

        return CallAppLookupResult(
            found     = found,
            name      = resolvedName,
            priority  = dto.data?.priority,
            status    = dto.status,
            message   = dto.message,
            timestamp = dto.timestamp,
            rawJson   = rawBody,
        )
    }
}
