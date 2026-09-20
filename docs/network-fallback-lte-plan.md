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

**Where it would NOT help:** a dead switch or AP at the edge site, or the handset's own internet
failing. And — see §1b, which supersedes the optimistic reading above — **not today's outage either,
as the system is currently built.**

---

## 1b. The relay and the control plane are on the same LAN — this breaks the naive LTE plan

Kresten asked whether this is a problem. It is, and it is the larger of the two problems.

**Facts (verified read-only, 2026-09-20):**

| Check | Result |
|---|---|
| `getent hosts headscale.warpfactor.dk` | `86.52.31.162` — the **site's own public IP** |
| `ip route get 86.52.31.162` | `via 192.168.0.1 dev enp0s25` — out through the **same router** |
| `ping 192.168.0.2` (server8) | 0.25–0.37 ms — **same LAN** as the edge (192.168.0.192) |
| `tailscale debug derp-map` | **one** region (999), `headscale.warpfactor.dk`, DERP 443 / STUN 3479 |
| Headscale `config.yaml` | `derp.urls: []`, `derp.paths: []`, embedded server only, `verify_clients: true` |
| Headscale `node.expiry` | `0` — node keys never expire (good: nodes tolerate control being down) |

**Consequence 1 — the whole tailnet shares one failure domain.** The edge, the DERP relay, the STUN
server and the Headscale control plane all sit behind `192.168.0.1`. When that uplink died at 14:51,
a remote handset had no path to *any* of them. That is exactly what we saw.

**Consequence 2 — the edge loses its own control plane even though it is 0.3 ms away.** The edge
reaches Headscale by its **public** name, so the traffic hairpins out to the router and back. With the
WAN down there is no hairpin, and `tailscaled` logged
`control: lite map update error … context canceled` at 14:54 while the server it was talking to was
sitting on the same switch.

**Consequence 3 — LTE fallback alone would NOT have restored service today.** On LTE the edge is
behind CGNAT: outbound-only, which is fine for reaching a relay. But the only relay in the map is at
the site whose uplink just died. The edge would come up on LTE, find its relay unreachable, and the
manager's phone would still have no path in. **The naive version of this plan does not work.**

**What does work:** a relay that lives outside the site. Then the path during an edge-uplink outage is
`phone (cellular) → offsite DERP → edge (LTE, outbound through CGNAT)` — both sides dial out, so
CGNAT on either end is not an obstacle. Control-plane downtime is survivable meanwhile, because
`node.expiry: 0` and clients run on a cached netmap; what they cannot do is onboard a new device or
pick up ACL changes until it returns.

**Therefore an offsite relay is a prerequisite for the LTE plan, not an alternative to it.** They fix
different halves of the same path: the relay makes the edge *findable*, LTE makes it *connected*.
Neither alone restores a remote handset.

### 1b.1 Options for an offsite relay, cheapest first

1. **Add public DERP regions to the Headscale DERP map** (`derp.urls:
   ["https://controlplane.tailscale.com/derpmap/default"]`). Zero cost, config-only, no hardware.
   **Uncertainty, flagged honestly:** Tailscale's public DERP servers are operated for Tailscale's
   own control plane and may refuse or rate-limit clients from a self-hosted Headscale. This is
   widely done in the Headscale community but it is not a guarantee we control, and relying on
   someone else's free infrastructure for our fallback path is exactly the kind of dependency that
   fails silently. **Test before trusting it.**
2. **Run our own `derper` on a small VPS** (~5 €/month, offsite). Fully under our control, and the
   honest choice for something a shop's phone line depends on. Note `verify_clients: true` requires
   the derper to reach Headscale to validate nodes; either join that VPS to the tailnet or relax
   verification on that region.
3. **Move Headscale itself to the VPS** (control plane *and* relay offsite). The complete fix: the
   coordination server stops sharing a failure domain with the thing it coordinates, and onboarding
   keeps working during a site outage. Most work of the three, and the one I would aim at eventually.

None of these needs a SIM, a modem, or any new recovery code.

### 1b.2 Where to host it — measured, 2026-09-20

