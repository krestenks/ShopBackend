package tools

import domain.BookingService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TimeSlotsTool(
    private val bookingService: BookingService
) : Tool {

    override val name = "list_open_time_slots"
    override val description = "List available general time slots for a given date (ISO date) and duration in minutes."
    override val jsonSchema = """
    {
      "type": "object",
      "properties": {
        "dateIso": {
          "type": "string",
          "description": "Date in ISO format, e.g. 2026-02-07"
        },
        "durationMinutes": {
          "type": "integer",
          "description": "Desired duration in minutes (e.g. 30, 60)"
        }
      },
      "required": ["dateIso", "durationMinutes"]
    }
  """.trimIndent()

    override suspend fun invoke(argumentsJson: String): String {
        val obj = Json.parseToJsonElement(argumentsJson).jsonObject
        val dateIso = obj["dateIso"]!!.jsonPrimitive.content

        val durationMinutes = obj["durationMinutes"]?.jsonPrimitive?.content?.toInt()
            ?: 60

        val slots = bookingService.listOpenTimeSlots(dateIso, durationMinutes)
        return Json.encodeToString(slots)
    }
}
