package tools

import domain.ServiceDirectoryService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class GetTherapistAvailabilityTool(
    private val serviceDirectory: ServiceDirectoryService
) : Tool {

    override val name = "get_therapist_availability"
    override val description = "Get available time slots for a specific therapist on a specific date (ISO date) for a given duration (minutes)."
    override val jsonSchema = """
    {
      "type": "object",
      "properties": {
        "therapistId": { "type": "string" },
        "dateIso": { "type": "string", "description": "Date in ISO format, e.g. 2026-02-07" },
        "durationMinutes": { "type": "integer", "description": "Desired duration in minutes, e.g. 30, 60" }
      },
      "required": ["therapistId", "dateIso", "durationMinutes"]
    }
  """.trimIndent()

    override suspend fun invoke(argumentsJson: String): String {
        val obj = Json.parseToJsonElement(argumentsJson).jsonObject
        val inputId = obj["therapistId"]!!.jsonPrimitive.content
        val dateIso = obj["dateIso"]!!.jsonPrimitive.content

        val durationMinutes = obj["durationMinutes"]?.jsonPrimitive?.content?.toInt()
            ?: 60

        val resolvedId = serviceDirectory.resolveTherapistId(inputId)
            ?: return Json.encodeToString(
                buildJsonObject { put("error", JsonPrimitive("Unknown therapist: $inputId")) }
            )

        val availability = serviceDirectory.getTherapistAvailability(resolvedId, dateIso, durationMinutes)
        return Json.encodeToString(availability)
    }
}