Latency matters: the relay sits in the voice path. Measured with `ping` **from the edge**:

| Candidate region | RTT from edge |
|---|---|
| **Hetzner Nuremberg (NBG1)** | **17 ms** |
| OVHcloud France | 22 ms |
| Hetzner Helsinki (HEL1) | 24 ms |
| Scaleway Paris | 28 ms |

For reference the current in-house relay adds ~2 ms, and calls already run 100-200 ms round trip to
the S26, so anything under ~25 ms is comfortable.

**Pick:** Hetzner **CX23** (2 vCPU Intel / 4 GB) or **CAX11** (2 vCPU ARM / 4 GB), ~EUR 4-5/month
including IPv4, in **NBG1** (or FSN1, equivalent). 20 TB traffic included versus well under 1 GB/month
actually needed. Both derper and headscale are Go; ARM is fine and usually cheapest.
- cheapest line: https://www.hetzner.com/cloud/cost-optimized/
- standard line (CPX12+): https://www.hetzner.com/cloud/regular-performance/
- prefilled order: `https://console.hetzner.com/create/server?type=cx23&useIPv4=true&useIPv6=true&location=nbg1`

Prices/stock did not render in the browser when checked (script-loaded; several plans showed
"currently unavailable") - **verify on the page before ordering.**

**Alternatives:** OVHcloud (~EUR 5-6, includes DDoS protection - the natural *second* provider),
UpCloud (FI) or Scaleway (FR/NL); GleSYS (SE) or a Danish host (Curanet/Hostnordic) for Nordic
jurisdiction and the lowest latency at ~3x the price. Avoid the bargain tier (Contabo et al) for
something in the call path. **Proton has no compute product** - mail/VPN/Drive/Pass only - so it is
not an option; privacy branding buys little here anyway, since relayed traffic is end-to-end
encrypted and the host sees only ciphertext plus metadata.

**Requirements when ordering:** dedicated public IPv4 (not shared/CGNAT), and the ability to open
TCP 443 (DERP) plus UDP 3478/3479 (STUN).

**End state worth aiming at:** two relay regions at *different* providers (e.g. Hetzner + OVH) - the
DERP map supports several, which removes the single-relay weak point entirely for another ~EUR 5.

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

**Revised after the §1b finding. Do them in this order:**

1. **Uplink-down SMS alert (this week, tiny, near-zero risk).** Today's outage ran 114 minutes
   before anyone knew. Detection is most of the value, and it is a probe plus an SMS.
2. **Test whether `AT+CCFC` forwarding works on a Lebara SIM (one phone call to the carrier).** If it
   does, network-side forward-on-no-reply is the single highest-value resilience we can buy: no
   hardware, no data, works even if the edge is dead. If it does not, fall back to 5.2's second-modem
   divert, which shares the SIM purchase with the LTE plan.
3. **Get a relay off the site (§1b.1).** Config-only or a 5 €/month VPS, no hardware, and it removes
   the single failure domain that currently contains the edge, the relay, the STUN server and the
   control plane. **This is a prerequisite for step 4 doing anything useful.**
4. **Then LTE uplink fallback**, as designed above, in alert-only mode first — and only once step 3
   is proven, because without an offsite relay the edge can come up on LTE and still be unreachable.

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
1b. Do Tailscale's public DERP servers actually accept our Headscale clients? — decides whether the
   offsite relay is free (config) or costs a VPS. Test with a throwaway `derp.urls` entry on a quiet
   evening and watch `tailscale netcheck` list a second region with sane latency.
2. What does the site's router/ISP actually do? Today's outage lasted ~114 min with no link-down on
   our NIC. Is this the first occurrence, or has it happened before unnoticed? Worth checking the
   router's own logs — the fix might simply be a better router or a second ISP.
3. Cheapest Danish data SIM that permits VoIP relay traffic, and whether an EC25 on CGNAT holds a
   stable DERP session for days.
4. Whether we want the fallback automatic at all, or operator-triggered from the admin UI. Manual is
   materially safer and, with alert (1) in place, only costs minutes.
