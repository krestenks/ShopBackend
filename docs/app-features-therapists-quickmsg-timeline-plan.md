# Plan: therapists, quick messages, customer timeline

Status: planned, not started. Written 2026-11-15.
Scope: ShopBackend (edge) and the ShopManager app. None of this touches call handling, so Rule 1
(recovery code) is not triggered. Deploying the backend restarts it, so Rule 2 applies at deploy time.

## What the app code looks like (read 2026-11-15)

Files read in full: `MainActivity`, `CustomerDetailActivity`, `MessageThreadActivity`, `drawer_menu.xml`,
`activity_message_thread.xml`, and the relevant parts of `ApiService.kt` and `ShopScheduleActivity.kt`.
Not read: `activity_customer_detail.xml`, `SettingsActivity` (grepped only), `CallLogFragment`, `PhoneStatusActivity`.
The app is Activities with XML layouts and Retrofit, not Compose.

- Navigation: `MainActivity` is only the login screen. The home is `ShopScheduleActivity`, a drawer plus a ViewPager with 4 tabs (Calls, Messages, Shop Chat, Plan). Drawer items are in `res/menu/drawer_menu.xml` and handled in `ShopScheduleActivity.onNavigationItemSelected` (around line 506). New list screens go in as drawer items that start an Activity.
- Roles: the `shop` role sees only Plan and Chat. New drawer items must be hidden for it, as the existing manager-only items are.
- Shop is the unit everywhere: `Shop(id, name)` is the only shop model. Customer, blacklist, star, SMS and booking calls all carry `shopId` in the path or body, and screens pass `shop_id` in intents.
- SMS thread (`MessageThreadActivity`): keyed by `shopId` plus customer phone, polls every 5 seconds, sends through `sendMessage(text)`. The compose row is `messageInput` plus `sendButton`. Two buttons (price list, book) already sit above it.
- Customer screen (`CustomerDetailActivity`): shows name, status, buttons and a list of appointments only, in an inner adapter on `simple_list_item_2`. It has no calls or SMS list. It receives `shop_id` and refuses to open without it.
- `Employee(id, name, phone?)` already exists in the app and backend: table `employees` plus `employee_shop` (with an `available` flag), referenced by appointments, services and availability, and used for the price-list SMS and the Availability screen. Confirmed by you: employees are the therapists.
- Backend customer queries are not scoped by owner: `getCustomerById` and `getCustomerIdByPhone` select by id or phone only. The customer detail route checks only that the manager may use the given shop, then returns any customer id. [CONFIRMED by reading the two functions and the route.] Whether this matters depends on the number of owners in production, which I have not checked.

## Decisions (from the interview)

| Topic | Decision |
|---|---|
| Model | A therapist is an existing `employees` row. No new table. A shop stays the location and owns the SIM line and number. |
| Number ownership | The SIM number stays on the shop (`shop_telephony_config.phone_number`). The app shows whether the number is dedicated or shared. |
| Dedicated or shared | Derived, not stored. A shop with exactly one `employee_shop` row: "Dedicated to <name>". Any other count: "Shared shop number". |
| Number editing | Read only in the app. Changed in WebAdmin only. |
| Therapist name | Editable from the app. |
| Therapist page | Name (editable), shop (editable), number (read only, with the dedicated or shared label). |
| Shared scope | Customer records are tenant-wide (all shops of one owner, all managers). |
| Quick messages | Private per manager, stored in the backend DB, plain text and emoji, no placeholders. Tap fills the message box, a second tap sends. |
| Timeline | On the customer screen. Calls and SMS in one chronological list. Optional filter by line (shop). Filtering by individual therapist on a shared line is not included. |

## Assumptions to confirm

1. "Dedicated" is derived from the number of employees in the shop, as in the table above.
2. Every shop already has its therapist as an `employees` row. Not verified against the live data; check one shop with no employee or with several before building.
3. A manager can edit therapists in every shop of the tenant, not only the shops they cover.
4. The timeline shows tenant-wide history for the customer, not only the manager's own shops.
5. `employees.phone` is the therapist's own mobile (confirmed by you). The therapist page shows it and the shop line number as two separate labelled rows: "Mobile" and "Shop line (dedicated / shared)". Both are read only in the app and changed in WebAdmin. Every manager of the tenant can see the therapist's mobile. The mobile can be empty, and the row is then hidden.
6. A therapist belongs to one shop at a time. WebAdmin assigns with `exclusive = true` (two call sites), although `employee_shop` allows several rows. "Edit shop" in the app moves the therapist to another shop.

## Phase 1: therapists

