# Offline alerting: two SMS per outage, not two hundred

**Plan only. Nothing changed.**

Goal: one SMS when the manager phone goes offline, one when it comes back. Not one per shop, and
no repeats while it stays down.

---

## 1. What it costs today — measured, not estimated

| Outage | Alert SMS queued |
|---|---|
| 7–8 Oct, ~18 h | **204** |
| 10 Oct, from 03:48 (ongoing at 09:23, ~5.5 h) | **144** |

In the 7–8 Oct window the backend sent 293 SMS in total, so **alerting was ~70% of all SMS
traffic**. And it is being paid for out of the SIMs' prepaid balance:

```
shop1   106.92 kr     <- below the 120 kr floor we monitor
shop2   196.27 kr
shop5   197.08 kr
shop6   178.44 kr
```

Shop1 is already under the threshold from [Lebara DK billing](../CLAUDE.md) — low cash silently
kills **outbound** SMS and calls while inbound keeps working, which is a failure that hides itself.
There is also the standing A2P fair-use risk: a SIM sending hundreds of identical machine-generated
messages is exactly the pattern that gets one blocked.

So this is not only noise. The alerting is eating the credit the shops need in order to work.

## 2. Why one offline phone produces so many messages

`SipReachabilityMonitor` keys everything on the **shop**, and alerts per manager within it:

```kotlin
private val downStreak  = HashMap<Int, Int>()    // shopId -> consecutive down polls
private val lastAlertAt = HashMap<Int, Long>()   // shopId -> last alert
```

Per 30-minute window, for a single unreachable phone:

- **4 shops** are each independently "unconnected"
- each queues an SMS to **every** manager on that shop with a number on file — Kresten
  (`+45XXXX36`) and Jiji (`+45XXXX55`)
- plus an admin SMS to `+45XXXX35`, and `ReliabilityAlerter`'s cooldown key is
  `"$category:${shopId ?: 0}"` — **per shop**, so that is 4 admin SMS, not 1

≈ **12 messages every 30 minutes**, for one phone, to three distinct numbers. Over 18 hours that is
the 204 above.

The irony: the thing that is actually down is *one phone*. Everything else is fan-out.

## 3. The design

Track state **per manager endpoint**, not per shop, and make the alert **edge-triggered**.

```
          unreachable ≥ DOWN_DEBOUNCE            reachable ≥ UP_DEBOUNCE
  UP  ─────────────────────────────────▶  DOWN  ─────────────────────────────▶  UP
         send ONE "offline" SMS                   send ONE "back online" SMS
                                                  (with the outage duration)
```

No re-notify. While the state is DOWN, nothing further is sent, however long it lasts.

Per-manager rather than "the manager phone" because it generalises if a second handset is ever put
on duty — and in practice there is one on duty, so it yields exactly one pair of messages.

### Only managers who are on duty somewhere

Unchanged from today, and it matters: the spares (S25, S21, A70) are offline most of the time by
design, and [phone fleet roles](../CLAUDE.md) says that is not an incident. A manager not on duty
in any shop is never alerted on.

### Thresholds, calibrated against the logs

Every self-healing gap ever logged, from `reachable again after Ns`:

```
   33s  x1
   40s  x1
   50s  x1
   60s  x4
  106s  x1
```

The longest gap that recovered on its own is **106 s**. The existing debounce — 5 polls × 2 min ≈
**10 min** — already sits 5.7× above that, which is why none of those gaps ever produced an alert.
**Keep it.** This is a threshold the data supports; there is no reason to invent a new number
(recovery-code-rules §6).

`UP_DEBOUNCE` is new, and needed: without it a flapping phone alternates DOWN/UP pairs. Two polls
(~4 min) is enough to ride out the measured gaps while keeping the "back online" message prompt.

### Flap guard, with a bound and a voice

A phone that cycles slower than the debounces could still produce repeated pairs. So: no new DOWN
alert within `FLAP_GUARD_MS` (30 min) of the last UP for the same manager.

Per recovery-code-rules §4 the suppression must be bounded and must say so:

```
[SipMonitor] SUPPRESSED offline-alert mgr=2 reason=flap-guard sinceLastRecovery=12m bound=30m
```

The bound is a time limit, not a counter, so it can never suppress permanently.

### Who gets it

The admin number only, by default — that is what was asked for. Done by calling
`alerter.record(..., shopId = null, ...)`, which makes the cooldown key `"category:0"`: one global
key instead of one per shop. No change needed inside `ReliabilityAlerter`.

The sending SIM comes from the existing `pickHealthyTrunk(null)` — any trunk currently `Free`.
Today it prefers the affected shop's SIM; with no single affected shop, any healthy one will do,
and that also stops the alerts always landing on shop1's balance.

**This is the one open question — see §6.**

### Messages

GSM-7 ASCII only, one segment each (the existing alerts already follow this):

```
ShopManager: Jiji's phone went OFFLINE at 03:48. Shops without a line: Nova, JJ, Gina, Koi.
ShopManager: Jiji's phone is back ONLINE at 09:31, after 5h 43m offline.
```

The duration in the recovery message is the part worth having: it is the difference between "it
blipped" and "the shops had no phone line all morning", and right now that has to be reconstructed
from the journal by hand.

## 4. What deliberately does not change

- **AMI down → skip the whole check.** Inconclusive input must not authorise alerting every shop;
  this already works and is the right behaviour.
- **No on-duty manager → no alert.** Nobody claimed coverage, so nothing is wrong.
- The 2-minute poll interval.
- `ReliabilityAlerter` itself, and the reliability event log — every transition still gets a row,
  so the history survives even though the SMS volume drops.

