# SMS → Chatbot Training Data Capture — Implementation Plan

**Status:** Planned (not implemented)
**Author:** drafted 2026-06-29
**Goal:** Capture customer SMS conversations linked to their booking outcomes into a
pseudonymized, long-lived store suitable for training/fine-tuning an in-house chatbot
that can auto-learn and answer customer SMS.

---

## 1. Decisions locked in

| Decision | Choice | Consequence |
|---|---|---|
| Anonymization level | **Pseudonymous** (HMAC hash of phone, secret key retained) | Dataset is still "personal data" under GDPR → needs a documented legal basis, but stays simple. |
| Compute location | **In-house / self-hosted** | Data never leaves our infra. Body scrubbing is good practice but not mandatory. |
| Capture strategy | **Incremental, decoupled from live retention** | The training store has its own lifecycle; live `sms_message` rows keep their 5-day deletion. **No change to `communicationRetentionDays` required.** |
| Scope | **Option A — full pipeline** | Table + DAO + live SMS hooks + booking-outcome hook + scrubbing + export. |

### Why decoupled capture (not just "increase retention")
Simply raising `communicationRetentionDays` would:
- Keep raw PII (real phone numbers, names) in the live DB longer — worse privacy posture.
- Still lose threads that span longer than the window.
- Not produce a clean, labeled, training-ready dataset.

Incremental capture into a separate pseudonymized table solves all three.

---

## 2. Current-state reference (as of this plan)

Relevant existing code:

- **`sms_message` table** — `src/main/kotlin/DataBase.kt:600`
  Columns: `id, shop_id, customer_id, counterparty_phone, from_phone, to_phone, body,
  direction, status, twilio_message_sid, error_message, created_at`.
- **`appointments` table** — `src/main/kotlin/DataBase.kt:469`
  Columns include `customer_id, shop_id, date_time, duration, price, status,
  completed_at, actual_duration_minutes`.
- **`appointment_services`** — `src/main/kotlin/DataBase.kt:483` (maps appointment → service_id).
- **`customers` table** — `src/main/kotlin/DataBase.kt:460` (`phone`, `name`).
- **Live retention job** — `deleteExpiredCommunications()` at `src/main/kotlin/DataBase.kt:3905`,
  scheduled hourly from `startCleanupScheduler()` at `src/main/kotlin/ShopBackend.kt:228`
  (default 5-day window, `communicationRetentionDays` default at `src/main/kotlin/DataBase.kt:183`).
- **SMS routes / send-receive** — `src/main/kotlin/twilio/SmsRoutes.kt`,
  `src/main/kotlin/twilio/SmsService.kt`.
- **Linkage available:** `sms_message.customer_id` ↔ `appointments.customer_id`;
  a conversation thread = all messages sharing `(shop_id, counterparty_phone)`.

**This plan does NOT modify the live retention path.** It only adds a parallel capture path.

---

## 3. New schema: `training_conversation`

```sql
CREATE TABLE IF NOT EXISTS training_conversation (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    shop_id            INTEGER NOT NULL,
    thread_key         TEXT    NOT NULL,   -- HMAC-SHA256(secret, counterparty_phone + '|' + shop_id), hex
    messages_json      TEXT    NOT NULL DEFAULT '[]',
                                           -- ordered array of {dir, body, rel_ts_min}
    first_message_at   INTEGER NOT NULL,   -- absolute ms of first message in thread (anchor for rel_ts)
    last_message_at    INTEGER NOT NULL,
    -- Booking outcome (nullable until a booking is attached) --
    booking_service    TEXT,               -- service name(s) from appointment_services
    booking_weekday    INTEGER,            -- 1=Mon .. 7=Sun  (generalized, NOT exact timestamp)
    booking_time_bucket TEXT,              -- e.g. 'morning' | 'midday' | 'afternoon' | 'evening'
    booking_duration   INTEGER,            -- minutes
    booking_price      REAL,
    booking_status     TEXT,               -- 'completed' | 'no_show' | 'cancelled' | 'waiting'
    booking_attached_at INTEGER,
    captured_at        INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_training_thread
    ON training_conversation(shop_id, thread_key);
CREATE INDEX IF NOT EXISTS idx_training_shop
    ON training_conversation(shop_id);
```

### `messages_json` element shape
```json
{ "dir": "in",  "body": "<scrubbed text>", "rel_ts_min": 0 }
{ "dir": "out", "body": "<scrubbed text>", "rel_ts_min": 4 }
```
- `dir`: `"in"` (customer→shop) or `"out"` (shop→customer), derived from `sms_message.direction`.
- `rel_ts_min`: minutes since `first_message_at` — relative timing only, no wall-clock leak.

### Privacy properties
- **No raw phone numbers** stored — only `thread_key` (HMAC, irreversible without the secret).
- **No `customer_id`, no name** stored in the training table.
- **Exact booking timestamp generalized** to weekday + coarse time bucket.
- Re-identification is possible only with the secret key → pseudonymous, in-house only.

---

## 4. Configuration

Add one secret, following the existing `TWILIO_*` env-var pattern:

- **`TRAINING_HASH_KEY`** — random ≥32-byte secret used as the HMAC key for `thread_key`.
  - Loaded at startup in `ShopBackend.kt` alongside other config.
  - If unset/blank → **capture is disabled** (fail closed, log a warning once). This makes
    the whole feature opt-in and safe to deploy dark.
- Optional: **`TRAINING_CAPTURE_ENABLED`** (default derived from key presence) as an explicit kill switch.

Document both in whatever config doc lists the Twilio vars.

---

## 5. Components to build

