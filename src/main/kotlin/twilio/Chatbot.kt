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
    val lmStudioModel: String = "essentialai/rnj-1"
)

data class ChatMessage(val role: String, val content: String)

class TwilioChatbotService(private val db: DataBase, private val config: ChatbotConfig) {
    private val history = mutableMapOf<String, MutableList<ChatMessage>>()
    private val client = HttpClient(CIO)
    private val initializedConversations = mutableSetOf<String>()
    // Store pending booking details for confirmation detection
    private val pendingBookings = mutableMapOf<String, MutableMap<String, String?>>()

    private val confirmationKeywords = listOf(
        "yes", "yes please", "yes!", "yes!", "correct", "that's correct",
        "book it", "book now", "book this", "proceed", "go ahead",
        "confirm", "confirmed", "sure", "absolutely", "sounds good", "that works",
        "ok", "okay", "kk", "fine", "great"
    )

    suspend fun processMessage(fromPhone: String, message: String, shopId: Int = 1): String {
        val phone = if (fromPhone.contains(":")) fromPhone.substringAfter(":") else fromPhone
        val convKey = "$phone:$shopId"
        val conv = history.getOrPut(convKey) { mutableListOf() }

        // First message: return welcome message directly, then initialize history
        if (!initializedConversations.contains(convKey)) {
            val welcomeMessage = getWelcomeMessage(shopId)
            
            // Initialize history AFTER returning welcome
            conv.add(ChatMessage("system", getSystemPrompt(shopId)))
            initializedConversations.add(convKey)
            conv.add(ChatMessage("assistant", welcomeMessage))
            
            return welcomeMessage
        }

        // Check if user is confirming a booking (looking at last assistant message)
        val userMessage = message.lowercase().trim()
        val isConfirmation = confirmationKeywords.any { userMessage.contains(it) }
        
        if (isConfirmation) {
            val pending = pendingBookings[convKey]
            if (pending != null) {
                // Auto-confirm the booking
                println("[AutoBooking] Detected confirmation, triggering book_appointment tool")
                val toolResult = executeBookAppointment(pending, shopId)
                pendingBookings.remove(convKey)
                
                // Parse the result and return confirmation message
                try {
                    val resultJson = Json.parseToJsonElement(toolResult).jsonObject
                    val data = resultJson["data"]?.jsonObject
                    if (data?.get("success")?.jsonPrimitive?.content == "true") {
                        val confId = data["confirmationId"]?.jsonPrimitive?.content ?: "unknown"
                        return "✅ Booking confirmed!\n\nConfirmation ID: $confId\n\nThank you for booking!"
                    } else {
                        val error = data?.get("error")?.jsonPrimitive?.content ?: "Unknown error"
                        return "Booking failed: $error\n\nPlease try again."
                    }
                } catch (e: Exception) {
                    return "Booking processed, but couldn't display confirmation details."
                }
            }
        }

        conv.add(ChatMessage("user", message))
        val response = callLLM(conv, shopId, convKey)
        
        // Store pending booking details from the conversation
        extractAndStorePendingBooking(conv, convKey, shopId)
        
        return response
    }

    private fun extractAndStorePendingBooking(conv: List<ChatMessage>, convKey: String, shopId: Int) {
        val pending = mutableMapOf<String, String?>()
        
        // Look through conversation for booking info
        for (msg in conv) {
            val content = msg.content.lowercase()
            
            // Extract therapist
            if (content.contains("bimi")) {
                val emp = db.getEmployeesForShop(shopId).find { it.name.equals("Bimi", ignoreCase = true) }
                if (emp != null) pending["therapist"] = emp.id.toString()
            } else if (content.contains("bill")) {
                val emp = db.getEmployeesForShop(shopId).find { it.name.equals("Bill", ignoreCase = true) }
                if (emp != null) pending["therapist"] = emp.id.toString()
            }
            
            // Extract service
            if (content.contains("b2b") || content.contains("b2b massage")) {
                pending["treatment"] = "B2B Massage"
            } else if (content.contains("massage")) {
                pending["treatment"] = "Massage"
            } else if (content.contains("neck")) {
                pending["treatment"] = "Neck massage"
            }
            
            // Extract time - handle 23:00, 23.00, 2300 formats and convert to HH:mm
            val timeRegex = Regex("""(\d{1,2})[.:](\d{2})""")
            val timeMatch = timeRegex.find(content)
            if (timeMatch != null) {
                val hour = timeMatch.groupValues[1]
                val minute = timeMatch.groupValues[2]
                pending["time"] = "$hour:$minute"
                println("[AutoBooking] Extracted time: $hour:$minute")
            } else if (content.contains("today")) {
                pending["time"] = "today"
            }
        }
        
        // Only store if we have at least therapist and some info
        if (pending.isNotEmpty() && pending.containsKey("therapist")) {
            pendingBookings[convKey] = pending
            println("[AutoBooking] Stored pending booking: $pending")
        }
    }

