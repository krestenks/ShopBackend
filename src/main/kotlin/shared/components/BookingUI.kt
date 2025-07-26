package shared.components

import kotlinx.html.*
import kotlinx.html.stream.createHTML

fun FlowContent.bookingForm(shopId: Int? = null, customerId: Int? = null) {
    div {
        h2 { +"Book Appointment" }

        form {
            action = "/api/booking/submit"
            method = FormMethod.post

            if (shopId != null && shopId > -1) {
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
                br()
            }

            if (customerId != null) {
                input {
                    type = InputType.hidden
                    name = "customer_id"
                    value = customerId.toString()
                }
            }

            label { htmlFor = "employeeSelect"; +"Select Employee:  " }
            select {
                id = "employeeSelect"
                name = "employee_id"
            }
            br()
            br()

            label { +"Select Services:" }
            div {
                id = "serviceCheckboxes"
            }

            br()
            br()

            label { htmlFor = "dateSelect"; +"Select Date:  " }
            select {
                id = "dateSelect"
                name = "appointment_date"
            }
            br()
            br()

            label { htmlFor = "timeSelect"; +"Select Time:  " }
            select {
                id = "timeSelect"
                name = "appointment_time"
            }
            br()
            br()
            input {
                type = InputType.submit
                value = "Book Appointment"
            }
        }

        script(src = "/static/booking-ui.js") {}
    }
}

