# Plan: drop calls from callers who withhold their number

Status: **planned, not started.** Written 2026-08-17.

---

## What a withheld caller actually looks like here

Checked against the live edge DB rather than assumed. `voice_call` rows whose `from_phone` is
empty, over the last ~12 days:

```
t                    shop_id  to_phone       state       outcome
2026-08-18 15:24:50  1        +4550376614    TERMINATED  OPERATOR_DECLINED
2026-08-15 19:59:57  1        +4550376614    TERMINATED  OPERATOR_DECLINED
2026-08-14 14:09:24  2        +4581921779    TERMINATED  OPERATOR_DECLINED
...                                                       (11 rows total)
2026-08-11 11:00:12  2        +4581921779    TERMINATED  OPERATOR_BRIDGED
```

Findings that shape the design:

- They arrive with an **empty** `${CALLERID(num)}` — not the literal string `anonymous`,
  `unknown` or `restricted`. Nothing else odd is in the data: every other `from_phone` is `+…`.
- **11 calls across ~12 days, both shops**, on the real GSM numbers. Not a rarity.
- Two were **answered** (`OPERATOR_BRIDGED`); the rest were declined by a manager. So today they
  ring through and cost the manager an interruption.

Do NOT rely on empty alone in the implementation. Empty is what *this* carrier/modem produces;
other networks present a literal `anonymous`/`unknown`/`restricted`/`private`, and chan_quectel
could change. Match empty **or** a small case-insensitive literal set.

## Why no dialplan change is needed

`DialplanWriter` already asks the backend for a verdict on every inbound call and already knows
how to drop one:

```
exten => s,1,NoOp(Inbound call for shop N from ${CALLERID(num)})
 same => n,Set(VERDICT=${CURL(.../call/inbound,...&from=${URIENCODE(${CALLERID(num)})}...)})
 same => n,GotoIf($["${VERDICT}" = "reject"]?rejected,1)
...
exten => rejected,1,Hangup(21)
```

So the whole feature is a new branch in `InternalTelephonyRoutes` returning `"reject"`. No
regenerated dialplan, no Asterisk reload, no risk to the routing that already works.

Cause 21 = "call rejected", which is what the blacklist path already returns.

## What happens today

In `POST /api/internal/telephony/call/inbound`, an empty `from` falls through every guard:

- `db.getCustomerIdByPhone(from)` is skipped (`from.isNotBlank()`), so no customer is created
- `blacklisted` is forced false by the same guard — **a withheld caller cannot be blacklisted**
- `isKnown` is false
- so it lands in "unknown caller": reject when closed, **ring the managers when open**

That last line is the behaviour to change.

## Implementation

### 1. Detection helper

```kotlin
/** True when the network withheld the caller's number. Empty is what our GSM modems report;
 *  the literals cover carriers that send a placeholder instead. */
private val WITHHELD_CALLER_IDS = setOf("anonymous", "unknown", "restricted", "private", "withheld")

fun isWithheldCaller(from: String): Boolean =
    from.isBlank() || from.trim().lowercase() in WITHHELD_CALLER_IDS
```

### 2. Verdict branch

Insert in `call/inbound` **after** the call-log row is created (the attempt must still be
visible in the app) and **before** the customer lookup — there is nothing to look up:

```kotlin
if (rejectWithheld && isWithheldCaller(from)) {
    db.updateCallState(callId, VoiceCallState.REJECTED_WITHHELD, "caller withheld their number")
    db.terminateCall(callId, VoiceCallOutcome.WITHHELD_REJECTED)
    println("[Asterisk/call-inbound] REJECT withheld caller shop=$shopId uniqueid=$uniqueId")
    call.respondText("reject")
    return@post
}
```

New enum values `VoiceCallState.REJECTED_WITHHELD` and `VoiceCallOutcome.WITHHELD_REJECTED`,
alongside the existing blacklist pair. Both are persisted as strings, so no data migration —
but check the app tolerates an unknown enum value before shipping, or old builds may crash on
the call log. If they parse strictly, ship the app change first.