## 5. Known limitation: state is in memory

`downStreak` / `alerted` live in the process. A backend restart **during** an outage loses them,
and after the restart the monitor will re-derive DOWN from the first polls and send one more
offline SMS. That is a duplicate, not a miss, which is the right direction — and the alternative
(persisting it) buys little for the complexity.

The converse case, a phone going down and recovering entirely within a restart window, is missed.
Acceptable: the reliability event log still records nothing, so there is nothing to explain away.

Worth stating plainly because §4 of the recovery rules applies to alerting too: **"the alert never
fired" is silent.** The mitigation is that every transition decision is logged, including
suppressions, so `grep SipMonitor` tells you what the state machine thought — rather than silence
being ambiguous between "nothing happened" and "it is broken".

## 6. Decided and built (2026-10-10)

**Keep the nudge** — Kresten's call. So each outage sends:

| Edge | To the admin | To the affected manager |
|---|---|---|
| offline | one SMS, naming the manager and the stranded shops | one nudge: "open the ShopManager app to reconnect" |
| back online | one SMS, with the outage duration | nothing — no point texting a phone that just came back |

**3 SMS per outage, down from 204.**

Built as `ManagerReachabilityTracker`, a pure state machine with no Asterisk, SMS or clock in it,
so the measured gaps can be replayed exactly. `SipReachabilityMonitor` now iterates **on-duty
managers** rather than shops, which is also what keeps the spares out: being off duty is how the
fleet declares a phone is not expected to be ringable, and today only mgr2 (Jiji) is on duty —
Kresten and Testmanager are both `on_duty = 0`. So this yields exactly one pair of messages, and
it stays correct if a second handset is ever put on duty.

Two things fixed while building:

- **`ReliabilityAlerter` gained an optional `dedupeKey`.** Its rate-limit key was
  `"category:${shopId ?: 0}"`, so with `shopId = null` two managers going offline inside the
  15-minute cooldown would have collapsed into one admin SMS. Now keyed per manager.
- **The reported outage duration was overstating by the up-debounce.** A test caught it: an
  18-hour outage reported 18 h 2 m, because the duration ran to the *confirming* reachable poll
  rather than to the first one. The phone was already back at the first good poll; the debounce is
  our caution, not part of the outage. Now measured to the first reachable poll, which also makes
  the figure independent of the debounce setting.

### Tests (13, all passing)

Replayed against the real data: **every gap that ever healed itself (33, 40, 50, 60, 106 s)
produces no alert**. Plus: the debounce boundary, an 18-hour outage producing exactly one pair,
200 further polls while down producing nothing, recovery needing the up-debounce, a recovery that
was never reported staying silent, inconclusive readings never alerting *and* never advancing the
streak, an AMI dropout mid-outage not re-alerting, the flap guard reporting once and being bounded,
managers tracked independently, and off-duty clearing the state.

Teeth verified by breaking it twice: removing the "already reported" gate turns 3 red (including
the 18-hour case), and treating an inconclusive reading as "down" turns the null-input test red.

## 7. Open, if you want it later

**Should the affected manager still get a nudge?**

Today Jiji gets an SMS per shop telling her to reopen the app. If this goes admin-only, that stops.

It matters because the manager SMS is **the only mechanism that actually fixes the problem** — it
arrives over GSM, so it reaches the phone even when the app and the tailnet are down, and it is
what prompts someone to reopen the app. You said this is not something you can do anything about,
which is exactly right: you cannot, but she can.

My recommendation: **keep exactly one nudge to the affected manager**, sent on the same DOWN edge
as your admin SMS, and nothing on recovery. That is 3 SMS per outage instead of 204, and it keeps
the thing that gets the phone back.

If you would rather have the pure two-SMS version, say so and I will drop it — it is a one-line
difference, and "it is the manager's responsibility to stay online" is a defensible reading too.

Two smaller choices, where I will just pick if you do not care:

- Should the offline message list the affected shops, or only name the manager? (I would list them —
  it tells you the blast radius at a glance.)
- Keep the 10-minute threshold, or go to 15 for extra margin? (I would keep 10; the data supports
  it, and a longer wait delays a genuine outage.)

## 7. Testability

The interesting logic is a pure state machine, so it should be extracted as one — a function from
`(previous state, now, reachable: Boolean?)` to an action (`None` / `AlertDown` / `AlertUp` /
`Suppressed(reason)`). Then these are unit tests with no AMI and no modem:

- every measured gap (33 s … 106 s) produces **no** alert
- a 20-minute outage produces exactly one DOWN and one UP, and nothing in between
- an 18-hour outage still produces exactly one DOWN and one UP
- `reachable = null` (AMI inconclusive) never produces an alert, and does not advance the streak
- flapping is capped by the guard, and the suppression is reported rather than silent
- a manager on duty nowhere is never alerted on
- the recovery message's duration is computed from the first down poll, not from the alert time

## 8. Expected result

| | Today | After |
|---|---|---|
| SMS per outage | 204 (18 h) | **2** (or 3 with the manager nudge) |
| Numbers messaged | 3 | 1 (or 2) |
| Repeats while down | every 30 min | none |
| Recovery notice | none | one, with the duration |
| Paid from | mostly shop1's balance | any healthy SIM |

## 9. Note on right now

There is an outage **in progress** as this was written: mgr2 `Unavailable` since 03:48 today,
~5.5 hours, 144 alert SMS already sent. The shops have no ringable phone while it lasts. That is
the thing to act on; this plan only stops the alerting about it from costing money.
