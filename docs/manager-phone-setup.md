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
A70 / S21 / S25 / S25 Edge / S26); other brands have equivalents under Battery / Apps /
VPN.

---

## Checklist (per phone)

1. **Battery → Unrestricted**
   Settings → Apps → **ShopManager** → Battery → **Unrestricted**.
   (This is the single most important one — it exempts the app from Doze.)

2. **Never sleeping app**
   Settings → Battery → **Background usage limits** →
   - **Never sleeping apps** → add **ShopManager**
   - make sure it is **not** in *Sleeping apps* or *Deep sleeping apps*.

   On One UI 9 the app is absent from this picker because step 1 already exempts it; see
   [Setting it up over adb](#setting-it-up-over-adb).

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

## Setting it up over adb

A phone on a USB cable takes the whole checklist in one pass, and sets the app-ops behind it
directly rather than through the menus. `pm grant` fails on a permission the manifest does not
declare, so each command below applies to this app only.

```bash
ADB=~/AppData/Local/Android/Sdk/platform-tools/adb.exe   # Windows path
S=<serial>                                               # from: adb devices -l
P=com.kkstech.shopmanager

# Runtime permissions. Granting them here means the Linphone Core sees them at its first
# start; granted through the dialog instead, they reach the Core only after the app is
# reopened.
$ADB -s $S shell pm grant $P android.permission.POST_NOTIFICATIONS
$ADB -s $S shell pm grant $P android.permission.BLUETOOTH_CONNECT
$ADB -s $S shell pm grant $P android.permission.RECORD_AUDIO

# Battery: the exemption behind checklist steps 1 and 2.
$ADB -s $S shell cmd deviceidle whitelist +$P
$ADB -s $S shell cmd appops set $P RUN_ANY_IN_BACKGROUND allow
$ADB -s $S shell cmd appops set $P RUN_IN_BACKGROUND allow

# Call screen over the lock screen, and the incoming-call heads-up.
$ADB -s $S shell cmd appops set $P SYSTEM_ALERT_WINDOW allow
$ADB -s $S shell cmd appops set $P USE_FULL_SCREEN_INTENT allow

# Checklist step 3, "Pause app activity if unused".
$ADB -s $S shell cmd appops set $P AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore

# OTA updates. See below.
$ADB -s $S shell cmd appops set $P REQUEST_INSTALL_PACKAGES allow

# Checklist step 4. Confirm this one in Settings; see the limits below.
$ADB -s $S shell settings put secure always_on_vpn_app $P
$ADB -s $S shell settings put secure always_on_vpn_lockdown 0
```

Join the tailnet by firing the invite's deep link, which the **Add a phone** page mints:

```bash
$ADB -s $S shell am start -a android.intent.action.VIEW -d "'shopmanager://join?server=…&key=…&api=…'" $P
```

The phone then asks for VPN consent, which someone taps. `JoinActivity` stores the invite's
`api=` value as the app's base URL, so the tailnet IP arrives with the link and is not typed in.

### REQUEST_INSTALL_PACKAGES

`ApkInstaller` calls `canRequestPackageInstalls()` and, when it is false, sends the manager to
the system's "Install unknown apps" screen. The permission is declared in the manifest but its
app-op starts unset, which the check treats as denied, so an OTA update costs a detour through
Settings on every release until the op is set to `allow`.

### Verifying the battery exemption

```bash
$ADB -s $S shell am get-standby-bucket $P      # 5 = EXEMPTED
$ADB -s $S shell dumpsys deviceidle whitelist | grep $P
```

Bucket 5 is `STANDBY_BUCKET_EXEMPTED`, the top of the scale (5 exempted, 10 active, 20 working
set, 30 frequent, 40 rare, 45 restricted). An app in it is outside App Standby. That is the
state checklist steps 1 and 2 produce, so bucket 5 plus a whitelist entry is the proof the
phone is set up, whatever the settings screens look like.

### One UI 9 (Android 17)

Checklist step 2 describes the One UI 6/7 menus. On One UI 9 — Galaxy S25 Edge and later,
`getprop ro.build.version.oneui` reads `90000` — **ShopManager does not appear in the
"Never sleeping apps" picker**. The picker offers apps that are candidates for exemption, and
an app already exempt from battery optimisation has nothing left to grant. Read the standby
bucket instead of looking for the app in that list.

### What adb does not cover

- **VPN consent** is a tap on the phone, once, when the join link opens.
- **Always-on VPN**: the secure setting is written and reads back, but nothing in `dumpsys`
  confirms the framework arms it on One UI 9. Confirm it in Settings → Connections → More
  connection settings → VPN.
- **Logging in** as the manager.

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
