# Where manager-phone latency actually comes from

Measured 2026-09-01 from the edge box with `scripts/derpprobe.sh`. This supersedes the
working assumption that the DERP relay was responsible for multi-second qualify RTTs.

## Method

Four round trips to the **same phone** over the **same relayed tailnet path**, differing only
in *who answers*. That is the whole trick: the first three are answered below the application,
so their agreement fixes the relay's true cost, and anything the SIP probe costs on top was
added by the app.

| probe | answered by | measures |
|---|---|---|
| `tailscale ping` (disco) | libtailscale | relay only |
| ICMP echo at 64 B and 1240 B | tailnet netstack | relay only, plus loss / MTU behaviour |
| TCP SYN → RST on closed :9 | tailnet netstack | relay only, over **TCP** instead of UDP |
| raw SIP OPTIONS → :5062 | **the app** | relay **+ app** |

## Result 1 — the relay is fast; the app is slow

| layer | S26 | S21 |
|---|---|---|
| edge → DERP server | 1.4–1.8 ms | (shared) |
| relay floor (disco / ICMP / TCP agree) | 30–98 ms | 46–91 ms |
| ICMP loss, 64 B **and 1240 B** | **0 %** | **0 %** |
| raw SIP OPTIONS | 106 → **2006 ms** | 111 → 647 ms |
| **app-added latency** | **53 → 1035 ms** | 57 → 1108 ms |

The relay costs ~50–90 ms and loses nothing, right up to the 1280 B tailnet MTU — so packet
size is not a factor either. The seconds are added *above* the tunnel.

## Result 2 — the mechanism is a 500 ms pump, one SIP message per tick

`SipEngine.kt` sets `ITERATE_INTERVAL_IDLE_MS = 500L`; Linphone only processes SIP when
`Core.iterate()` runs. Firing five OPTIONS **simultaneously** returns replies spaced at
*exactly* one tick:

```
S26  burst of 5 ->  370,  874, 1373, 1885, 2391 ms   gaps 504, 499, 512, 505
S26  burst of 5 -> 1977, 2484, 2978, 3496, 4001 ms   gaps 507, 493, 519, 505
S21  burst of 5 ->  164,  707, 1229, 1766, 2297 ms   gaps 543, 522, 537, 531
```

So each phone answers **one SIP message per 500 ms tick**. With five AORs registered per phone,
Asterisk's qualify burst makes the last AOR wait ~5 × 500 ms — which is exactly the ladder seen
in `pjsip show contacts` on a single device, single port, single 5-tuple:

```
mgr2 247 ms   shop1-manager 665 ms   shop2-manager 1645 ms   shop5-manager 2170 ms
```

The second burst above shows the compounding case: the pump itself was stalled ~1.6 s, so the
whole ladder shifted and the tail reached 4 s. `qualify_timeout` is 15 s today, which is why
this is currently survivable rather than fatal — but the headroom is smaller than it looks.

## Result 3 — UDP vs TCP: the observation is real, the cause is not the transport

DERP encapsulates every packet, UDP and TCP alike, inside one TLS/TCP connection to the relay.
There is therefore no transport-level difference to find at the relay, and the measurements
agree: TCP SYN→RST and ICMP echo track each other (S26 med 106 vs 82 ms; S21 med 99 vs 51 ms).

HTTP polls look healthy during outages because OkHttp runs on its own threads. SIP looks
terrible because it is gated behind the 500 ms pump. **The same pump processes SIP over TCP**,
so moving the pjsip transport from UDP to TCP would not fix this — it would add TCP's
head-of-line blocking over a relay for no gain. Fix the pump, not the transport.

## Result 4 — direct paths can never form (separate bug, worth fixing)

`tailscale debug netmap` on the edge:

```
self edge-1  endpoints: ['192.168.0.1:41641', '192.168.0.192:41641']     <- both RFC1918
peer s26     endpoints: ['5.103.230.90:41273',  '10.133.70.45:41273', ...]
peer s21     endpoints: ['37.96.39.144:1074',   '10.142.206.225:39858', ...]
```

The phones advertise correct public endpoints. **The edge advertises only private ones** — no
`86.52.31.162:…` — because its only STUN server sits on its own LAN (headscale at
192.168.0.2:3479), so STUN returns the router's internal SNAT address. The phones have nothing
routable to punch towards, so every path falls back to the relay, permanently.

STUN itself is fine: `tcpdump` on server8 shows both phones' public IPs sending binding
requests to 3479/udp and getting responses, so that router forward is in place. A single DERP
region does leave `MappingVariesByDestIP` blank in netcheck (NAT-type detection needs ≥2).

Fixes, cheapest first:

1. Forward **UDP 41641 → 192.168.0.192** on the UniFi router, then re-check `tailscale ping`.
   Tailscale learns candidate paths from inbound disco packets, so one reachable side may be
   enough. Low risk, quick to test.
2. Give the edge an **off-LAN STUN source** (a second DERP region) so it discovers its real
   public endpoint. This reverses the deliberate self-host-only `derp.urls: []` choice, so it
   is a judgement call rather than an obvious win.

Worth doing — it removes ~60 ms and a relay hop from both signalling and audio — but note it
does **not** address the 500 ms pump, which is the dominant term by an order of magnitude.

## The probe

`scripts/derpprobe.sh` + `scripts/probe/{sipprobe,burst,tcprtt}.py`, deployed to
`/home/phone/probe/` on the edge and running as `derpprobe.service` (60 s cycle, burst test
every 10th). Output: `/home/phone/derpprobe.csv`, one row per phone per cycle, with
`relay_ms`, `app_added_ms` and `burst_gap_ms` as the columns that matter.

