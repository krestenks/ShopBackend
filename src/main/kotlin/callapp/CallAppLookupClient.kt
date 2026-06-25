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
     * Look up a phone number by its international E.164 representation.
     *
     * @param phoneNumber any E.164 format: "+4551941736", "+66812345678", "+966512345678", etc.
     * @throws InvalidPhoneNumberException if the number cannot be parsed into a recognized
     *   country-code + national-number pair.
     * @throws Exception for network / timeout failures — callers decide fail-open or fail-closed.
     */
    suspend fun lookup(phoneNumber: String): CallAppLookupResult
}

// ─── Phone number parsing ─────────────────────────────────────────────────────

/**
 * Parses any international phone number in E.164 format (+CC…) into (countryCode, nationalNumber).
 *
 * Uses **longest-match-first** (3 digits → 2 → 1) against the complete ITU-T calling code list.
 * This correctly handles Danish (+45), Thai (+66), all Arab country codes (+966, +971, +974, …),
 * and the vast majority of international numbers.
 *
 * @throws InvalidPhoneNumberException if the number has no recognizable calling code.
 */
internal fun parsePhoneForCallApp(raw: String): Pair<String, String> {
    var s = raw.trim().replace(Regex("[\\s\\-()]"), "")
    // Normalise: strip leading "00" or "+"
    if (s.startsWith("00")) s = s.removePrefix("00")
    if (s.startsWith("+"))  s = s.removePrefix("+")

    if (s.isBlank() || !s.all { it.isDigit() }) {
        throw InvalidPhoneNumberException("Not a valid phone number (non-digit characters): '$raw'")
    }
    if (s.length < 7) {
        throw InvalidPhoneNumberException("Too short to be a valid phone number: '$raw'")
    }

    // Longest-match-first: try 3-digit CC, then 2-digit, then 1-digit.
    // Require at least 4 national digits after the country code.
    for (len in 3 downTo 1) {
        if (s.length >= len + 4) {
            val candidate = s.substring(0, len)
            if (candidate in CALLING_CODES) {
                return Pair(candidate, s.substring(len))
            }
        }
    }

    throw InvalidPhoneNumberException(
        "Cannot determine country calling code for '$raw' (normalised: '$s'). " +
        "Check that the number is in full international format."
    )
}

/**
 * Complete ITU-T calling code list.
 * 3-digit codes are listed explicitly so the longest-match check fires before any 2-digit prefix
 * (e.g. +966 Saudi Arabia must match "966" before falling through to 2-digit "96").
 */
