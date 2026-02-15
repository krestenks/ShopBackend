package domain

import java.util.UUID

class BookingService {
    // Stubbed booking store (in-memory)
    private val bookings = mutableListOf<BookingRequest>()

    fun listOpenTimeSlots(
        dateIso: String,
        durationMinutes: Int
    ): List<TimeSlot> {
        // Generic shop-level availability stub:
        // - Return a few sample slots for the requested duration
        // - This does NOT bind to a therapist
        val starts = listOf(
            "${dateIso}T10:00",
            "${dateIso}T13:00",
            "${dateIso}T15:00"
        )

        return starts.map { startIso ->
            val start = java.time.LocalDateTime.parse(startIso)
            TimeSlot(
                startIso = start.toString(),
                endIso = start.plusMinutes(durationMinutes.toLong()).toString()
            )
        }
    }

    fun book(req: BookingRequest): BookingConfirmation {
        // Minimal validation for prototype
        bookings.add(req)
        return BookingConfirmation(confirmationId = "b-${UUID.randomUUID()}")
    }

    fun bookMultiple(requests: List<BookingRequest>): List<BookingConfirmation> {
        // Stub "atomic" multi booking:
        // - Either all succeed (here: always) and we return confirmations
        // In a real system you would lock and verify all slots first.
        val confirmations = mutableListOf<BookingConfirmation>()
        for (r in requests) {
            bookings.add(r)
            confirmations.add(BookingConfirmation(confirmationId = "b-${UUID.randomUUID()}"))
        }
        return confirmations
    }
}
