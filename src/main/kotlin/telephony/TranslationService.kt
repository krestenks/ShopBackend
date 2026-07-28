package telephony

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Configuration for the self-hosted translation model.
 *
 * The model runs on a box inside the tailnet (Ollama, LM Studio, llama.cpp, …) exposing an
 * OpenAI-compatible `/chat/completions` endpoint. [url] must include the API prefix, e.g.
 * `http://100.107.165.93:11434/v1`. The whole feature is disabled when [url] is blank.
 *
 * gemma2:9b is the default because in testing it produced clean, accurate Thai from Danish/English
 * with no Chinese/Devanagari script bleed (qwen2.5 drifts to Chinese; aya-expanse garbles meaning).
 */
data class TranslationConfig(
    val url: String,
    val model: String = "gemma2:9b",
    /** Per-request timeout. Translation runs in the background, so this can be generous. */
    val timeoutMs: Long = 45_000,
    /**
     * Translate source → English → target instead of source → target directly. The English
     * intermediate is markedly more accurate for Danish→Thai (both models understand English
     * better than Danish) and naturally covers English-speaking customers too.
     */
    val pivotThroughEnglish: Boolean = true,
    /**
     * If the final translation is unusable (wrong script, or the model replied conversationally
     * instead of translating), re-run the final hop this many times with a reinforced prompt and a
     * little randomness.
     */
    val maxCleanupRetries: Int = 2,
    /**
     * Use Ollama's native `/api/chat` with `think:false` instead of the OpenAI `/chat/completions`
     * endpoint. Reasoning models (e.g. the Gemma4 QAT variants) otherwise spend 20–30s emitting a
     * chain-of-thought before the answer; the OpenAI endpoint ignores `think`, so we must call the
     * native API to turn it off (drops a hop from ~30s to ~1.5s with identical output). Set false
     * for non-Ollama backends (LM Studio, llama.cpp), which have no `/api/chat`.
     */
    val ollamaThinkOff: Boolean = true,
)

/**
 * Translates inbound SMS text using a self-hosted, OpenAI-compatible LLM on the tailnet.
 *
 * Display-only: callers translate *inbound* customer messages into the shop staff's language so a
 * non-Danish-speaking manager can read them. The original message is never altered — the translation
 * is cached separately and shown alongside the original.
 *
 * All calls are best-effort: any failure (model down, timeout, non-translation) returns null and the
 * caller simply shows the untranslated original. Nothing here ever blocks sending or receiving.
 *
 * IMPORTANT: the instruction and the text are sent as a single *user* turn (not a system message).
 * gemma2 has no native system role, and with the instruction in a system message it replies
 * conversationally ("Please provide me with the message…") to short inputs instead of translating.
 */