    private fun executeBookAppointment(pending: Map<String, String?>, shopId: Int): String {
        val therapistId = pending["therapist"] ?: return """{"error":"No therapist","success":false}"""
        val treatment = pending["treatment"] ?: "Massage"
        val time = pending["time"] ?: ""
        
        // Determine the actual time
        val today = LocalDate.now().toString()
        val startIso = if (time.contains("today")) {
            // Use current time or default
            "$today 23:00"
        } else if (time.isNotEmpty() && time.contains(":")) {
            "$today $time"
        } else {
            "$today 23:00"
        }
        
        // Get duration for the treatment
        val duration = try {
            val services = db.getServicesForEmployee(therapistId.toInt())
            services.find { it.name.equals(treatment, ignoreCase = true) }?.duration ?: 60
        } catch (e: Exception) {
            60
        }
        
        return bookAppointment(therapistId, null, treatment, startIso, duration, shopId)
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
        
        // Unescape the JSON string (LLM returns escaped quotes)
        val unescapedContent = content
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .trim()
        
        // Find all potential tool call JSON objects
        val indices = findJsonObjectIndices(unescapedContent)
        
        for ((start, end) in indices) {
            val jsonStr = unescapedContent.substring(start, end)
            try {
                val json = Json.parseToJsonElement(jsonStr).jsonObject
                
                // Check for "tool" format
                val toolName = json["tool"]?.jsonPrimitive?.content
                val argsElement = json["arguments"]
                
                // Or check for "name" format (parallel/OpenAI style)
                val name = if (toolName == null) json["name"]?.jsonPrimitive?.content else null
                val finalToolName = toolName ?: name
                
                if (finalToolName != null && argsElement != null) {
                    // argsElement is already a JsonObject - pass it directly!
                    val argsJson = argsElement.jsonObject
                    val result = executeTool(finalToolName, argsJson, shopId)
                    results.add(result)
                    println("[Tool] Executed $finalToolName: $result")
                }
            } catch (e: Exception) {
                println("[Parse] Skipping invalid JSON: ${jsonStr.take(100)}")
            }
        }
        
        return results
    }

