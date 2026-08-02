import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.span

/**
 * Single source of truth for the admin sidebar, shared by [WebAdmin]'s pages and the
 * [SetupAppRoutes] phone pages so the whole backend has ONE unified, grouped navigation.
 *
 * Grouped by domain (Booking & CRM / Phone system / Platform) instead of one flat list.
 * The Phone-system group surfaces the device overview at top level so it's no longer buried
 * inside the "Install app" sub-area.
 */
data class SidebarItem(val href: String, val label: String, val icon: String)
data class SidebarGroup(val title: String?, val items: List<SidebarItem>)

val adminNavGroups: List<SidebarGroup> = listOf(
    SidebarGroup(null, listOf(
        SidebarItem("/", "Dashboard", "📊"),
    )),
    SidebarGroup("Booking & CRM", listOf(
        SidebarItem("/shops", "Shops", "🏪"),
        SidebarItem("/services", "Services", "🧾"),
        SidebarItem("/employees", "Employees", "👥"),
        SidebarItem("/availability", "Availability", "🟢"),
        SidebarItem("/appointments", "Appointments", "📅"),
        SidebarItem("/customers", "Customers", "👤"),
        SidebarItem("/test-booking-link", "Booking link", "🔗"),
        SidebarItem("/reports", "Reports", "💰"),
    )),
    SidebarGroup("Phone system", listOf(
        SidebarItem("/setup-app/devices", "Phones", "📱"),
        SidebarItem("/setup-app/add-phone", "Add phone", "➕"),
        SidebarItem("/setup-app/download", "Install app", "📲"),
        SidebarItem("/setup-app/updates", "App updates", "⬆️"),
        SidebarItem("/telephony/setup", "Telephony setup", "📞"),
    )),
    SidebarGroup("Platform", listOf(
        SidebarItem("/managers", "Managers", "🧑‍💼"),
        SidebarItem("/admin/owners", "Owners", "🏢"),
    )),
)

/** Renders the full admin sidebar (brand + grouped nav + logout). [activePath] highlights the current page. */
fun FlowContent.adminSidebar(activePath: String?, logoutHref: String = "/logout") {
    div("sidebar") {
        div("brand") {
            div {
                div("brand-title") { +"ShopManager" }
                div("brand-sub") { +"Admin" }
            }
        }
        div("nav") {
            for (group in adminNavGroups) {
                group.title?.let { div("nav-group-title") { +it } }
                for (item in group.items) {
                    a(href = item.href, classes = if (activePath == item.href) "active" else null) {
                        span { +item.icon }
                        span { +item.label }
                    }
                }
            }
            div("spacer") {}
            a(href = logoutHref) { span { +"🚪" }; span { +"Logout" } }
        }
    }
}
