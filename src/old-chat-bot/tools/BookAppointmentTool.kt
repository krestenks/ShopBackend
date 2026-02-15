package tools

import domain.BookingRequest
import domain.BookingService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.serializersModuleOf

class BookAppointmentTool(
    private val bookingService: BookingService
) : Tool {

    override val name = "book_appointment"
    override val description = "Book an appointment given customer treatment, and start time ISO."
    override val jsonSchema = """
 {
  "type": "object",
  "properties": {
    "therapistId": { "type": "string" },
    "customerName": { "type": "string" },
    "treatment": { "type": "string" },
    "startIso": { "type": "string" },
    "durationMinutes": {
      "type": "integer",
      "description": "Duration of the treatment in minutes (e.g. 30, 60)"
    }
  },
  "required": ["treatment", "startIso", "durationMinutes"]
}
  """.trimIndent()

    override suspend fun invoke(argumentsJson: String): String {
        val obj = Json.parseToJsonElement(argumentsJson).jsonObject

        val therapistId = obj["therapistId"]?.jsonPrimitive?.content
        val customerName = obj["customerName"]?.jsonPrimitive?.content
        val treatment = obj["treatment"]!!.jsonPrimitive.content
        val startIso = obj["startIso"]!!.jsonPrimitive.content

        val durationMinutes = obj["durationMinutes"]?.jsonPrimitive?.content?.toInt()
            ?: 60 // sensible default if the model forgot

        val req = BookingRequest(
            therapistId = therapistId,
            customerName = customerName,
            treatment = treatment,
            startIso = startIso,
            durationMinutes = durationMinutes
        )

        val confirmation = bookingService.book(req)
        return Json.encodeToString(confirmation)
    }
}
