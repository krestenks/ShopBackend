# Phone Server Setup — one-time steps before first provisioning

Target: the ThinkPad T510 phone server (`ssh phone@192.168.0.192`), Asterisk 18 +
RoEdAl chan_quectel, already validated end-to-end for voice+SMS (POC 2026-07-14).
These steps wire that box up so the **backend** can drive it (Phase 2–4 code).

## 1. AMI user — `/etc/asterisk/manager.conf`

```ini
[general]
enabled = yes
port = 5038
bindaddr = 127.0.0.1

[kotlinbackend]
secret = <ASTERISK_AMI_SECRET>
read = all
write = all
```

Reload: `sudo asterisk -rx "manager reload"`
Verify: `sudo asterisk -rx "manager show users"` → shows `kotlinbackend`.

## 2. HTTP + ARI — `/etc/asterisk/http.conf` and `/etc/asterisk/ari.conf`

```ini
; http.conf
[general]
enabled = yes
bindaddr = 127.0.0.1
bindport = 8088
```

```ini
; ari.conf
[general]
enabled = yes

[kotlinbackend]
type = user
read_only = no
password = <ASTERISK_ARI_PASSWORD>
```

Reload: `sudo asterisk -rx "module reload res_http_websocket.so"` then
`sudo asterisk -rx "ari show status"` (or just restart Asterisk once at the end).

## 3. Sorcery mapping for ARI push config — `/etc/asterisk/sorcery.conf`

The backend creates PJSIP endpoints dynamically (no file edits). That requires
res_pjsip objects to be mapped to a writable wizard (astdb):

```ini
[res_pjsip]
endpoint = astdb,pjsip/endpoint
auth = astdb,pjsip/auth
aor = astdb,pjsip/aor
```

Restart Asterisk after adding this (sorcery maps are read at module load).
NOTE: existing static `[linphone]` test endpoint in pjsip.conf keeps working —
config file and astdb objects coexist.

## 4. Include the backend-generated files

