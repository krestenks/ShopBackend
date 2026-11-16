# Plan: daily accounts per therapist, in the app

Status: first version (one row of amounts per day) built 2026-11-16, then revised the same day to job lines per day (see "Revision: job lines" at the end). The revision is in backend c6e5963 (286 tests pass) and the app commit made with it. Staging runs the revision. Tested on the S21 against staging: month overview, day screen, live totals, + for a sixth line, save, reload, month sums, discard prompt, clearing a day. Not tested: the 403 and 404 answers over HTTP, screen rotation, an owner with no therapists, a day with 20 lines. Not deployed to the edge.
Scope: ShopBackend and the ShopManager app. No call handling, no Asterisk, no recovery code, so Rule 1 is not triggered. Deploying restarts the backend, so Rule 2 applies at deploy time.

## Request

A month view on each therapist's page. One row per date. Columns: Cash, MobilePay, Revolut, Food expenses, Total. A month selector. A total row for the month at the bottom. The manager enters the amounts.

## What exists today (read 2026-11-16)

- The app's Reports screen is a WebView over `/api/mobile/manager/reports` (`FinancialReports.kt`). It sums appointment prices per shop and per employee, in kr. It has no manual money entry and no payment method.
- No table holds payments, cash or expenses. `appointments.price` is a REAL.
- The therapist page is `TherapistDetailActivity` (name, shop, line number). Therapist routes are owner scoped through `resolveOwnerId`, manager role only.
- Dates in reports use the Copenhagen time zone.

## Decisions proposed (confirm or change)

| Topic | Proposal |
|---|---|
| Storage | New table `daily_accounts(employee_id, day TEXT, cash INTEGER, mobilepay INTEGER, revolut INTEGER, food INTEGER, updated_at INTEGER, PRIMARY KEY (employee_id, day))`. `day` is an ISO date, `yyyy-MM-dd`. A row exists only for a day that has been entered. |
| Key | The therapist (employee id), not the shop. Moving a therapist to another shop does not change or split the history. |
| Amounts | Integer øre in the database. The app accepts whole kroner or two decimals. Display in kr. Currency is DKK only. |
| Categories | Fixed columns: Cash, MobilePay, Revolut, Food. A new category later is a migration. |
| Total column | Net: Cash + MobilePay + Revolut minus Food. It can be negative. |
| Month total row | The sum of each column over the month, computed by the server. |
| Independence | Not linked to appointments or the Reports screen. No automatic fill. |
| Who can use it | Managers, for therapists of their owner. The in-shop role gets 403 and no button. |
| Editing | Last write wins per day. A save sends only the days that changed. No conflict detection. |
| Past months | Editable. No lock. |

## Backend

1. Migration: `CREATE TABLE IF NOT EXISTS daily_accounts (...)` in the existing list in `DataBase.kt`. Additive only, nothing existing changes. Index not needed (primary key covers employee and day).
2. DB functions: `getDailyAccounts(employeeId, month)` returns the entered days; `saveDailyAccounts(employeeId, days)` writes the changed days in one transaction (a day with all four values empty or zero is deleted, so no empty rows stay).
3. Routes in `MobileFeatureRoutes.kt`, owner scoped, managers only, 404 for another owner's therapist:
   - `GET /api/mobile/therapists/{id}/accounts?month=yyyy-MM` returns every date of the month (entered or not), the per-day total, and the month totals. Bad month gives 400.
   - `PUT /api/mobile/therapists/{id}/accounts` with `days: [{day, cash, mobilepay, revolut, food}]`. Validation: the day is a real date; amounts are not negative; amounts have at most 2 decimals; a limit per value to catch a typing error (UNCERTAIN: choose the limit, proposal 1,000,000 kr).
4. One log line per save: therapist, number of days, login.
5. Employee delete: the entries are kept. `employees.id` is AUTOINCREMENT (checked in the table definition), so a new therapist cannot receive a deleted therapist's id and so cannot inherit the entries. The kept entries are not visible in the app.

