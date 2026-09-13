# Link telemetry plan — layered reachability probe

Status: **phase 0 running** (2026-09-01). Goal: when a manager phone's SIP endpoint goes
Unavailable and a customer hits "line busy", identify **which layer actually broke** — rather
than guessing, as we have done for every field outage so far.

This document replaced an earlier draft built around WiFi/LTE signal-strength telemetry and
location correlation against a Telenor coverage map. **That premise was wrong, and the evidence
below is why.** Signal telemetry would not have found this fault; it would have produced a
beautiful RF trace showing nothing unusual.

---

## 1. What the evidence actually says

Investigated 2026-09-01 against the Headscale journal on `t6.warpfactor.dk` and the backend
journal on the edge. Subject: `mgr2` = the S26 = tailnet `100.64.0.11`, which accounts for
**164 of 184** `[SipCommand]` events this week (mgr3: 17, mgr1: 3).

**The tunnel was not down, and it was not coverage.**

- During the 92-minute SIP outage (14:35:47 → 16:07:34), Headscale had node 16
  **continuously connected** from 13:31:41 to 16:06:21. Measured tailnet downtime for the whole
  day: **~14 seconds** across three blips (2 s, 6 s, 4 s, 2 s). Measured SIP unreachability the
  same day: **~2.5 hours**.
- Throughout that outage the backend logged `[SipCommand] mgr2 unreachable Ns -> sending ...`
  every five minutes. `SipCommandDirector.onVerdict` is reachable from **exactly two call sites**
  (`MobileApi.kt:512` sip-health GET, `MobileApi.kt:554` sip-report POST) — both HTTP handlers.
  Every one of those log lines is therefore *proof that the phone successfully reached the
  backend over HTTP at that moment*.
- SIP recovered 15–70 s **after** a tailnet control-stream reconnect, at 16:07 / 17:13 / 19:52 /
  22:07. The reconnect is the **cure**, not the cause.

**Two structural faults found in passing:**

1. **No manager phone ever establishes a direct WireGuard path.** `tailscale ping` from the edge
   reports `direct connection not established` for both the S26 (`100.64.0.11`) and the S21
   (`100.64.0.7`); `tailscale status` shows `relay "headscale"` for both. All SIP signalling
   **and all RTP audio** relay through the DERP server on `t6.warpfactor.dk`. Suspect the
   STUN/endpoint-discovery setup — DERP STUN sits on **3479** because UniFi holds 3478
   (see `headscale-server` notes).
2. **Every phone carries a permanent ghost contact.** `pjsip show contacts` shows two contacts
   per AOR: the good fixed-port one (`mgr2@100.64.0.11:5062`, from commit `b48ed34`) and a stale
   ephemeral-port one (`mgr2@100.64.0.11:41724`) sitting `Unavail` / RTT `nan` indefinitely.
   `remove_existing=true` caps them at one, but nothing cleans them.

**Qualify RTT is the smoking gun.** One phone, one instant:

| Contact (all on 100.64.0.11) | RTT |
|---|---|
| `mgr2` | 333 ms |
| `shop1-manager` | 1112 ms |
| `shop2-manager` | **2601 ms** |
| `shop5-manager` | **3119 ms** |

