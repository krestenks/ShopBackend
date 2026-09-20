# LTE network fallback for the edge — feasibility and plan

Written 2026-09-20, after the 14:51–16:45 outage. **Planning only; nothing was changed.** All findings
below are read-only observations from the edge box.

---

## 1. What actually failed on 2026-09-20 — the edge's *uplink*, not the local network

This matters more than anything else in this document, because LTE at the edge only helps for one of
those two failures.

**Evidence (STRONG, four independent sources):**

| Source | Observation |
|---|---|
| `systemd-timesyncd` | Timed out against **four** public NTP servers at 15:04; "Initial synchronization" again at **16:47**. |
| `tailscaled` | 14:54:18 `control: lite map update error … Post "https://headscale.warpfactor.dk/machine/map": context canceled`, then repeated `connect: no route to host` / `network is unreachable` to external IPs. |
| Kernel | **No link events at all** on `enp0s25` in the window — the NIC stayed physically up. |
| `tailscaled` again | Kept exchanging disco pings with `eriks-s21` (the **LAN** handset, direct path `192.168.0.229`) *throughout* the window — 92 log references between 15:05 and 16:40. |

So: layer 1 and the LAN survived; the **default route to the internet went away** (`no route to host`
is the gateway being unreachable, not DNS or a far-side failure). The site's router or its WAN died,
and came back at ~16:45–16:47.

**Correction to my earlier report.** I previously said "all phones dropped at once, including the
LAN-attached S21, so it must be the LAN". That reasoning was wrong: **only the S26 (mgr2) sends
telemetry at all** — 953 heartbeats today, zero from mgr1/mgr3, because the spares are off duty and
their SIP/telemetry services are stopped by design. The S26 alone carries `mgr2` **and** all four
`shopN-manager` identities, so one phone going offline is sufficient to make all four shops alert.
There was never evidence of a fleet-wide drop.

**Does LTE fallback help this failure? Yes.** The S26 is remote: it reaches the edge over the internet
via the Headscale DERP relay. Restoring *any* uplink at the edge restores that path, provided the
handset's own site is up (it was — the phone recovered the instant the edge did).

**Where it would NOT help:** a dead switch or AP at the edge site, the handset's own internet failing,
or Headscale (`t6.warpfactor.dk`) being down. Those are different outages and LTE at the edge changes
nothing for them.

---

## 2. Feasibility on this hardware

### 2.1 The blocker: the spare modems have no SIM

```
hub 1.2.5   SIM: +CME ERROR: 10 (SIM not inserted)   CSQ 22 (good signal)   usbnet=0 (QMI)
hub 1.2.6   SIM: +CME ERROR: 10 (SIM not inserted)   CSQ 23 (good signal)   usbnet=0 (QMI)
```

Six EC25s are in the rack; four carry shops (`shop1, shop2, shop5, shop6`), two are spare **and
empty**. The backend agrees: "Full provisioning pass complete (6 shop(s), 4 with SIM)".

**So this plan needs a SIM bought for it.** That is the main cost decision, and it is a feature, not a
problem — see 2.3.

### 2.2 The plumbing is already present

- `qmi_wwan`, `cdc_wdm`, `usbnet` are loaded; `wwan0..wwan5` exist (all DOWN).
- The modems are in `usbnet=0` (QMI/RMNET) — the right mode for a data session.
- ModemManager runs but **sees no modems** (`mmcli -L` → none); a udev rule keeps it off the
  Quectels. Whatever we do must keep MM away from the four telephony modems (see
  [phone-server-setup.md](phone-server-setup.md)); letting it manage *only* the data modem needs a
  narrowly-scoped rule, by USB path, not by vendor id.
- The box also has an unused wifi interface (`wlp3s0`, DOWN) — relevant to the cheap alternative in 5.3.

### 2.3 Voice/data contention — why a dedicated modem is the right design

On a shop modem, voice and data *can* coexist now that all four have VoLTE plus the carrier profile
(see [modem radio config](phone-server-setup.md) §7b): with VoLTE the call rides IMS and the data
bearer stays up. But the fallback would then depend on VoLTE staying healthy, and a CSFB fallback
suspends data for the duration of every call — i.e. **the uplink would drop exactly when a customer
is talking**. chan_quectel also owns the AT port and reinitialises the module on its own recovery
ladder, which would take the uplink with it.

