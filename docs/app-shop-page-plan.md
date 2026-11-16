# Plan: shop page in the app, with "Use employee name"

Status: backend and app built 2026-11-16 (commit 488a3ac and the app commit "Shops page"). Backend tests pass (273). Staging backend runs it. Tested on the S21 against staging 2026-11-16: shop list, address save, rename follows, paused state with two therapists, resume after moving a therapist back. Not tested: the WebAdmin checkbox in a browser, Thai and emoji input. Written 2026-11-16.
Scope: ShopBackend and the ShopManager app. Nothing here touches call handling or the Asterisk
provisioning, so Rule 1 (recovery code) is not triggered. Deploying the backend restarts it, so Rule 2 applies at deploy time.

## What the code does today (read 2026-11-16)

- `shops` columns: `id, name, address, directions, manager_id, owner_id` (`DataBase.kt` line 796 and the owner_id migration at line 1045). `employees` has `id, name, phone, owner_id`. `employee_shop(employee_id, shop_id, available)` links them.
- The app has no shop screen. `Shop(id, name)` is the only shop model in the app, loaded from `GET /api/mobile/manager/shops`, which is scoped to the manager's own shops.
- Therapist rename already renames the shop when the shop has exactly one employee: `renameTherapist` in `DataBase.kt` (line 6915), decided by `numberMode == "dedicated"`. This is fixed behaviour. It cannot be turned off.
- `updateShop(id, name, address, directions, managerId)` is used by three WebAdmin forms (`WebAdmin.kt` lines 1750, 3814, 4048). `updateEmployee(id, name, phone)` is used at lines 2059 and 4266. Neither knows about the other, so a WebAdmin edit can make the names differ.
- The shop name is only displayed: in the app, in WebAdmin, in reports and in the modem scanner. I found no use of it in the Asterisk config writers (`quectel_shops.conf` is keyed by shop id and device). So renaming a shop does not need a module reload. Checked by grep, not by running it.
- Shops are identified in the login path by `shops.username`, not by name (`DataBase.kt` line 2313).

## Decisions proposed (confirm or change)

| Topic | Proposal |
|---|---|
| Option | New column `shops.use_employee_name INTEGER NOT NULL DEFAULT 0`. Label in the app: "Use employee name". |
| Meaning | When the option is on and the shop has exactly one employee, the shop name is that employee's name. The server keeps them equal. |
| Shop with several employees | The option cannot be turned on (400). If a second employee is added to a shop that has it on, the option stays set but is paused: the name keeps its last value and the page says "Paused: this shop has 2 therapists". It resumes when only one is left. |
| Shop with no employee | The option cannot be turned on (400). |
| Name field | Disabled in the app while the option is on and active. The server rejects a name in the PATCH in that state (400, "Turn off Use employee name to set the name by hand"). |
| Existing behaviour | Replaced by the option. Backfill: set the option to 1 where the shop has exactly one employee and the two names are equal today. That keeps the present result for every shop that is in step, and changes nothing else. |
| Editable fields | Name, address, directions, the option. |
| Read only | Line number, therapist list for the shop. |
| Not in the app | Manager assignment, owner, SIM and telephony config, delete shop, create shop. These stay in WebAdmin. |
| Who can edit | Managers only, for any shop of their owner (same scope as the therapist page). The in-shop role gets 403 and no menu item. |

## Backend

1. Migration: `ALTER TABLE shops ADD COLUMN use_employee_name INTEGER NOT NULL DEFAULT 0` in the existing list at `DataBase.kt` line 1045, then the backfill above as a separate statement that runs once (guard it so a later restart does not re-set a flag the manager turned off).
2. One function `syncShopName(shopId)` in the DB layer: if the option is on and the shop has exactly one employee, set `shops.name` to that employee's name. Called inside the transaction of every operation that can change the answer:
   - `renameTherapist` (replaces the `numberMode == "dedicated"` test),
   - `moveTherapist` and `assignEmployeeToShop` (both the shop moved from and the shop moved to),
   - `updateEmployee` (so the WebAdmin employee forms stay consistent),
   - employee delete and employee add with a shop,
   - turning the option on.
