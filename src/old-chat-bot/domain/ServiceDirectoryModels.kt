package domain

import kotlinx.serialization.Serializable

@Serializable
data class Shop(
    val shopId: String,
    val name: String
)

@Serializable
data class Therapist(
    val therapistId: String,
    val name: String,
    val shopId: String,
    val specialities: List<String>
)

@Serializable
data class TherapistAvailability(
    val therapistId: String,
    val dateIso: String,
    val durationMinutes: Int,
    val slots: List<TimeSlot>
)
