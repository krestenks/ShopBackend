# Audit: automated recovery paths that can drop live calls

**Date:** 2026-09-13. **Status:** findings verified, fixes NOT yet applied (deliberately deferred).

Triggered by the CLCC-reaper incident, in which a self-healing watchdog hung up healthy bridged
calls ~15s after answer (see [telephony-reliability.md](telephony-reliability.md) failure mode 5).
This audit asks whether anything else in the fleet has the same shape.

## The invariant being tested

> An automated recovery action must not destroy working state on weak evidence, and must not fire
> while a call is live.

"Weak evidence" means a timeout, a missed poll, an unparsed response, a counter threshold, or any
*absence* of data. Absence of evidence is not evidence of absence — that single confusion is the
root cause of every finding below.

Two supporting rules that fall out of it:

- **Fail closed on inconclusive input.** "I could not ask" must be distinguishable from "the answer
  is no", and must never authorise a destructive action.
- **Rate limits are not guards.** A cooldown reduces how often you destroy a working call. It does
  not stop you doing it.

---

## Findings, ranked by blast radius x likelihood

### 1. CRITICAL — `AT+CLCC` timeout tears down the entire modem
`chan_quectel/src/at_response.c:577-584`

`CMD_AT_CLCC` sits in the `/* critical errors */` list, so a timeout is interpreted as
*"mean disconnected from device"* -> `pvt_disconnect()` -> `AT+CHUP` on **every call on that modem**.

Aggravating detail: `at_command.c` polls `AT+CLCC` **every 5s and only while calls are up**, with a
**5s timeout** (`ATQ_CMD_TIMEOUT_MEDIUM`). The command's budget equals its own repeat interval, so
they overlap under load. One reply arriving 5.1s late drops every call on the shop.

This is the same weak signal that commit `6c341d6` just ruled insufficient to kill *one* call, but
here it is authorised to kill *all* of them. Especially relevant on shop1/shop2, whose CLCC is
demonstrably unreliable (VoLTE; see [phone-server-setup.md](phone-server-setup.md) 7b).

**Fix:** remove `CMD_AT_CLCC` from the critical list — a late CLCC is not a dead modem. Give
`at_enqueue_clcc` a timeout comfortably longer than its poll interval, and consider
`ATQ_CMD_FLAG_IGNORE`.

### 2. CRITICAL — `cmd_timeout()` ignores the error classification
`chan_quectel/src/monitor_thread.c:77-95`

```c
if (at_response(pvt, &pvt->empty_str, RES_TIMEOUT)) { ...; pvt->terminate_monitor = 1; return; }
if (ecmd->flags & ATQ_CMD_FLAG_IGNORE) { return; }
pvt->terminate_monitor = 1;      /* <-- runs even when at_response() said "not critical" */
```

The final assignment executes even when `at_response()` returned 0, i.e. for errors
`at_response_error()` explicitly classified as *not* critical. The only escape is
`ATQ_CMD_FLAG_IGNORE`, a queue-layer flag unrelated to error severity. So a timeout on a cosmetic
command (`AT+CLVL`, `AT+CSQ`, `AT+COPS`) tears the device down and drops live calls, and the
careful CRITICAL/non-critical classification only changes the log level.

**Production evidence:** this path has fired **1,924 times** (`[shopN] Fail to handle response`
from `monitor_thread.c`), all on shop5/shop6, clustered on days those modems were genuinely broken
(ALSA card-shuffle incidents). So it is heavily exercised and easy to trigger — it simply has not
yet coincided with a live call on an otherwise-healthy modem.

**Fix (highest leverage — neutralises this whole class):** make `terminate_monitor` conditional on
`at_response()` actually reporting a critical error, not on the absence of an unrelated flag.

### 3. CRITICAL — app: `onAvailable` -> `bounceNetwork()` with no call guard
`ShopManager/app/src/main/java/com/example/shopmanager/sip/SipService.kt:80-85`