3. `updateShop` keeps the stored name when the option is active, so the WebAdmin shop forms cannot break the rule. The three forms show the option as a checkbox, and save it with the shop.
4. Routes in `MobileFeatureRoutes.kt`, owner-scoped through `resolveOwnerId`, managers only:
   - `GET /api/mobile/shops`: every shop of the owner with `id, name, address, directions, useEmployeeName, nameFollowActive, lineNumber, therapists[]`.
   - `GET /api/mobile/shops/{id}`.
   - `PATCH /api/mobile/shops/{id}` with any of `name, address, directions, useEmployeeName`. Validation: name not blank and at most the existing therapist name limit; address and directions at most 200 and 1000 characters (UNCERTAIN: confirm the limits the WebAdmin forms use); turning the option on needs exactly one employee.
5. `TherapistDto` gets `shopUsesEmployeeName` so the therapist page can say what a rename will do, instead of deriving it from `numberMode`.
6. Log one line per change, as the therapist route does.

## App

1. `ShopsActivity` (list) and `ShopDetailActivity` (edit), written like `TherapistsActivity` and `TherapistDetailActivity`. Drawer item "🏪 Shops" next to Therapists, hidden for the shop role, started from `ShopScheduleActivity.onNavigationItemSelected`, declared in `AndroidManifest.xml`.
2. Detail page layout: name field, "Use employee name" switch with the employee name shown under it, address, directions, read-only line number, read-only list of therapists (each opens the therapist page), Save.
3. While the switch is on and active, the name field shows the employee name and is disabled. Turning the switch off enables it with the current name in it. Turning it on shows what the name will become. The switch is disabled with a one-line reason when the shop has no therapist or several.
4. `ApiService`: `getShops`, `getShop`, `updateShop`, with new models. The existing `Shop(id, name)` and `getManagerShops` stay as they are.
5. `TherapistDetailActivity`: replace the hint text by one driven by `shopUsesEmployeeName`.
6. After a save, the other screens read the new name the next time they load. No cache is kept for shop names (to confirm when implementing: `ShopScheduleActivity` and `ScheduleFragment` read the shop list on start).

## Tests

- Backend, in-memory DB (the existing `world()` fixture): option on with one employee syncs the name; rename of the employee changes the shop; a move into and out of a follow shop; second employee pauses it and removing one resumes it; turning on with 0 or 2 employees is refused; name in the PATCH refused while active; a different owner gets 404; the in-shop role gets 403; the backfill sets only equal-name single-employee shops and is not repeated; WebAdmin `updateShop` does not change the name while active.
- Device (S21 against the EliteDesk staging backend, as before): edit address and directions; turn the option on and off; rename the employee from the therapist page and see the shop name follow; move a therapist, which was not tested on the device last time.

## Order of work

1. Migration, `syncShopName`, `renameTherapist` and `updateShop` changes, with tests.
2. Shop routes and `TherapistDto` field, with tests.
3. App screens, built and tested on the S21 against staging.
4. Commit in both repos. No push. No deploy to the edge without your permission.

## Risks

- Existing behaviour changes shape: today the rename always renames a dedicated shop. After the backfill it does so only where the option is on. A shop whose two names differ today (for example shop 1 "Aor" with employee "Patty" if not corrected on the edge) gets the option off, so a rename no longer changes it. This is the intent, but it is a visible change.
- The backfill runs on the edge database at the next deploy. It only sets a flag and does not rename anything.
- Reports and exported file names use the shop name, so a rename changes later file names. Old reports keep the name they were made with.

## Answers (2026-11-16)

1. Paused state: when a second therapist is added, the option stays set and is paused. The name keeps its last value.
2. Editable fields: name, address, directions and the option, as proposed.
3. WebAdmin: the three shop forms get the option as a checkbox, not only a note.
4. Creating and deleting shops stays in WebAdmin.
