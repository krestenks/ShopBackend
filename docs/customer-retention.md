# Customer retention — flag at two years, delete after a grace period

Replaces the old "delete customers with no recent activity" sweep, which could not keep a customer
longer than it kept their messages, and deleted in a single pass with no review.

---

## The bug this fixes

`deleteExpiredCustomers` decided whether a customer was active by looking for `sms_message` or
`voice_call` rows newer than the cutoff. But `deleteExpiredCommunications` deletes exactly those
rows first, on a **separate and much shorter** window (live shops 354–365 days, test shops 5).

So:

| Day | What existed | Verdict |
|---|---|---|
| 0 | customer calls; `voice_call` row written | active |
| 364 | row still there | active |
| 366 | comms retention deleted the row | **no activity found → deletable** |

The customer window was therefore capped at the communications window, whatever the setting said.
Raising `customer_retention_days` to 730 would have changed nothing: they would still have gone at
~365 days. Worse, only a **future** appointment protected a customer — a past booking counted for
nothing at all.

Evidence that this was live, not theoretical: `[DataRetention] Cleanup cycle: 0 communications,
1 customer profiles deleted.` (1 Oct 2026).

## The fix: a durable stamp

`customers.last_activity_at` is written at the moment of contact and never inferred from rows
another job may have deleted. It is updated from the **insert choke points**, not the route
handlers, so no code path can forget it:

| Hook | Stamped by |
|---|---|
| `insertSmsMessage` | phone |
| `insertInboundSmsDeduped` (real messages only, not suppressed duplicates) | phone |
| `insertOutboundSmsIfNotDuplicate` | phone |
| `createInboundCallLog` | caller's phone |
| `addAppointment` | customer id |

Matching is by **phone**, not customer id, because `sms_message.customer_id` stays NULL until a
booking is confirmed — most inbound traffic only ever carries `counterparty_phone`.

Writes are **monotonic**: a replayed or out-of-order write can never move a customer's last
activity backwards and make them look staler than they are.

### `NULL` means unknown, and unknown is never deleted

A customer with no stamp and no surviving evidence is in the third state — not active, not
inactive, *unknown* — and is excluded from flagging and deletion entirely. The count appears in
the log line each sweep (`N skipped with unknown last activity`) so the deliberate non-action is
visible rather than silent.

Note the protection is partly structural: in SQL, `NULL < cutoff` is `NULL`, not true, so such
rows drop out of the predicate regardless. The explicit `IS NOT NULL` documents the intent, and
the tests pin the *behaviour* — they catch the realistic mistake of "fixing" it with
`COALESCE(last_activity_at, 0)`, which is the `?: 0` anti-pattern
[recovery-code-rules.md §2](recovery-code-rules.md) calls out by name.

### Backfill

At startup, `last_activity_at` is reconstructed once from whatever evidence survives. Because
communication retention only ever deletes rows **older** than its window, the newest surviving row
*is* that customer's most recent activity — an exact reconstruction, not a guess. Customers with
no surviving evidence stay NULL. Idempotent: only fills NULLs.

**Known bound:** shops can have different `communication_retention_days`. A customer whose most
recent contact was with a short-retention shop, and whose only surviving row is older (from a
long-retention shop), is backfilled too early and looks staler than they are. Today the only shops
at 5 days are both Testshops with zero SMS and zero calls, so it cannot bite; the grace window and
the review list are the backstop if it ever does.

## Two-stage deletion

```
no activity for customer_retention_days (default 730)
        ↓   flag  (deletion_flagged_at = now)
   visible in WebAdmin → /customers/flagged
        ↓   30 more days (CUSTOMER_DELETION_GRACE_DAYS)
      delete
```

Any call, SMS or booking during the grace window **clears the flag outright** and restarts the
two-year clock. "Keep" in the review list does the same manually.

Each sweep (hourly) evaluates eligibility once and applies all three stages to that one snapshot,
so they cannot disagree with each other.

### Guards