class TranslationService(private val config: TranslationConfig) {

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeoutMs   // allow for a cold model load
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = config.timeoutMs
        }
    }

    /**
     * Serialises all model calls: the tailnet box runs a single Ollama instance, so firing many
     * translations at once (e.g. a thread backfill under a 5s app poll) just overloads it and every
     * request times out. One at a time keeps each call fast and reliable.
     */
    private val gate = Semaphore(1)

    /** ISO-639-1 code → human-readable name used in the prompt. Extend as needed. */
    private fun languageName(code: String): String = when (code.lowercase().trim()) {
        "th" -> "Thai"
        "da" -> "Danish"
        "en" -> "English"
        "de" -> "German"
        "sv" -> "Swedish"
        "no" -> "Norwegian"
        else -> code
    }

    /**
     * Translate [text] into the language given by [targetLangCode] (ISO-639-1, e.g. "th").
     * Returns the translated string, or null on failure, on blank/untranslatable input, or when the
     * text is already in the target language.
     */
    suspend fun translate(text: String, targetLangCode: String): String? = withContext(Dispatchers.IO) {
        val source = text.trim()
        val target = targetLangCode.lowercase().trim()
        if (source.isBlank() || target.isBlank()) return@withContext null

        // Nothing translatable (numbers / emoji / punctuation only) — showing the original is correct.
        if (source.none { Character.isLetter(it) }) return@withContext null

        // Already in the target script (e.g. a Thai customer texting a Thai-reading shop) — nothing to do.
        if (target == "th" && isMostlyThai(source)) return@withContext null

        // One model call at a time (see [gate]).
        gate.withPermit {
            // Stage 1: normalise any source language to English for a more accurate final hop.
            val english = if (config.pivotThroughEnglish && target != "en") {
                translateOnce(source, "en", reinforced = false, temperature = 0.0) ?: return@withPermit null
            } else {
                source
            }

            // Stage 2: English → target, retrying if the model didn't return a usable target-language result.
            var out = translateOnce(english, target, reinforced = false, temperature = 0.0)
            var attempt = 0
            while (attempt < config.maxCleanupRetries && !isUsable(out, target)) {
                attempt++
                // Retries must vary or they reproduce the same slip at temperature 0.
                out = translateOnce(english, target, reinforced = true, temperature = 0.5)
            }

            if (!isUsable(out, target)) null else out?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    /** One translation hop. Instruction + fenced text in a single user turn (see class note). */
    private suspend fun translateOnce(text: String, targetCode: String, reinforced: Boolean, temperature: Double): String? {
        val language = languageName(targetCode)
        val instruction = buildString {
            append("You are a translation engine. Translate the message between the <<< >>> markers into $language. ")
            append("Preserve names, numbers, dates, times and phone numbers exactly. ")
            append("If it is already in $language, repeat it unchanged. ")
            append("Reply with ONLY the $language translation and nothing else — no notes, no questions, ")
            append("no explanations, no quotes, no markers.")
            if (reinforced && targetCode == "th") {
                append(" Write the output entirely in Thai script; do NOT use Chinese, Japanese, Korean or any other foreign script.")
            }
        }
        val user = "$instruction\n\n<<<\n$text\n>>>"
        return chat(user, temperature)
    }

    /**
     * Is [out] a usable translation into [target]? Rejects nulls, and — for Thai — anything that
     * contains no Thai script (a conversational reply like "Please provide the message…", an echo, or
     * a wrong-language answer) or that bleeds a foreign script (Chinese/etc.).
     */
    private fun isUsable(out: String?, target: String): Boolean {
        val s = out?.trim()
        if (s.isNullOrBlank()) return false
        if (target != "th") return true
        return hasThai(s) && !hasForeignScript(s)
    }

    /** One chat call with a single user turn. Returns trimmed content or null. */
    private suspend fun chat(user: String, temperature: Double): String? =
        if (config.ollamaThinkOff) chatOllamaNative(user, temperature) else chatOpenAi(user, temperature)

    /** OpenAI-compatible `/chat/completions` call (portable; cannot disable thinking). */
    private suspend fun chatOpenAi(user: String, temperature: Double): String? {
        val requestBody = buildJsonObject {
            put("model", config.model)
            put("stream", false)
            put("temperature", temperature)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }
        return try {
            val responseBody = client.post("${config.url.trimEnd('/')}/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }.body<String>()

            Json.parseToJsonElement(responseBody)
                .jsonObject["choices"]?.jsonArray?.getOrNull(0)
                ?.jsonObject?.get("message")?.jsonObject?.get("content")
                ?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            println("[Translate] failed (${config.url}, model=${config.model}): ${e.message}")
            System.out.flush()
            null
        }
    }

    /**
     * Ollama native `/api/chat` with `think:false` — skips the reasoning pass. The base URL is the
     * config URL with any trailing `/v1` stripped (so `.../v1` → `.../api/chat`). The native response
     * shape is `{message:{content, thinking}}` (not `choices[]`).
     */
    private suspend fun chatOllamaNative(user: String, temperature: Double): String? {
        val base = config.url.trimEnd('/').removeSuffix("/v1")
        val requestBody = buildJsonObject {
            put("model", config.model)
            put("stream", false)
            put("think", false)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            put("options", buildJsonObject { put("temperature", temperature) })
        }
        return try {
            val responseBody = client.post("$base/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }.body<String>()

            Json.parseToJsonElement(responseBody)
                .jsonObject["message"]?.jsonObject?.get("content")
                ?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            println("[Translate] failed (native ${config.url}, model=${config.model}): ${e.message}")
            System.out.flush()
            null
        }
    }

    /** True if the text contains at least one Thai-script letter. */
    private fun hasThai(s: String): Boolean =
        s.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.THAI }

    /** True if the text is predominantly Thai script (so it doesn't need translating into Thai). */
    private fun isMostlyThai(s: String): Boolean {
        var letters = 0
        var thai = 0
        for (ch in s) {
            if (!Character.isLetter(ch)) continue
            letters++
            if (Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.THAI) thai++
        }
        return letters > 0 && thai.toDouble() / letters >= 0.6
    }

    /** True if the text contains letters from a script the Thai output should never contain. */
    private fun hasForeignScript(s: String): Boolean {
        val foreign = setOf(
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
            Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
            Character.UnicodeBlock.HIRAGANA,
            Character.UnicodeBlock.KATAKANA,
            Character.UnicodeBlock.HANGUL_SYLLABLES,
            Character.UnicodeBlock.HANGUL_JAMO,
            Character.UnicodeBlock.DEVANAGARI,
            Character.UnicodeBlock.CYRILLIC,
            Character.UnicodeBlock.ARABIC,
        )
        return s.any { ch -> Character.isLetter(ch) && Character.UnicodeBlock.of(ch) in foreign }
    }
}
