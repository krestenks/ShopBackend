# The "Dialing-wedge": root cause and fix plan

Written 2026-09-25. **Investigation only — nothing was changed, deployed, restarted or sent to a
modem.** Every claim below is marked CONFIRMED (I read the code or the log line myself) or
SUSPECTED. Source paths are on the edge at `/home/phone/asterisk-chan-quectel` unless stated.

---

## TL;DR

The wedge is **not** AT-port contention with SMS, and **not** a phantom call inside the modem.
It is a **call-index mismatch inside chan_quectel**, made permanent by two of our own safety
mechanisms working exactly as written:

1. chan_quectel invents a call index (always `1`) when it dials, then re-indexes to the modem's
   real index when CLCC reports the call as *dialing/alerting*.
2. If the call ends **before** that re-index lands, the driver still thinks the call is at index 1
   and sends `AT+QHUP=<cause>,1`.
3. The modem answers `+CME ERROR: operation not allowed` — a response string **missing from the
   driver's response table** — so the queue entry is never completed and the **entire AT port for
   that shop stalls for 40 s** until the command times out.
4. The timeout is reported as `Error sending hangup for call idx:1`, and **nothing cleans up**: the
   stranded call object stays in `DIALING`, so the trunk reports "Dialing" and refuses every new
   call with cause 44.
5. The stale call object can never be reaped, because index 1 is what the EC25 lists as its
   **data bearer** (`mode:1`), and our keep-alive guard treats any listing of that index as proof
   the call still exists.

Only the backend's `ModemStuckWatchdog` breaks the deadlock, by restarting the trunk.

**Recommended fix order: (A) teach the driver `+CME ERROR`, (C) stop the data bearer granting
unlimited immunity, then (B) fix the index mismatch itself.** A and C are small and together turn
a multi-minute outage into a ~30 s self-heal; B removes the fault but is the riskiest.

---

## 1. The verified chain

### 1.1 The driver invents the index — CONFIRMED
`pvt_get_pseudo_call_idx` (`src/chan_quectel.c`) returns the lowest index **not used by a driver
call object** — for a first call that is always `1`. It knows nothing about what the modem will
use. `channel.c:173` passes it to `channel_new`.

### 1.2 The correction only happens in two states — CONFIRMED
In `handle_clcc`'s no-matching-call branch (`src/at_response.c:1077-1090`), `cpvt->call_idx` is
rewritten only under `case CALL_STATE_DIALING:` / `case CALL_STATE_ALERTING:`. A call first seen in
any other state is never re-indexed.

### 1.3 The race, seen in one trace — CONFIRMED
shop2, 2026-09-22, same trunk, 80 seconds apart:

```
HEALTHY
18:56:30  Call - idx:1 channel:attached [init] → [dialing]     ← invented index
18:56:45  Call - idx:3 channel:attached [dialing] → [alerting] ← re-indexed to the modem's index
18:57:30  Hanging up call - idx:3
18:57:30  [AT+QHUP] ✓ Successful hangup for call idx:3

WEDGED
18:57:49  Call - idx:1 channel:attached [init] → [dialing]     ← invented index
18:57:58  Hanging up call - idx:1                              ← only 9 s later: no re-index yet
18:57:58  Ignoring unknown result: '+CME ERROR: operation not allowed'
18:58:38  ERROR [AT+QHUP] ⍻ Error sending hangup for call idx:1   ← exactly +40 s
```

**The trigger is therefore a short call** — one that ends before CLCC re-indexes it. The healthy
call ran 60 s; the wedging one was hung up after 9 s.

### 1.4 `+CME ERROR` is not in the response table — CONFIRMED
`src/at_response.h:27` lists `+CMS ERROR:` — there is **no `+CME ERROR:` entry anywhere**. So
`at_str2res` returns `RES_UNKNOWN`, the response is logged as `Ignoring unknown result`, and the
queue head is never completed. `at_queue_run` only ever writes the head, so **every other AT command
for that shop waits** — call control, CLCC polling, SMS.

### 1.5 The 40-second stall — CONFIRMED by arithmetic on the logs
Hangups use `ATQ_CMD_TIMEOUT_LONG = 40` (`src/at_queue.h:41`). All three incidents match exactly:

| Trunk | `+CME ERROR` ignored | "Error sending hangup" | Δ |
|---|---|---|---|
| shop1 | 09:16:57 | 09:17:37 | 40 s |
| shop2 | 18:57:58 | 18:58:38 | 40 s |
| shop2 | 19:01:11 | 19:01:51 | 40 s |

`+CME ERROR: operation not allowed` appears **exactly 3 times in the entire retained debug log**
(Jul 14 → Sep 25) — those three. It is close to a unique marker for this fault.