    // Find all balanced JSON objects in the content
    private fun findJsonObjectIndices(content: String): List<Pair<Int, Int>> {
        val results = mutableListOf<Pair<Int, Int>>()
        var i = 0
        
        while (i < content.length) {
            if (content[i] == '{') {
                val end = findMatchingBrace(content, i)
                if (end > i) {
                    results.add(Pair(i, end + 1))
                    i = end + 1
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        
        return results
    }

    // Find the index of the matching closing brace
    private fun findMatchingBrace(s: String, openIndex: Int): Int {
        var depth = 0
        var inString = false
        var i = openIndex
        
        while (i < s.length) {
            val c = s[i]
            
            if (c == '"' && (i == 0 || s[i - 1] != '\\')) {
                inString = !inString
            } else if (!inString) {
                if (c == '{') depth++
                if (c == '}') {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun executeTool(toolName: String, args: JsonObject, shopId: Int): String {
        return try {
            // Helper to get string from args with flexible key names
            fun getArg(key1: String, key2: String? = null): String? {
                return args[key1]?.jsonPrimitive?.content ?: key2?.let { args[it]?.jsonPrimitive?.content }
            }
            
            when (toolName) {
                "list_therapists" -> {
                    val therapists = getTherapists(shopId)
                    """{"tool":"list_therapists","data":$therapists}"""
                }
                "get_therapist_details" -> {
                    val therapistId = getArg("therapistId", "therapist_id")
                        ?: return """{"error":"Missing therapistId"}"""
                    val details = getTherapistDetails(therapistId)
                    """{"tool":"get_therapist_details","data":$details}"""
                }
                "get_therapist_availability" -> {
                    var therapistIdInput = getArg("therapistId", "therapist_id") ?: getArg("therapist")
                    // If therapist is a name (not ID), look it up
                    val therapistId = therapistIdInput?.toIntOrNull() ?: therapistIdInput?.let { name ->
                        db.getEmployeesForShop(shopId).find { emp -> 
                            emp.name.equals(name, ignoreCase = true) 
                        }?.id
                    } ?: return """{"error":"Missing therapist"}"""
                    
                    val dateIso = getArg("dateIso", "date") ?: LocalDate.now().toString()
                    val durationMinutes = getArg("durationMinutes")?.toInt() ?: 60
                    val result = db.getTherapistAvailabilityResult(therapistId, dateIso, durationMinutes, shopId)
                    """{"tool":"get_therapist_availability","data":$result}"""
                }
                "find_joint_availability" -> {
                    val therapistIdsStr = getArg("therapistIds", "therapist_ids")
                        ?: return """{"error":"Missing therapistIds"}"""
                    val therapistIds = therapistIdsStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                    val dateIso = getArg("dateIso", "date") ?: LocalDate.now().toString()
                    val durationMinutes = getArg("durationMinutes")?.toInt() ?: 60
                    val result = db.getJointAvailabilityResult(therapistIds, shopId, dateIso, durationMinutes)
                    """{"tool":"find_joint_availability","data":$result}"""
                }
                "list_open_time_slots" -> {
                    val dateIso = getArg("dateIso", "date") ?: LocalDate.now().toString()
                    val durationMinutes = getArg("durationMinutes")?.toInt() ?: 60
                    val slots = listOpenTimeSlots(dateIso, durationMinutes)
                    """{"tool":"list_open_time_slots","data":$slots}"""
                }
                "book_appointment" -> {
                    // Accept flexible parameter names
                    var therapistIdInput = getArg("therapistId", "therapist_id") ?: getArg("therapist")
                    val customerName = getArg("customerName")
                    val serviceIdInput = getArg("service_id", "serviceId")?.toIntOrNull()
                    val treatment = getArg("treatment") ?: getArg("service")
                    val startIso = getArg("startIso") ?: getArg("start_iso") ?: getArg("date_time") ?: run {
                        val date = getArg("date", "dateIso")
                        val time = getArg("time", "timeIso")
                        if (date != null && time != null) "$date $time" else null
                    }
                    val durationMinutes = getArg("durationMinutes")?.toInt() ?: getArg("duration")?.toInt()
                    
                    // If therapist is a name (not ID), look it up
                    val therapistId = therapistIdInput?.toIntOrNull()?.toString() ?: therapistIdInput?.let { name ->
                        db.getEmployeesForShop(shopId).find { emp -> 
                            emp.name.equals(name, ignoreCase = true) 
                        }?.id?.toString()
                    }
                    
                    // If service_id is provided, look up the service
                    var finalTreatment: String? = treatment
                    var finalDuration: Int? = durationMinutes
                    if (serviceIdInput != null && therapistId != null) {
                        val services = db.getServicesForEmployee(therapistId.toInt())
                        val service = services.find { it.id == serviceIdInput }
                        if (service != null) {
                            finalTreatment = service.name
                            finalDuration = service.duration
                        }
                    }
                    
                    // Clean up treatment name if still needed
                    val cleanTreatment = finalTreatment?.let { raw ->
                        val empId = therapistId?.toIntOrNull()
                        if (empId != null) {
                            val services = db.getServicesForEmployee(empId)
                            val match = services.find { s -> 
                                raw.contains(s.name, ignoreCase = true) || 
                                s.name.equals(raw, ignoreCase = true)
                            }
                            match?.name ?: raw
                        } else raw
                    }
                    
                    // Validation
                    if (therapistId == null) {
                        return """{"error":"Missing therapist"}"""
                    }
                    if (cleanTreatment == null) {
                        val availableServices = db.getServicesForEmployee(therapistId.toInt()).joinToString(", ") { it.name }
                        return """{"error":"ASK FIRST: Which treatment? Available: $availableServices"}"""
                    }
                    if (startIso == null) {
                        return """{"error":"ASK FIRST: When? Please provide date and time like: 2024-03-15 20:00"}"""
                    }
                    if (finalDuration == null) {
                        val matchingService = db.getServicesForEmployee(therapistId.toInt()).find { s -> 
                            s.name.equals(cleanTreatment, ignoreCase = true) 
                        }
                        if (matchingService != null) {
                            finalDuration = matchingService.duration
                        } else {
                            return """{"error":"ASK FIRST: How long? The $cleanTreatment is available in different durations."}"""
                        }
                    }
                    
                    val confirmation = bookAppointment(therapistId, customerName, cleanTreatment!!, startIso, finalDuration!!, shopId)
                    """{"tool":"book_appointment","data":$confirmation}"""
                }
                "show_menu" -> {
                    val menu = generateTherapistMenu(shopId)
                    """{"tool":"show_menu","data":"$menu"}"""
                }
                "book_multi_appointment" -> {
                    val customerName = getArg("customerName")
                    val appointmentsJson = getArg("appointments")
                        ?: return """{"error":"ASK FIRST: Ask customer for all booking details before calling this tool."}"""
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
        val servicesJson = services.joinToString(",") { s -> 
            """{"id":${s.id},"name":"${s.name}","price":${s.price},"duration":${s.duration}}"""
        }
        return """{"therapistId":"$therapistId","name":"${emp.name}","specialities":[],"services":[$servicesJson]}"""
    }

    private fun getTherapistAvailability(therapistId: String, dateIso: String, durationMinutes: Int, shopId: Int): String {
        val id = therapistId.toIntOrNull() ?: return """{"error":"Invalid therapistId"}"""
        
        // Get slots summary from database helper
        val available = db.getAvailableTimeSlotsSummary(id, shopId, dateIso, durationMinutes)
        
        return """{"therapistId":"$therapistId","dateIso":"$dateIso","durationMinutes":$durationMinutes,"available":"$available"}"""
    }

    private fun findJointAvailability(therapistIds: List<String>, dateIso: String, durationMinutes: Int, shopId: Int): String {
        // Find common slots across all therapists
        var common: MutableList<String>? = null
        for (id in therapistIds) {
            val intId = id.toIntOrNull() ?: continue
            val slots = db.getAvailableTimeSlots(intId, shopId, dateIso, durationMinutes)
            val slotSet = mutableSetOf<String>()
            for (s in slots) slotSet.add(s)
            if (common == null) {
                common = ArrayList()
                for (s in slotSet) common.add(s)
            } else {
                val newCommon = ArrayList<String>()
                for (c in common) {
                    if (slotSet.contains(c)) newCommon.add(c)
                }
                common = newCommon
            }
        }
        val allSlots = common ?: emptyList<String>()
        
        // Summarize instead of listing all
        val count = allSlots.size
        val firstSlot = if (allSlots.isNotEmpty()) allSlots[0].replace(" ", "T") else "none"
        val lastSlot = if (allSlots.isNotEmpty()) allSlots[allSlots.size - 1].replace(" ", "T") else "none"
        
        val therapistIdsJson = therapistIds.joinToString(",") { "\"$it\"" }
        return "{\"therapistIds\":[$therapistIdsJson],\"dateIso\":\"$dateIso\",\"durationMinutes\":$durationMinutes,\"available\":\"$count slots from $firstSlot to $lastSlot\"}"
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

    private fun getSystemPrompt(shopId: Int): String {
        return """
Booking assistant for massage studio. Your job is to help customers book appointments.

CRITICAL RULES:
1. When customer says YES, CORRECT, BOOK IT, CONFIRM, or any confirmation words -> IMMEDIATELY call the book_appointment tool with all the booking details. Do NOT ask again if they already confirmed!
2. After booking tool returns success -> Tell customer "Booking confirmed!" with the details
3. Do NOT say "booking confirmed" until AFTER the tool returns success
4. If tool returns error -> Handle it appropriately (ask for different time, etc.)

Available tools:
- list_therapists: List all therapists
- get_therapist_details: Get details for a specific therapist
- get_therapist_availability: Get available time slots for a therapist
- list_open_time_slots: List open time slots
- book_appointment: Book an appointment (THIS IS THE FINAL STEP!)
- show_menu: Show the massage menu with services and prices

Booking flow:
1. Ask for therapist name (or let customer pick from menu)
2. Ask for service/treatment name
3. Ask for date and time
4. Ask customer to confirm (yes/correct)
5. Call book_appointment tool IMMEDIATELY when they confirm
6. Share confirmation details from tool result

Remember: Once customer confirms, call book_appointment tool - don't ask again!
        """.trimIndent()
    }

    fun generateTherapistMenu(shopId: Int = 1): String {
        val employees = db.getEmployeesForShop(shopId)
        if (employees.isEmpty()) {
            return "No therapists available at the moment."
        }
        
        val sb = StringBuilder()
        sb.appendLine("=== MASSAGE MENU ===")
        sb.appendLine()
        
        for (emp in employees) {
            sb.appendLine("👩‍⚕️ ${emp.name}")
            val services = db.getServicesForEmployee(emp.id)
            if (services.isEmpty()) {
                sb.appendLine("  • No services available")
            } else {
                for (svc in services) {
                    sb.appendLine("  • ${svc.name} - ${svc.duration} min - ${svc.price.toInt()} kr")
                }
            }
            sb.appendLine()
        }
        
        sb.appendLine("To book, tell me:")
        sb.appendLine("1. Therapist name")
        sb.appendLine("2. Service you want")
        sb.appendLine("3. Preferred date and time")
        
        return sb.toString()
    }

    private fun getWelcomeMessage(shopId: Int = 1): String {
        val menu = generateTherapistMenu(shopId)
        return "Welcome!\n\nI'm the booking assistant for our massage studio.\n\n$menu\nHow can I help you today?"
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