**Recommendation: dedicate a spare modem + its own data SIM.** No contention with telephony, no
interaction with the chan_quectel watchdogs, and the shop SIMs' voice minutes and A2P standing stay
untouched. Marginal cost is one data SIM.

### 2.4 Data volume and cost — plausible, but verify before buying

- A relayed SIP call is G.711 both ways: **~90 kbit/s**, about **40 MB/hour** of call.
- Idle cost is small: qualify probes every ~30 s per AoR plus the app's polling — historically a few
  MB/hour, though a churning registration once burned ~66 MB in a day.
- A day fully on LTE with an hour of calls is comfortably inside **1 GB**.
- The shop SIMs' Lebara package is recorded as 60 GB / 10 h voice for 99 kr/30 d. **Unverified** for a
  new data SIM, and I did not query the live SIMs' allowances for this document (network-bound AT
  queries through `quectel cmd` caused command timeouts earlier today — see
  [telephony-reliability.md](telephony-reliability.md)).
- **Open question:** whether the cheapest Lebara data SIM permits this traffic pattern; also whether
  the edge's public-facing needs (none — it only makes *outbound* connections to Headscale) survive
  CGNAT on mobile. They should: the tailnet is outbound-only and DERP-relayed.

---

## 3. Design, if we go ahead

### 3.1 Topology

```
shops' customers ──GSM──> 4 EC25 (voice) ──> Asterisk ──> backend
                                                  │
manager's S26 <── tailnet/DERP ── Headscale ──────┤ primary: enp0s25 → site router  (metric 100)
                                                  └ fallback: wwan4 → LTE data SIM  (metric 700)
```

Only the *edge's* uplink changes. The handsets, Asterisk, chan_quectel and the dialplan are untouched.

### 3.2 Failover detection that cannot flap

The rule that matters (RULE 1): **weak evidence must never authorise destroying working state.**
A route switch is destructive — it rebinds every socket the box has.

- Probe the thing we actually need: a TCP connect to `headscale.warpfactor.dk:443` **and** an ICMP
  ping to the default gateway, every 15 s.
- Switch to LTE only after **4 consecutive failures of both** (≈60 s) — gateway-up-but-internet-down
  and gateway-down are both real failures, but one probe failing alone is not.
- Switch back only after **10 consecutive successes** (≈2.5 min) on the primary.
- **Never switch while a call is up** unless the primary has been dead for >5 min: check
  `core show channels count` first. A live call is already being carried by *something*; moving the
  default route under it is how we would turn a 5-call outage into a dropped conversation.
- Rate-limit: at most one transition per 10 min, and log every decision with the evidence that drove
  it (`[NetFallback] TRIGGER=… EVIDENCE=… ACTION=… callsUp=…`).
- Fail closed: if the probe itself cannot run (script error, modem missing), **do nothing** and alert.

### 3.3 Mechanics

- Bring the data session up **permanently** (a always-on second default route at high metric), not
  on demand: attaching takes 10–30 s, and we do not want that latency inside an outage. Cost is a few
  MB/day of keepalive.
- Route switching by metric, via `ip route` or NetworkManager, rather than tearing interfaces down.
- `tailscaled` handles the interface change itself and re-establishes DERP within seconds; no restart
  of Asterisk, the backend or anything else is needed. **This is the key reason the design is safe:
  nothing in the call path restarts.**
- Alert the admin by SMS through a shop modem on every transition (the modems work regardless of IP).

### 3.4 Interaction with existing recovery code

- `ModemStuckWatchdog` and the down-recovery ladder must **ignore the data modem** — otherwise a
  stalled data session could trigger a telephony recovery, or vice versa. Explicit allow-list.
- `SipMonitor` will still alert during the ~60 s detection window. Expected, not a fault.
- The app's own SIP watchdog will re-register when the path returns; no change needed.

### 3.5 Testing without taking shops offline

1. Bench first: bring the data SIM up on the spare modem, confirm the tailnet reaches the S26 over it
   with the primary still active (policy-route a single test flow).
2. Measure a real call over LTE (test call to a spare handset), checking audio and latency.
3. Rehearse failover by blackholing the primary route in a namespace, or by an evening window with
   shops closed, unplugging the WAN cable and timing full recovery.
4. Only then arm automatic failover, initially in **alert-only mode** (log what it *would* do) for a
   week before letting it act.

---

## 4. Risk summary

