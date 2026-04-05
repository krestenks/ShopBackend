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
        <h2>Book an Appointment</h2>
        <form method="POST" action="/api/booking/submit">
            <input type="hidden" name="shop_id" value="$shopId" />
            $customerIdHtml
            <label>Employee:</label>
            <select id="employeeSelect" name="employee_id" required>
                <option value="">Select an employee</option>
            </select>
            <br><br>
            <label>Services:</label>
            <div id="serviceCheckboxes"></div><br>
            <label>Date:</label>
            <select id="dateSelect" required></select>
            <br><br>
            <label>Time:</label>
            <select id="timeSelect" name="appointment_time" required></select>
            <br><br>
            <button type="submit">Book Appointment</button>
        </form>
        """.trimIndent()
    }
}
