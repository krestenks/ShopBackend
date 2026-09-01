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