| Risk | Severity | Mitigation |
|---|---|---|
| Route switch drops a live call | High | Never switch while a call is up unless dead >5 min |
| Flapping between uplinks | High | Asymmetric hysteresis, 10-min transition cap |
| ModemManager grabs a telephony modem | **Critical** (all shops) | udev allow-list by USB path; verify `mmcli -L` after every change |
| Data SIM runs out / is throttled | Medium | Monitor bytes; alert at 80% of allowance |
| Silent failure of the fallback itself | Medium | Weekly automated LTE reachability self-test with SMS alert |
| Added latency on relayed calls | Low–Medium | Measure in step 2 before arming |

---

## 5. Honest comparison with the alternatives

### 5.1 LTE uplink fallback (this plan)
Keeps **everything** working during an edge-uplink outage: calls, SMS, bookings, the app, admin.
Costs one SIM plus a moderate amount of careful recovery code. Helps only that one failure class.

### 5.2 Divert unanswered calls to the manager's mobile
Saves the *call* in **every** unreachability case — edge uplink down, handset offline, app dead,
tailnet broken. Cheaper to build. Two important findings:

- **It does not need the internet at all.** The dialplan and backend run locally on the edge, so a
  divert executes even with the WAN dead. That makes it strictly more robust than LTE fallback for
  the narrow goal of not losing customers.
- **But it cannot reuse the same modem.** chan_quectel handles one call per module, so bridging an
  inbound call to an outbound one needs a *second* modem with a SIM — the same purchase as 5.1.
  The alternative is **network-side call forwarding** (`AT+CCFC`, forward-on-no-reply to the
  manager's mobile), which needs no hardware, no data and survives the edge being switched off
  entirely. **Uncertainty:** `AT+CCFC=1,2` returned `+CME ERROR: retry operation` / `unknown` on all
  four SIMs today, so I cannot confirm Lebara provisions the service on these prepaid SIMs. That is
  a 10-minute test with the carrier, and it decides whether the cheapest option exists at all.

### 5.3 Cheap stopgaps worth knowing about
- The edge has an **unused wifi adapter**. A phone hotspot (or a spare handset kept on charge) is a
  zero-cost manual fallback: connect `wlp3s0`, and the box has an uplink in a minute. Manual, but it
  needs no purchase and no new code.
- Simply **alerting you** when the edge's uplink dies (SMS through a modem, which works regardless)
  turns a two-hour silent outage into a five-minute one. That is a few lines of code and no hardware.

---

## 6. Recommendation

**Do them in this order:**

1. **Uplink-down SMS alert (this week, tiny, near-zero risk).** Today's outage ran 114 minutes
   before anyone knew. Detection is most of the value, and it is a probe plus an SMS.
2. **Test whether `AT+CCFC` forwarding works on a Lebara SIM (one phone call to the carrier).** If it
   does, network-side forward-on-no-reply is the single highest-value resilience we can buy: no
   hardware, no data, works even if the edge is dead. If it does not, fall back to 5.2's second-modem
   divert, which shares the SIM purchase with the LTE plan.
3. **Then LTE uplink fallback**, as designed above, in alert-only mode first.

**Which gives more resilience per unit of effort:** the divert/forwarding path, clearly — it covers
more failure modes for less code and less risk, and it protects the thing that actually costs money
(a customer who cannot reach the shop). LTE fallback protects the *system*, which matters, but today
it would have saved five calls that forwarding would also have saved.

**They are complementary, not competing.** Forwarding saves calls when everything is broken; LTE
fallback keeps the system running so that SMS, bookings and the app also survive. If the budget is
one SIM, buy it for whichever of 5.2/5.1 we build first — the same SIM can serve both if it has
voice *and* data.

---

## 7. Open questions to settle before building

1. Does Lebara provision call forwarding (`CCFC`) on these prepaid SIMs? — decides the cheapest option.
2. What does the site's router/ISP actually do? Today's outage lasted ~114 min with no link-down on
   our NIC. Is this the first occurrence, or has it happened before unnoticed? Worth checking the
   router's own logs — the fix might simply be a better router or a second ISP.
3. Cheapest Danish data SIM that permits VoIP relay traffic, and whether an EC25 on CGNAT holds a
   stable DERP session for days.
4. Whether we want the fallback automatic at all, or operator-triggered from the admin UI. Manual is
   materially safer and, with alert (1) in place, only costs minutes.
