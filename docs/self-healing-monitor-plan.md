# Self-healing monitor + auto-recovery plan

Status: **in progress** (started 2026-08-26). Goal: monitor the telephony system and
**automatically bring it back** the way an operator would today (restart a wedged modem,
reload chan_quectel, reconnect/restart the backend, restart Asterisk as a last resort) — with
strict rate limits, full audit logging, and an **automatic rollback** to the previous jar if the
new self-healing build ever restart-loops.

Grew out of the 2026-08-26 incident: shop2's modem sat in **"Not initialized"** (Provider NONE,
RSSI 0) and call audio was dead; a manual `quectel restart` + `systemctl restart shopbackend`
recovered it. The watchdog *had* the hook to restart modems but its policy only *alerted* on a
"down" modem, on the (here-wrong) assumption that a down modem is always an unrecoverable
SIM/signal fault.

---

## What already exists (we extend, not replace)

- **Process crash recovery:** systemd `Restart=always, RestartSec=5` — a *crashed* backend already
  comes back. It does **not** catch a *hung* (alive-but-stuck) process, and it can crash-loop.
- **`ModemStuckWatchdog`** (60 s): auto-runs `quectel restart now {trunk}` for a modem **orphaned
  in a call state** (debounce 2, cooldown 3 min). For a **down / "Not initialized"** modem it only
  records `modem_down` + admin SMS — never restarts. ← the gap.
- **`SipReachabilityMonitor`** (120 s): SMS-alerts when no on-duty manager is reachable. No
  remediation. Guards on `amiClient.connected`.
- **`ReliabilityAlerter.record(sev, category, shopId, msg, alertAdmin)`**: logs to
  `reliability_event` (kept to 1000 rows, shown in WebAdmin) + rate-limited admin SMS from a
  healthy trunk. Reused by every tier below.
- **`AmiClient`**: `command()` (CLI over AMI), `quectelDeviceStates()`, `pjsipReachableAors()`,
  `reloadChanQuectel()` (`module reload chan_quectel.so`), `sendSms()`, `connected` flag.

---

## ⚠️ Safety prerequisite — fix the lying "reachable" signal FIRST

The link-stability audit (2026-08-26) found the backend's reachability signal **false-positives a
"down" verdict whenever its own AMI socket hiccups**. Auto-restart wired to that signal would
restart a healthy system on a phantom outage. So these land **before** any SIP-triggered
remediation:

- **H1 — `AmiClient.connected` is one-shot.** Set `true` once after first login; only cleared in
  `stop()`. A *post-login* disconnect (asterisk-java reconnecting, or gave up) leaves it stale-true.
  **Fix:** reconcile from real connection state — register a disconnect/reconnect listener (or poll
  `connection.getState()`), set `connected` from that.
- **H2 — `/api/mobile/telephony/sip-health` has no `connected` guard.** `MobileApi.kt:464` does
  `pjsipReachableAors().contains(aor)`; on an empty set (AMI down/slow/pre-connect) it returns
  `reachable=false` to *every* polling phone. **Fix:** return a tri-state — `reachable=true|false`
  only when AMI is genuinely connected and the query succeeded; otherwise `reachable=null`
  ("inconclusive"), which the app already treats as "no change" (its watchdog does not escalate on
  a failed/inconclusive health check).

---

## The tiers (escalating remediation, each rate-limited)

Every tier: **debounce** (N consecutive bad checks) → **cooldown** → **max attempts per rolling
window with exponential backoff** → then **stop + escalate to human SMS**. Never remediate a trunk
with a **live call** on it. Each tier has a **kill-switch env var** (like the existing
`MODEM_WATCHDOG_ENABLED`). Every action is a `ReliabilityAlerter.record(...)`.

### Tier 1 — Modem (extend `ModemStuckWatchdog`)
Keep the orphan-in-call auto-restart. Turn the **down / "Not initialized" / "Not connected"** branch
from alert-only into a ladder:
1. `quectel restart now {trunk}` — up to 2 attempts, spaced by cooldown.
2. still bad → `module reload chan_quectel.so` (re-inits **all** trunks — effectively what the
   backend restart did for shop2), at most once per long window.