internal val CALLING_CODES: Set<String> = setOf(
    // ── 1-digit ──────────────────────────────────────────────────────────────
    "1",  // NANP (USA, Canada, Caribbean)
    "7",  // Russia / Kazakhstan

    // ── 2-digit ──────────────────────────────────────────────────────────────
    "20", // Egypt
    "27", // South Africa
    "30", // Greece
    "31", // Netherlands
    "32", // Belgium
    "33", // France
    "34", // Spain
    "36", // Hungary
    "39", // Italy
    "40", // Romania
    "41", // Switzerland
    "43", // Austria
    "44", // United Kingdom
    "45", // Denmark ✓
    "46", // Sweden
    "47", // Norway
    "48", // Poland
    "49", // Germany
    "51", // Peru
    "52", // Mexico
    "53", // Cuba
    "54", // Argentina
    "55", // Brazil
    "56", // Chile
    "57", // Colombia
    "58", // Venezuela
    "60", // Malaysia
    "61", // Australia
    "62", // Indonesia
    "63", // Philippines
    "64", // New Zealand
    "65", // Singapore
    "66", // Thailand ✓
    "81", // Japan
    "82", // South Korea
    "84", // Vietnam
    "86", // China
    "90", // Turkey
    "91", // India
    "92", // Pakistan
    "93", // Afghanistan
    "94", // Sri Lanka
    "95", // Myanmar
    "98", // Iran

    // ── 3-digit (Africa, Americas, Middle East, Pacific, European micro-states) ──
    "211", // South Sudan
    "212", // Morocco ✓
    "213", // Algeria ✓
    "216", // Tunisia ✓
    "218", // Libya ✓
    "220", // Gambia
    "221", // Senegal
    "222", // Mauritania
    "223", // Mali
    "224", // Guinea
    "225", // Côte d'Ivoire
    "226", // Burkina Faso
    "227", // Niger
    "228", // Togo
    "229", // Benin
    "230", // Mauritius
    "231", // Liberia
    "232", // Sierra Leone
    "233", // Ghana
    "234", // Nigeria
    "235", // Chad
    "236", // Central African Republic
    "237", // Cameroon
    "238", // Cape Verde
    "239", // São Tomé and Príncipe
    "240", // Equatorial Guinea
    "241", // Gabon
    "242", // Congo
    "243", // DR Congo
    "244", // Angola
    "245", // Guinea-Bissau
    "246", // British Indian Ocean Territory
    "247", // Ascension Island
    "248", // Seychelles
    "249", // Sudan
    "250", // Rwanda
    "251", // Ethiopia
    "252", // Somalia
    "253", // Djibouti
    "254", // Kenya
    "255", // Tanzania
    "256", // Uganda
    "257", // Burundi
    "258", // Mozambique
    "260", // Zambia
    "261", // Madagascar
    "262", // Réunion / Mayotte
    "263", // Zimbabwe
    "264", // Namibia
    "265", // Malawi
    "266", // Lesotho
    "267", // Botswana
    "268", // Eswatini (Swaziland)
    "269", // Comoros
    "290", // St. Helena
    "291", // Eritrea
    "297", // Aruba
    "298", // Faroe Islands
    "299", // Greenland
    "350", // Gibraltar
    "351", // Portugal
    "352", // Luxembourg
    "353", // Ireland
    "354", // Iceland
    "355", // Albania
    "356", // Malta
    "357", // Cyprus
    "358", // Finland
    "359", // Bulgaria
    "370", // Lithuania
    "371", // Latvia
    "372", // Estonia
    "373", // Moldova
    "374", // Armenia
    "375", // Belarus
    "376", // Andorra
    "377", // Monaco
    "378", // San Marino
    "380", // Ukraine
    "381", // Serbia
    "382", // Montenegro
    "383", // Kosovo
    "385", // Croatia
    "386", // Slovenia
    "387", // Bosnia and Herzegovina
    "389", // North Macedonia
    "420", // Czech Republic
    "421", // Slovakia
    "423", // Liechtenstein
    "500", // Falkland Islands
    "501", // Belize
    "502", // Guatemala
    "503", // El Salvador
    "504", // Honduras
    "505", // Nicaragua
    "506", // Costa Rica
    "507", // Panama
    "508", // St. Pierre and Miquelon
    "509", // Haiti
    "590", // Guadeloupe
    "591", // Bolivia
    "592", // Guyana
    "593", // Ecuador
    "594", // French Guiana
    "595", // Paraguay
    "596", // Martinique
    "597", // Suriname
    "598", // Uruguay
    "599", // Netherlands Antilles / Curaçao
    "670", // East Timor
    "672", // Norfolk Island
    "673", // Brunei
    "674", // Nauru
    "675", // Papua New Guinea
    "676", // Tonga
    "677", // Solomon Islands
    "678", // Vanuatu
    "679", // Fiji
    "680", // Palau
    "681", // Wallis and Futuna
    "682", // Cook Islands
    "683", // Niue
    "685", // Samoa
    "686", // Kiribati
    "687", // New Caledonia
    "688", // Tuvalu
    "689", // French Polynesia
    "690", // Tokelau
    "691", // Micronesia
    "692", // Marshall Islands
    "850", // North Korea
    "852", // Hong Kong
    "853", // Macau
    "855", // Cambodia
    "856", // Laos
    "880", // Bangladesh
    "886", // Taiwan
    "960", // Maldives
    "961", // Lebanon ✓
    "962", // Jordan ✓
    "963", // Syria ✓
    "964", // Iraq ✓
    "965", // Kuwait ✓
    "966", // Saudi Arabia ✓
    "967", // Yemen ✓
    "968", // Oman ✓
    "970", // Palestine ✓
    "971", // UAE ✓
    "972", // Israel ✓
    "973", // Bahrain ✓
    "974", // Qatar ✓
    "975", // Bhutan
    "976", // Mongolia
    "977", // Nepal
    "992", // Tajikistan
    "993", // Turkmenistan
    "994", // Azerbaijan
    "995", // Georgia
    "996", // Kyrgyzstan
    "998", // Uzbekistan
)

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
        val (code, number) = parsePhoneForCallApp(phoneNumber)  // throws InvalidPhoneNumberException

        val response: HttpResponse = client.get("${config.baseUrl}/api/v1/search") {
            parameter("code",   code)
            parameter("number", number)
            header("X-RapidAPI-Key",  config.apiKey)   // never logged
            header("X-RapidAPI-Host", config.host)
        }

        // Explicitly use UTF-8 regardless of what the Content-Type header says.
        // Some API gateways omit or mis-report charset, which would cause Arabic/Thai
        // multi-byte sequences to be decoded as Latin-1 and silently corrupted.
        val rawBody = response.bodyAsText(Charsets.UTF_8)
        val httpStatus = response.status.value

        if (!response.status.isSuccess()) {
            return CallAppLookupResult(
                found    = false,
                name     = null,
                priority = null,
                status   = false,
                message  = "HTTP $httpStatus: ${response.status.description}",
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
