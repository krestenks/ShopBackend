# Rules for recovery / self-healing code

Every serious call-dropping incident in this system has come from the code meant to *protect* it.
Not from the call path — from the watchdogs, reapers and health checks. This document exists so we
stop re-deriving the same lesson.

Applies to any code that automatically, without a human, does something destructive: hangs up a
call, restarts a modem or a service, reloads a module, rewrites live config, bounces a socket,
unregisters an endpoint, or tells another device to restart.

---

## The invariant

> **Weak evidence must never authorise destroying working state.**
> **No automated recovery may fire while a call is live**, unless there is a written reason why it
> is safe.

---

## 1. Classify your evidence before you act

This is the whole ballgame. Every incident we have had is the same reasoning error: treating the
**absence of evidence as evidence of absence.**

**STRONG** — the system positively asserted the bad state:
- the modem reported the call ended (`^DSCI`, `CEND`, a CLCC entry whose state *is* released)
- `tty_status()` says the fd is dead
- an API returned a definite, parsed negative

**WEAK** — you inferred it from something missing:
- a command timed out
- a poll came back without the thing you expected
- a counter crossed a threshold
- a response could not be parsed
- a query failed, or the transport was down, so you could not ask at all

**WEAK evidence may only justify: logging, alerting, escalating a non-destructive action
(re-register, refresh, re-assert reachability), or waiting longer.**
It may never justify hanging up a call or tearing down a working device.

Real instances where we got this wrong — all from
[recovery-paths-audit-2026-09-13.md](recovery-paths-audit-2026-09-13.md):

| Code | Inferred | From (all WEAK) |
|---|---|---|
| CLCC reaper | "the call is gone" | it was absent from 2 pollings |
| `AmiClient.command()` | "there are no channels" | AMI did not answer usefully |
| `ModemScanner` | "this modem is free" | AMI returned an empty map |
| `SystemHealthMonitor` | "the system is unhealthy" | a modem was busy, i.e. *working* |
| uplink watchdog | "the mic is dead" | no bandwidth reading |
| `AT+CLCC` timeout | "disconnected from device" | one reply was >5s late |

## 2. Fail closed on inconclusive input

"I could not determine the answer" is a **third state**, distinct from yes and no, and it must
survive the API boundary.

- Do not collapse it into a falsy default. `?: emptyList()`, `?: false`, `?: 0` on a failed query is
  how `AmiClient.command()` turned an AMI error into "no calls are in progress".
- Return a nullable / sealed / `Result` type, and make callers handle "unknown" explicitly.
- The good example already in the tree: `SipService.fetchVerdict()` returns null on a failed health
  poll and does not escalate. `RegResult.Inconclusive` does the same. Copy those.

## 3. Guard on live calls, and mean it

- Check for an active call/channel **immediately before** the destructive act, not several
  statements earlier and not before a `delay`/coroutine boundary.
- If the guard's input is itself weak (e.g. a channel list from a query that may have failed),
  treat an inconclusive read as "a call IS live" — the safe direction.
- **A rate limit is not a guard.** A cooldown reduces how often you hang up a working call; it does
  not stop you doing it. Budgets and cooldowns are for limiting blast radius *after* you have a
  guard, never instead of one.

## 4. Prefer the least destructive action that could work

Escalate in order, and only on repeated, independent evidence:

```
log  ->  alert  ->  re-assert/refresh  ->  re-register  ->  rebuild  ->  restart process/device
```

Never jump to the end because it is more likely to work. `restart` is not a stronger `refresh`; it
is a different decision with a customer on the line.

## 5. Calibrate thresholds against measured data, not intuition

The reaper used "2 missed sweeps" with no measurement of how late CLCC replies actually are. The
correct move is to instrument first (log the latency distribution), then choose a threshold with
real headroom — and when in doubt, be an order of magnitude more patient. A recovery that acts a
minute late costs almost nothing; one that acts wrongly costs a customer call.

Corollary: **a command's timeout must be comfortably longer than its own repeat interval.**
`AT+CLCC` had a 5s timeout on a 5s poll, guaranteeing overlap under load.

## 6. Every destructive action must announce itself

Non-negotiable, because this is what turns a silent two-week bug into a same-day fix.

When a destructive action fires, log one line, at WARNING or above, containing:
- what it did, and to what (trunk / AOR / device)
- **the evidence it acted on**, with the actual values
- **whether a call was live at the time**

```
[Recovery] <action> target=<x> evidence=<what, with numbers> callLive=<true|false>
```

And **alert if a destructive action ever fires with `callLive=true`.** That single alarm catches
this entire class of bug empirically, including the ones we have not thought of. The CLCC reaper
logged 139 warnings a day for two weeks; nobody was watching. Make the canary page us.

## 7. Declare the failure mode in the code

Every automated destructive action carries a comment block stating:

```
TRIGGER:    what condition, measured how, over what window
EVIDENCE:   STRONG or WEAK, and why that is sufficient
BLAST:      one call? one device? one shop? the whole PBX? the fleet?
GUARD:      what prevents this firing during a live call
IF WRONG:   what happens to a customer if this fires on a false positive
```

If `IF WRONG` says "a customer is hung up mid-conversation", the design is not finished.

## 8. Testing

A recovery path that has never been triggered on purpose is untested, regardless of coverage
numbers. Two tiers, and most of our findings are in the first:

**Unit-testable (no hardware).** Most guard logic is pure:
- an inconclusive query result does not authorise the destructive branch
- a no-op config regeneration does not reload
- "busy" is healthy
- thresholds behave at their boundaries

**Fault injection on the edge.** The acceptance test for any recovery change is always the same:
**place a real call, trigger the recovery path deliberately, assert the call survived.** We have
proven techniques — `iptables` DROP on qualify/RTP to force "unreachable" (validated 1.0.83 and the
one-way detector), and forcing modem states. Use them.

---

## Review checklist

Before any change to recovery/watchdog/self-healing code is considered done:

- [ ] Evidence classified STRONG or WEAK; if WEAK, nothing destructive happens
- [ ] "Could not determine" is a distinct state and fails closed
- [ ] Live-call guard immediately precedes the destructive act
- [ ] Guard fails safe if its own input is unreliable
- [ ] Least destructive effective action chosen; escalation ordered
- [ ] Thresholds justified by measurement; timeout > repeat interval
- [ ] Fires a `[Recovery]` log line with evidence and `callLive`
- [ ] `TRIGGER/EVIDENCE/BLAST/GUARD/IF WRONG` comment block present
- [ ] Guard logic has a unit test; destructive path fault-injected against a live call
- [ ] Blast radius is the minimum that fixes the problem (one trunk, not the PBX)