### 5.1 Pseudonymization + scrubbing util — new file `src/main/kotlin/twilio/TrainingCapture.kt`
- `fun threadKey(counterpartyPhone: String, shopId: Int): String`
  → `HMAC-SHA256(TRAINING_HASH_KEY, "$counterpartyPhone|$shopId")` hex-encoded.
- `fun scrubBody(raw: String): String` — light regex pass:
  - Replace phone-number-like sequences → `[phone]`.
  - Replace email addresses → `[email]`.
  - (Names left intact: pseudonymous + in-house. Documented as a known limitation.)
  - Keep it conservative to avoid corrupting legitimate message content.
- `fun timeBucket(epochMs: Long, zone: ZoneId): String` and `fun weekday(epochMs: Long, zone): Int`.

> Note: choose the shop's timezone for weekday/bucket derivation if available; otherwise a
> single configured default zone. Confirm during implementation.

### 5.2 DAO methods — in `src/main/kotlin/DataBase.kt`
- `createTrainingConversationTable()` — add the `CREATE TABLE`/indexes to the existing
  schema bootstrap block (near `src/main/kotlin/DataBase.kt:600`).
- `appendTrainingMessage(shopId, threadKey, dir, scrubbedBody, tsMs)`:
  - Upsert by `(shop_id, thread_key)`.
  - If new: insert with `first_message_at = last_message_at = tsMs`, `messages_json = [msg@0]`.
  - If existing: append `{dir, body, rel_ts_min = (tsMs - first_message_at)/60000}`,
    update `last_message_at`.
  - Must be resilient: never throw into the SMS hot path (wrap, log, swallow).
- `attachBookingOutcome(shopId, customerId, appointment)`:
  - Resolve the customer's `counterparty_phone` → `threadKey`.
  - Find the open training_conversation for `(shop_id, thread_key)`.
  - Set booking_* fields (service from `appointment_services`, generalized time, price,
    duration, status) and `booking_attached_at`.
  - If multiple bookings attach to one thread over time: decide policy — **recommended:**
    most recent booking wins, OR split into a new row per booking. (Pick during impl; default = most recent wins, documented.)
- `exportTrainingConversations(shopId?, sinceMs?)` → list/stream for export.

### 5.3 Live SMS hook points
- **Inbound** (customer → shop): in the Twilio inbound webhook handler in
  `src/main/kotlin/twilio/SmsRoutes.kt`, right after the message is persisted to
  `sms_message`, call `appendTrainingMessage(..., dir="in", ...)`.
- **Outbound** (shop → customer): after a successful send in `SmsRoutes.kt` /
  `SmsService.kt`, call `appendTrainingMessage(..., dir="out", ...)`.
- Both calls guarded by capture-enabled check and wrapped so a capture failure never
  affects message delivery.

### 5.4 Booking-outcome hook
- In the appointment create and complete/status-update paths in `DataBase.kt`
  (e.g. wherever `appointments` rows are inserted / their `status`/`completed_at` change),
  call `attachBookingOutcome(...)`.
- Re-attaching on status change (Waiting → Completed/No-show) keeps `booking_status` current.

### 5.5 Export surface
- A small admin-only endpoint or CLI/Gradle task that streams `training_conversation`
  as **JSONL**, one example per line:
  ```json
  {"messages":[{"dir":"in","body":"...","rel_ts_min":0}, ...],
   "label":{"service":"haircut","weekday":3,"time_bucket":"afternoon",
            "duration":30,"price":250.0,"status":"completed"}}
  ```
- Gate behind existing admin auth (see `WebAdmin.kt` / `JwtConfig.kt`).

---

## 6. Data lifecycle / retention of the training store
- Training rows are **not** touched by `deleteExpiredCommunications()`.
- Decide a separate retention for `training_conversation` (e.g. 12–24 months) and add an
  optional pruning pass to the cleanup scheduler. Default in this plan: **no auto-pruning**
  initially; revisit once volume is known. (Document the GDPR storage-limitation tradeoff.)

---

## 7. Implementation order (suggested)
1. Schema + indexes + table bootstrap.
2. `TrainingCapture.kt` util (threadKey, scrubBody, time helpers) + unit tests.
3. DAO methods (`appendTrainingMessage`, `attachBookingOutcome`, `export`) + tests.
4. Config wiring (`TRAINING_HASH_KEY`, enable flag, fail-closed).
5. Inbound SMS hook.
6. Outbound SMS hook.
7. Booking-outcome hook.
8. JSONL export endpoint/task.
9. End-to-end test: simulate a thread + booking → verify one well-formed training row,
   no raw PII, correct relative timestamps and generalized booking fields.

---

## 8. Testing checklist
- [ ] `thread_key` is stable for the same (phone, shop) and differs across shops.
- [ ] No raw phone number, email, name, or `customer_id` appears in any stored row.
- [ ] `scrubBody` removes phone/email patterns without mangling normal text.
- [ ] Multi-message thread appends in order with correct `rel_ts_min`.
- [ ] Booking attaches to the correct thread; weekday/time bucket generalized correctly.
- [ ] Capture failure (e.g. missing key) never blocks SMS send/receive.
- [ ] Live `sms_message` deletion still works unchanged.
- [ ] Export emits valid JSONL.

---

## 9. Known limitations / follow-ups
- **Names in message bodies are not scrubbed** (pseudonymous + in-house tradeoff). If the
  data is ever moved off-prem or the anonymization bar rises, add name detection before export.
- **Legal basis** for repurposing customer SMS for ML must be documented (privacy policy /
  ROPA). This is an organizational task, not code.
- **Thread/booking attribution** is heuristic (latest booking per thread). Revisit if a
  customer has many bookings interleaved with one long SMS thread.
- **Timezone** for weekday/bucket derivation needs a definitive source (per-shop vs global).