Append to `/etc/asterisk/quectel.conf`:
```ini
#include quectel_shops.conf
```
(Keep the existing `[general]` section; the backend only owns `[shopN]` trunks.
Remove/comment the old hand-made `[quectel0]` section once shop 1 is provisioned,
or it will hold the modem's tty and block the `shop1` trunk.)

Append to `/etc/asterisk/extensions.conf`:
```ini
#include extensions_shops.conf
```

Create empty placeholders so reloads don't warn before first provisioning:
```bash
sudo touch /etc/asterisk/quectel_shops.conf /etc/asterisk/extensions_shops.conf
sudo chown <backend-user>:asterisk /etc/asterisk/quectel_shops.conf /etc/asterisk/extensions_shops.conf
```

## 5. Dialplan helpers present?

```bash
sudo asterisk -rx "module show like func_curl"     # CURL()
sudo asterisk -rx "module show like res_json"      # JSON_DECODE() — already used by the SMS auto-responder POC
```
If func_curl is missing: `sudo apt install asterisk-modules` (it ships there) and
`sudo asterisk -rx "module load func_curl.so"`.

## 5b. TTS for the DTMF menu prompts

The backend renders the welcome/menu prompts as WAV files (pico2wave, validated
in the POC; sox downsamples to 8 kHz so Asterisk plays them without transcoding):

```bash
sudo apt install libttspico-utils sox
sudo mkdir -p /usr/share/asterisk/sounds/shopbackend
sudo chown <backend-user>:asterisk /usr/share/asterisk/sounds/shopbackend
```

Prompts are (re)generated on every provisioning run and whenever a shop's
voice-config texts are saved in the web admin. Language: `ASTERISK_TTS_LANG`
(default en-GB — pico has no Danish voice; the default messages are English).

## 6. Backend service on the box

The backend must run ON this machine (it writes /etc/asterisk files and is CURLed
from the dialplan on localhost). The service user needs:

- write access to `/etc/asterisk` (see chown above; or a shared group)
- membership of `dialout` (modem scanner AT probing): `sudo usermod -aG dialout <backend-user>`

Example `/etc/systemd/system/shopbackend.service`:
```ini
[Unit]
Description=ShopBackend (self-hosted telephony mode)
After=network-online.target asterisk.service

[Service]
User=phone
Environment=PORT=8080
Environment=ASTERISK_AMI_SECRET=...
Environment=ASTERISK_ARI_PASSWORD=...
Environment=ASTERISK_INTERNAL_SECRET=...
Environment=ASTERISK_SIP_HOST=192.168.0.192   ; LAN for now; Tailscale IP later (Phase 10)
# plus the usual ADMIN_* vars as needed
ExecStart=/usr/bin/java -jar /opt/shopbackend/ShopBackend.jar --db /opt/shopbackend/data/ShopManager.db
Restart=always

[Install]
WantedBy=multi-user.target
```

## 7. Stable modem naming (per shop)

ttyUSB numbers move on replug — pin the AT port (USB interface if02) per modem
by IMEI-agnostic USB path or serial. Existing rule `98-quectel-atport.rules`
pins `/dev/ttyQuectelAT` to `KERNELS=="1-1.2.1:1.2"`. For multi-shop, one
symlink per modem, e.g. `/etc/udev/rules.d/98-quectel-shops.rules`:

```
SUBSYSTEM=="tty", KERNELS=="1-1.2.1:1.2", ATTRS{idVendor}=="2c7c", SYMLINK+="ttyQuectelShop1"
SUBSYSTEM=="tty", KERNELS=="1-1.2.2:1.2", ATTRS{idVendor}=="2c7c", SYMLINK+="ttyQuectelShop2"
```
`sudo udevadm control --reload && sudo udevadm trigger`

ALSA: with two modems both enumerate as card `EC25EUX` — give each a distinct
card id via udev (`ATTR{id}="EC25Shop1"`) or use `hw:CARD=EC25EUX,DEV=0` style
addressing per USB path. Configure the resulting name in the shop's
"ALSA audio device" field.

## 8. First provisioning + smoke test

1. Start the backend, log into the web admin → Shops → Edit shop → GSM Telephony.
2. "Scan for modems" → assign the udev symlink → fill SIM number → **Save & Provision**.
   (Or: `curl -X POST localhost:8080/api/internal/telephony/provision -d "secret=...&shopId=1"`)
3. Verify: `sudo asterisk -rx "quectel show devices"` → `shop1` CONNECTED/Free;
   `sudo asterisk -rx "pjsip show endpoints"` → `shop1-manager`.
4. Manager app: log out/in (starts SipService) → notification "Shop phone online (1)".
5. Test SMS button on the shop edit page → arrives on a real phone.
6. Call the SIM from an UNKNOWN phone → app shows the in-app incoming call screen
   (pure ringing for the caller) → answer → two-way audio. Backend call log gets
   the row (INCOMING_CALL → BRIDGED → ended).
7. Whitelist that customer in the app (status ≠ New), call again → welcome message
   plays → press 1 → booking-link SMS arrives; call again → press 2 → app rings.
8. Set phone status to closed → known customer still gets the SMS-link menu;
   unknown numbers are rejected silently.
9. In the app, open a message thread → 📞 → outbound call goes out via the SIM.

## Deferred (known, deliberate)

- **FCM push wake** (plan Phase 5.3): needs a Firebase project + google-services.json.
  Until then the app keeps SIP registered via a persistent foreground service —
  acceptable for dedicated manager phones. TODO hook is in
  `InternalTelephonyRoutes.kt` (backend) once FCM exists.
- **Phase 9 (LTE failover)**: infra on the box.
- **Phase 10 (Tailscale)**: setup plan in `docs/tailscale-setup.md`;
  `ASTERISK_SIP_HOST` switches to the Tailscale hostname when done.
  DDNS half stays deferred (nothing external needs to reach the box).
- ~~Inbound call-flow extras~~ **DONE**: the full phone flow now runs in the
  dialplan — blacklist/closed rejection, known-customer welcome + DTMF menu
  (1 = SMS booking link, 2 = operator when open), unknown callers ring the app
  directly, TTS prompts generated from the per-shop voice-config texts.
