# Telephony reliability & self-healing

How the self-hosted phone system stays up, what breaks it, and how it recovers.
Written 2026-08-16 after a run of reliability work. See also `manager-phone-setup.md`,
`phone-server-setup.md`, `asterisk-integration.md`.

> **Changing networking code?** Re-run `network-resilience-tests.md` first. Every bug that
> suite catches was invisible in normal use — healthy on the desk, on WiFi, plugged in, and
> broken only when idle, away from WiFi, or moving between networks.

Topology recap: one **edge box** (`phone@192.168.0.192`, tailnet `100.64.0.4`) runs
Asterisk + chan_quectel + the `shopbackend` jar; a **control-plane box**
(`kresten@t6.warpfactor.dk`, `server8`) runs Headscale + the onboarding service.
Manager phones run the ShopManager app with an **embedded Tailscale** tunnel; there is
**no push-wake** — a phone only rings while the app is alive and SIP-registered.

---

## Failure modes and the fixes

### 1. Two overlapping ringtones on an incoming call
The app played its own ringtone (CallActivity/MediaPlayer) **and** a second sound.
- Linphone Core was ringing too → `SipEngine.start()` now calls `disableCallRinging(true)` + `ring = null`.
- The incoming-notification channel (`sip_incoming_calls`) was `IMPORTANCE_HIGH` with the
  default sound; per-notification `setSilent(true)` does not override a channel's sound on
  Android 8+. Recreated it silent under a new id (`sip_incoming_calls_silent`), vibration off.
  CallActivity is the sole ring/vibration source.

### 2. Manager phone drifts to `Unavailable` → callers get "line busy"
Inbound rings `PJSIP/mgr{id}`; if that AOR has no reachable contact Asterisk logs
`Could not create dialog to invalid URI 'mgrN'` + `No route to destination` and the GSM
caller hears busy. **Confirmed root cause:** the app's Linphone `core.iterate()` starved.
Auto-iterate posts `iterate()` to the main Looper, so under UI load the SIP pump falls
behind — measured qualify RTT climbed ~100 ms → ~1 s within 30 s of launch, tripping
Asterisk's `qualify_timeout`. Layered fixes:
- **App:** `SipEngine` disables auto-iterate and drives `Core.iterate()` on a dedicated
  `HandlerThread` (URGENT_DISPLAY, ~20 ms). RTT now holds ~tens of ms. (Callbacks fire off
  the main thread — safe: CallActivity uses `runOnUiThread`, SipService only does
  notifications/startActivity; the SipEngine-monitor ↔ Core-lock deadlock invariant is intact.)
- **App:** self-healing watchdog in `SipService` (60 s) — on `!anyLineRegistered` or a server
  `GET /api/mobile/telephony/sip-health` "unreachable" verdict, `refreshRegisters()` →
  `bounceNetwork()` (setNetworkReachable false→true). Also fires `refreshRegisters()` on
  foreground (`ShopScheduleActivity.onResume`) and on ConnectivityManager network changes.
- **App:** in-app keep-alive setup (`KeepAlive`) — one-tap Doze battery-optimization exemption
  (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, allowed since self-distributed) + a "keep this phone
  reachable" card on PhoneStatusActivity. Samsung's "Unrestricted" does **not** add the app to
  the AOSP Doze whitelist — verify with `adb shell dumpsys deviceidle whitelist | grep shopmanager`.
- **Server backstop:** manager/shop AOR `qualify_timeout` 3 s → 8 s (`AriClient`), tolerating
  brief app stalls.
- **Gotcha:** `mgr{id}` is `max_contacts=1` — two phones on one manager account bump each
  other's registration. One phone per manager account.

### 3. Modem wedged in "Dialing" → all calls to that shop fail (cause 44)
chan_quectel loses a call hangup (`AT+CHLD Error sending hangup`) under **AT-port
contention with SMS** (one AT port serves both call control and SMS), so the device sits in
"Dialing" forever; every later call fails "device can not make call" (cause 44).
- **`SmsQueue`** (all senders route through it): serializes SMS per modem and holds each send
  until the modem has no active call — one worker per trunk, callers get a `Deferred` and never
  block. The during-call IVR booking-link enqueues `immediate=true` (must not wait, or it
  deadlocks the IVR). `AmiClient.sendSms` is now a raw send; gating lives in the queue.