```kotlin
override fun onAvailable(network: Network) { SipEngine.bounceNetwork() }
```

The same file states the rule at line 283 — *"bounceNetwork() would DESTROY the transport and drop
that call"* ... *"never bounce while a call is live"* — and line 296 obeys it. Line 83 does not, and
fires on the **same handover event**, racing the guarded path.

This is the 1.0.98 bug that 1.0.99 was written to fix; the fix added `hasActiveCall()` to
`recoverAfterHandover()` and `escalate()` but missed this callback, which predates both.

**Fix:** mirror line 296 —
`if (SipEngine.hasActiveCall()) SipEngine.forceReachableAndRefresh() else SipEngine.bounceNetwork()`

### 4. CRITICAL — every backend restart drops every GSM call, fleet-wide
`src/main/kotlin/asterisk/AsteriskConfigWriters.kt:96-97`, via
`AsteriskProvisioner.regenerateFiles()` and the `telephony-startup` thread in `ShopBackend.kt`

`regenerate()` rewrites `quectel_shops.conf` and calls `amiClient.reloadChanQuectel()`
unconditionally — even when the rendered config is byte-identical, which is the common case at
startup. A chan_quectel reload re-inits **all trunks on the PBX**.

The hazard is documented elsewhere in the codebase (`ModemStuckWatchdog.kt:87-89`,
`AsteriskProvisioner.kt:271-273` — *"callers must ensure no GSM call is live"*) but the startup
caller does not honour it. With systemd `Restart=always`, **any crash or deploy hangs up every call
in every shop.** Certain, not probabilistic.

**Fix:** skip the reload when the rendered content is unchanged; and defer it while any `Quectel/`
channel exists. `regenerate()` already takes a `reload` flag that nothing currently uses.

### 5. HIGH — backend commands a phone to `restart` mid-call
`src/main/kotlin/SipCommandDirector.kt:112-143` -> app `SipService.kt:612-659`

After 5 min unreachable the backend sends `rebuild`; after 12 min, `restart`. The app's
`applyServerCommand()` executes `rebuild` (`unregisterAll()` + `rebuildCore()`) or `restart`
(`android.os.Process.killProcess()`). **Neither side checks for an active call**, and
`applyServerCommand` returns early, bypassing the call-aware rung clamp at `SipService.kt:684`.
The only protections are cooldowns and budgets.

**Production evidence:** on 2026-09-07 this fired three times against mgr2 (`rebuild` at 311s, then
`restart` twice) while the phone consistently reported `state=HEALTHY registered=3` with a healthy
pump — the DISAGREEMENT case. Qualify verdicts for that handset are known to be unreliable.

**Fix:** guard `applyServerCommand` with `hasActiveCall()`, or clamp `rebuild`/`restart` down to
`refresh` while a call is up. Ideally also check for an active channel server-side before issuing.

### 6. MEDIUM-HIGH — `AmiClient.command()` fails open
`src/main/kotlin/asterisk/AmiClient.kt:100-101`

```kotlin
return (response as? CommandResponse)?.result ?: listOfNotNull(response.message)
```

When the reply is not a `CommandResponse` (AMI error, permission change, truncated reply), the
caller receives a non-empty list containing an error string. `ModemStuckWatchdog` greps that list
for `Quectel/` to decide whether a call is live — so an AMI hiccup makes **every busy modem look
orphaned**, and two such ticks fire `quectel restart now` on trunks carrying calls. The same list
backs the `anyQuectelCall` guard on the fleet-wide chan_quectel reload.

**Fix:** return `List<String>?` (or throw) when the response is not a `CommandResponse`; treat null
as inconclusive and do nothing. One change closes the guard in both consumers.

### 7. MEDIUM — `/health` reports 503 *because* a call is in progress (latent trap)
`src/main/kotlin/SystemHealthMonitor.kt:82-84`

