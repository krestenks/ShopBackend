# chan_quectel — fixing the "Dialing" wedge and the restart-crash

Status: **plan only** (2026-08-27). Nothing on the live box has been changed. See also
`telephony-reliability.md`, `self-healing-monitor-plan.md`, `phone-server-setup.md`.

## Recommendation (revised): BACKPORT into the RoEdAl fork — do NOT migrate

> **Note on the reversal.** An earlier draft of this file led with "migrate to IchthysMaranatha."
> The completed driver comparison reversed that conclusion, and this version replaces it. The honest
> reason: migration is *feasible* but fixes nothing that's actually broken and adds new risk. The
> evidence is below so the reversal is auditable, not hidden.

Do three small, localized changes **in the RoEdAl source we already run**, rebuild the module with
the existing toolchain, and swap it in:

1. **Add the CLCC reap loop** RoEdAl dropped (the wedge fix).
2. **Add a proactive trigger** — a periodic CLCC poll and/or a per-call dialing timeout — because
   the reap only fires when *something* issues a CLCC, and a stranded call with no follow-up
   operation never gets one. This is the load-bearing fix.
3. **Add a `cpvt->pvt` NULL/dangling guard** in the hangup/teardown path (the confirmed SIGSEGV fix).

No backend changes, no config translation, single-artifact rollback. Migration is evaluated and
rejected in §5 and kept on record as the fallback only if backporting proves untenable.

---

## 1. The two defects (confirmed)

**A. The wedge.** Once `ATD` is acknowledged, a call is torn down only by a call-end URC
(`^DSCI …,6` / ccinfo / CLCC-RELEASED). If that URC is lost/unparsed the call is stranded in
"Dialing" forever → later calls fail cause 44 until `quectel restart`. RoEdAl has **no reader for
`CALL_FLAG_ALIVE`** (set `at_response.c:950`, reset `:1145`, **never tested** — grep of the whole
tree) so the CLCC reap that would reconcile a lost URC is absent, and **no periodic CLCC poll or
per-call timeout** exists in either tree. IchthysMaranatha *does* have the reap
(`at_response.c` ~1271-1274) — RoEdAl's rewrite dropped it. This is a RoEdAl regression.

**B. The restart-crash.** `quectel restart` on a wedged device → `pvt_disconnect`
(`chan_quectel.c:110`) walks `pvt->chans` calling `at_hangup_immediately`, which dereferences
`cpvt->pvt` (`at_command.c:1388`) → `pvt->is_simcom` (`:1390`) with **no NULL check** → SIGSEGV on
the stranded/corrupt call object. Confirmed from two identical coredumps (elfutils backtrace:
`at_hangup_immediately ← pvt_disconnect ← monitor_threadproc_pvt`). **This deref is unguarded in
IchthysMaranatha too** (`at_hangup_immediality` → `at_write(cpvt->pvt, …)`, `at_command.c:991`, no
NULL check) — so **migrating does not fix it.**

## 2. Why NOT migrate (this is the crux of the reversal)

