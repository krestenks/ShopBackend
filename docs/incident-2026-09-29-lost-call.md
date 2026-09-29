# Incident 2026-09-29 13:02 — one customer call lost to shop 2

**Investigation only. Nothing was changed, restarted or sent to any device.** Claims are marked
CONFIRMED (I read the log line) or SUSPECTED. Written while the evidence was fresh; the edge's
`quecteldbg` and journal still hold it if you want to re-check.

---

## What the customer experienced

A customer called **shop 2 at 13:02:59** and got nothing — no ring, no busy tone, dead line. The call
never reached the manager's phone. Two seconds later Asterisk gave up.

This is the **first call lost to this cause** since the 1.0.110 deploy on 20 Sept: 1 of ~130 inbound
calls. Every previous reachability gap happened to fall in a quiet moment.

## Timeline (CONFIRMED)

| Time | Event |
|---|---|
| 13:00:37 | Last normal heartbeat from the S26. Wifi, signal steady, battery 86%, Doze whitelist on, SIP pump **not** suspended. |
| 13:00:37 → 13:04:02 | **Nothing from the phone at all** — no heartbeats, no SIP. ~3.5 minutes of silence. |
| 13:02:59 | Customer calls shop 2. Backend resolves the on-duty target to `PJSIP/mgr2`. |
| 13:03:01 | Asterisk: `Could not create dialog to invalid URI 'mgr2'. Is endpoint registered and reachable?` → `cause 3 — No route to destination`. Call ends as OPERATOR_DECLINED. |
| 13:03:25 | I check: endpoint **Unavailable**, *both* contacts `Unavail / nan`. |
| 13:04:02 | One heartbeat arrives, then silence again. |
| 13:06:24 | Heartbeats resume properly. |
| 13:06:58 | Endpoint back — and already **Ringing** (a later call being delivered). Ping to the phone normal (43–209 ms). |
| 13:07:27 | Backend logs `mgr2 reachable again after 204s`. |

## What the evidence shows

**1. It was a total loss of connectivity to the handset, not a SIP-only fault. CONFIRMED.**
The app's HTTP heartbeats stopped at the same time as SIP. That rules out the "reachable but
SIP-deaf" class we have chased before, and it rules out Asterisk, the dialplan and the modems — all
four trunks were Free and healthy throughout.

**2. The phone was not dozing, throttled, or low on battery. CONFIRMED.**
Every heartbeat up to the gap reports `dozeWhitelisted=true bgRestricted=false powerSave=false
deviceIdle=false pumpSuspendedMs=0`, battery 86%. The known Android power-management causes are
excluded.

**3. It did not change network. CONFIRMED.**
`transport=wifi` before, during the brief 13:04 heartbeat, and after. Reported signal is flat
(−104 to −106) across the whole window. So this is **not** the wifi↔cellular handover pattern that
explained Saturday's dropouts.

**4. The edge end was fine. CONFIRMED.**
No NTP loss, no tailnet control errors, no service restarts; the other tailnet peers were unaffected
and `tailscaled` logged nothing unusual about this phone during the gap — it simply went quiet.

**5. So the break was on the phone's side of the path. SUSPECTED (strong).**
Either the handset's wifi/internet dropped briefly, or its tunnel to the relay stalled. I cannot
distinguish those two from the edge alone: both look identical from here — the peer goes silent and
comes back. Distinguishing them needs the phone (its own logs), which I did not touch.

**6. Recovery took 204 seconds, and nothing we run made it faster. CONFIRMED.**
No self-heal rung, no DISAGREEMENT line, no rebuild — the app's watchdog runs on a 60 s cycle but
left no trace here. The phone simply came back when its connectivity returned.

## Why the call was lost rather than degraded

When the only on-duty phone has no usable registration, the dialplan has nowhere to send the call:
`Dial(${TARGETS},30)` fails instantly and the caller gets dead air. There is **no fallback** — no
voicemail, no divert, no second device. That is a design gap, not a bug: the system has exactly one
ringable phone (by design — see [phone fleet roles](../CLAUDE.md)), so any gap in that phone's
connectivity is a gap in the shop's phone line.

## What I would *not* conclude

- **Not** the 1.0.110 release. The app behaved correctly; it was unreachable, not misbehaving.
- **Not** the phantom-call/driver defect — different signature entirely, and no trunk was wedged.
- **Not** the shared-relay finding from 20 Sept. The edge reached everything else normally; only this
  peer went quiet.
- **Not** proof of a worsening trend. This gap (3.5 min) is in line with the others this week
  (2–7 min); what changed is that a customer happened to call during one.

## Options, for when you are back

Ranked by value against effort. **None of these is urgent, and none is started.**

1. **Accept and do nothing.** Consistent with your standing position that keeping the phone online is
   the manager's responsibility. Cost: roughly this — one lost call per week or two, invisible to us
   unless we look.
2. **A fallback for "nobody reachable" — the highest-value fix.** Covered in
   [network-fallback-lte-plan.md §5.2](network-fallback-lte-plan.md): network-side call forwarding on
   the SIM (no hardware, no data, works even if the edge is off) or a divert via a second modem.
   Either turns this incident into a customer reaching *someone* instead of silence. Still blocked on
   the one open question: whether Lebara provisions `CCFC` on these prepaid SIMs.
3. **A second ringable phone on duty.** The S25 already registers the shop identities; putting it on
   duty would make the pool ring two devices. Costs nothing technically — but it rings a spare
   handset that nobody carries, so it only helps if someone is actually near it.
4. **Shorten detection.** Asterisk needs ~30 s of failed probes to mark the phone unavailable, so a
   call arriving in that window fails with no ring at all. Probing more often would narrow the
   window, at the cost of more traffic to a phone whose path already loses packets. Marginal.
5. **The rebuild lever.** The backend can command the app to rebuild its SIP account. It would not
   have helped here: the phone was unreachable, so the command could not have arrived. Useful for the
   *SIP-deaf* class, not this one.

## Open question worth answering with the phone in hand

Whether these gaps are the handset's wifi dropping or the tunnel stalling. The phone's own logs would
settle it in minutes (`adb logcat` around a gap), and it decides whether the fix is a router/wifi
change at that location or something in the app's networking.