Backend
- No new table and no migration. `employees` already has `owner_id` (nullable, added by `ALTER TABLE`), so first check for employees with a null `owner_id` and set it from their shop, as was done for shops.
- `GET /api/mobile/therapists`: the tenant's employees, each with shop id and name (via `employee_shop`), the employee's own `phone`, the shop's line number from `shop_telephony_config.phone_number`, and the derived `numberMode` (`dedicated` or `shared`). Today the only employee endpoint is `GET manager/employees?shopId=`, which returns available employees of one shop, and `getAllEmployees()` has no owner filter.
- `GET /api/mobile/therapists/{id}`.
- `PATCH /api/mobile/therapists/{id}` with `name` and `shopId`. The owner check applies to both the employee and the target shop. A shop change calls the existing `assignEmployeeToShop(id, shopId, exclusive = true)`.
- Moving a therapist changes shop availability (`employee_shop.available`, which the Availability screen controls). The move keeps the default available state and the response says so. Appointments keep their `employee_id`.
- Shop name: decided that a rename in the app updates only `employees.name`. The shop name is not changed, so the call log, search and SMS header keep showing the shop name as set in WebAdmin. Until the shop is renamed there, it can differ from the therapist's name. The therapist page shows both, with the shop name labelled as the shop.

App
- New drawer item "Therapists" in `drawer_menu.xml`, handled in `ShopScheduleActivity`, hidden for the `shop` role. It opens a list Activity, then a therapist page Activity. Name and shop are editable; the number is read only with the dedicated or shared label.
- `Employee(id, name, phone?)` already exists. Add a therapist model with shop id, shop name, line number and number mode. The Availability screen (`AvailabilityActivity`, not read yet) already lists employees per shop; the new list is a separate screen with a different purpose, and the two should link to each other.
- Screens that show a shop name (call log, search, SMS thread header, new-customer flow) do not change.

## Phase 2: tenant-wide customer data

- Customers are currently global by phone number, not by owner (see the findings above). Make `getCustomerById`, `getCustomerIdByPhone` and the routes that use them filter by `customers.owner_id`, with the owner taken from the authenticated manager.
- The app passes `shop_id` to customer screens and calls. Keep that for compatibility. The timeline endpoint takes the customer id only and does not need a shop.
- Check that a customer is one record per owner and normalised phone. Check for duplicates created per shop in existing data (`SELECT owner_id, phone, COUNT(*) ... HAVING COUNT(*) > 1`).
- `voice_call.customer_id` is nullable. Where it is empty, match by normalised phone within the owner.

## Phase 3: quick messages

Backend
- Table `quick_message (id, manager_id, owner_id, text, sort_order, created_at, updated_at)`.
- `GET /api/mobile/quick-messages`, `POST`, `PUT /{id}`, `DELETE /{id}`, and `PUT /order`. Every query filters by the authenticated `manager_id`, so one manager never sees another's list.
- Limit: 20 messages per manager, 320 characters per message (limit values are a proposal).

App
- `activity_message_thread.xml`: a horizontally scrolling chip row between the price-list/book buttons and the compose row. Tap sets `messageInput` text and focuses it; the existing `sendButton` and `sendMessage(text)` send. The thread screen is already crowded with two full-width buttons, so the chip row replaces nothing and the layout needs checking on a small phone.
- New `QuickMessagesActivity` (add, edit, delete, reorder), opened from a new drawer item or from `SettingsActivity`. The drawer item is hidden for the `shop` role.
- New `ApiService` methods and models for the endpoints above.
- UNCERTAIN: emoji changes the SMS encoding to UCS-2, which allows 70 characters per segment instead of 160, and each segment is billed. Whether chan_quectel sends UCS-2 correctly is not verified. Test one emoji message to a spare SIM before release. The app should show the segment count when the text contains emoji.

## Phase 4: customer timeline

Backend
- `GET /api/mobile/customers/{id}/timeline?shopId=&before=&limit=` returns a merged list ordered by time, newest first, paged by `before`.
- Entries from `sms_message` (direction, body, status) and `voice_call` (direction, outcome, started_at, ended_at, duration). Each entry carries its `shopId` and line display name.
- The customer is resolved within the owner. The `shopId` filter is optional and must belong to the owner.
- Index needed: `sms_message(customer_id, created_at)` and `voice_call(customer_id, started_at)`, with a phone-based fallback.

App
- `CustomerDetailActivity` today lists appointments only. Add a Timeline list below the buttons (second adapter and a row layout for SMS and call entries) and a line filter above it ("All lines" or one line, built from `getManagerShops`).
- The activity currently refuses to open without `shop_id`. That check stays for the existing actions; the timeline uses the customer id only.
- An SMS entry opens `MessageThreadActivity` with that entry's `shopId` and the customer phone.
- Layout `activity_customer_detail.xml` has not been read yet; its structure decides whether the timeline fits in the existing scroll area or needs its own screen.

## Order and release

1. Phase 2 audit first, because Phases 1 and 4 depend on tenant-wide customer queries being correct.
2. Phase 1, then Phase 3 and Phase 4 independently.
3. Backend first. New endpoints are additive, so the currently installed app keeps working. App release through the WebAdmin "App updates" page.

## Out of scope

- Filtering by individual therapist on a shared line.
- Quick message placeholders, per-language variants and sharing between managers.
- Editing numbers from the app.
- Changing how calls ring or which manager is on duty.

## Verification

- Migration: run against a copy of the edge DB, check one therapist per shop and no change to shop rows.
- Owner scoping: for each new endpoint, a request from a manager of owner A for an id of owner B returns 403 or 404.
- Quick messages: two managers, each sees only their own list.
- Timeline: customer with calls and SMS across two shops, check order, the line filter, and paging across the boundary.
- Not verified until built: the emoji SMS behaviour above.
