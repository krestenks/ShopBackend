# Plan: manager-entered customer info (colour) + walk-in customer creation

Status: **planned, not started.** Two independent features; either can ship alone.
Written 2026-08-17.

---

## What exists today

`customers` table (edge DB):

```sql
CREATE TABLE customers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    phone TEXT NOT NULL,
    name TEXT, status TEXT, payment INTEGER, language INTEGER,
    owner_id INTEGER
);
```

Auto-created stub (`DataBase.insertNewCustomer`) is `name='NoName'`, `status='New'`. A second
name source lives in `customer_callapp_screening`, surfaced as `Customer.callappName` via a
LEFT JOIN in `getCustomerById`.

The app already collapses the sources in one place — `ApiService.kt:101`:

```kotlin
fun Customer.displayName(): String =
    name.trim().takeIf { it.isNotBlank() && it != "NoName" }   // 1. stored name
        ?: callappName                                          // 2. CallApp directory
        ?: phone                                                // 3. nothing known
```

`infoLine()` already marks the external name with `📇`. WebAdmin uses the same idea
(`status == "New"` = auto stub, `📇` badge for callappName).

Existing endpoints: `PATCH` name, `PUT` editable fields. **There is no create-customer
endpoint for the app** — `ensureCustomerByPhone` is internal, used by the SMS/booking paths.

---

## Feature 1 — distinctive colour for manager-entered name/info

### The catch, up front

We can already distinguish *where a name came from* (stored vs CallApp vs phone). We **cannot**
distinguish *who stored it*: `customers.name` is written by the manager app, the web admin and
the booking flow alike, and nothing records which. So decide what "entered by the manager"
should mean:

- **Reading A — "a human typed it, as opposed to auto/screened."** Ships with no schema change.
- **Reading B — "this specific manager typed it, in the app."** Needs provenance columns.

Recommend starting at A: it is one afternoon's work, needs no migration, and is probably what
the colour is actually for (trust this name vs treat it with suspicion).

### Level A — no schema change

1. Add a provenance helper next to `displayName()`:

   ```kotlin
   enum class NameSource { ENTERED, DIRECTORY, UNKNOWN }
   fun Customer.nameSource(): NameSource =
       when {
           name.trim().isNotBlank() && name != "NoName" -> NameSource.ENTERED
           !callappName.isNullOrBlank()                 -> NameSource.DIRECTORY
           else                                         -> NameSource.UNKNOWN
       }
   ```

2. Add three colours to `res/values/colors.xml` and apply at each display site. Sites that call
   `displayName()` / `infoLine()` today:
   - `CustomerDetailActivity` (title + `txtName`)
   - `SearchAdapter`
   - `CallLogAdapter` / `CallLogFragment` / `CallDetailActivity`
   - `AppointmentAdapter` / `AppointmentDetailSheet`
   - `sip/CallActivity` (`customerInfo`, the in-call context line)

3. Keep `📇` for DIRECTORY. **Do not rely on colour alone** — it fails for colour-blind users and
   in sunlight on a shop counter. Colour + existing glyph together.

Deliberately not changing `displayName()`'s return type: it is used in string contexts
(action-bar titles, dialog messages, intent extras) where a span would be wrong. Add a sibling
helper instead.

### Level B — real provenance (only if Reading B is wanted)

Migration on `customers`:

```sql
ALTER TABLE customers ADD COLUMN name_source  TEXT;     -- 'manager' | 'webadmin' | 'booking' | 'import'
ALTER TABLE customers ADD COLUMN name_set_by  INTEGER;  -- manager id, when source='manager'
ALTER TABLE customers ADD COLUMN name_set_at  INTEGER;  -- epoch ms
```

Set in `updateCustomerName` / `updateCustomerEditable` (both already take the caller's
`LoginInfo`, so the manager id is to hand). Backfill: leave NULL and treat NULL as "unknown
origin" rather than guessing.

If they want the colour on **status / payment / language** too, this is unavoidable — those are
plain columns with no stub sentinel to infer from, unlike name's `'NoName'`. Ask whether the
colour is for the name only or the whole record before building this.

---

## Feature 2 — add a walk-in customer

### The blocking design decision: phone number

`phone` is `NOT NULL` **and** is the dedupe key (`getCustomerIdByPhone`), and it is what the SMS,
blacklist, booking-link and inbound-call-matching paths all key on.

- **(a) Require a phone. Recommended.** Nothing downstream changes; the walk-in is a normal
  customer from the first second, and a later inbound call from that number matches them
  automatically. Most walk-ins will give a number if it means an SMS reminder.
- **(b) Allow no phone.** Needs a synthetic key (`walkin:<uuid>`) and then *every* phone-keyed
  path must tolerate a non-dialable value — SMS send, blacklist, booking link, call matching.
  Large blast radius for a small convenience. Only do this if walk-ins genuinely refuse numbers.

Ship (a) first. (b) can follow if the shops ask for it.

### Backend

New endpoint, mirroring the existing customer routes in `MobileApi.kt`:

```
POST /api/mobile/customers
  { phone, name, status?, payment?, language? }
  → 200 CustomerDetailResponse
```

- **Upsert, don't 409.** If `getCustomerIdByPhone` hits, update the blank fields and return the
  existing customer, with a flag so the app can say "this customer already existed". A manager
  mid-conversation should not be handed a duplicate-key error.
- Normalise the phone to E.164 the same way the call paths do, or the walk-in will not match
  their own inbound call later.
- Default `status` to something other than `'New'` — `'New'` is the auto-stub sentinel and also
  gates SMS booking (see `CustomerDetailActivity`'s reset dialog). A deliberately created
  customer should not look like a stub.
- Set `owner_id`. **Note:** `insertNewCustomer` currently does *not* set it, despite the column
  and `idx_customers_owner` existing — worth fixing in the same pass, as auto-created customers
  are landing with `owner_id = NULL`.
- If Level B lands, stamp `name_source='manager'` + `name_set_by`.

### App

Entry point — recommend **`SearchActivity`**: the natural flow is already "search for them,
they're not there, add them", and it puts the button exactly where the manager discovers the
need. A drawer entry is a reasonable second.

Screen: phone, name, and the same status / payment / language controls as the existing
customer-detail edit dialog — reuse that layout rather than inventing a second one. On success,
open `CustomerDetailActivity` for the new id so the manager can book straight away.

---

## Suggested order

1. Feature 2 (a) — self-contained, immediate daily value.
2. Feature 1 Level A — cheap, visible, no migration.
3. Feature 1 Level B — only after confirming the colour must mean "this manager", and whether
   it covers fields beyond the name.

## Open questions for Kresten

1. Does the colour mean "a human typed it" (Level A) or "*this manager* typed it" (Level B)?
2. Name only, or status/payment/language too?
3. Must walk-ins be creatable with **no** phone number at all?
