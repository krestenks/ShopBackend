package domain

import kotlinx.serialization.Serializable

@Serializable
data class TimeSlot(
    val startIso: String,
    val endIso: String
)

@Serializable
data class BookingRequest(
    val therapistId: String? = null,
    val customerName: String? = null,
    val treatment: String,
    val startIso: String,
    val durationMinutes: Int
)

@Serializable
data class BookingConfirmation(
    val confirmationId: String
)

@Serializable
data class MultiBookingConfirmation(
    val confirmationIds: List<String>
)