### 1.6 Nothing cleans up after the failed hangup — CONFIRMED
`src/at_response.c:782-785` handles `CMD_AT_CHUP` / `CMD_AT_QHUP` / `CMD_AT_CHLD_1x` failure with a
log line and `break` — no state change, no retry, no release. The same line covers a real `ERROR`,
a `+CMS ERROR` and a **timeout**, which is why the message is misleading: in these incidents it was
a timeout, not a modem rejection.

### 1.7 The stale call is immortal — CONFIRMED (this is the part I verified first-hand)
`src/at_response.c:1289-1305`: when the modem lists an index as non-voice while we hold a call
there, the driver sets `CALL_FLAG_ALIVE` and logs *"listed as mode:N state:N while a call is active
on it; not processed, kept alive"*. The reaper (`:1310-1350`) skips any call with that flag **and
resets its miss counter to 0**. With VoLTE on, the EC25 lists its PDP data bearer at index 1 on
every sweep, so a stale call object at index 1 is granted immunity forever.

That guard has fired **11 times in the retained logs, and every single one is the identical
signature `idx:1 listed as mode:1 state:0`** — the data bearer. It has never once protected a real
voice call.

### 1.8 Why the trunk refuses calls — CONFIRMED
The stale object keeps `pvt->dialing` set, so `pvt_ready` refuses new calls
(`chan_quectel.c:531`) → `Request to call on device which can not make call at this moment` →
**cause 44** to the customer. `pvt_str_state` reports "Dialing".

### 1.9 The driver's own timeout recovery cannot help — CONFIRMED
`monitor_thread.c` restarts a device after consecutive AT timeouts **only when it has no channels**
(`if (!PVT_NO_CHANS(pvt))` → "carrying calls - NOT restarting"). The stale call object still counts
as a channel, so the driver believes it is carrying a call and holds off. Hence the observed
`AT command timeout (1/4)` never escalating.

---

## 2. What this is *not* — three corrections

1. **Not AT-port contention with SMS.** [telephony-reliability.md:53](telephony-reliability.md:53)
   attributes failure mode 3 to SMS sharing the AT port. For shop1 the nearest preceding SMS was
   **5 m 42 s** earlier; for shop2 the SMS overlapped a *different* call that hung up cleanly
   (`✓ Successful hangup for call idx:3`) and the wedge came on the **next** dial with nothing on
   the port. The doc needs correcting. (The `SmsQueue` gate was built for this theory.)
2. **Not a phantom call in the modem.** The modem's call is gone too — the log shows the real call
   released (`CLCC (not found) idx:3 … state:6`). What is stranded is **chan_quectel's own call
   object at the wrong index**. My earlier "the modem thinks a call is in progress" framing was
   inverted.
3. **Not primarily caused by my 1.0.110 busy change.** Confirmed earlier and reconfirmed here: the
   fault predates it (first occurrence 23 Aug), and two of the three wedges involved no busy
   decline at all.

---

## 3. Frequency and impact

- `Error sending hangup`: **16–20 occurrences** across all four trunks since 23 Aug. A failed hangup
  is **necessary but not sufficient** — most self-heal, because the normal reaper clears a call the
  modem simply stopped listing.
- Wedges that needed a trunk restart: **3** in the retained journal — shop5 17 Sep 20:14, shop1
  22 Sep 09:18, shop2 22 Sep 19:03.
- Customer-visible: **18 logged `can not make call at this moment`** refusals, i.e. calls that could
  not be placed on a wedged trunk (Sep 2 shop5 ×8, Sep 5 shop1 ×3, Sep 13 shop1 ×2, Sep 17 shop5 ×2,
  Sep 22 shop2 ×2, Aug 27 shop2 ×1). Inbound customers in that window get nothing.
- Outage length per wedge: **~60–120 s** (watchdog needs 2 checks at 60 s), plus ~1 s for the
  restart itself. Measured: shop1 wedged 09:16:57, restarted 09:18:08, re-inited within 1 s.
- **Correction to an earlier claim:** the shop5 restart on 17 Sep did *not* cause lasting harm —
  shop5 sent SMS successfully 8 s later (20:15:04). The `Not initialized` churn beginning 22:50 was
  a separate episode 2.5 h later, not the restart's aftermath.

---

## 4. Why the current recovery is unsatisfying

It works, but it is blunt and slow: we restart a whole trunk to clear a bookkeeping error. The
watchdog's own audit line calls its evidence `WEAK` — and correctly so, because "state says Dialing,
Asterisk has no channel" is exactly what a *real* dialing call looks like for a moment. The guard
that makes this safe is the channel check, which has held in all three incidents (`callLive=idle`).

Two weaknesses worth noting while we are here:
- `AmiClient.trunkInCall` (`src/main/kotlin/asterisk/AmiClient.kt:303`) uses `command()` rather than
  `commandOrNull()`, so an AMI failure parses to "not on a call" — it **fails open**, against
  [recovery-code-rules.md](recovery-code-rules.md) §2. Non-destructive (it only gates SMS), but it
  is the same bug class the audit exists to catch.
