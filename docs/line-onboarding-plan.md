# Plan: adding a SIM and a shop line

Written 2026-11-19, after adding Mon, Nana and Nina on the EliteDesk (see elitedesk-migration.md).

## Goal

A person puts a SIM in a modem, opens one page, sees whether the line will work and why not if it
will not, assigns the SIM to a shop, and tests the line. No serial terminal, no ssh, no guessing which
modem holds the SIM.

## What went wrong on 2026-11-17 (the evidence for each work package)

| # | Observation | Cause (verified unless marked) |
|---|---|---|
| 1 | The modem holding the new SIM was found by restarting spares one at a time | `AT+QSIMDET?` reads `0,0` on every spare: a SIM inserted while the modem runs is not detected until the modem restarts |
| 2 | Nana's line stayed `Not connected` for 40 minutes after its modem was power-cycled | The ALSA card id changed from `EC25EUX_6` to `EC25EUX`. The scan could not read the IMSI of a trunk that is not connected, so the planner found no modem for the shop and left the old id in place |
| 3 | Nina's line was Free and registered but every SMS failed after 15 s, then the AT port stayed silent | `ims` stayed `1,0` (VoLTE never registered). The cause of the failed SMS is not proven. The SIM may also lack credit |
| 4 | The silent AT port was cleared only by switching the hub port off and on over ssh | No backend function does it |
| 5 | A modem needs several radio settings that were applied by hand | Documented in phone-server-setup.md section 7b; nothing checks them or applies them |
| 6 | Nothing told us why a line did not work | The scan table shows IMSI, signal and "UAC ok" only |

## Work packages

### W1. Readiness check of a modem (read only)

For a modem that no trunk holds, read over its AT port: SIM state, IMSI, registration, access
technology, carrier profile (`QMBNCFG "AutoSel"`, `"Select"`), IMS setting and state (`QCFG "ims"`),
PDP contexts (`CGACT?`), USB layout (`QCFG "usbcfg"`, UAC flag), SMS service centre.
A pure function turns the readings into a list of checks. Each check is OK, FIXABLE (W2 can apply it)
or BLOCKED (needs a person), with a one-sentence reason in plain words.
For a modem that a trunk holds, the port is not probed (existing rule, audit finding 8). The page shows
what AMI reports and says "not read: the trunk holds the port".
Files: `asterisk/ModemReadiness.kt` (pure), probe added in `ModemScanner.kt`, column in the scan table.

### W2. Prepare modem (writes, free modem only)

A button per modem. Applies only the FIXABLE checks of W1:
1. `AT+QMBNCFG="AutoSel",1` when it reads 0, followed by one `AT+CFUN=1,1` of that modem.
2. `AT+QCFG="ims",1` when the setting reads 0.
3. `AT+CGACT=1,2` when the carrier profile is active, `ims` state is 0 and PDP context 2 is not active.
It never writes `usbcfg`: a layout change re-enumerates the USB device and caused the `-110` failures.
A missing UAC flag is reported as BLOCKED with the existing hint.
Steps are planned by a pure function (`ModemPreparePlan`) and executed one by one; every step is read
back and reported.

### W3. Correct the ALSA card of a trunk that is not connected

`DevicePathPlanner` gets one more case. When no modem reports the shop's IMSI, and the modem on the
USB port named in the shop's current AT path reports no IMSI, and its trunk state is a known down state
(`Not connected`, `Not initialized`), only the ALSA device of that shop is updated, to the card the
resolver sees on that port. The AT path is not changed.

### W4. Pin ALSA card ids to USB ports (server change)

`scripts/alsa-socket-names.sh` already generates a udev rule that sets the card `id` from the USB port.
`/sys/class/sound/cardN/id` is writable on the EliteDesk (mode 0644, root). Install the rule, then
migrate the trunk config and the database to the new ids once, with the shops closed and no call active.
After that a power cycle cannot change a card id.

### W5. Test line

After assignment a "Test line" button sends one SMS from the shop's trunk to a number the administrator
enters, waits up to 45 s for the report that chan_quectel posts to `/api/internal/telephony/sms/report`
(the message row goes from queued to sent or failed), and prints the result with the reasons that the
backend can know: trunk state, balance recorded for the SIM against the 100 kr renewal price, and for
a modem no trunk holds the W1 checks. The reasons are written in the result message in plain words.

### W6. Power-cycle a modem's hub port (writes, human-started)

A button per modem. Runs a helper that switches the hub port off, waits 4 s, and switches it on
(`/sys/bus/usb/devices/<hub>:1.0/<hub>-port<n>/disable`), then waits for the modem to enumerate.
Guard: AMI must have answered, and the modem must be either held by no trunk, or held by a trunk whose
state is `Free`. Any other state, and any case where AMI did not answer, refuses.
The helper (`/usr/local/bin/ec25-port-cycle`) accepts only a port name that matches
`1-1.4.<digit>.<digit>` and exists as an EC25 device, so the backend cannot be made to write elsewhere.

### W7. Page

The scan table gains: Ready (W1 reasons), Prepare (W2), Power cycle (W6), Test line (W5, for assigned
modems). The assign form stays as it is. After "Assign" the message names the next step.

## Not built in this plan, and why