3. still bad after the reload → **now** record `modem_down` + admin SMS and **stop restarting**
   (genuine SIM/signal fault; a restart won't fix it).
   The ladder itself separates "transient, restart fixed it" from "really dead" — no guessing.
New event categories: `chan_reload`.

### Tier 2 — Backend / AMI / Asterisk (gated on the H1/H2 fixes)
- **AMI dropped (real):** if `connection.getState()` is not connected for > N s and asterisk-java
  isn't reconnecting, force a reconnect of the `ManagerConnection`.
- **Backend wedged:** a health heartbeat (below) that stalls → the backend `exitProcess()`s so
  systemd restarts it clean. Internal rate limit + persisted restart counter (see rollback) so it
  self-disables before looping.
- **Asterisk unresponsive:** AMI won't connect at all for several minutes → `systemctl restart
  asterisk` as a **last resort** (drops all registrations/calls briefly). Heavily rate-limited,
  always alerts. Needs a sudoers entry for the service user. New categories:
  `ami_reconnect`, `backend_selfrestart`, `asterisk_restart`.

### Tier 3 — End-to-end "can this shop take a call?" synthetic check
Ties the two monitors together. For each shop with an on-duty manager,
`callReady = (≥1 manager phone Avail) AND (its modem Free)`. If a shop is not call-ready for X min,
route the fix to the right tier (SIP-side vs modem-side) and record `shop_not_call_ready` instead of
two monitors alerting in isolation. This is the single "is the product actually working" signal, and
it powers the WebAdmin health page.

---

## Liveness heartbeat + auto-rollback (the "in case it restart-loops" requirement)

**Heartbeat (catch a *hung* process):**
- systemd `WatchdogSec=45` on the unit; the app calls `sd_notify(WATCHDOG=1)` from a loop that only
  pings while its core threads (AMI reader, monitors) are healthy. A hung JVM stops pinging →
  systemd kills + restarts it. (`Type=notify` + `NOTIFY_SOCKET`; use a tiny JNI/`libsystemd`
  shim or write to `$NOTIFY_SOCKET` directly via a Unix datagram socket.)
- Optional external backstop: a 1-min cron `curl -fsS localhost:8080/health` that `systemctl
  restart shopbackend` on repeated failure — independent of the JVM.

**Auto-rollback (catch a *crash/restart loop*, incl. an over-eager self-restart):**
- systemd drop-in: `StartLimitIntervalSec=600`, `StartLimitBurst=5`, `OnFailure=shopbackend-rollback.service`.
- `shopbackend-rollback.service` (oneshot): if the unit exceeded its start limit, it
  1. copies `standby/ShopBackend.prev.jar` → `ShopBackend.jar` (the known-good build),
  2. writes `standby/SELF_HEALING_DISABLED` (the new build, if it ever runs again, reads this and
     starts with all Tier-2/Tier-3 self-restart paths **off** — modem Tier 1 stays on),
  3. `systemctl reset-failed shopbackend && systemctl start shopbackend`,
  4. SMSes the admin "auto-rolled back to previous build after N restarts in 10 min".
- **Independent app guard:** Tier 2's self-restart also keeps a persisted
  `restarts:<epoch-min>` counter and refuses to self-restart more than K times per hour, logging
  `selfrestart_suppressed` — so the app throttles itself *before* systemd's limit, and the systemd
  rollback is the outer net.

**Standby is already in place:** `/home/phone/shopbackend/standby/ShopBackend.prev.jar`
(sha256 `88cec5df…`, the jar running as of 2026-08-18). Manual rollback is always:
```bash
cp /home/phone/shopbackend/standby/ShopBackend.prev.jar /home/phone/shopbackend/ShopBackend.jar
sudo systemctl restart shopbackend
```

---

## Lebara balance monitor (daily low-balance → in-app notification)

Mechanism already half-built: `LebaraTopup.requestBalance(telephony, shopId)` texts **"balance"** to
**5010** from the shop's own SIM; the reply arrives async from 5010 and is stored raw in
`shop_telephony_config.balance` / `balance_at` (via `setShopBalance`, `isCarrierReply`).

- **`LebaraBalanceMonitor`** (daily, e.g. 09:00 local via a scheduled tick): for every shop whose
  `carrier == "lebara"` and has a `modem_data_device`, call `requestBalance`. Spread sends a few
  seconds apart (shared AMI socket).
- **Parse + threshold:** when a 5010 reply lands (`setShopBalance` path), parse the amount
  (tolerant regex for `NN,NN`/`NN.NN` + `kr`/`DKK`) into øre; store the numeric value alongside the
  raw text. `LOW_BALANCE_THRESHOLD_KR = 25` (env-overridable). If `balance < 25 kr`, raise a
  **manager-facing** low-balance notification for that shop; clear it when a later balance ≥ 25 kr
  arrives. Unparseable replies are logged, not alerted.
- **Surfacing in the manager app:** reuse the app's existing polled-notifications channel (the app
  already polls the backend for manager notifications) — add a `low_balance` item type
  `{shopId, shopName, balance, topupDeepLink}` so the existing UI renders it with minimal/no new
  app code; tapping it opens the existing top-up screen. New endpoint or extension:
  `/api/mobile/telephony/alerts` (manager-scoped) returning active low-balance items. (Exact
  integration pinned to what the app already renders — see the app-notification investigation.)
- New event category: `low_balance`.

---

## Build / deploy / verify

- Build the fat jar locally, scp to `/home/phone/shopbackend/ShopBackend.jar`, `systemctl restart`.
- **Verify no restart-loop after deploy:** watch `systemctl show shopbackend -p NRestarts` and the
  journal for ~15 min; confirm modems stay Free, phones Avail, and no unexpected `*_restart`
  reliability events. The auto-rollback is the safety net if this goes wrong unattended.

## Rollout order
1. Standby snapshot ✅ + rollback service + systemd drop-in (StartLimit/OnFailure/WatchdogSec).
2. Audit **H1 + H2** (the safe-signal prerequisite).
3. **Tier 1** modem ladder (closes the actual incident).
4. Heartbeat `sd_notify` + Tier 2 backend self-heal (self-throttled).
5. **Tier 3** synthetic readiness + WebAdmin health page + optional cron backstop.
6. **Lebara** daily balance monitor + parser + low-balance in-app notification.
