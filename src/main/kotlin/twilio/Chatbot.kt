package twilio

import DataBase
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.withContext

@Serializable
data class ChatbotConfig(
    val twilioAccountSid: String = "",
    val twilioAuthToken: String = "",
    val twilioFromNumber: String = "",
    val lmStudioUrl: String = "http://localhost:1234/v1",
    val lmStudioModel: String = "llama-3-groq-8b-tool-use"
)

data class ChatMessage(val role: String, val content: String)

class TwilioChatbotService(private val db: DataBase, private val config: ChatbotConfig) {
    private val history = mutableMapOf<String, MutableList<ChatMessage>>()
    private val client = HttpClient(CIO)
    private val initializedConversations = mutableSetOf<String>()

    suspend fun processMessage(fromPhone: String, message: String, shopId: Int = 1): String {
        val phone = if (fromPhone.contains(":")) fromPhone.substringAfter(":") else fromPhone
        val convKey = "$phone:$shopId"
        val conv = history.getOrPut(convKey) { mutableListOf() }

        // First message: initialize with system prompt AND force therapist list
        if (!initializedConversations.contains(convKey)) {
            conv.add(ChatMessage("system", getSystemPrompt(shopId)))
            initializedConversations.add(convKey)
            
            // Force fetch therapists so LLM can't hallucinate
            val therapists = getTherapists(shopId)
            conv.add(ChatMessage("tool", """{"tool":"list_therapists","data":$therapists}"""))
        }

        conv.add(ChatMessage("user", message))
        return callLLM(conv, shopId, convKey)
    }

    private suspend fun callLLM(messages: List<ChatMessage>, shopId: Int = 1, convKey: String): String = withContext(Dispatchers.IO) {
        try {
            val url = "${config.lmStudioUrl}/chat/completions"
            val requestBody = buildJsonObject {
                put("model", config.lmStudioModel)
                put("messages", JsonArray(messages.map { msg ->
                    buildJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    }
                }))
                put("max_tokens", 512)
                put("temperature", 0.1)
            }

            println("[LLM] Calling $url")
            val responseBody = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }.body<String>()

            println("[LLM] Response: $responseBody")

            val json = Json.parseToJsonElement(responseBody)
            val content = json.jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: "[LLM Error: No content]"

            // Handle ALL tool calls in the response
            val toolResults = parseAndExecuteAllTools(content, shopId)
            
            if (toolResults.isNotEmpty()) {
                // Feed all tool results back to LLM
                val updatedMessages = messages.toMutableList()
                updatedMessages.add(ChatMessage("assistant", content))
                for (result in toolResults) {
                    updatedMessages.add(ChatMessage("tool", result))
                }
                return@withContext callLLM(updatedMessages, shopId, convKey)
            }
            