```bash
ssh phone@192.168.0.192 'column -s, -t < /home/phone/derpprobe.csv | tail -20'
```

Stop it with `sudo systemctl disable --now derpprobe.service`. The CSV grows ~0.5 MB/day and
is not self-trimming.

---

# Result of the fix: idle pump 500 ms → 20 ms

Applied 2026-09-01 to the S21 (`eriks-s21`, 100.64.0.7) as ShopManager `1.0.99-pump20`,
release-signed and installed in place. The S26 was deliberately left on 500 ms as a control in
the same time window.

Five OPTIONS fired simultaneously:

```
S21 before   541, 1069, 1578, 2112, 2633 ms   gaps 528/510/533/521   spread 2092 ms
S21 after     62,   89,  118,  149,  176 ms   gaps  27/ 29/ 31/ 27   spread  114 ms
S26 control 3984, 4509, 4984, 5500, 5998 ms   gaps 524/475/516/498   spread 2014 ms
```

| metric (S21) | before | after |
|---|---|---|
| burst gap (= pump interval) | 493–568 ms | **20–35 ms** |
| spread across 5 queued OPTIONS | ~2100 ms | **~111 ms** |
| single SIP OPTIONS, median | 475 ms | **63 ms** |
| `app_added_ms` (probe CSV) | 582 ms | **43 ms** |
| Asterisk qualify RTT, all 5 AORs | 178–472 ms | **63–157 ms** |

The single-OPTIONS median of 63 ms now sits **on the relay floor** (50–90 ms measured
independently via ICMP and TCP-RST), i.e. the application's contribution is essentially gone.
The S26 control moved not at all, so this is the pump and not ambient conditions.

`derpprobe.service` caught the transition unattended:

```
22:52:33  burst_gap=534ms  app_added=582ms  sip_udp=669ms  relay=87ms
23:06:08  burst_gap=47ms   app_added=43ms   sip_udp=91ms   relay=48ms
23:09:27  burst_gap=49ms   app_added=43ms   sip_udp=99ms   relay=56ms
```

Not yet settled:

- **Battery.** The 20 ms pump was previously measured at ~1.6 % of one core, and the wakelock
  that dominates the drain is unchanged — but that is a bench figure, not a field one. Watch
  the S21 over a full duty day before rolling out.
- **Rollout.** The test build keeps `versionCode = 100` so OTA cannot revert it; a fleet
  release needs vc101 and a new OTA manifest.
- **The S26 is still on 500 ms** and remains the phone generating nearly all the field alarms.

---

# Rollout, 2026-09-01

## The S26 cannot be fixed remotely — by any route

Worth stating plainly, because it is not obvious from the architecture:

- `ShopScheduleActivity` calls `checkForUpdates(manual = false)` on create, so the app **only
  looks for an update when a human opens the UI**. There is no background check.
- `ApkInstaller` fires the system package-installer intent (`REQUEST_INSTALL_PACKAGES`). There is
  no `DevicePolicyManager` / device-owner anywhere, so **the install needs a tap on the phone**.
- The only remote command the backend can send is `CMD_RESTART`, which restarts the SIP stack.

So the fix reaches a distant handset exactly when someone there opens the app — no sooner,
whether the OTA was published today or next week.

## Published: 1.0.100 / vc101

`data/apk/shopmanager-1.0.100.apk` on the edge, sha256
`8b741f71a5388802aeaadadb1871ce5a53e7b1a237c8efbf230edc5673a0f426`, manifest written to
`data/apk/version.json` (previous manifest backed up alongside, per the existing convention).
Both `/api/app/version.json` and `/api/app/download/...` return 401 unauthenticated, i.e. wired.

Validated end to end on the S21 rather than assumed: the prompt appeared with the right release
notes, the download ran, the sha check passed, and vc101 installed with the signer unchanged.

### ⚠️ Play Protect gates the install, and hides the way through

The step that will strand a non-technical user. After "Update", Google Play Protect interrupts
with **"App scan recommended — Play Protect hasn't seen this app before"**, offering only:

```
[ Scan app ]            <- uploads the APK to Google
[ Don't install app ]
```

**"Install without scanning" is hidden behind the "More details" chevron** and only appears once
that is expanded. Neither visible button installs the app. Tell whoever holds the phone:

1. Open ShopManager.
2. **DOWNLOAD UPDATE** on the "Faster, more reliable ringing" prompt.
3. **Update** on the system installer.
4. On the Play Protect screen: **More details → Install without scanning**.

Step 4 is the one to spell out. "Scan app" sends the app binary to Google, which is contrary to
the deliberate no-Google-dependency stance elsewhere in this system; it is also slower and may
still end in a warning.

## Mitigation shipped for handsets still on the old pump

`AriClient` now derives `qualify_frequency` per AOR (27–33 s, mean unchanged at 30 s) instead of
giving every AOR 30 s. The AORs on one handset were created together and stayed in lockstep, so
Asterisk fired all of a phone's OPTIONS at the same instant — the worst case for a one-message-
per-tick app. Deployed; the startup provisioning pass re-pushed every AOR (verified: mgr2 33 s,
mgr3 27 s, shop1 31 s, shop2 32 s, shop4 30 s, shop5 27 s).

Effect on the S26, still on the 500 ms pump: worst-case qualify RTT fell from 2170 ms to 913 ms
immediately, and the AORs continue to decorrelate as they drift. This addresses the
serialisation only — **not** the Doze pump stall, which still needs the app update.