- The orphan-restart branch has **no attempt cap** (the down-ladder does). In practice it fired once
  per episode.

---

## 5. Candidate fixes

### A. Teach the driver `+CME ERROR:` — *recommended first*
**Change:** add `+CME ERROR:` to `AT_RESPONSES_TABLE` (`src/at_response.h`, beside `+CMS ERROR:`)
so it parses as an error instead of `RES_UNKNOWN`.

**Effect:** the hangup fails **immediately** instead of stalling the shop's entire AT port for 40 s.
That alone removes the 40 s blackout, the bogus AT-timeout strike, and the misleading log.

**Why it is safe:** it changes *how a response is classified*, not what any recovery does. Today the
response is silently discarded; afterwards it completes the queue entry with an error, which is the
same path a plain `ERROR` already takes.

**Risk (the real one):** every command that can legitimately answer `+CME ERROR` now takes its error
branch instead of hanging until timeout. That is desirable, but it is a **fleet-wide behavioural
change** and must be enumerated before shipping: which commands can return `+CME ERROR` (SMS
sending, CLCC in some states, SIM queries), and does any handler rely on the current silent
fallthrough. **This enumeration is the main pre-work for A.**

**If it fires wrongly:** a command that would previously have waited 40 s and timed out now reports
an error sooner. Worst case is a faster, more visible failure — not a new failure.

### B. Fix the index mismatch at source — *the real fix, highest risk*
**Options:**
- B1: re-index the call object from **any** CLCC line whose number and direction match, not just
  `DIALING`/`ALERTING` (`src/at_response.c:1077-1090`).
- B2: stop trusting the invented index for hangups — target by number, or refuse to send `QHUP`
  until a real index is known (and fall back to `AT+CHUP`, which hangs up all calls, only when the
  driver holds exactly one).

**Why it matters:** A and C make the failure self-healing; B stops it happening.

**Risk:** this is the call path for every call on every shop. Index reuse is a live hazard — the
modem reuses the lowest free index, so a mis-matched re-index could point our call object at a
**different customer's call** and hang up a stranger. Commit `f3734d0` already carries an identity
check for the retry path (new-call state or changed number clears the hangup-requested flag); any
B-change needs the same. **B should not ship in the same deployment as A and C.**

### C. Stop the data bearer granting unlimited immunity — *recommended second*
**Change:** in the keep-alive guard (`src/at_response.c:1289-1305`), stop treating a **non-voice**
listing as proof that our *voice* call exists. Two variants, in increasing conservatism:
- C1: grant `CALL_FLAG_ALIVE` only when `mode` is voice but the *state* is unparseable
  (`state > CALL_STATE_WAITING`) — i.e. the evidence really is inconclusive. A `mode:1` data bearer
  is positive evidence the voice call is **not** there.
- C2 (more conservative): keep granting it, but only while an **Asterisk channel is attached**, and
  bound it to N consecutive sweeps; after that let the normal miss counting resume.

**Effect:** the existing reaper then clears the stale object after `CLCC_REAP_MISSES = 6` sweeps
(~30 s) when no channel is attached — which is the case in **every wedge observed**. The trunk
returns to Free by itself; the watchdog never needs to restart it.

**Why it is safe:** the reaper already distinguishes live conversations — `CLCC_REAP_MISSES_BRIDGED
= 24` (~2 min) when a channel is attached (`src/at_response.c:1240-1241`, `:1333`). Under C2 nothing
changes for a call Asterisk still holds. Under C1, a live VoLTE voice call is unaffected because it
is listed as `mode:0` and never reaches the guard.

**Risk:** the guard exists because the reaper once hung up live calls (13 Sep, 139×/day, cause 31) —
the single worst incident this system has had. C1 narrows it; if the EC25 ever lists a *live voice*
call as `mode:1`, C1 would let a real conversation be reaped after ~2 min (channel attached). I have
**no evidence of that happening** — all 11 firings were the data bearer — but it is the exact failure
we must not reintroduce. **C2 cannot cause it and is the safer starting point; prefer C2 unless the
evidence for C1 is strengthened.**

**If it fires wrongly:** a customer mid-conversation is hung up. That is why C2 (channel-gated)
is preferred and why this needs the [recovery-code-rules.md](recovery-code-rules.md) checklist.

### D. Backend-side hygiene — independent of the driver
- D1: make `trunkInCall` fail **closed** (`commandOrNull`), so an AMI failure does not read as
  "no call".
- D2: alarm on `+CME ERROR: operation not allowed` in the debug log — a near-unique, *early* wedge
  marker that fires ~40 s before the current detection and ~2 min before the restart.
