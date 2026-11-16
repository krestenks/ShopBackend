import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * The owner of the signed-in user. The token carries it; for a token without one it is looked
 * up from the manager or shop. Null when no owner can be found.
 */
internal fun resolveOwnerId(login: LoginInfo, db: DataBase): Int? =
    login.ownerId.takeIf { it != 0 }
        ?: login.managerId?.let { db.getOwnerIdForManager(it) }
        ?: login.shopId?.let { db.getOwnerIdForShop(it) }

/** Parses yyyy-MM; null when missing or not a month. */
internal fun parseAccountsMonth(text: String?): java.time.YearMonth? =
    text?.let { runCatching { java.time.YearMonth.parse(it) }.getOrNull() }

/** Parses yyyy-MM-dd; null when missing or not a date. */
internal fun parseAccountsDate(text: String?): java.time.LocalDate? =
    text?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }

/** True when the customer belongs to the user's owner. The user's owner must be known. */
internal fun customerBelongsToOwner(customerId: Int, login: LoginInfo, db: DataBase): Boolean {
    val ownerId = resolveOwnerId(login, db) ?: return false
    return db.getOwnerIdForCustomer(customerId) == ownerId
}

/**
 * Therapist page, quick messages and customer timeline for the manager app. These are for the
 * manager role; the in-shop role gets 403.
 */
