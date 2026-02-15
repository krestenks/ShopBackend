package tools

import domain.ServiceDirectoryService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class GetTherapistDetailsTool(
    private val serviceDirectory: ServiceDirectoryService
) : Tool {

    override val name = "get_therapist_details"
    override val description =
        "Get details for a therapist. therapistId must come from tool results (e.g. t-anna), but names like 'Anna' are also accepted."

    override val jsonSchema = """
    {
      "type": "object",
      "properties": {
        "therapistId": { "type": "string" }
      },
      "required": ["therapistId"]
    }
  """.trimIndent()

    override suspend fun invoke(argumentsJson: String): String {
        val obj = Json.parseToJsonElement(argumentsJson).jsonObject
        val inputId = obj["therapistId"]!!.jsonPrimitive.content

        val resolvedId = serviceDirectory.resolveTherapistId(inputId)
            ?: return Json.encodeToString(
                buildJsonObject { put("error", "Unknown therapist: $inputId") }
            )

        val therapist = serviceDirectory.getTherapistById(resolvedId)
            ?: return Json.encodeToString(
                buildJsonObject { put("error", "Therapist not found: $resolvedId") }
            )

        return Json.encodeToString(therapist)
    }
}
