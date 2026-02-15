package chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import llm.ModelBackend
import tools.ToolRegistry
import java.time.LocalDate

class ChatOrchestrator(
    private val model: ModelBackend,
    private val store: ConversationStore,
    private val tools: ToolRegistry
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun handleUserMessage(ctx: ConversationContext, userText: String): String {
        val conversationId = ctx.conversationId
        val history = store.get(conversationId)

        if (history.isEmpty()) {
            store.append(
                conversationId,
                ChatMessage(
                    role = "system",
                    content = buildSystemPrompt(ctx)
                )
            )

            // Forced startup: fetch therapists so the model cannot hallucinate names
            forceToolCall(
                conversationId = conversationId,
                toolName = "list_therapists",
                argsJson = """{"shopId":"${ctx.shopId}"}"""
            )
        }

        store.append(conversationId, ChatMessage(role = "user", content = userText))

        // Forced availability checks (heuristic): if user mentions date/time, fetch availability first
        maybeForceAvailability(conversationId, ctx, userText)

        // Main loop: model -> (optional) tools -> model -> ...
        for (i in 0 until 8) {
            val reply = model.generate(store.get(conversationId), tools.specs())

            if (reply.toolCalls.isNotEmpty()) {
                for (call in reply.toolCalls) {
                    val tool = tools.get(call.name)

                    val patchedArgs = if (call.name == "list_therapists") {
                        ensureShopId(call.argumentsJson, ctx.shopId)
                    } else {
                        call.argumentsJson
                    }

                    val result = if (tool == null) {
                        """{"error":"Unknown tool: ${call.name}"}"""
                    } else {
                        println("[Calling tool: ${tool.name}]")
                        tool.invoke(patchedArgs)
                    }

                    store.append(
                        conversationId,
                        ChatMessage(
                            role = "tool",
                            name = call.name,
                            toolCallId = call.id,
                            content = result
                        )
                    )
                }
                // Continue: ask the model again with tool results in the history
                continue
            }

            val text = (reply.assistantText ?: "").trim()
            store.append(conversationId, ChatMessage(role = "assistant", content = text))
            return text
        }

        return "Sorry — I got stuck. Could you rephrase your request?"
    }

    private fun buildSystemPrompt(ctx: ConversationContext): String {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)

        return """
You are a booking assistant for a massage studio that offers massage and other services.
You can reply in English only. If addressed in any other language ask the customer to switch to English.

Context:
- The customer is contacting shopId=${ctx.shopId}. Do not ask which shop.
- Today's date is $today (ISO format, local to the shop).
Interpret:
- "today" as $today
- "tomorrow" as $tomorrow

CRITICAL RULES:
- Never invent therapists, services, or availability. Use tools.
- Never list therapist names unless they came from list_therapists tool output.
- Never say a time is "with a therapist" unless it came from get_therapist_availability for that therapist.
- Never claim a booking is confirmed unless book_appointment returns a confirmationId.

Tool usage rules:
- If you need to call a tool, respond with ONLY:
{"name":"TOOL_NAME","arguments":{...}}
</tool_call>
No other text.

Availability rules:
- If a therapist is specified or selected, use get_therapist_availability(therapistId, dateIso).
- Only use list_open_time_slots(dateIso) when no therapist is specified.
- If duration is mentioned, you MUST use durationMinutes accordingly (15/30/45/60/90).
- If booking an appointment, you MUST include durationMinutes.
- If the customer does not specify a duration, assume 30 minutes.
- If the customer wants two treatments done quickly in parallel (e.g. foot + neck), you MUST:
  1) select two therapists with matching specialities,
  2) call find_joint_availability(therapistIds, dateIso, durationMinutes),
  3) then call book_multi_appointment with two appointments at the same start time.

Flow:
- Show therapists + specialities based on tools.
- Ask for treatment, date, and time.
- If the user requests a time, verify availability with tools.
- Book only after confirming an available slot.
    """.trimIndent()
    }

    private suspend fun maybeForceAvailability(conversationId: String, ctx: ConversationContext, userText: String) {
        val dateIso = detectDateIso(userText) ?: return
        val therapistId = detectTherapistIdFromHistory(conversationId, userText)

        if (therapistId != null) {
            forceToolCall(
                conversationId = conversationId,
                toolName = "get_therapist_availability",
                argsJson = """{"therapistId":"$therapistId","dateIso":"$dateIso"}"""
            )
        } else {
            forceToolCall(
                conversationId = conversationId,
                toolName = "list_open_time_slots",
                argsJson = """{"dateIso":"$dateIso"}"""
            )
        }
    }

    private fun detectDateIso(text: String): String? {
        val lower = text.lowercase()

        // Interpret "today"/"tomorrow"
        if (lower.contains("today")) return LocalDate.now().toString()
        if (lower.contains("tomorrow")) return LocalDate.now().plusDays(1).toString()

        // Look for an ISO date in the message: YYYY-MM-DD
        val isoRegex = Regex("""\b(20\d{2}-\d{2}-\d{2})\b""")
        val match = isoRegex.find(text)
        if (match != null) return match.groupValues[1]

        // If no date clue, do not force availability
        return null
    }

    private fun detectTherapistIdFromHistory(conversationId: String, userText: String): String? {
        // Best-effort: if user mentions a therapist name, map it using the last tool output
        // We’ll scan history for the latest list_therapists tool result and see if any names match.

        val lower = userText.lowercase()
        val history = store.get(conversationId)

        // Find the most recent list_therapists tool message
        val toolMsg = history.asReversed().firstOrNull { it.role == "tool" && it.name == "list_therapists" }
            ?: return null

        // Tool message content is JSON array of therapists
        return try {
            val arr = json.parseToJsonElement(toolMsg.content)
            val therapists = arr.jsonArray

            for (t in therapists) {
                val obj = t.jsonObject
                val therapistId = obj["therapistId"]?.toString()?.trim('"') ?: continue
                val name = obj["name"]?.toString()?.trim('"') ?: continue
                if (lower.contains(name.lowercase())) {
                    return therapistId
                }
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun forceToolCall(conversationId: String, toolName: String, argsJson: String) {
        val tool = tools.get(toolName) ?: return
        println("[Calling tool: ${tool.name}]")
        val result = tool.invoke(argsJson)
        store.append(
            conversationId,
            ChatMessage(
                role = "tool",
                name = toolName,
                toolCallId = "forced-${toolName}-${System.currentTimeMillis()}",
                content = result
            )
        )
    }

    private fun ensureShopId(argumentsJson: String, shopId: String): String {
        val obj: JsonObject = try {
            json.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Exception) {
            return buildJsonObject { put("shopId", JsonPrimitive(shopId)) }.toString()
        }

        if (obj.containsKey("shopId")) {
            return obj.toString()
        }

        val merged = buildJsonObject {
            put("shopId", JsonPrimitive(shopId))
            for ((k, v) in obj) {
                put(k, v)
            }
        }

        return merged.toString()
    }
}