fun Route.mobileFeatureRoutes(db: DataBase) {

    // ── Therapists ────────────────────────────────────────────────────────────

    get("/api/mobile/therapists") {
        val login = authenticateManager() ?: return@get
        if (login.role != "manager") return@get call.respond(HttpStatusCode.Forbidden, "Managers only")
        val ownerId = resolveOwnerId(login, db) ?: return@get call.respond(HttpStatusCode.Forbidden, "No owner")
        call.respond(db.getTherapistsForOwner(ownerId))
    }

    get("/api/mobile/therapists/{id}") {
        val login = authenticateManager() ?: return@get
        if (login.role != "manager") return@get call.respond(HttpStatusCode.Forbidden, "Managers only")
        val ownerId = resolveOwnerId(login, db) ?: return@get call.respond(HttpStatusCode.Forbidden, "No owner")
        val id = call.parameters["id"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")
        val t = db.getTherapistForOwner(id, ownerId) ?: return@get call.respond(HttpStatusCode.NotFound, "Therapist not found")
        call.respond(t)
    }

    patch("/api/mobile/therapists/{id}") {
        val login = authenticateManager() ?: return@patch
        if (login.role != "manager") return@patch call.respond(HttpStatusCode.Forbidden, "Managers only")
        val ownerId = resolveOwnerId(login, db) ?: return@patch call.respond(HttpStatusCode.Forbidden, "No owner")
        val id = call.parameters["id"]?.toIntOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing id")
        val body = runCatching { call.receive<UpdateTherapistRequest>() }.getOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, "Invalid request body")
        if (body.name == null && body.shopId == null) {
            return@patch call.respond(HttpStatusCode.BadRequest, "Nothing to change")
        }
        if (db.getTherapistForOwner(id, ownerId) == null) {
            return@patch call.respond(HttpStatusCode.NotFound, "Therapist not found")
        }

        // Move first: a rename afterwards then sees the shop the therapist is in now.
        if (body.shopId != null) {
            db.moveTherapist(id, ownerId, body.shopId)
                ?: return@patch call.respond(HttpStatusCode.NotFound, "Shop not found")
        }
        var employeeRenamed = false
        var shopRenamed = false
        if (body.name != null) {
            val renamed = try {
                db.renameTherapist(id, ownerId, body.name)
            } catch (e: IllegalArgumentException) {
                return@patch call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid name")
            } ?: return@patch call.respond(HttpStatusCode.NotFound, "Therapist not found")
            employeeRenamed = renamed.employeeRenamed
            shopRenamed = renamed.shopRenamed
        }
        println("[MobileApi/therapists] manager=${login.managerId} therapist=$id name=${body.name != null} shop=${body.shopId} shopRenamed=$shopRenamed")
        call.respond(UpdateTherapistResponse(db.getTherapistForOwner(id, ownerId)!!, employeeRenamed, shopRenamed))
    }

    // ── Daily accounts per therapist ──────────────────────────────────────────

    get("/api/mobile/therapists/{id}/accounts") {
        val login = authenticateManager() ?: return@get
        if (login.role != "manager") return@get call.respond(HttpStatusCode.Forbidden, "Managers only")
        val ownerId = resolveOwnerId(login, db) ?: return@get call.respond(HttpStatusCode.Forbidden, "No owner")
        val id = call.parameters["id"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")
        val month = parseAccountsMonth(call.request.queryParameters["month"])
            ?: return@get call.respond(HttpStatusCode.BadRequest, "month must be yyyy-MM")
        if (db.getTherapistForOwner(id, ownerId) == null) return@get call.respond(HttpStatusCode.NotFound, "Therapist not found")
        call.respond(db.getAccountsMonth(id, month))
    }

    get("/api/mobile/therapists/{id}/accounts/{date}") {
        val login = authenticateManager() ?: return@get
        if (login.role != "manager") return@get call.respond(HttpStatusCode.Forbidden, "Managers only")
        val ownerId = resolveOwnerId(login, db) ?: return@get call.respond(HttpStatusCode.Forbidden, "No owner")
        val id = call.parameters["id"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")
        val date = parseAccountsDate(call.parameters["date"]) ?: return@get call.respond(HttpStatusCode.BadRequest, "date must be yyyy-MM-dd")
        if (db.getTherapistForOwner(id, ownerId) == null) return@get call.respond(HttpStatusCode.NotFound, "Therapist not found")
        call.respond(db.getAccountsDay(id, date))
    }

    put("/api/mobile/therapists/{id}/accounts/{date}") {
        val login = authenticateManager() ?: return@put
        if (login.role != "manager") return@put call.respond(HttpStatusCode.Forbidden, "Managers only")
        val ownerId = resolveOwnerId(login, db) ?: return@put call.respond(HttpStatusCode.Forbidden, "No owner")
        val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing id")
        val date = parseAccountsDate(call.parameters["date"]) ?: return@put call.respond(HttpStatusCode.BadRequest, "date must be yyyy-MM-dd")
        val body = runCatching { call.receive<SaveAccountDayRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid request body")
        if (db.getTherapistForOwner(id, ownerId) == null) return@put call.respond(HttpStatusCode.NotFound, "Therapist not found")
        val saved = try {
            db.saveAccountsDay(id, date, body.jobs)
        } catch (e: IllegalArgumentException) {
            return@put call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid value")
        }
        println("[MobileApi/accounts] manager=${login.managerId} therapist=$id day=$date jobs=${saved.jobs.size}")
        call.respond(saved)
    }

    // ── Price list per therapist ──────────────────────────────────────────────

    // Read by a manager of the shop's owner and by the shop's own login; only managers can edit.
    get("/api/mobile/shops/{id}/price-lists") {
        val login = authenticateManager() ?: return@get
        val shopId = call.parameters["id"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")
        val allowed = when (login.role) {
            "shop" -> login.shopId == shopId
            "manager" -> resolveOwnerId(login, db)?.let { db.getShopDtoForOwner(shopId, it) != null } ?: false
            else -> false
        }
        if (!allowed) return@get call.respond(HttpStatusCode.NotFound, "Shop not found")
        call.respond(ShopPriceListsDto(canEdit = login.role == "manager", therapists = db.getPriceListsForShop(shopId)))
    }

    put("/api/mobile/therapists/{id}/price-list") {
        val login = authenticateManager() ?: return@put
        if (login.role != "manager") return@put call.respond(HttpStatusCode.Forbidden, "Managers only")
        val ownerId = resolveOwnerId(login, db) ?: return@put call.respond(HttpStatusCode.Forbidden, "No owner")
        val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing id")
        val body = runCatching { call.receive<SavePriceListRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid request body")
        if (db.getTherapistForOwner(id, ownerId) == null) return@put call.respond(HttpStatusCode.NotFound, "Therapist not found")
        val saved = try {
            db.setTherapistPriceList(id, body.priceList)
        } catch (e: IllegalArgumentException) {
            return@put call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid price list")
        }
        println("[MobileApi/pricelist] manager=${login.managerId} therapist=$id length=${saved.length}")
        call.respond(SavePriceListRequest(saved))
    }

    // ── Shops ─────────────────────────────────────────────────────────────────

    get("/api/mobile/shops") {
        val login = authenticateManager() ?: return@get
        if (login.role != "manager") return@get call.respond(HttpStatusCode.Forbidden, "Managers only")
        val ownerId = resolveOwnerId(login, db) ?: return@get call.respond(HttpStatusCode.Forbidden, "No owner")
        call.respond(db.getShopDtosForOwner(ownerId))
    }

    get("/api/mobile/shops/{id}") {
        val login = authenticateManager() ?: return@get
        if (login.role != "manager") return@get call.respond(HttpStatusCode.Forbidden, "Managers only")
        val ownerId = resolveOwnerId(login, db) ?: return@get call.respond(HttpStatusCode.Forbidden, "No owner")
        val id = call.parameters["id"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")
        call.respond(db.getShopDtoForOwner(id, ownerId) ?: return@get call.respond(HttpStatusCode.NotFound, "Shop not found"))
    }

    patch("/api/mobile/shops/{id}") {
        val login = authenticateManager() ?: return@patch
        if (login.role != "manager") return@patch call.respond(HttpStatusCode.Forbidden, "Managers only")
        val ownerId = resolveOwnerId(login, db) ?: return@patch call.respond(HttpStatusCode.Forbidden, "No owner")
        val id = call.parameters["id"]?.toIntOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing id")
        val body = runCatching { call.receive<UpdateShopRequest>() }.getOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, "Invalid request body")
        if (body.name == null && body.address == null && body.directions == null && body.useEmployeeName == null) {
            return@patch call.respond(HttpStatusCode.BadRequest, "Nothing to change")
        }
        val updated = try {
            db.updateShopForOwner(id, ownerId, body)
        } catch (e: IllegalArgumentException) {
            return@patch call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid value")
        } ?: return@patch call.respond(HttpStatusCode.NotFound, "Shop not found")
        println("[MobileApi/shops] manager=${login.managerId} shop=$id name=${body.name != null} address=${body.address != null} directions=${body.directions != null} useEmployeeName=${body.useEmployeeName}")
        call.respond(updated)
    }

    // ── Quick messages (each manager sees only their own) ─────────────────────

    get("/api/mobile/quick-messages") {
        val login = authenticateManager() ?: return@get
        val managerId = login.managerId ?: return@get call.respond(HttpStatusCode.Forbidden, "Managers only")
        call.respond(db.getQuickMessages(managerId))
    }

    post("/api/mobile/quick-messages") {
        val login = authenticateManager() ?: return@post
        val managerId = login.managerId ?: return@post call.respond(HttpStatusCode.Forbidden, "Managers only")
        val body = runCatching { call.receive<QuickMessageRequest>() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid request body")
        val created = try {
            db.addQuickMessage(managerId, resolveOwnerId(login, db), body.text)
        } catch (e: IllegalArgumentException) {
            return@post call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid message")
        }
        call.respond(HttpStatusCode.Created, created)
    }

    put("/api/mobile/quick-messages/order") {
        val login = authenticateManager() ?: return@put
        val managerId = login.managerId ?: return@put call.respond(HttpStatusCode.Forbidden, "Managers only")
        val body = runCatching { call.receive<QuickMessageOrderRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid request body")
        if (!db.reorderQuickMessages(managerId, body.ids)) {
            return@put call.respond(HttpStatusCode.BadRequest, "ids must be exactly your current messages")
        }
        call.respond(db.getQuickMessages(managerId))
    }

    put("/api/mobile/quick-messages/{id}") {
        val login = authenticateManager() ?: return@put
        val managerId = login.managerId ?: return@put call.respond(HttpStatusCode.Forbidden, "Managers only")
        val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing id")
        val body = runCatching { call.receive<QuickMessageRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid request body")
        val ok = try {
            db.updateQuickMessage(id, managerId, body.text)
        } catch (e: IllegalArgumentException) {
            return@put call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid message")
        }
        if (!ok) return@put call.respond(HttpStatusCode.NotFound, "Message not found")
        call.respond(db.getQuickMessages(managerId))
    }

    delete("/api/mobile/quick-messages/{id}") {
        val login = authenticateManager() ?: return@delete
        val managerId = login.managerId ?: return@delete call.respond(HttpStatusCode.Forbidden, "Managers only")
        val id = call.parameters["id"]?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing id")
        if (!db.deleteQuickMessage(id, managerId)) return@delete call.respond(HttpStatusCode.NotFound, "Message not found")
        call.respond(HttpStatusCode.NoContent)
    }

    // ── Customer timeline ─────────────────────────────────────────────────────

    get("/api/mobile/customers/{customerId}/timeline") {
        val login = authenticateManager() ?: return@get
        if (login.role != "manager") return@get call.respond(HttpStatusCode.Forbidden, "Managers only")
        val ownerId = resolveOwnerId(login, db) ?: return@get call.respond(HttpStatusCode.Forbidden, "No owner")
        val customerId = call.parameters["customerId"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing customerId")
        val shopId = call.request.queryParameters["shopId"]?.let {
            it.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid shopId")
        }
        val before = call.request.queryParameters["before"]?.let {
            it.toLongOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid before")
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        // A customer of another owner and an unknown id answer the same, so ids cannot be probed.
        val timeline = db.getCustomerTimeline(customerId, ownerId, shopId, before, limit)
            ?: return@get call.respond(HttpStatusCode.NotFound, "Customer not found")
        call.respond(timeline)
    }
}
