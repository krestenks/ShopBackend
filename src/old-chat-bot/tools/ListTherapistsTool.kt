package tools

import domain.ServiceDirectoryService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ListTherapistsTool(
    private val serviceDirectory: ServiceDirectoryService
) : Tool {

    override val name = "list_therapists"
    override val description = "List therapists at a specific shop. Optionally filter by massage type (e.g. swedish, deep_tissue, sports, prenatal)."
    override val jsonSchema = """
    {
      "type": "object",
      "properties": {
        "shopId": { "type": "string", "description": "Shop identifier, e.g. cph-centrum" },
        "massageType": { "type": "string", "description": "Optional filter, e.g. swedish" }
      },
      "required": ["shopId"]
    }
  """.trimIndent()

    override suspend fun invoke(argumentsJson: String): String {
        val obj = Json.parseToJsonElement(argumentsJson).jsonObject
        val shopId = obj["shopId"]!!.jsonPrimitive.content
        val massageType = obj["massageType"]?.jsonPrimitive?.content
        val therapists = serviceDirectory.listTherapists(shopId = shopId, massageType = massageType)
        return Json.encodeToString(therapists)
    }
}