| Reason | Evidence |
|---|---|
| **Doesn't fix the restart-crash** | The `cpvt->pvt` deref is unguarded in *both* trees (RoEdAl `at_command.c:1388-1390`; upstream `at_command.c:991`). Crash-hardening must be written fresh either way. |
| **Adds an inbound-SMS-during-call crash** | Upstream README + `BUGS` (#10): chan_quectel may crash when a **received** SMS lands during a call; mitigation `disablesms=yes` disables *reception only*. This box has `autodeletesms=yes` and receives SMS, so it could hit it. RoEdAl does not carry this documented bug. |
| **Regresses the hangup method** | Upstream hangs up with `AT+CHLD=1x` (`at_command.c:991`), flagged in its own `BUGS` (#4) as a device-killer; RoEdAl uses cleaner per-call `AT+QHUP=<cause>,<idx>` / `AT+CHUP`. |
| **Breaks the backend contract** | `quectel sms send …` → upstream is `quectel sms …` (**`send` token dropped**, `cli.c:465`), and `show devices` grows from 9 to 12 columns (+`Submode`/`IMEI`/`IMSI`). See §5 for exactly what shifts. |
| **The "better handling" isn't free upstream** | Neither tree has a periodic CLCC poll or dialing timeout — must be written fresh regardless. |

Migration **is** feasible on the make-or-break axis — upstream fully supports the EC25 UAC/ALSA
voice path (`snd_pcm_open` on `alsadev`, `AT+QPCMV=1,2`, EC25 `2c7c:0125` in the device table), key
renamed `uac=on`→`quec_uac=1`. It's simply not worth the churn for zero capability gain and net-new
crash surface.

## 3. The backport (recommended) — three changes into RoEdAl

All three are small, localized C edits in `/home/phone/asterisk-chan-quectel/src/`, built with the
CMake toolchain already on the box.

1. **CLCC reap loop.** RoEdAl's `at_response_clcc` already has the two halves it needs — it resets
   `CALL_FLAG_ALIVE` on all cpvts (`:1145`) and re-sets it for calls the modem lists (`:950`). Add
   the missing reader before the function returns: traverse `pvt->chans` and
   `cpvt_change_state(cpvt, CALL_STATE_RELEASED, …)` for any cpvt **without** `CALL_FLAG_ALIVE`
   (port IchthysMaranatha `at_response.c` ~1271-1274, adapted to RoEdAl's `cpvt_change_state`/
   `CPVT_TEST_FLAG`). ~4 lines.
2. **Proactive trigger (the load-bearing one).** The reap only helps if a CLCC is issued. Add
   *either* a periodic `AT+CLCC` poll in the monitor thread (e.g. every few seconds while any call
   is non-idle) *or* a per-call dialing watchdog that reaps a cpvt sitting in
   INIT/DIALING/ALERTING with no Asterisk channel after N seconds. Without this, a call stranded
   with no follow-up operation is never reconciled even with the reap present.
3. **Crash guard.** In `at_hangup_immediately` (and the `pvt_disconnect` loop), guard against a
   NULL/dangling `cpvt->pvt` before dereferencing it — a corrupt call object must never crash
   teardown, even after the strand itself is fixed.

Reap + trigger together prevent the wedge; the crash guard makes teardown safe regardless. Upstream
is useful only as the **reference** for the reap logic (and the `CPCMREG` PCM fallback), both of
which we're adding to / already have in RoEdAl.

## 4. Mechanics — backport (to execute later)

- **Build.** Patch the three spots; rebuild `chan_quectel.so` with the existing CMake build under
  `/home/phone/asterisk-chan-quectel/build`. **Save the current `.so`** (build-id `bc06dce2…`) as
  the rollback artifact.
- **Test rig — prerequisites not yet in place (state plainly):** a **test SIM** (spare modems have
  none — DB shows shops 3 & 4 empty) and **confirmation that a spare EC25 presents a working UAC
  audio card** (6 modems present, only 2 assigned, only 2 UAC cards currently enumerated). Use a
  free device slot / test stanza on a spare modem so live shop1/shop2 are untouched.
- **Test matrix:** reproduce the wedge (outbound call **then cancel**, unanswered-callback
  teardown), confirm the reap + trigger clears a stranded call, confirm `quectel restart` on a
  (formerly) wedged device **no longer crashes**; regression-check inbound/outbound audio (UAC),
  SMS send/receive.
- **Cutover (off-hours; calls cluster in business hours):** swap the `.so`, `systemctl restart
  asterisk`, re-validate both shops. **No config or backend change** — this is the big advantage
  over migration.
- **Rollback (fast — only phone line):** restore the saved `.so` + `systemctl restart asterisk`.
  **Single artifact**, no paired jar/config revert.

## 5. Migration — evaluated and REJECTED (kept on record)

Only pursue this if backporting proves untenable. Feasible but higher-risk and net-worse (§2).

**Audio (make-or-break): feasible.** Upstream supports EC25 UAC/ALSA (`snd_pcm_open` on
`alsadev`, `AT+QPCMV=1,2`, EC25 in device table). Voice would need end-to-end re-validation (its
ALSA path differs from RoEdAl's).

**AMI contract — would need backend changes:**
- `quectel show devices` grows **9 → 12 columns**: upstream inserts **`Submode` at col 5** and
  **`IMEI`,`IMSI` before `Number`**. **Correction to the earlier draft:** `col0=ID` and
  **`col2=State` are unchanged**, so the backend's watchdog/status read (`parts[0]`, `parts[2]` in
  `AmiClient.quectelDeviceStates`) actually **survives**; what breaks is any read of
  Provider/Model/Firmware/Number by index. Still, switch to header-based parsing to be safe.
- `quectel sms send …` → **`quectel sms …`** (the `send` subcommand is dropped) — `AmiClient.sendSms`
  would break and must change.
- Unchanged and safe: `quectel show device state`, `quectel restart now`, `module reload
  chan_quectel.so`, and all State strings (`Free`/`Dialing`/`Not initialized`/`Not connected`/`Ring`).

**Config translation (both shops), and the generator must change too** (`QuectelConfigWriter`
hard-codes `uac=on`, so provisioning would clobber a migrated config):

| Deployed (RoEdAl) | IchthysMaranatha | Note |
|---|---|---|
| `uac=on` | **`quec_uac=1`** | confirmed rename (errors+skips device if set without `alsadev`; we set both) |
| `resetmodem=yes` | **`resetquectel=yes`** | confirmed rename |
| `data=` / `alsadev=` / `context=` / `group=` / `autodeletesms=` / `initstate=` / `interval=` | same | confirmed same |

**Crash posture (the decisive point):** migration does **not** remove crash risk — the `cpvt->pvt`
hangup SIGSEGV is unguarded in upstream too (`at_command.c:991`), and upstream *adds* the
inbound-SMS-during-call crash. You'd be adding a crash, not trading one away.

## 6. Observability (do alongside, from the self-healing work)

- Record **"Error sending hangup"** and any Asterisk **SIGSEGV/core-dump** as `reliability_event`
  rows — the wedge is currently only inferred indirectly and the crashes aren't tracked at all.
- Persist SIP **qualify history**; keep the `/health` per-shop call-readiness check to verify both
  shops stay call-ready across a full day after the fix.

## 7. Open items / uncertainties (not settled)

- Whether the shops need **inbound SMS** — only relevant to the rejected migration path (would
  decide if `disablesms=yes` were an acceptable mitigation there). Moot for the backport.
- Whether a spare EC25 can present a working **UAC audio card**, and availability of a **test SIM** —
  both prerequisites for the test rig, neither currently in place.
- Exact form of the **proactive trigger** (periodic CLCC poll vs per-call timeout, and the interval)
  — decide during implementation/testing; the poll is the more robust of the two.
