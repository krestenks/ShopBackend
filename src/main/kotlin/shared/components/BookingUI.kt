package shared.components

import kotlinx.html.*
import kotlinx.html.stream.createHTML

fun FlowContent.bookingForm(shopId: Int? = null, customerId: Int? = null) {
    div {
        h2 { +"Book Appointment" }

        form {
            action = "/appointments/submit"
            method = FormMethod.post

            if (shopId != null) {
                input {
                    type = InputType.hidden
                    name = "shop_id"
                    value = shopId.toString()
                }
            } else {
                label { htmlFor = "shopSelect"; +"Select Shop: " }
                select {
                    id = "shopSelect"
                    name = "shop_id"
                }
                br()
            }

            if (customerId != null) {
                input {
                    type = InputType.hidden
                    name = "customer_id"
                    value = customerId.toString()
                }
            }

            label { htmlFor = "employeeSelect"; +"Select Employee:" }
            select {
                id = "employeeSelect"
                name = "employee_id"
            }
            br()

            label { +"Select Services:" }
            div {
                id = "serviceCheckboxes"
            }

            label { htmlFor = "dateSelect"; +"Select Date:" }
            select {
                id = "dateSelect"
                name = "appointment_date"
            }
            br()

            label { htmlFor = "timeSelect"; +"Select Time:" }
            select {
                id = "timeSelect"
                name = "appointment_time"
            }
            br()

            input {
                type = InputType.submit
                value = "Book Appointment"
            }
        }

        script(src = "/static/booking-ui.js") {}
    }
}