### 3. Per-shop toggle

Not tenant-wide and not hardcoded: one shop may want these calls, another may not, and a global
switch cannot express that. `shop_voice_config` is the right home — it already holds
`phone_override` and `temporary_operator_closed`, and the file has an established list of
idempotent migrations to append to:

```sql
ALTER TABLE shop_voice_config ADD COLUMN reject_withheld_callers INTEGER NOT NULL DEFAULT 0
```

**Default 0 (off).** This silently drops customers; it should be a decision each shop makes, not
one that lands on them in a release. Add to `ShopVoiceConfig`, its SELECT and its upsert, mirroring
`upsertShopPhoneOverride`.

### 4. Web admin

A checkbox on the shop's telephony page next to the temporary-closure control, worded so the
consequence is obvious — "Reject callers who withhold their number (they hear a rejection, not a
ring)". Same page pattern as the existing voice settings.

### 5. Reliability log

Worth one `reliability_event` row at `info` on each rejection, category `withheld_rejected`. The
manager otherwise has no way to know a customer was turned away — the call log shows the attempt,
but the log is the place someone looks when asking "are we missing calls?". Do NOT SMS-alert on
it; at ~1 per day that would be noise.

## Deliberately out of scope

- **Inbound SMS from withheld senders.** The `sms` extension passes the same `CALLERID(num)`, but
  a withheld SMS sender is a different problem (the message body is still useful, and there is
  nobody to reject). Leave it.
- **Blacklisting withheld callers.** Meaningless — there is no number to key on. The feature
  above is the substitute.
- **The dead voice menu / booking-link code.** Tempting to delete while in here, but that is a
  separate change with its own blast radius (dialplan regeneration, prompt files, the
  `KNOWN_CUSTOMER_*` states and `SMS_SENT` outcome that historic rows still reference). Leave it
  alone for this feature.

## Testing

The nasty part: you cannot easily place a withheld call to yourself. Options in order of
preference:

1. **Unit-test `isWithheldCaller`** over empty, whitespace, each literal, mixed case, and a normal
   `+45…` number. Cheap and covers the logic that matters.
2. **Drive the endpoint directly** — the dialplan just does an HTTP GET, so
   `curl ".../call/inbound?secret=…&shopId=1&from=&uniqueid=test-1"` must return `reject` with the
   toggle on and `ring`/`menu_*` with it off. This tests the real code path end to end without a
   phone.
3. **A real withheld call** — dial the shop SIM with `#31#` prefix (Denmark) from a mobile. Worth
   doing once to confirm the modem really does present empty, since the whole feature rests on
   that.

Verify on a shop with the toggle OFF too, confirming nothing changes for it.

## Decision (Kresten, 2026-08-17): reject outright

Withheld callers get `reject` -> `Hangup(21)`. No menu, no booking link, no second chance.

The alternative I had floated (give them the closed menu so they could still self-book) is
**dead on arrival**: the DTMF voice menu and the SMS booking links are switched off and no longer
used. Confirmed in the data — 229 calls over the last 30 days produced only:

```
OPERATOR_DECLINED   105
OPERATOR_BRIDGED     88
BLACKLIST_REJECTED   29
CLOSED_HOURS          7
```

Not one menu state, and not one `SMS_SENT`. The `isKnown` branch in `call/inbound` that returns
`menu_open` / `menu_closed` / `menu_temp` is never reached in practice, and the dialplan's
`welcome*`, `menu*` and `smslink` extensions are dead code that Asterisk still carries.

Consequences for this feature:

- There are only three live paths through `call/inbound`: blacklist -> reject, closed -> reject,
  otherwise ring the on-duty pool. The withheld branch is simply a fourth reject, checked first.
- Do not spend effort on how withheld callers interact with the menu — nothing does.
- The implementer should not "fix" the menu branch if it looks unreachable while working here.
  It is unreachable on purpose.
