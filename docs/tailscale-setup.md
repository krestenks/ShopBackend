# Tailscale Setup — stable SIP/HTTP address for the phones (plan Phase 10, VPN half)

Goal: both phones (manager + shop) reach Asterisk SIP and the backend HTTP API
via a stable address that works on WiFi, mobile data, and through future
internet failover — replacing the LAN-only `192.168.0.192`.

Scope note: the original Phase 10 bundled DDNS with Tailscale. With Twilio
removed and FCM deferred, nothing external needs to reach the box anymore — so
DDNS stays deferred. Tailscale alone covers the phones.

How the pieces connect today (what makes this switch cheap):

- Backend runs ON the T510; apps fetch their SIP server address from the
  backend, which serves `ASTERISK_SIP_HOST` (see `MobileApi.kt`, sip-config
  endpoint). Switching the env var propagates to every app at next
  credential fetch (log out/in).
- The PJSIP transport is static in `/etc/asterisk/pjsip.conf`; the backend only
  pushes endpoints via ARI. If the transport binds `0.0.0.0`, no Asterisk
  change is needed at all.

## Actual values (setup completed 2026-07-16)

| What | Value |
|------|-------|
| Server MagicDNS name | `kresten-thinkpad-t510.tailf92028.ts.net` |
| Server Tailscale IP | `100.91.227.56` |
| Manager test phone | `galaxy-a70-manager` (100.99.52.5) |
| Shop test phone | `eriks-s21-ejby` (100.90.227.95) |
| Env file | `/home/phone/shopbackend/backend.env` (`ASTERISK_SIP_HOST` on line 7) |
| CLI without sudo | `tailscale up` was run with `--operator=phone` |

Pre-existing on this box: Tailscale **Funnel** publicly serves `/gmail-pubsub`
→ `127.0.0.1:8788` (from an older project). Not part of the phone system —
`tailscale funnel off` if that service is retired.

## 1. Create the tailnet

- Sign up at tailscale.com (Google login is easiest). Free "Personal" plan:
  3 users / 100 devices — plenty.
- Admin console → Settings → DNS → enable **MagicDNS**, so devices get names
  like `phone-server.your-tailnet.ts.net` instead of raw `100.x` IPs.

## 2. Install on the T510 phone server

```bash
ssh phone@192.168.0.192
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
# open the printed auth URL in a browser, log in
tailscale ip -4          # note the 100.x.y.z IP
tailscale status         # note the MagicDNS hostname
sudo systemctl enable tailscaled
```

### Prevent node-key expiry (or SIP dies silently in ~6 months)

Pick ONE:

- **Per-machine toggle**: web admin console (login.tailscale.com/admin/machines)
  → three-dot menu on the phone-server row → "Disable key expiry". Only
  appears AFTER the machine has joined; it's not in the phone app or CLI.
- **Tag the server** (canonical for servers — tagged devices' keys never
  expire): in the admin console → Access controls, add
  ```json
  "tagOwners": { "tag:phone-server": ["autogroup:admin"] }
  ```
  then on the box: `sudo tailscale up --advertise-tags=tag:phone-server`
- **Fallback** if neither works: accept the default 180-day expiry and
  re-auth with `sudo tailscale up` when the admin console / email warns the
  key is expiring. Put a reminder somewhere that outlives the memory of this
  doc.

The phones don't need this — re-auth there is an interactive tap in the app.

## 3. Verify Asterisk listens on the Tailscale interface

Check the transport bind in `/etc/asterisk/pjsip.conf`. If it is
`bind=0.0.0.0:5060` (likely), nothing to change — the `tailscale0` interface
is covered automatically. Tailscale is a flat network with no NAT between
peers, so no `external_media_address` / `external_signaling_address` is
needed. RTP (`rtp.conf`, default 10000–20000/udp) also just works.

Quick check:

```bash
sudo ss -lunp | grep 5060    # SIP — expect 0.0.0.0 or the Tailscale IP
sudo ss -ltnp | grep 8080    # backend HTTP — same
```