The S21 sits at a consistent 178–239 ms. `qualify_timeout` is 15 s (commit `4e98958`); when an
OPTIONS round trip exceeds it, the contact flips Unavailable while the phone's TCP HTTP polls
keep succeeding — which is precisely the `DISAGREEMENT` signature (`state=HEALTHY registered=3`
against Asterisk's `UNREACHABLE`).

**And the first probe rows narrow it further.** Sampled simultaneously on the same path:

```
ts,icmp_ok,icmp_rtt_ms,...,ts_path,ts_rtt_ms,...,sip_status,sip_rtt_ms,sip_contacts
2026-09-01T22:35:42,1,72,0,derp,132,,Avail,208,2
2026-09-01T22:36:36,1,46,0,derp, 85,,Avail,476,2
```

ICMP over the tunnel is **37–72 ms** while SIP qualify on the same path at the same moment is
**208–476 ms**, and elsewhere seconds. ICMP is answered by the phone's kernel/tailscale; SIP
OPTIONS must be processed by liblinphone's `iterate()` loop **inside the app**. The network path
is fine — **the latency is being added above it, in the phone's SIP stack.** That points at
scheduling/Doze throttling of the app, not at the radio and not at DERP.

DERP-only remains a genuine problem for **audio** (relayed RTP, jittery: ICMP mdev 31 ms), but it
is not on its own the explanation for the unreachability — the S21 is equally relay-only and
flaps ~10× less.

---

## 2. The layered model

The whole design reduces to one idea: **sample independent layers simultaneously, and let the
lowest failing layer name the culprit.**

| Layer | What it proves | How it is measured |
|---|---|---|
| **L1** tailnet control | the phone has working internet | Headscale journal `node connected` / `node disconnected` |
| **L2** tunnel data path | the WireGuard path edge→phone works | ICMP to the tailnet IP, **from the edge** |
| **L3** path quality | direct vs DERP relay, and its RTT | `tailscale ping` |
| **L4** app HTTP | the phone→edge app path is alive | backend log activity for the AOR |
| **L5** SIP | what Asterisk actually believes | `pjsip show contacts` status + RTT |

**L2 is the measurement nothing in the system previously had.** Headscale only knows about the
control-plane stream (public internet, TCP to `headscale.warpfactor.dk` — *not* through the
tunnel). The backend logs only prove the **phone→edge** direction. Nothing tested **edge→phone**
— which is the direction `qualify` OPTIONS and inbound `INVITE` travel, and therefore the only
direction that matters for a customer being able to ring the shop.

A failure signature reads directly off the row:

- L1 down → genuinely no coverage. Nothing to fix; this is the case we would have to accept.
- L1 up, L2 down → tunnel data path broken while control plane is fine (DERP session stale,
  NAT rebind). Fixable.
- L1/L2 up, L5 Unavail → **the observed case.** Not coverage, not the tunnel. Look at the L3 and
  L5 RTT columns and at phone-side SIP processing.

---

## 3. Phase 0 — edge probe (deployed and running)

`scripts/linkprobe.sh`, deployed to `/home/phone/linkprobe.sh` on the edge, running under
`setsid`, appending CSV to `/home/phone/linkprobe.csv` every 30 s.

```bash
ssh phone@192.168.0.192 'tail -20 /home/phone/linkprobe.csv'   # inspect
ssh phone@192.168.0.192 'pkill -f linkprobe.sh'                # stop
```

Costs nothing to run, needs **no APK release, no new permissions, no location data**, and every
helper is `timeout`-bounded so a probe failure writes an empty field rather than hanging.

Defaults to the S26 / `mgr2`; takes `[tailnet-ip] [aor]` to point at another phone — run a second
copy against the S21 (`100.64.0.7 mgr3`) to get the contrast that isolates device-specific
behaviour from environment.

**Known weakness, deliberate:** L4 is coarse. The backend does not log every poll — `[SipCommand]`
lines appear only on a state change or when the 5-minute command cooldown lapses — so a *small*
`backend_log_age_s` proves the phone→edge path was alive, but a *large* one proves nothing. Making
it exact is a one-line backend change: log each `sip-health` / `sip-report` hit
(`MobileApi.kt:490` / `:539`). Worth doing; it is the cheapest remaining observability win.

**What to look for once a flap is captured:** whether `sip_rtt_ms` climbs *before* `sip_status`
flips (→ progressive degradation, a timeout being crossed) or flips with no warning (→ a discrete
event: re-registration, contact churn, Doze entry). Those imply different fixes.

---

## 4. Phase 1 — backend observability

Small, no fleet risk, all independently useful:

1. **Log every sip-health / sip-report hit** — makes L4 exact (see above).
2. **Persist the qualify RTT.** `AmiClient.pjsipReachableAors()` already parses the contact line
   and **discards `cols[3]`, the RTT**. Capturing it turns a currently-invisible quantity into a
   continuous per-phone series — this is the single highest-value number in the whole system and
   it is already being thrown away.
3. **Record modem RSSI** from `AmiClient.quectelDeviceState()` (already parsed, already surfaced
   in WebAdmin, never stored). Shop-side, and unrelated to the manager-phone fault, but free.
4. **Count and expose ghost contacts** so the `remove_existing` behaviour is observable.

Follow the `SipReachabilityMonitor` pattern — daemon thread, 60 s tick, **guarded on
`amiClient.connected`** (the H2 lesson: an AMI hiccup must never be recorded as a real reading).

---

## 5. Phase 2 — app-side ladder (only if phases 0–1 do not settle it)

The evidence has redirected this substantially. The interesting app-side quantities are **not**
RF — they are scheduling and SIP-stack liveness:

- `iterate()` scheduling latency and gap distribution (`SipEngine.iterateStalledMs()` /
  `iterateSuspendedMs()` already exist — they are reported but never *stored* as a series).
- Doze / standby-bucket / Data Saver state at sample time (`KeepAlive` already reads the last).
- Time-to-respond to an inbound OPTIONS, measured in-app — pair it with the backend's qualify RTT
  and the difference **is** the app-side processing delay.
- Transport (WiFi/cellular), metered flag, and a handover counter — cheap, no permission.

**Signal strength is demoted to a secondary correlate.** Sample RSRP/RSSI only to *explain* an
L1/L2 failure when one actually occurs — not as the primary instrument. It needs no permission
(`TelephonyCallback.SignalStrengthsListener`, API 31+; whole fleet is 31+), so it is nearly free
to include, but it is no longer the point.

**Location: dropped.** Not required by this diagnosis, and it carries a GDPR obligation
(continuous position data on an identifiable employee) that buys us nothing here.

### ⚠️ Deployment landmine — read before touching `SipStatusReport`

`ShopBackend.kt:207` installs `json(Json { prettyPrint = false })` — **no `ignoreUnknownKeys`**.
The app already documents the consequence in `buildStatusReport()`: an added field makes *every*
report a 400 and **blinds the backend's disagreement check fleet-wide**. It is why `"SUSPENDED"`
was smuggled in as a state string rather than a new field.

So: set `ignoreUnknownKeys = true` (the control plane already does, `ControlPlaneApp.kt:70`),
**deploy and verify the backend, then release the APK** — and keep telemetry on a separate
endpoint decoded with its own lenient `Json`, so a telemetry bug cannot break self-healing. The
app must treat 400/404 from it as "older backend, drop the batch silently".

### Store-and-forward

Still mandatory if the app ever reports: **the phone cannot report while its link is down, and
the link being down is the event.** Buffer to a capped file in `filesDir` (the `CrashLog.kt`
pattern — trimmed to the tail, never throws), upload oldest-first in batches, stamp every sample
with both wall clock and `elapsedRealtime` so a clock step is detectable. Sample faster (10 s)
while `unreachableStreak > 0` or a call is up.

Note the watchdog only ticks **while on duty**, so app-sourced telemetry inherits that gate unless
deliberately changed.

---

## 6. Storage

Only once phase 1 produces a series worth keeping. Follow the `reliability_event` precedent —
same shape, same self-trimming discipline, added through the existing idempotent
`CREATE TABLE IF NOT EXISTS` / `ALTER TABLE` list in `DataBase.kt`.

```sql
CREATE TABLE IF NOT EXISTS link_sample (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at  INTEGER NOT NULL,
    subject     TEXT NOT NULL,      -- aor (mgr2) or trunk name
    icmp_rtt_ms INTEGER, icmp_loss_pct INTEGER,
    path        TEXT,               -- direct | derp | none
    path_rtt_ms INTEGER,
    sip_status  TEXT,               -- Avail | Unavail | none
    sip_rtt_ms  INTEGER, sip_contacts INTEGER,
    modem_rssi_dbm INTEGER,
    app_version TEXT
);
CREATE INDEX IF NOT EXISTS idx_link_sample_subject_time ON link_sample(subject, created_at);
```

The DB is a **single connection with no WAL** (`upsun-sqlite-no-wal`), so batch: accumulate in
memory, flush one transaction every 5 min. Trim on a 14-day window.

---

## 7. Candidate fixes the evidence already points at

Independent of any further telemetry:

1. **Get direct WireGuard paths working.** Every phone relay-only is an architectural weakness —
   it puts `t6.warpfactor.dk` in the path of every call's audio. Investigate STUN on 3479 and
   endpoint discovery. Biggest single lever, and it improves audio quality as well as signalling.
2. **Attack the app-side SIP processing delay** (ICMP 46 ms vs qualify 476 ms on the same path).
   Doze/standby throttling of `iterate()` is the leading suspect.
3. **Clean up ghost contacts.**
4. **Reconsider `qualify_timeout` / `qualify_frequency`** for a relayed path — symptom treatment,
   but cheap, and it directly reduces false "line busy".

---

## Open questions

- Does `sip_rtt_ms` degrade progressively before a flip, or flip discretely? (Phase 0 answers this.)
- Does the S21 show the same ICMP-vs-qualify gap, or is it S26-specific? (Run a second probe.)
- `mgr2` → S26 is established from the contact IP (`100.64.0.11`), not the `managers` table —
  worth confirming.
- Retention: 14 days proposed. Long enough for the intermittent drops `dropcatch` is chasing?
