package util

/**
 * Masks a phone number for logs: "+4551941736" -> "+45XXXX36".
 *
 * Logs and audit trails get read by more people, and end up in more places, than the database
 * does. Keep enough of the number to correlate two lines about the same caller, not enough to
 * identify them.
 */
fun maskPhone(phone: String?): String {
    val p = phone?.trim().orEmpty()
    if (p.length < 6) return "+XXXX"
    return p.take(3) + "XXXX" + p.takeLast(2)
}
