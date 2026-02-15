package tools

import domain.ServiceDirectoryService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FindJointAvailabilityTool(
    private val serviceDirectory: ServiceDirectoryService
) : Tool {

    override val name = "find_joint_availability"
    override val description = "Find time slots where multiple therapists are available simultaneously on a given date for a given duration."
    override val jsonSchema = """
    {
      "type": "object",
      "properties": {
        "therapistIds": { "type": "array", "items": { "type": "string" } },
        "dateIso": { "type": "string" },
        "durationMinutes": { "type": "integer", "description": "e.g. 30, 60" }
      },
      "required": ["therapistIds", "dateIso", "durationMinutes"]
    }
  """.trimIndent()

    override suspend fun invoke(argumentsJson: String): String {
        val obj = Json.parseToJsonElement(argumentsJson).jsonObject
        val ids = obj["therapistIds"]!!.jsonArray.map { it.jsonPrimitive.content }
        val dateIso = obj["dateIso"]!!.jsonPrimitive.content
        val durationMinutes = obj["durationMinutes"]!!.jsonPrimitive.content.toInt()

        val slots = serviceDirectory.findJointAvailability(ids, dateIso, durationMinutes)
        return Json.encodeToString(slots)
    }
}
