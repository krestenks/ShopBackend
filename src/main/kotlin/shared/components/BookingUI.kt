package shared.components

/**
 * Placeholder for booking form - the actual form is in SharedRoutes.kt
 */
object BookingUI {
    fun getFormHtml(shopId: Int, customerId: Int? = null): String {
        val customerIdHtml = if (customerId != null) {
            """<input type="hidden" name="customer_id" value="$customerId" />"""
        } else {
            ""
        }
        return """
        <div class="booking-page">
          <div class="booking-card">
            <div class="booking-header">
              <h1 class="booking-title">Book appointments</h1>
              <p class="booking-subtitle">Add one or more therapists. Each therapist can have different services, but all appointments will be at the same time.</p>
            </div>

            <form class="booking-form" method="POST" action="/api/booking/submit-multi" id="bookingMultiForm">
              <input type="hidden" name="shop_id" value="$shopId" />
              $customerIdHtml
              <input type="hidden" name="payload" id="multiPayload" value="" />

              <div id="therapistBlocks"></div>

              <button class="btn secondary" type="button" id="addTherapistBtn">Add therapist</button>

              <div class="grid-2">
                <div>
                  <label for="dateSelect">Date</label>
                  <select id="dateSelect" required></select>
                </div>
                <div>
                  <label for="timeSelect">Time</label>
                  <select id="timeSelect" name="appointment_time" required></select>
                </div>
              </div>

              <button class="btn" type="submit">Confirm bookings</button>
              <div class="note">Time slots shown are only those that match all selected therapists + services.</div>
            </form>
          </div>
        </div>
        """.trimIndent()
    }
}
