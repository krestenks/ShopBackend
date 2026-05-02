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
              <h1 class="booking-title">Book an appointment</h1>
              <p class="booking-subtitle">Choose employee, services and an available time slot.</p>
            </div>

            <form class="booking-form" method="POST" action="/api/booking/submit">
              <input type="hidden" name="shop_id" value="$shopId" />
              $customerIdHtml

              <div>
                <label for="employeeSelect">Employee</label>
                <select id="employeeSelect" name="employee_id" required>
                  <option value="">Select an employee</option>
                </select>
              </div>

              <div>
                <label>Services</label>
                <div id="serviceCheckboxes" class="services"></div>
                <div class="note">Select one or more services to see available times.</div>
              </div>

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

              <button class="btn" type="submit">Confirm booking</button>
              <div class="note">You’ll see a confirmation screen after booking.</div>
            </form>
          </div>
        </div>
        """.trimIndent()
    }
}