A modem carrying a call reports `Ring`/`Active`, not `Free`, so `callReady = false` and `ok = false`.
`/health` therefore answers 503 for the duration of every normal call.

**Currently harmless** — the deployed `/home/phone/shopbackend/standby/health-check.sh` treats *any*
HTTP response as alive and only counts total non-response (3 consecutive) before restarting. But
`ShopBackend.kt` comments *"503 otherwise so `curl -fsS` can gate a restart"*, actively inviting the
dangerous version. Anyone who "fixes" the script per that comment arms a loop: busy shop -> 503 ->
backend restart -> finding 4 -> every call in every shop dropped.

**Fix:** treat a call-state modem as call-ready, require N consecutive not-ready snapshots, and
delete the misleading comment.

### 8. MEDIUM — `ModemScanner.scan()` AT-probes modems when AMI is down
`src/main/kotlin/asterisk/ModemScanner.kt:44-80`

The "is this modem busy?" map comes from `ami.quectelDeviceStates()`, which returns an **empty map**
when AMI is down or the reply is unparseable — so every modem, including ones mid-call, is treated
as free and AT-probed. AT-port contention while chan_quectel owns the port is the documented
mechanism that makes the driver lose a hangup and wedge, i.e. it can manufacture the fault the
watchdog then "repairs" with restarts.

**Fix:** fail closed — refuse to probe any port whose trunk state is unknown.

### 9. LOW-MEDIUM — app: other unguarded teardowns

- `SipService.kt:359-365` — `stopSelf()` on `RegResult.Known(0)`. The emptiness test uses
  `registeredShopIds()`, which ignores the manager-pool identity, so a manager registered only via
  `mgr{id}` and **on a call** satisfies it; the 15-minute refresh then stops SIP mid-call.
- `SessionManager.clearSessionAndGoToLogin()` — any background 401/403 (including from a 5-second
  poll) stops the SIP service with no call check. The `Inconclusive` reasoning was applied to
  network failures but not to auth failures.
- `SipEngine.kt:596-603` — stage-2 uplink reINVITE fires on "we are sending nothing", which is also
  what a 5-10s RTP gap during a handover looks like. No downlink cross-check, no handover grace.
- `SipEngine.kt:521-555` — `recoverCapture()` has no rate limit and re-enters its own trigger
  (toggling `isMicEnabled` changes the recording config), so a mic-grab flap can chain audio resets.
- `SipService.kt:480-485` — `restartIterateLoop()` on pump stall runs *before* the call clamp,
  contradicting the file's own classification of that action as call-dropping.

---

## Checked and clean

- ALSA XRUN / capture errors never hang up — `channel.c:631-635` converts a failed read into a
  silence frame.
- `AT+CFUN=1,1` modem reset is CLI-only, not reachable from a watchdog.
- `ModemFirmwareUpdater` requires an explicit WebAdmin click.
- `db.terminateActiveCalls()` is DB bookkeeping only; no channel is touched.
- OTA/update never auto-installs (download and install both need a tap).
- `fetchVerdict()` correctly treats a failed health poll as inconclusive — this is the pattern the
  other findings should follow.
- `escalate()` rungs and the `lastHandoverAt` grace are correctly call-aware.
- `cpvt_free` / queued-task interaction is safe (`relink_to_sys_chan` rewrites `task->cpvt`).

---

## Suggested fix order

1. **Finding 2** — one function; neutralises the timeout->teardown class wholesale.
2. **Finding 1** — CLCC must not be a critical error.
3. **Finding 3** — three lines; the one most likely to be dropping real calls today.
4. **Finding 4** — skip no-op reloads, defer while a call is live.
5. **Finding 6** — distinguish "no" from "couldn't ask".
6. **Finding 7** — busy is healthy; remove the comment that invites the trap.
7. Findings 5, 8, 9 as capacity allows.

Every fix should land with the observability described in
[recovery-code-rules.md](recovery-code-rules.md): a destructive action that fires must say so, and
name the evidence it acted on.
