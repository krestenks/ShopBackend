package tools

import domain.BookingRequest
import domain.BookingService
import domain.MultiBookingConfirmation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BookMultiAppointmentTool(
    private val bookingService: BookingService
) : Tool {

    override val name = "book_multi_appointment"
    override val description = "Book multiple appointments (e.g. two therapists simultaneously). Prefer this when booking more than one therapist."
    override val jsonSchema = """
    {
      "type": "object",
      "properties": {
        "customerName": { "type": "string" },
        "appointments": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "therapistId": { "type": "string" },
              "treatment": { "type": "string" },
              "startIso": { "type": "string" },
              "durationMinutes": { "type": "integer" }
            },
            "required": ["therapistId","treatment","startIso","durationMinutes"]
          }
        }
      },
      "required": ["appointments"]
    }
  """.trimIndent()

    override suspend fun invoke(argumentsJson: String): String {
        val obj = Json.parseToJsonElement(argumentsJson).jsonObject
        val customerName = obj["customerName"]?.jsonPrimitive?.content

        val appts = obj["appointments"]!!.jsonArray.map { el ->
            val a = el.jsonObject
            BookingRequest(
                therapistId = a["therapistId"]!!.jsonPrimitive.content,
                customerName = customerName,
                treatment = a["treatment"]!!.jsonPrimitive.content,
                startIso = a["startIso"]!!.jsonPrimitive.content,
                durationMinutes = a["durationMinutes"]!!.jsonPrimitive.content.toInt()
            )
        }

        val confirmations = bookingService.bookMultiple(appts)
        return Json.encodeToString(MultiBookingConfirmation(confirmations.map { it.confirmationId }))
    }
}