## App

1. A button "Accounts" on `TherapistDetailActivity` opens `AccountsActivity` with the therapist id.
2. Screen: toolbar with the therapist name, a month bar with previous and next arrows and the month name, then a table. Header row: Date, Cash, MobilePay, Revolut, Food, Total. One row per date, shown as weekday and day number (for example "Mon 3"). A bottom row "Month" with the totals, always visible.
3. Cells are number fields. The Total cell is read only and updates while typing. The month row updates while typing. Weekends are shaded so the rows are easy to find.
4. A Save button saves the changed days. Leaving the screen with unsaved changes asks first. Changing the month with unsaved changes asks first.
5. Width: seven columns on a phone fit only with narrow cells and small numbers (about 50 dp per column on a 360 dp screen). Amounts above 99,999 kr will not fit in one cell. This is the main layout risk. If it does not read well on the S21, the fallback is one row per date and a dialog to edit that day.
6. The month total row and the per-day total are shown from the server answer after a save, so the screen never keeps a total the server did not calculate.

## Tests

- Backend, in-memory DB: save and read a month; a day with all values empty is removed; month totals and day totals; the total follows the answer to question 1; another owner gets 404; the in-shop role gets 403; bad month, bad date, negative amount, three decimals each give 400; a save of several days is all or nothing; moving a therapist to another shop keeps the entries.
- Device (S21 against EliteDesk staging, as before): enter amounts for a few days, save, close and reopen, change month, check the totals against a hand calculation, rotate the phone, unsaved-change prompts.

## Order of work

1. Migration, DB functions and routes with tests.
2. App screen, built and tested on the S21 against staging.
3. Commit in both repos. No push. No deploy to the edge without your permission. The deploy needs a backend restart.

## Risks

- This holds money records. A wrong total or a lost entry matters more than a display fault. Totals are calculated by the server and tested; the app only previews them while typing.
- Backup: the table is in the same SQLite database as everything else, so it is covered by whatever backs up that database. UNCERTAIN: whether the edge database is backed up off the box; check before relying on it for accounts.
- Two managers editing the same therapist and day: the later save wins without a warning.
- Fixed categories: adding a category later needs a migration and an app release.

## Answers (2026-11-16)

1. Total column: received minus food (net).
2. Access: managers only.
3. Therapist deleted in WebAdmin: the entries are kept.
4. Export: app only. No xlsx and no WebAdmin page for now.

## Revision: job lines (2026-11-16)

Request: the month is an overview only. A tap on a day opens that day with the same fields per job, so the manager can enter each amount as the money comes in. Five job lines to start with, and a + button for more.

| Topic | Decision |
|---|---|
| Storage | Table `account_jobs(employee_id, day, line, cash, mobilepay, revolut, food, updated_at, PRIMARY KEY (employee_id, day, line))` replaces `daily_accounts`. `line` is the position, 1 first. A line with every amount zero is not stored. Position is kept: jobs 1, 2 and 6 stored leave 3 to 5 empty. `daily_accounts` was never on the edge, so nothing is migrated. Staging has an empty `daily_accounts` table that nothing reads. |
| Month | `GET .../accounts?month=yyyy-MM` is unchanged in shape. Each day is the sum of its job lines. Read only in the app. |
| Day | `GET` and `PUT /api/mobile/therapists/{id}/accounts/{yyyy-MM-dd}`. The PUT sends all job lines of the day and replaces the day in one transaction; lines left out are removed. The old month `PUT` is removed. |
| Limits | At most 20 lines per day (`ACCOUNT_JOB_MAX_LINES`, my choice). Same per-amount limit as before. |
| App | Month screen: rows open the day. Day screen: 5 lines, + adds one, Day row at the bottom, Save, discard prompt. The month reloads when the day screen closes. |
| Not included | A note or customer name per job. A job cannot be moved to another day. |
| Access | Unchanged: managers only, owner scoped. |

Risk: a save replaces the whole day. If two phones edit the same day, the later save wins.