- **`ModemStuckWatchdog`** (60 s): a modem in a call state with **no matching `Quectel/{trunk}`
  channel** in Asterisk = orphaned → after a ~2 min debounce, `quectel restart now {trunk}`.
  Also flags a modem down (not Free, no call for ~4 min) → `modem_down` event.

### 4. App blank / "not connected" (DNS landmine)
The app's backend `base_url` was a `*.ts.warpfactor.dk` hostname. On-device that resolves via
the phone's normal DNS to the **public wildcard `93.191.156.63`** (dead for :8080) whenever the
embedded Tailscale MagicDNS isn't applied — every API call hangs 10 s and times out → blank
screen. (SIP is unaffected: it dials `100.64.0.4:5060` **by IP**, so the server still shows the
phone registered.)
- **Fix:** new `APP_BASE_URL` env (edge's **tailnet IP**, `http://100.64.0.4:8080`) drives the
  onboarding `api=` and the OTA `apkUrl`, so the app reaches the edge by IP with no DNS
  dependency. `SetupAppRoutes` passes it as the invite's `edgeApiUrl` and prefers it in
  `currentUpdateBase()`. Kept **distinct from `PUBLIC_BASE_URL`**, which must stay public for
  customer booking-link SMS.
- **Underlying landmine (external):** the public `*.ts.warpfactor.dk → 93.191.156.63` wildcard is
  at **simply.com** (warpfactor.dk's DNS host). Remove/repoint it when possible. The embedded
  Tailscale not reliably applying MagicDNS is an app-side follow-up, now non-critical.

---

### 5. Calls cut out mid-conversation at ~15 s (the CLCC reaper — not VoLTE)
The fix for #3 added a CLCC reconciliation that releases a call the modem no longer lists.
It was too impatient — `CLCC_REAP_MISSES = 2` (~10 s at the 5 s poll) — and started hanging up
**healthy, bridged calls** about 15 s after answer. Proof it was a false positive: the CLCC reply
arriving moments after each reap listed the call as `state:0` (**ACTIVE**).

**Why only some shops — and why that no longer protects anyone.** Perfect 4/4 correlation with
**IMS actually registered**: the two modems reading `AT+QCFG="ims"` → `1,1` averaged ~1.1–1.2 false
reaps per call; the two whose IMS was never registered averaged ~0 across 89 calls. Measured per
call, so it is not a volume artifact. Same firmware, identical `quectel show device settings`, same
USB hub — the EC25 simply reports IMS voice calls inconsistently in the polled `AT+CLCC`, while CSFB
calls list cleanly.

Note the causal variable is the *state*, not the config flag: a modem set to `ims,1` with no carrier
profile stays at `1,0` and keeps making CSFB calls. **Since 2026-09-16 all four modems are at `1,1`**
(see [phone-server-setup.md](phone-server-setup.md) §7b; shop6 needed a manual `AT+CGACT=1,2` to get
there), so every shop is now in the flaky-CLCC regime and the hardening below is load-bearing
fleet-wide rather than for the original two. Watch
`grep "CLCC reap" /var/log/asterisk/dropcatch | grep channel:yes` — it must stay empty.

**How to recognise it** (do this *before* suspecting the app or the network):
- `grep "CLCC reap" /var/log/asterisk/dropcatch` — a reap logged with **`channel:yes` is always a
  bug**, never a legitimate catch.
- Check the hangup cause: a constant **~15–18 s call ending `cause=31`** is the reaper;
  **`cause=16`** is a real party hanging up.
- The manager phone and the network will both look innocent, because they are — the phone's RTP
  runs clean (0 lost, ~0 jitter) right up to the teardown, on wifi and cellular alike.

**The red herring.** The reap released only Asterisk's side and never sent `AT+CHUP`, so the modem
still believed the call was up: the *caller's* handset kept a running timer on a silent line for
minutes (observed 2m46s against an 18 s Asterisk-side call). That looks exactly like a phone or
audio fault and sends you chasing the handset.

**Fixed** (edge repo `/home/phone/asterisk-chan-quectel`): `4c861cf` raises the threshold to 6, and
to **24 (~2 min) for a call Asterisk still holds a channel for**, skips reconciliation when a CLCC
line fails to parse, and issues `at_enqueue_hangup()` when genuinely reaping so the caller is
released. `f71b1f9` then closes the underlying hole: an entry the parser declines to process
(a `mode:1` PDP bearer, an out-of-range state) no longer falls through without setting
`CALL_FLAG_ALIVE` — if the modem lists an index at all, the call on it is not absent. It logs a
warning naming the mode/state, so a recurrence is visible instead of silent.

### 6. A modem goes mute for hours after an SMS (orphaned SMS prompt)
**Symptom.** One trunk stops answering entirely: `AT command timeout` over and over, the trunk
reads `Not initialized`, calls and SMS to that shop fail — yet there is **no USB disconnect**, the
other modems are fine, and neither `quectel restart` nor the watchdog's reloads bring it back.
shop5, 2026-09-17 22:47 → ~01:15: 253 AT timeouts, and the watchdog reloaded the whole PBX four
times trying.

**Cause.** To send an SMS, chan_quectel sends `AT+CMGS=<len>`, waits (5 s) for the modem's `>`
prompt, then sends the PDU. If the prompt arrives *after* that timeout, the SMS task has already
been failed and removed; the late `>` found nothing waiting and was ignored. But a modem that has
issued `>` is in **PDU input mode**: every byte it receives is treated as message body until
Ctrl-Z (send) or ESC (abort). So every later command — keepalives, init, everything — was swallowed.
Nothing outside can fix that: a restart only reopens the port, and a reset needs a working AT port
to deliver `AT+CFUN=1,1`. It is the same family as the Sep 14 segfault (a late `+CMGS` reply after
its task was removed) — that fix covered the result stage, this is the prompt stage.

**Proven on the EC25** (spare tty, nothing sent): at a `>` prompt `AT` and `AT+CSQ` get no reply;
one ESC byte answers `OK` and the modem is normal again. A bare ESC with no prompt pending gets no
reply at all. `ESC`+`AT+CSQ` answers `+CSQ: n,m OK` normally and a bare `OK` at a prompt — exactly
one `OK` either way.

**How to recognise it:**
- `grep "unexpected SMS PROMPT\|Orphaned SMS prompt\|stuck at an SMS prompt" /var/log/asterisk/dropcatch`
- A burst of `AT command timeout` on one shop that starts in the same second as an
  `[AT+CMGS] ... Error sending message`.
- No kernel USB events for that modem (`journalctl -k | grep 1-1.2.x`) — it never rebooted.

**Fixed** (edge `2ac3b38`, live 2026-09-19 00:28):
- An orphaned prompt now gets an **ESC** (`at_enqueue_escape`, uid −2), logged
  `Orphaned SMS prompt aborted - modem is answering commands again`. The SMS is reported failed and
  nothing is sent.
- **Before every device (re)init** chan_quectel sends `ESC`+`AT+CSQ` and reads until the `OK` is
  followed by 200 ms of silence (2 s cap), then drains. That clears a prompt left over when the
  device restarted before the ESC went out, logs `Modem was stuck at an SMS prompt` when it rescues
  one, and cannot shift init's reply accounting (reading only to the *first* `OK` would have let a
  stale `OK` credit the probe's reply to init — IMEI parsed as IMSI; a review caught that).

**What the watchdog did meanwhile, and what that review changed** (backend, 2026-09-19). The
down-recovery ladder re-ran every 30 min and each pass ended in `module reload chan_quectel.so` —
four times that night. That rung turned out to be a **no-op**: with an unchanged config
chan_quectel's `pvt_reconfigure` restarts no device, so it neither helped shop5 nor touched the
healthy shops (an earlier note here claimed it dropped them; it did not). It has been removed. What
does recover a stuck modem is the per-trunk `quectel restart`, which now also sends the pre-init
ESC — on 09-17 chan_quectel was restarting shop5 every ~30 s itself, so the first cycle would have
cleared it.

The rung that **had** hurt a healthy shop was the device-path **resync**. 2026-09-12 16:03: run
while shop6's modem was hung (its IMSI not found, so it kept `EC25EUX_3`), the scan put the healthy
shop5 on `EC25EUX_3`; the rewrite restarted shop5 onto a card another trunk held and shop5 could not
open audio until a manual restart six minutes later. Now (`DevicePathPlanner`): the watchdog may only
move trunks it has confirmed down, a shop is only moved onto a complete scan result, and any move
that would give one device to two trunks is dropped (logged `[Recovery] SUPPRESSED device-path
rewrite` plus a `device_rewrite_refused` reliability event); the other shops' moves still apply. A
genuine re-enumeration that swaps two shops' sound cards is still applied. Provisioning a shop that
ends up with no device now fails loudly instead of reporting a dead line as provisioned. A live call
elsewhere now only skips the resync for that pass instead of holding back the admin alert.

**Deploying chan_quectel code needs a full Asterisk restart.** `module reload chan_quectel.so` only
re-runs the module's config reload — the process keeps the old `.so` in memory. Verify what is
*running*: `sudo grep chan_quectel /proc/$(pidof asterisk)/maps` must not say `(deleted)`.

## Reliability event log + admin alerts
`ReliabilityAlerter` records events to the `reliability_event` table (capped 1000) and, for
call-blocking ones, SMSes the admin (from a currently-healthy SIM, via `SmsQueue`, rate-limited
15 min per event kind). Categories: `modem_restart`, `modem_down`, `shop_unconnected`.
- **View:** web admin → **Reliability log** (`/reliability`), and a link on Telephony setup.
- **Admin number:** web admin → Telephony setup → Reachability alerts (DB-backed
  `sip_alert_admin_phone`; read fresh each alert, no restart needed).
- `SipReachabilityMonitor` (2 min) texts a shop's managers directly when it has an on-duty
  manager but none are SIP-reachable, and records the `shop_unconnected` event.

---

## Configuration

Edge `backend.env`:
- `APP_BASE_URL=http://100.64.0.4:8080` — app-facing (tailnet IP) for onboarding + OTA. **Distinct from `PUBLIC_BASE_URL`** (public, for booking links).
- `SIP_MONITOR_ENABLED` (default on), `MODEM_WATCHDOG_ENABLED` (default on) — kill switches.
- `SMS_CALL_GATE_MS` (default 15000) — max wait for a call to clear before an SMS sends anyway.

Web admin (DB-backed, no restart):
- Telephony setup → Reachability alerts → **admin alert phone**.

---

## The phone fleet & re-onboarding

Manager phones store `base_url` from onboarding. To move an existing phone onto the tailnet IP
(or fix a stranded one), re-fire the join deep link — `JoinActivity` sets `base_url` from `api=`
in `onCreate` (before the tunnel step) and **keeps the login**; VPN consent is not re-prompted if
already granted. Same machine key ⇒ same tailnet node/IP on re-auth.

Create an invite (api = tailnet IP) and fire it, per phone:
```bash
# on the edge: mint an invite scoped to this tenant, api = tailnet IP
CPU=$(grep ^CONTROL_PLANE_URL= backend.env | cut -d= -f2-); TOK=$(grep ^CONTROL_PLANE_EDGE_TOKEN= backend.env | cut -d= -f2-)
curl -sS -X POST "$CPU/api/invites" -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -d '{"deviceLabel":"<label>","edgeApiUrl":"http://100.64.0.4:8080"}'   # returns deepLink
# on the phone (USB adb): fire it — note the single-quotes protect the & in the URL
adb -s <serial> shell "am start -a android.intent.action.VIEW -d '<deepLink>'"
```
Verify: `adb -s <serial> logcat -d | grep -E "api=http://100.64.0.4:8080|joined tailnet"`, and
server-side `sudo asterisk -rx "pjsip show contacts"`. Also Doze-whitelist:
`adb -s <serial> shell dumpsys deviceidle whitelist +com.kkstech.shopmanager`.

Release APKs are release-signed (`dbb3370b…`); `adb install -r` upgrades in place (a debug-signed
install would conflict and need uninstall + re-onboard). Publish via the edge WebAdmin **App
updates** page (one upload → edge OTA + control-plane onboarding APK).

---

## Where to look when something's off
- `sudo asterisk -rx "pjsip show contacts"` — Avail/Unavail + RTT (tens of ms healthy; ~1 s = iterate starving; high/flapping = phone).
- `sudo asterisk -rx "quectel show devices"` — Free (idle) / Dialing-stuck / Not connected.
- Web admin **Reliability log** — modem restarts + call-blocking events.
- `journalctl -u shopbackend.service` — `[SipMonitor]`, `[ModemWatchdog]`, `[SmsQueue]`, `[Reliability]` lines.
- Phone: `adb logcat | grep -E "SipEngine|TailnetJoin|SocketTimeout|93.191.156"`.

## Outstanding follow-ups
- Remove/repoint the public `*.ts.warpfactor.dk` wildcard at simply.com.
- Harden the app's embedded Tailscale to reliably apply MagicDNS (now low priority — app uses the IP).
- Re-onboard any phone still on the hostname `base_url`; log the manager back in on freshly re-onboarded phones.