            // No tool calls, return as chat response
            content
        } catch (e: Exception) {
            println("[LLM] Error: ${e.message}")
            "Sorry, I'm having trouble connecting to the AI. Please try again."
        }
    }

    private fun parseAndExecuteAllTools(content: String, shopId: Int): List<String> {
        val results = mutableListOf<String>()
        
        // Try parallel format: {"Name": "tool_name", "arguments": {...}}
        val parallelPattern = """\{"name":\s*"([^"]+)",\s*"arguments":\s*(\{[^{}]*(?:\{[^{}]*\}[^{}]*)*)\}""".toRegex()
        val parallelMatches = parallelPattern.findAll(content).toList()
        
        if (parallelMatches.isNotEmpty()) {
            parallelMatches.forEach { match ->
                val toolName = match.groupValues[1]
                val argsJson = match.groupValues[2]
                val result = executeTool(toolName, argsJson, shopId)
                results.add(result)
                println("[Tool] Executed $toolName: $result")
            }
            return results
        }
        
        // Try sequential format: {"tool":"...","arguments":{...}}
        val sequentialPattern = """\{"tool":\s*"([^"]+)",\s*"arguments":\s*(\{[^{}]*(?:\{[^{}]*\}[^{}]*)*)\}""".toRegex()
        val sequentialMatches = sequentialPattern.findAll(content).toList()
        
        if (sequentialMatches.isNotEmpty()) {
            sequentialMatches.forEach { match ->
                val toolName = match.groupValues[1]
                val argsJson = match.groupValues[2]
                val result = executeTool(toolName, argsJson, shopId)
                results.add(result)
                println("[Tool] Executed $toolName: $result")
            }
            return results
        }
        
        return results
    }

    private fun executeTool(toolName: String, argsJson: String, shopId: Int): String {
        return try {
            val args = Json.parseToJsonElement("{$argsJson}").jsonObject
            when (toolName) {
                "list_therapists" -> {
                    val therapists = getTherapists(shopId)
                    """{"tool":"list_therapists","data":$therapists}"""
                }
                "get_therapist_details" -> {
                    val therapistId = args["therapistId"]?.jsonPrimitive?.content
                        ?: return """{"error":"Missing therapistId"}"""
                    val details = getTherapistDetails(therapistId)
                    """{"tool":"get_therapist_details","data":$details}"""
                }
                "get_therapist_availability" -> {
                    val therapistId = args["therapistId"]?.jsonPrimitive?.content
                        ?: return """{"error":"Missing therapistId"}"""
                    val dateIso = args["dateIso"]?.jsonPrimitive?.content ?: LocalDate.now().toString()
                    val durationMinutes = args["durationMinutes"]?.jsonPrimitive?.content?.toInt() ?: 60
                    val slots = getTherapistAvailability(therapistId, dateIso, durationMinutes, shopId)
                    """{"tool":"get_therapist_availability","data":$slots}"""
                }
                "find_joint_availability" -> {
                    val therapistIdsStr = args["therapistIds"]?.jsonPrimitive?.content
                        ?: return """{"error":"Missing therapistIds"}"""
                    val therapistIds = therapistIdsStr.split(",").map { it.trim() }
                    val dateIso = args["dateIso"]?.jsonPrimitive?.content ?: LocalDate.now().toString()
                    val durationMinutes = args["durationMinutes"]?.jsonPrimitive?.content?.toInt() ?: 60
                    val slots = findJointAvailability(therapistIds, dateIso, durationMinutes, shopId)
                    """{"tool":"find_joint_availability","data":$slots}"""
                }
                "list_open_time_slots" -> {
                    val dateIso = args["dateIso"]?.jsonPrimitive?.content ?: LocalDate.now().toString()
                    val durationMinutes = args["durationMinutes"]?.jsonPrimitive?.content?.toInt() ?: 60
                    val slots = listOpenTimeSlots(dateIso, durationMinutes)
                    """{"tool":"list_open_time_slots","data":$slots}"""
                }
                "book_appointment" -> {
                    val therapistId = args["therapistId"]?.jsonPrimitive?.content
                    val customerName = args["customerName"]?.jsonPrimitive?.content
                    val treatment = args["treatment"]?.jsonPrimitive?.content
                        ?: return """{"error":"Missing treatment"}"""
                    val startIso = args["startIso"]?.jsonPrimitive?.content
                        ?: return """{"error":"Missing startIso"}"""
                    val durationMinutes = args["durationMinutes"]?.jsonPrimitive?.content?.toInt() ?: 60
                    val confirmation = bookAppointment(therapistId, customerName, treatment, startIso, durationMinutes, shopId)
                    """{"tool":"book_appointment","data":$confirmation}"""
                }
                "book_multi_appointment" -> {
                    val customerName = args["customerName"]?.jsonPrimitive?.content
                    val appointmentsJson = args["appointments"]?.jsonPrimitive?.content
                        ?: return """{"error":"Missing appointments"}"""
                    val confirmations = bookMultiAppointment(appointmentsJson, customerName, shopId)
                    """{"tool":"book_multi_appointment","data":$confirmations}"""
                }
                else -> """{"error":"Unknown tool: $toolName"}"""
            }
        } catch (e: Exception) {
            """{"error":"Failed to execute $toolName: ${e.message}"}"""
        }
    }

    private fun getTherapists(shopId: Int): String {
        val emps = db.getEmployeesForShop(shopId).take(5)
        return "[" + emps.map { """{"therapistId":"${it.id}","name":"${it.name}"}""" }.joinToString() + "]"
    }

    private fun getTherapistDetails(therapistId: String): String {
        val id = therapistId.toIntOrNull() ?: return """{"error":"Invalid therapistId"}"""
        val emp = db.getEmployeeById(id) ?: return """{"error":"Therapist not found"}"""
        val services = db.getServicesForEmployee(id)
        return """{"therapistId":"$therapistId","name":"${emp.name}","specialities":[],"services":[${services.map { """{"id":${it.id},"name":"${it.name}","price":${it.price},"duration":${it.duration}}""" }.joinToString()}]}"""
    }

    private fun getTherapistAvailability(therapistId: String, dateIso: String, durationMinutes: Int, shopId: Int): String {
        val id = therapistId.toIntOrNull() ?: return """{"error":"Invalid therapistId"}"""
        val slots = db.getAvailableTimeSlots(id, shopId, dateIso, durationMinutes)
        val slotObjects = slots.map { """{"startIso":"$it","endIso":"${it.replace(" ", "T")}"}""" }.joinToString()
        return """{"therapistId":"$therapistId","dateIso":"$dateIso","durationMinutes":$durationMinutes,"slots":[$slotObjects]}"""
    }

    private fun findJointAvailability(therapistIds: List<String>, dateIso: String, durationMinutes: Int, shopId: Int): String {
        val allSlots = therapistIds.mapNotNull { id ->
            val slots = db.getAvailableTimeSlots(id.toIntOrNull() ?: return@mapNotNull null, shopId, dateIso, durationMinutes).toSet()
            slots
        }.reduceOrNull { acc, set -> acc.intersect(set) } ?: emptySet()
        
        val slotObjects = allSlots.map { """{"startIso":"$it","endIso":"${it.replace(" ", "T")}"}""" }.joinToString()
        return """{"therapistIds":${therapistIds.map { "\"$it\"" }},"dateIso":"$dateIso","slots":[$slotObjects]}"""
    }

    private fun listOpenTimeSlots(dateIso: String, durationMinutes: Int): String {
        val slots = listOf("${dateIso}T10:00", "${dateIso}T13:00", "${dateIso}T15:00")
        val slotObjects = slots.map { """{"startIso":"$it","endIso":"$it"}""" }.joinToString()
        return """{"dateIso":"$dateIso","durationMinutes":$durationMinutes,"slots":[$slotObjects]}"""
    }

    private fun bookAppointment(therapistId: String?, customerName: String?, treatment: String, startIso: String, durationMinutes: Int, shopId: Int): String {
        return try {
            val epochMilli = try {
                LocalDateTime.parse(startIso.replace(" ", "T"))
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (e: Exception) {
                return """{"error":"Invalid date format: $startIso"}"""
            }
            
            val empId = therapistId?.toIntOrNull() ?: return """{"error":"Missing therapistId"}"""
            val customerPhone = "unknown"
            
            if (db.isAppointmentOverlapping(empId, epochMilli, durationMinutes)) {
                """{"error":"Time slot is no longer available","success":false}"""
            } else {
                val customerId = db.ensureCustomerByPhone(customerPhone)
                val appointmentId = db.addAppointment(empId, shopId, customerId, epochMilli, durationMinutes)
                """{"success":true,"confirmationId":"b-$appointmentId","message":"Booking confirmed!"}"""
            }
        } catch (e: Exception) {
            """{"error":"${e.message}","success":false}"""
        }
    }

    private fun bookMultiAppointment(appointmentsJson: String, customerName: String?, shopId: Int): String {
        return try {
            val appointments = Json.parseToJsonElement(appointmentsJson).jsonArray
            val confirmations = mutableListOf<String>()
            
            for (appt in appointments) {
                val obj = appt.jsonObject
                val therapistId = obj["therapistId"]?.jsonPrimitive?.content
                val treatment = obj["treatment"]?.jsonPrimitive?.content ?: continue
                val startIso = obj["startIso"]?.jsonPrimitive?.content ?: continue
                val durationMinutes = obj["durationMinutes"]?.jsonPrimitive?.content?.toInt() ?: 60
                
                val result = bookAppointment(therapistId, customerName, treatment, startIso, durationMinutes, shopId)
                confirmations.add(result)
            }
            
            """{"confirmationIds":${confirmations.joinToString(",")}}"""
        } catch (e: Exception) {
            """{"error":"${e.message}"}"""
        }
    }

    private fun getSystemPrompt(shopId: Int = 1): String {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        
        return """
You are a booking assistant for a massage studio that offers massage and other services.

Context:
- The customer is contacting shopId=$shopId. Do not ask which shop.
- Today's date is $today (ISO format, local to the shop).

Interpret:
- "today" as $today
- "tomorrow" as $tomorrow

CRITICAL RULES:
- Never invent therapists, services, or availability. Use tools.
- Never list therapist names unless they came from list_therapists tool output.
- Never say a time is "with a therapist" unless it came from get_therapist_availability for that therapist.
- Never claim a booking is confirmed unless book_appointment returns a confirmationId.

Tool usage:
If you need to call a tool, respond with ONLY:
{"tool":"TOOL_NAME","arguments":{...}}
No other text.

Available tools:
1. {"tool":"list_therapists","arguments":{"shopId":"$shopId"}}
   For: who works here?, list therapists, staff, team, etc.

2. {"tool":"get_therapist_details","arguments":{"therapistId":"1"}}
   For: what can [name] do?, [name]'s details, therapist info.

3. {"tool":"get_therapist_availability","arguments":{"therapistId":"1","dateIso":"$today","durationMinutes":60}}
   For: is [name] available?, [name]'s schedule, free times for [name].

4. {"tool":"find_joint_availability","arguments":{"therapistIds":["1","2"],"dateIso":"$today","durationMinutes":60}}
   For: find time when multiple therapists available.

5. {"tool":"list_open_time_slots","arguments":{"dateIso":"$today","durationMinutes":60}}
   For: general availability, when can I book?

6. {"tool":"book_appointment","arguments":{"therapistId":"1","customerName":"John","treatment":"Massage","startIso":"$today T10:00","durationMinutes":60}}
   For: book an appointment, confirm booking, complete booking.

7. {"tool":"book_multi_appointment","arguments":{"customerName":"John","appointments":[{"therapistId":"1","treatment":"Massage","startIso":"$today T10:00","durationMinutes":60}]}}
   For: book multiple therapists simultaneously.

Flow:
- Show therapists + specialities based on tools.
- Ask for treatment, date, and time.
- Verify availability with tools before booking.
- Book only after confirming an available slot.
        """.trimIndent()
    }

    // Public helper functions for testing
    public fun getTherapistsPublic(shopId: Int): String = getTherapists(shopId)
}

fun Route.twilioRoutes(db: DataBase, service: TwilioChatbotService) {
    post("/api/twilio/webhook") {
        val params = call.receiveParameters()
        val from = params["From"] ?: call.request.headers["X-Twilio-From"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing From")
        val body = params["Body"] ?: call.request.headers["X-Twilio-Body"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing Body")

        println("[Twilio] From $from: $body")

        val response = service.processMessage(from, body)
        println("[Twilio] Response: $response")

        call.respondText("""<?xml version="1.0"?><Response><Message>${response}</Message></Response>""", ContentType.Text.Xml)
    }

    get("/api/twilio/health") {
        call.respondText("OK")
    }
}
