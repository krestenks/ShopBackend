# Network resilience tests (manager phones)

**Re-run these whenever you touch networking code.** That means anything in `SipEngine`,
`SipService`, `NetworkChangeCallback`, `TailnetAppContext`, the recovery ladder, the PJSIP
account/AoR settings in `AriClient`, or the app's Tailscale integration.

The reason is that every bug this suite catches was **invisible in normal use**. Each one looked
completely healthy on the desk, on WiFi, plugged in — and failed only when the phone was idle, or
away from WiFi, or moving between networks. Two of them were live for weeks. A phone that answers
your test call proves almost nothing; these tests are what actually distinguishes "working" from
"working on your desk".

Written 2026-08-30. See also `telephony-reliability.md`, `manager-phone-setup.md`.

Scripts live in `scripts/net-resilience/`.

---

## Before you start

- **Both phones must not be under test at once.** Each manager phone independently covers the
  shops, so test one while the other holds the fort. Check first:
  `curl -s http://127.0.0.1:8080/health` on the edge — every shop should be `callReady`.
- **Know which phone is which.** `adb devices -l`, then `adb -s <serial> shell getprop ro.product.model`.
  The AoR and tailnet IP matter: S26 = `mgr2` = `100.64.0.11`, spare S21 = `mgr3` = `100.64.0.7`.
- **Check for a SIM.** `adb -s <serial> shell getprop gsm.sim.state`. `ABSENT` means WiFi-off is a
  *total network loss* test, not a handover test — a different (harder) scenario. Pick the right script.
- Install the build under test as a **release-signed** APK and verify the signer matches before
  installing, or you will be forced into an uninstall and a full re-onboard. See
  `android-signing-setup.md`.

---

## 1. Handover: WiFi ↔ cellular

```bash
scripts/net-resilience/handover-test.sh <adb-serial> <aor> 10
```

Cycles WiFi off/on 10 times on a phone that also has cellular, watching reachability and — the part
that matters most — **the set of contact ports**.

**Pass:** every phase `firstAvail` 0–2 s, `dropped=0`, and `ports` never grows beyond the fixed
`5062`. New ports appearing means the ghost-contact bug is back.

**Known-good (2026-08-30, S26, app 1.0.96):** 20/20 transitions reachable, `firstAvail` 0–1 s,
ports stayed `[5062]`, zero backend `unreachable`/`DISAGREEMENT` events.

**What it caught:** the app never configured SIP transports, so Linphone bound a *random* ephemeral
port. With no NAT on the tailnet (`tun0` is point-to-point) that bind port is literally what Asterisk
records in the Contact URI, so every socket rebuild minted a new URI — Asterisk added a contact and
qualified both. Measured: 5 ports per account, 18 contacts across 6 AoRs with `max_contacts=1`, and
~95% of the server's SIP output going to dead ports. The pre-fix run collapsed from cycle 3 into a
**1310 s outage the recovery ladder could not clear**. Fixed by binding `udp/5062` (commit `b48ed34`).

This is not a synthetic cadence: **a phone parked at the edge of WiFi range flaps continuously** and
mints ghosts the whole time.

---

## 2. Total network loss and recovery

```bash
scripts/net-resilience/network-loss-test.sh <adb-serial> <aor> 10
```

For a phone with **no cellular fallback**, WiFi off removes the network entirely. Going Unavailable
is then correct — what is measured is how fast it comes *back*.

**Pass:** every cycle recovers (never `NEVER_IN_120s`), ports stay `[5062]`.

**Known-good (2026-08-30, S21, app 1.0.96):** 10/10 recovered, median ~28 s, range 17–52 s, ports
stable.

**Read the RTT with care.** The figure printed is sampled *at the moment of recovery* and is
routinely multi-second — 5 of 10 cycles exceeded 8 s (up to 12242 ms). That is a recovery artifact:
the same phone settles to 163–288 ms a couple of minutes later. Always re-measure after settling
before concluding anything. It is also why `qualify_timeout` is 15 s (commit `4e98958`) — at 8 s
those cycles were flagged Unavailable at the exact moment the phone was returning, which triggered
recovery commands against a phone that was already fine.

---

## 3. Severe throttling / lossy link

```bash
scripts/net-resilience/throttle-test.sh <aor> <tailnet-ip> 150
```

Shapes edge→phone traffic to **64 kbit, 25% loss, 400±100 ms delay** using `tc`/`netem`, filtered to
one tailnet IP so nothing else is affected.