| Guard | Protects against |
|---|---|
| `last_activity_at IS NOT NULL` | deleting someone whose history we simply do not know |
| no appointment in the future | deleting someone with a booking coming up |
| no SMS or call inside the window | a missed stamp hook; also covers a call **in progress**, whose row is dated now |
| no active blacklist entry | silently un-blocking a nuisance caller (`deleteCustomer` deactivates their block) |
| 30-day grace, cleared by any activity | a wrong flag becoming a deletion |
| most-lenient shop window | one shop deleting a customer another shop still considers active |
| flag re-verified against a fresh snapshot before DELETE | a flag that is up to 30 days stale |

The last two are **independently sufficient** for the blacklist case — verified by disabling each
in turn (the test still passes) and then both (it fails). That is defence in depth, deliberately.

A live call needs no separate guard: an in-progress call or SMS writes a row dated now, which is
inside the window, so that customer cannot be eligible.

### Failure mode declaration

```
TRIGGER:  customers.last_activity_at older than max(customer_retention_days) across shops
          (default 730 days), evaluated hourly by the cleanup scheduler.
EVIDENCE: STRONG. last_activity_at is written at the moment of each call/SMS/booking and is
          never inferred from rows another retention job may already have deleted.
BLAST:    one customer profile row per firing, plus deactivating that phone's blacklist entries.
          Communications are not touched. No effect on calls, trunks or config.
GUARD:    the table above; re-evaluated every sweep, required again before the DELETE.
IF WRONG: a customer profile (name, phone, language, CallApp name) is lost. Their booking and
          message history survive. The next time they call they are re-created as a new
          "New/NoName" customer, so the shop can still serve them — the loss is their recognised
          name, not their ability to get through.
```

## Performance — measured, not assumed

The eligibility predicate looks up SMS and calls by **phone alone**, but both existing compound
indexes are led by `shop_id` and cannot serve that. Measured on a copy of the live DB
(1372 customers, 11379 SMS, 1034 calls), worst case with every customer eligible:

| | Time |
|---|---|
| Before (full table scans, `SCAN s`) | **5.6 s** |
| After six supporting indexes | **22 ms** |

5.6 s matters because this runs hourly on the **single serialized SQLite connection** that also
handles inbound SMS and call logging — it would have been 5.6 s of blocked phone system, three
times per sweep in the first draft. Indexes added:
`sms_message(counterparty_phone)`, `sms_message(customer_id)`, `voice_call(from_phone)`,
`voice_call(customer_id)`, `appointments(customer_id)`, `phone_blacklist(phone_e164, active)`.

The worst case only arrives once customers actually age past the window — today the first condition
excludes everyone and the sweep costs ~4 ms. It would have shown up in two years, by which time
nobody would have connected it to this change.

## Configuration

Both windows are per-shop and now independent, with the UI cap raised from 365 to 36500 days:

- **Communication history** — keep for N days (default 5). Deleted outright.
- **Customer profile** — flag after N days without activity (default **730**).

A one-time migration (`raiseCustomerRetentionToTwoYearsOnce`, guarded by an `app_setting` marker)
raises every shop to 730 on first boot, because the live shops carry an explicit 365 and the test
shops 90, so changing the default alone would not reach them. It **only ever raises** — a longer
window deletes strictly less, so it cannot destroy anything — and runs exactly once, so a later
deliberate reduction from the web admin is not overridden.

## What to expect after deploy

Nothing visible for a long time. Every current customer has recent surviving evidence (oldest SMS
is 2 Aug 2026), so backfill gives them all a recent stamp and **no customer becomes eligible for
flagging for roughly two years**. That is the intended behaviour, not a sign it is not working.
The proof it is wired up is the startup line and the hourly sweep's silence.

## Tests

`CustomerRetentionTest` — 22 tests against a throwaway on-disk SQLite DB, with time injected via
`runCustomerRetention(now = …)` rather than back-dating rows, so they exercise the production path.
Covers: flagging boundaries, unknown-activity exclusion (including over a simulated decade),
flag idempotence, grace-window boundaries either side, activity clearing a flag, un-flagging when
eligibility lapses, blacklist protection, monotonic touch, phone-based matching with a NULL
`customer_id`, the most-lenient-shop rule, backfill behaviour, and the one-time raise being
raise-only and once-only.
