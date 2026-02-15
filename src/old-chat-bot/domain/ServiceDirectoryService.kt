package domain

import java.time.LocalDate
import java.time.LocalDateTime

class ServiceDirectoryService {
    private val shops = listOf(
        Shop(shopId = "cph-centrum", name = "Copenhagen Centrum"),
        Shop(shopId = "cph-nord", name = "Copenhagen Nord")
    )

    private val therapists = listOf(
        Therapist(
            therapistId = "t-anna",
            name = "Anna",
            shopId = "cph-centrum",
            specialities = listOf("swedish", "deep_tissue", "sports")
        ),
        Therapist(
            therapistId = "t-bo",
            name = "Bo",
            shopId = "cph-centrum",
            specialities = listOf("sports", "deep_tissue", "neck")
        ),
        Therapist(
            therapistId = "t-clara",
            name = "Clara",
            shopId = "cph-nord",
            specialities = listOf("swedish", "prenatal", "foot")
        )
    )

    fun listShops(): List<Shop> {
        return shops
    }

    fun listTherapists(
        shopId: String,
        massageType: String? = null
    ): List<Therapist> {
        val normalized = massageType?.trim()?.lowercase()
        return therapists
            .filter { it.shopId == shopId }
            .filter { normalized == null || it.specialities.any { s -> s.equals(normalized, ignoreCase = true) } }
    }

    fun getTherapistById(therapistId: String): Therapist? {
        return therapists.firstOrNull { it.therapistId.equals(therapistId, ignoreCase = true) }
    }

    fun resolveTherapistId(input: String): String? {
        val trimmed = input.trim()

        // Direct therapistId match (e.g. "t-anna")
        therapists.firstOrNull { it.therapistId.equals(trimmed, ignoreCase = true) }?.let {
            return it.therapistId
        }

        // Name match (e.g. "Anna")
        therapists.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let {
            return it.therapistId
        }

        return null
    }

    fun getTherapistAvailability(
        therapistId: String,
        dateIso: String,
        durationMinutes: Int
    ): TherapistAvailability {
        // Stub rules:
        // - Workday 09:00-17:00
        // - Start times every 30 minutes
        // - Each therapist has a few blocked start times (different per therapist)
        val day = LocalDate.parse(dateIso)
        val workStart = day.atTime(9, 0)
        val workEnd = day.atTime(17, 0)

        val stepMinutes = 30
        val blockedStarts = blockedStartTimesForTherapist(therapistId, day)

        val slots = mutableListOf<TimeSlot>()
        var t = workStart
        while (t.plusMinutes(durationMinutes.toLong()).isBefore(workEnd.plusSeconds(1))) {
            val isBlocked = blockedStarts.any { it == t }
            if (!isBlocked) {
                val startIso = t.toString()
                val endIso = t.plusMinutes(durationMinutes.toLong()).toString()
                slots.add(TimeSlot(startIso = startIso, endIso = endIso))
            }
            t = t.plusMinutes(stepMinutes.toLong())
        }

        return TherapistAvailability(
            therapistId = therapistId,
            dateIso = dateIso,
            durationMinutes = durationMinutes,
            slots = slots
        )
    }

    fun findJointAvailability(
        therapistIds: List<String>,
        dateIso: String,
        durationMinutes: Int
    ): List<TimeSlot> {
        if (therapistIds.isEmpty()) return emptyList()

        // Compute availability for each therapist, then intersect on startIso
        val availByTherapist = therapistIds.map { tid ->
            getTherapistAvailability(tid, dateIso, durationMinutes).slots
        }

        var commonStarts = availByTherapist.first().map { it.startIso }.toSet()
        for (slots in availByTherapist.drop(1)) {
            commonStarts = commonStarts.intersect(slots.map { it.startIso }.toSet())
        }

        // Return slots sorted by start time; end time is start + duration
        val day = LocalDate.parse(dateIso)
        val result = commonStarts
            .mapNotNull { startIso ->
                try {
                    val start = LocalDateTime.parse(startIso)
                    TimeSlot(
                        startIso = start.toString(),
                        endIso = start.plusMinutes(durationMinutes.toLong()).toString()
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .sortedBy { it.startIso }

        return result
    }

    private fun blockedStartTimesForTherapist(therapistId: String, day: LocalDate): Set<LocalDateTime> {
        // Deterministic-ish per therapist for demo purposes
        return when (therapistId.lowercase()) {
            "t-anna" -> setOf(
                day.atTime(11, 0),
                day.atTime(15, 30)
            )
            "t-bo" -> setOf(
                day.atTime(10, 30),
                day.atTime(13, 0)
            )
            else -> setOf(
                day.atTime(12, 0),
                day.atTime(16, 0)
            )
        }
    }
}