- **Automatic power cycle by the modem watchdog.** The only evidence is a silent AT port, which is weak
  evidence (docs/recovery-code-rules.md section 1). It needs its own design: two independent probes,
  the trunk Free, no call on that trunk for N minutes, a strike limit, and a `[Recovery]` line. W6 gives
  the human version first and the log will show how often it is needed.
- **`QSIMDET=1,1` (SIM hot detection).** Not verified on the EC25 firmware here. It needs a test on a
  spare with a SIM inserted while the modem runs. Until then a modem needs a restart after a SIM change;
  W1 shows `no SIM` and W6 restarts the modem.

## Recovery-code review (CLAUDE.md RULE 1)

Triggers: W2 resets a modem; W3 rewrites live trunk config; W6 restarts a modem. All three are started by
a person or by provisioning, none runs on a timer. For each, the final report states trigger, evidence
(STRONG or WEAK), blast radius, live-call guard and what a customer sees if it fires wrongly, and walks
the checklist in recovery-code-rules.md. Then the diff goes to parallel reviewers (correctness, checklist,
blast radius, concurrency, inconclusive paths). Every serious finding is read in the code before it is
reported.

| Item | Trigger | Evidence | Blast | Guard | If wrong |
|---|---|---|---|---|---|
| W2 | administrator clicks Prepare | STRONG: readbacks from the modem | one modem that no trunk holds | modem not held by a trunk, AMI answered | a free modem restarts once; no trunk affected |
| W3 | provisioning or watchdog scan | WEAK (a missing IMSI), so limited to a trunk in a known down state and to the card id only | one trunk that is already down | trunk state down, resulting config has no shared device (existing planner check) | a trunk that was down stays down |
| W6 | administrator clicks Power cycle | STRONG for `Free`: the driver reports it | one modem | AMI answered and trunk `Free` or not held; a call on the trunk refuses | the trunk is down for about 20 s and then comes back; a customer on that trunk would be dropped, which the guard prevents |

## Tests

- Unit tests (no hardware): `ModemReadinessTest`, `ModemPreparePlanTest`, `DevicePathPlannerTest`
  (new cases), `PortCycleGuardTest`.
- On the EliteDesk: W1 and W2 on a spare modem; W3 by removing and restoring a modem while the backend
  runs; W4 by power-cycling a hub port and checking the card id is unchanged; W5 on the shops; W6 with a
  call placed first (the guard must refuse) and then idle.

## Order

W1, W3, W2, W6, W5, W7 (code), then W4 (server), then review, deploy, verify.

## Review result, 2026-11-19

Three parallel reviews (recovery checklist, correctness, concurrency) read the commit. Findings that were
confirmed in the code and fixed:

- The scan decided "no trunk holds this modem" from a snapshot in which a failed AMI query dropped a trunk. The radio
  read now asks `lookupTrunk` immediately before it and reads only on a definite "none".
- `lookupTrunk` treated a modem without a resolved AT port, and a missing AMI client, as "no trunk". Both are now unknown.
- Prepare re-asks `lookupTrunk` immediately before the writes.
- The power-cycle guard refused every non-Free state with no bound. Force is the bound: it needs Asterisk to show no channel of the
  trunk, and still refuses when the channel list cannot be read.
- The power cycle excludes scans, reads and prepare (same lock), refuses while a firmware update runs, and records the
  database event after the helper, so no write sits between the guard and the helper.
- The helper script switches the port on again on any exit (trap).
- The helper output was read before the timeout was applied; the process is now stopped first.
- `isReady` was true for a modem that answered nothing; the SIM and audio checks must now be OK.
- A context that is not listed is unknown, not off. Turning VoLTE on is followed by one restart, as in section 7b.
- The line test matches a failure by shop, time and recipient in one query, and does not run twice for one shop.
- The planner rule carries its TRIGGER/EVIDENCE/BLAST/GUARD/IF WRONG block, and an applied rewrite logs a `[Recovery]` line.

Not changed:

- `probe()`, `atSendSms` and QFirehose open AT ports outside the new lock (existing behaviour).
- `AmiClient.trunkInCall` returns false when the state cannot be read, and the call states do not include "Ring" (existing).
- `AT+QCFG="ims"` mode 2 (carrier controlled) is read as "not enabled". Unverified on this firmware.
- The 45 s wait of the line test keeps the HTTP request open.
- Fault injection of the power-cycle guard against a live call has not been run.

## W4 result, 2026-11-19 (card id pinning)

- On a spare modem (no trunk), a udev rule `ATTR{id}="ec_1_1_4_2_2"` renamed the card, and the name came back
  unchanged after the hub port was switched off and on. The rule applies when the card is created.
- On a card that a trunk uses, writing the id fails with `Invalid argument`, with the trunk connected and also with the
  trunk stopped (the sound device was released, the write still failed). A rollout that renames live cards does not work.
- Every live card therefore takes its pinned name only when its modem enumerates again (hub port cycle or host reboot).
  The trunk config and the database must name the new card before that happens. The planner rule in W3 corrects the sound
  card of a trunk that is down, so it also covers a modem that enumerates after the rule is installed.
- Nothing is installed on the EliteDesk: the test rule was removed, and all eight trunks use their old card names.