Keep `bindaddr = 127.0.0.1` for AMI (`manager.conf`) and ARI (`http.conf`)
as-is — those are backend-local and must NOT be exposed on the tailnet.

## 4. Install Tailscale on both test phones

- Play Store → Tailscale, on the manager phone and the shop phone; log in to
  the same tailnet (same Google account is fine for testing).
- On each phone:
  - **Always-on VPN**: Android Settings → Network → VPN → Tailscale gear icon
    → Always-on VPN. Without this, Android kills the tunnel in Doze and SIP
    registration drops overnight.
  - Exclude Tailscale from **battery optimization**.
- Verify from each phone: open
  `http://phone-server.your-tailnet.ts.net:8080` in the phone browser and see
  the backend respond.

## 5. Switch the backend to the Tailscale hostname

In `/etc/systemd/system/shopbackend.service`:

```ini
Environment=ASTERISK_SIP_HOST=phone-server.your-tailnet.ts.net
```

```bash
sudo systemctl daemon-reload && sudo systemctl restart shopbackend
```

Apps pick up the new SIP host next time they fetch SIP credentials
(log out/in triggers it).

Use the MagicDNS hostname rather than the `100.x` IP — it survives the rare
case of Tailscale reassigning the IP, and it's self-documenting.

## 6. Point the apps' backend URL at the tailnet

The apps also talk HTTP to the backend (base URL in the ShopManager app,
AndroidStudioProjects repo). Two app-side edits (done 2026-07-16, v1.0.38):

- `RetrofitClient.kt` → `BASE_URL = "http://kresten-thinkpad-t510.tailf92028.ts.net:8080/"`
- `res/xml/network_security_config.xml` → add the ts.net domain to the
  cleartext whitelist (plain HTTP is fine here — Tailscale encrypts the
  tunnel — but Android blocks cleartext to non-whitelisted domains).

Everything SIP flows from step 5 automatically.

CAVEAT: MagicDNS names only resolve while the Tailscale VPN is up on the
phone. Once the base URL is switched, the app requires Tailscale even on home
WiFi. That is the intended end state (one address that works everywhere), and
always-on VPN makes it a non-issue — but do NOT switch the app URL until
step 4 is verified on both phones.

## 7. Test matrix

1. Both phones on WiFi, Tailscale on: log out/in → "Shop phone online"
   notification, SIP registered via the new host.
2. Inbound GSM call from an unknown number → in-app call screen → answer →
   two-way audio. (Tailscale peers on the same LAN take a direct local path,
   so audio latency should be unchanged.)
3. Internal intercom: shop phone → manager, and manager → shop.
4. **The real test:** take one phone off WiFi onto mobile data → SIP stays /
   re-registers; repeat the inbound call and intercom tests. Audio now
   traverses the tunnel over the internet — listen for quality.
5. Toggle a phone between WiFi and mobile data mid-registration → confirm it
   recovers (Tailscale keeps the same 100.x address across network switches —
   exactly why we're doing this).
6. Leave both phones idle overnight → SIP still registered in the morning
   (validates the battery-optimization exclusion).

## Known gotchas

- A phone with **private DNS** (DNS-over-TLS) set to a fixed provider can
  fight MagicDNS — set private DNS to off/automatic if names don't resolve.
- CGNAT (some carriers/routers) can force Tailscale into DERP **relay** mode
  → relayed audio = higher latency. `tailscale status` on the server shows
  `direct` vs `relay` per peer — check during the mobile-data test.
- Default tailnet ACLs allow all devices to talk to each other — fine for a
  personal test tailnet; tighten later if non-phone devices join.

## Deferred (still)

- **DDNS / public hostname** — only needed if something external must reach
  the box again (e.g. FCM callbacks are NOT external-inbound, so even FCM
  won't require it).
- **LTE internet failover** (plan Phase 9) — Tailscale survives the WAN swap
  transparently once that lands; no re-work expected here.