**Pass:** the endpoint **never** goes Unavailable while throttled, and RTT settles afterwards.

**Known-good (2026-08-30, S21):** never went Unavailable across 2.5 min. RTT rose from ~233 ms
baseline to a 3177 ms peak, then settled.

**Two caveats.** The shaping is **egress-only** (`u32 match ip dst`), so it degrades edge→phone —
the direction carrying OPTIONS and INVITEs, but only half of a genuinely bad link. And the removal
depends on a `trap`; the script prints the qdisc afterwards so you can *verify* the restore. If it
is ever killed `-9`, clean up by hand:

```bash
ssh phone@192.168.0.192 'sudo tc qdisc del dev tailscale0 root'
```

---

## 4. Wire RTT vs Asterisk-reported RTT

```bash
scripts/net-resilience/wire-rtt.sh <tailnet-ip> 480
# and simultaneously, on the edge:
for i in $(seq 1 48); do echo "$(date +%H:%M:%S) $(sudo asterisk -rx 'pjsip show contacts' \
  | grep '<aor>/' | grep ' Avail ' | awk '{print $NF}')"; sleep 10; done
```

Run **both over the same window**. `pjsip show contacts` reports Asterisk's own measurement, which
includes its internal scheduling; `wire-rtt.sh` measures the truth from packet timestamps.

**Known-good (2026-08-30, S26, matched window):** wire n=64, median 287 ms, p90 455 ms, **max
951 ms**. Asterisk-reported over the same window: median 310 ms (agrees), max 1451 ms.

**Interpretation:** the medians agreeing validates both methods. A large divergence *in the tail*
means Asterisk is inflating, and since `qualify_timeout` is evaluated against Asterisk's number, it
can mark a phone Unavailable that answered promptly.

**Open issue:** Asterisk has reported 3864 / 6574 / 7549 ms in other windows, and those extremes have
never been captured on the wire, so the cause is still unknown. It is **not** Asterisk CPU (measured
~0.9% of a core) and appears on both S26 and S21, so it is unlikely to be phone-specific.

---

## 5. Idle behaviour (screen off, unplugged)

Not a script — leave the phone unplugged and idle on a table, then measure qualify RTT from the edge
for 20–30 min. No ADB needed. Afterwards, reconnect ADB and check the watchdog line.

**Pass:** zero `Unavail` samples; `suspended=0ms` and `wakelock=true` in every watchdog line;
`iterN` advancing steadily (~119/min at the 500 ms idle cadence).

**Known-good (2026-08-30, S26):** 30 min unplugged, 0 `Unavail`, `suspended=0ms` throughout, not one
missed pump cycle.

**What it caught:** `Handler.postDelayed` schedules on `SystemClock.uptimeMillis`, which **stops
during CPU suspend**. An idle phone silently stopped pumping SIP. Worse, `iterateStalledMs()`
measured on that same clock, so the pump and its own health metric froze together and read as
healthy — the phone reported `state=HEALTHY registered=3` while Asterisk had already given up on it.
Fixed with a `PARTIAL_WAKE_LOCK` plus a suspend-aware metric (commit `9d8afb4`).

---

## Gotchas that cost real time

- **`ps -eo pcpu` is a LIFETIME AVERAGE, not current load.** It showed Asterisk at 98.8% and nearly
  produced a completely wrong root cause. Sample `/proc/<pid>/stat` twice instead.
- **`tcpdump -A` prefixes the payload line with raw header bytes**, so `startswith("OPTIONS")` never
  matches. Take the method from tcpdump's summary line.
- **`In` is padded with TWO spaces** to align with `Out`; a ` (Out|In) IP ` regex silently matches
  egress only. Both tcpdump traps fail by returning **zero results rather than an error**, which
  reads exactly like "the network is quiet". Distrust an empty capture.
- **ICMP does not test the SIP path.** Ping is answered inside Tailscale's Go netstack and never
  reaches Linphone's socket. A clean `ping` proves the tunnel is up, *not* that SIP can work — the
  Data Saver bug had 0% ICMP loss at every packet size while the phone sent no SIP at all.
- **`top -H -b -n 1` reports 0% for every thread** on its first iteration; there is no delta to
  compute. Use two samples.
- **The backend deploy is not safe to re-run blindly.** A failed-looking run may already have swapped
  the jar; re-running then copies the *new* jar over the standby, leaving auto-rollback pointing at
  the build it would need to roll back from. Check
  `sha256sum ShopBackend.jar standby/ShopBackend.prev.jar` — they must differ.
