# Manager phone setup — reliable calls without push

**Why this exists.** The system has **no FCM/push-wake** (deliberate — no Google-account
dependency). An incoming call is a SIP `INVITE` the server delivers to the phone's
current registration, so **the ShopManager app must be alive and registered to ring.**
If Android/Samsung kills the app or lets the phone sleep the tunnel, calls to that
manager land on `NO ANSWER` even though everything server-side is correct.

Symptom of a mis-configured phone: another manager can call *some* colleagues but not
this one; on the server the endpoint shows `Unavailable`:

```bash
sudo asterisk -rx "pjsip show endpoint mgr<ID>"   # "Not in use" = reachable, "Unavailable" = app not answering
```

Do the steps below **on each manager phone**. Paths are Samsung **One UI** (Galaxy
A70 / S21 / S25 / S26); other brands have equivalents under Battery / Apps / VPN.

---

## Checklist (per phone)

1. **Battery → Unrestricted**
   Settings → Apps → **ShopManager** → Battery → **Unrestricted**.
   (This is the single most important one — it exempts the app from Doze.)

2. **Never sleeping app**
   Settings → Battery → **Background usage limits** →
   - **Never sleeping apps** → add **ShopManager**
   - make sure it is **not** in *Sleeping apps* or *Deep sleeping apps*.

3. **Don't auto-remove the app**
   Settings → Apps → ShopManager → turn **off** "Pause app activity if unused"
   (a.k.a. "Remove permissions and free up space if unused").

4. **Always-on VPN** (keeps the tunnel up so the server can reach the phone)
   Settings → Connections → More connection settings → **VPN** → tap the gear on the
   ShopManager / Tailscale profile → enable **Always-on VPN**.
   Leave **Block connections without VPN _off_** (the tunnel is split/per-app; blocking
   would cut normal traffic).

5. **Private DNS → Off or Automatic**
   Settings → Connections → More connection settings → **Private DNS**.
   A fixed DNS-over-TLS provider fights MagicDNS and can break name resolution.

6. **Keep it logged in and open**
   - Log in as the correct manager and leave the app **logged in**.
   - **Do not swipe it away** from Recents, and don't Force stop it.

7. **Dedicated phones on a charger** (optional, best reliability)
   Developer options → **Stay awake** (screen on while charging). Keeps the app hot on
   a phone that lives on the counter/charger.

---

## Verify it worked

1. Open the app once (foreground) to force a fresh SIP registration.
2. **Lock the phone**, wait ~1 minute (let it try to Doze).
3. From another manager's phone, call this one — it should ring.
4. Server-side confirmation:
   ```bash
   sudo asterisk -rx "pjsip show endpoint mgr<ID>"   # expect: Not in use
   ```
   Repeat after leaving the phone idle overnight — still `Not in use` = settings stuck.

---

## Known limits (accepted trade-offs of the no-push design)

- **After a reboot**, a PIN-locked phone's `BOOT_COMPLETED` only fires after the **first
  unlock**. Unlock the phone once after any reboot to bring the tunnel + registration
  back.
- A **force-stopped or swiped-away** app will not ring until reopened. The battery
  settings above prevent the OS from doing this on its own, but a manual force-stop wins.
- Reachability needs the phone to have **network** (WiFi or mobile data) with the tunnel
  up; in a dead-zone the phone is simply offline.

---

## How it works under the hood (for future debugging)

- SIP registration is held open by a **persistent foreground service**; Doze and Samsung
  task-killing are what sever it — hence the battery/never-sleeping settings.
- The embedded **Tailscale tun auto-reconnects** on app launch and on boot
  (`ShopManagerApp.onCreate`, `BootReceiver`), but a killed process can't answer an
  incoming `INVITE`.
- Asterisk **qualifies** each registration (`OPTIONS` every 30s): this both tracks
  reachability (`Avail`/`Unavail`) and keeps the UDP/WireGuard path warm — but only helps
  while the app is alive to answer.
- Manager identities are `mgr{id}` (pool) and `shop{id}-manager`; the app places
  colleague calls from its shop-manager identity, and the dialplan resolves `mgr{id}`
  via the shared `internal-shop{id}` include (see `AsteriskConfigWriters.kt`).
