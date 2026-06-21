package twilio

import DataBase
import Shop
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object BookingConfirmationSms {

    private val dkLocale = Locale("da", "DK")

    fun buildMessage(
        shop: Shop,
        appointmentTimeMillis: Long,
        directions: String?,
        appointmentCount: Int = 1,
        zoneId: ZoneId = ZoneId.of("Europe/Copenhagen"),
    ): String {
        val dt = Instant.ofEpochMilli(appointmentTimeMillis).atZone(zoneId)
        val fmt = DateTimeFormatter.ofPattern("EEE dd/MM HH:mm", dkLocale)
        val whenText = dt.format(fmt)

        val addressLine = shop.address?.trim().orEmpty()
        val directionsLine = directions?.trim().orEmpty()

        return buildString {
            append("✅ Booking confirmed")
            if (appointmentCount > 1) append(" ($appointmentCount)")
            append("\n")
            append("🕒 ").append(whenText).append("\n")

            if (addressLine.isNotBlank()) {
                append("📍 ").append(addressLine).append("\n")
            }
            if (directionsLine.isNotBlank()) {
                append("➡️ ").append(directionsLine)
            }
        }.trim()
    }

    suspend fun send(
        db: DataBase,
        smsService: TwilioSmsService,
        shopId: Int,
        toPhoneE164: String,
        appointmentTimeMillis: Long,
        appointmentCount: Int = 1,
    ): TwilioSmsService.SmsResult {
        val voice = db.getShopVoiceConfig(shopId)
        val shop = db.getShopById(shopId)
            ?: return TwilioSmsService.SmsResult(false, 404, "Shop not found")

        val fromNumber = voice.twilioNumber?.trim().takeIf { !it.isNullOrBlank() }
            ?: (System.getenv("TWILIO_FROM_NUMBER") ?: "").trim()

        val body = buildMessage(
            shop = shop,
            appointmentTimeMillis = appointmentTimeMillis,
            directions = shop.directions,
            appointmentCount = appointmentCount,
        )

        val result = smsService.sendSms(
            fromNumberE164 = fromNumber,
            toNumberE164 = toPhoneE164,
            bodyText = body,
        )

        // Persist the outbound confirmation so it appears in the Messages view.
        // We do this regardless of Twilio success so the manager can see it was attempted.
        runCatching {
            val customerId = db.getCustomerIdByPhone(toPhoneE164)
            db.insertSmsMessage(
                shopId            = shopId,
                customerId        = customerId,
                counterpartyPhone = toPhoneE164,
                fromPhone         = fromNumber,
                toPhone           = toPhoneE164,
                body              = body,
                direction         = "outbound",
                status            = if (result.success) "sent" else "failed",
            )
        }

        return result
    }
}