- D3: cap the orphan-restart branch like the down-ladder does.
- D4: revisit the 15 s SMS gate. Its stated rationale is the SMS-contention theory these logs
  contradict; during a wedge it also guarantees a 15 s delay on every SMS for that shop. Not urgent:
  no SMS has ever failed because of it (0 `SmsQueue … FAILED` lines in the journal).

### E. Make the logs honest — cosmetic, cheap
`src/at_response.c:784` prints `task->cpvt->call_idx`, which is `0` when the hangup was queued on the
system channel, and cannot distinguish a timeout from a modem `ERROR`. Both cost real diagnostic
time here.

---

## 6. Recommended sequence

| Step | Change | Blast radius | Why this order |
|---|---|---|---|
| 1 | **D2** (alarm on `+CME ERROR`) | none — logging only | Gives us live telemetry on a fault we can currently only see after the fact. Ship first, learn from it. |
| 2 | **A** (`+CME ERROR` in the table) + **E** (log honesty) | all trunks, response classification | Removes the 40 s port stall. Needs the handler enumeration first. |
| 3 | **C2** (channel-gated, bounded keep-alive) | the reaper — live-call risk | Makes the wedge self-heal in ~30 s. RULE 1 checklist + review. |
| 4 | **D1**, **D3** | backend only | Small correctness fixes, no driver risk. |
| 5 | **B** (index mismatch) | every call on every shop | Only after 1–4 are proven in production for a week or more. |

Steps 2 and 3 each need an **Asterisk restart** to load new driver code (`module reload` does not
load a new `.so` — see [CLAUDE.md](../CLAUDE.md)), so each is a RULE 2 action: confirm with Kresten,
check `core show channels` is 0, act when idle.

---

## 7. How to test — including a real reproduction

**We can reproduce this deliberately, which is unusual and valuable.** From §1.3 the recipe is:
place an outbound call on a trunk and hang up **within ~5–8 seconds**, before CLCC re-indexes it.

Proposed test, to be run **only with Kresten's approval and only while the shops are closed**:
1. Pick one trunk, verify `core show channels` is 0.
2. Originate an outbound call to a number we control, hang up after ~5 s.
3. Watch for `Ignoring unknown result: '+CME ERROR'` → `Error sending hangup` → trunk state
   `Dialing` with no channel. If it wedges, we have a reproduction on demand.
4. Let the existing watchdog recover it (do not intervene) and confirm the trunk returns to Free.

Then, per change:
- **A:** re-run the reproduction. Expect the hangup error **immediately** rather than after 40 s,
  and no AT-timeout strike. Confirm normal calls and SMS still work on all four trunks.
- **C2:** re-run. Expect the trunk to return to **Free within ~30–40 s by itself**, with a
  `CLCC reap: releasing stranded call … channel:no` line and **no** watchdog restart.
- **Live-call regression check for C2 (the important one):** place a normal call, hold it for
  3+ minutes, confirm it is never reaped (`channel:yes` must never appear in a reap line — per
  CLAUDE.md that is always a bug).
- **B:** needs far more: outbound and inbound, short and long, two calls in quick succession,
  call-waiting, and a check that no `QHUP` is ever sent to an index the modem does not list.

Build and stage on the edge as usual; verify the running `.so` by hash and by `/proc/<pid>/maps`
showing no `(deleted)`.

---

## 8. Deployment and rollback

- Build on the edge (source lives only there), commit to our fork, **do not push to `upstream`**.
- Keep the current `chan_quectel.so` as a timestamped rollback copy, as we did for `8e95f61`.
- Deploy one change at a time, with at least a few days between, so any regression is attributable.
- Rollback = restore the saved `.so` + restart Asterisk when idle (~1 s of trunk downtime).
- Watch after each deploy: `Error sending hangup`, `CLCC reap … channel:yes` (must stay zero),
  `stuck in 'Dialing'`, and the per-trunk call success rate.

---

## 9. What I did not verify / open questions

1. **Which commands can legitimately return `+CME ERROR`** on an EC25 in our configuration, and
   whether any handler depends on the current silent fallthrough. This is the gating pre-work for A.
2. **Why the modem answers `operation not allowed`** rather than a "no such call" error — presumably
   because index 1 is a data bearer, but not proven.
3. **Whether a live voice call can ever be listed as `mode:1`** on this firmware. If it can, C1 is
   unsafe and only C2 is acceptable. Nothing in 30+ days of logs shows it.
4. Whether the index mismatch can also strike **inbound** calls; every observed case was outbound.
5. `cpvt_free` (`src/cpvt.c:129` vs `:134`) tests `PVT_NO_CHANS` before decrementing the counter, so
   `pvt_on_remove_last_channel()` may never fire — an upstream bug (RoEdAl, 2023), unrelated but
   worth a look if we touch this area.
