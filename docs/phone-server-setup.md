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

## 7a. Hub port map — which physical port holds which shop

All modems hang off one 16-port USB hub. The hub presents an upstream hub and
four downstream 4-port chips, and **both numberings run opposite to the labels
on the case**: chip 4 carries ports 1-4, chip 3 ports 5-8, chip 2 ports 9-12,
chip 1 ports 13-16, and within a chip `.4` is the lowest-numbered physical port
and `.1` the highest.

A USB path `1-1.2.C.P` is therefore the labelled port:

```
physical port = (4 - C) * 4 + (5 - P)
```

| Port | USB path  | ALSA card   | Shop        |
|------|-----------|-------------|-------------|
| 1    | 1.2.4.4   | EC25EUX     | shop2 — JJ   |
| 2    | 1.2.4.3   | EC25EUX_1   | shop1 — Aim  |
| 3    | 1.2.4.2   | EC25EUX_2   | shop5 — Gina |
| 4    | 1.2.4.1   | EC25EUX_3   | shop6 — Koi  |
| 5    | 1.2.3.4   | EC25EUX_5   | spare        |
| 6    | 1.2.3.3   | EC25EUX_4   | shop7 — Nan  |
| 7    | 1.2.3.2   | EC25EUX_9   | spare        |
| 8    | 1.2.3.1   | EC25EUX_7   | spare        |
| 9    | 1.2.2.4   | EC25EUX_A   | spare        |
| 10   | 1.2.2.3   | EC25EUX_6   | spare        |
| 11   | 1.2.2.2   | EC25EUX_8   | spare        |
| 12   | 1.2.2.1   | —           | empty        |
| 13-16| 1.2.1.x   | —           | empty        |

Ports 1-11 are populated; chip 1 (ports 13-16) is unused. ALSA card ids follow
USB *registration* order, not socket order, so the card column is only valid
until the next re-enumeration — derive it, do not trust it:

```bash
for p in /dev/snd/by-path/*usb*; do
  c=$(readlink -f "$p" | grep -oE '[0-9]+$')
  echo "$(basename "$p" | sed -E 's/.*usb-0:([0-9.]+):.*/\1/')  card$c  $(cat /proc/asound/card$c/id)"
done | sort
```

**The direction within chips 2 and 3 is verified** by the switch-sequence
mapping run (powering ports on one at a time and watching which path appeared).
Chip 4's direction is inferred from the hub being four identical chips, and is
consistent with ports 1-11 being contiguously populated.

**Confirm by IMEI before pulling a SIM or a cable.** The port number is a
convenience; the modem's identity is not. `quectel show device state shopN`
prints the IMEI, and neighbouring ports are other live shops:

```bash
sudo asterisk -rx "quectel show devices" | awk '/^shop/{print $1, $(NF-1), $NF}'   # trunk, IMEI, IMSI
```

A SIM swap needs more than the slot: see §7c.

## 7b. Modem radio config — the three settings every EC25 must have

**All three are required, on every modem, and two of ours ran for months without
them.** A half-configured EC25 works *most* of the time — it registers, it carries
calls, it sends most SMS — so the gap shows up as an intermittent reliability
problem months later, not as a failure at install time. Configure every new modem
the same way and there is nothing to correlate later.

Apply the three below, then **one** `AT+CFUN=1,1` (or `quectel reset shopN`) and
verify. Do it while the trunk is idle: `quectel show device state shopN` → `Free`,
and no `Quectel/shopN-` channel in `core show channels`. Activating a carrier
profile can trigger a second self-reboot a few seconds later, so allow ~2 min.

### 1. USB audio (UAC) — without it there is no voice path at all

A fresh EC25 ships with the audio interface off, so `chan_quectel` has no
`alsadev` to open.

```
AT+QCFG="usbcfg",0x2C7C,0x0125,1,1,1,1,1,0,1
```

(The trailing `1` is the UAC flag. The backend surfaces this same hint when it
detects a modem with no audio device.) After the reboot the modem must appear in
`/proc/asound/cards` as `EC25EUX_<n>`, and `quectel show device settings shopN`
must show `Audio UAC : hw:CARD=EC25EUX_<n>`. **If that card name changes or
disappears, stop and roll back** — the trunk is mute even though it looks healthy.

### 2. Carrier profile (MBN) — without it SMS submits intermittently time out

```
AT+QMBNCFG="AutoSel",1
```

This makes the modem auto-select the operator profile at boot. On Telenor/Lebara
DK it lands on `ROW_Generic_3GPP`, which is what defines the **`ims` and `SOS` PDP
contexts** — a modem without it has only the bare internet context.

**Evidence (2026-09-13 → 16).** shop5 and shop6 ran at `AutoSel,0` for months.
~4.5% of their SMS submits timed out (the 40s `+CMGS` wait) — and *only* theirs;
shop1 and shop2, which had the profile, never did. shop5 was given the profile on
2026-09-14 and then sent **67 messages with 0 failures** (p ≈ 3.2% that a modem
with the old failure rate does that by chance). shop6 was brought into line on
2026-09-16. Treat this as the required setting it is.

Verify:

```
AT+QMBNCFG="AutoSel"     → +QMBNCFG: "AutoSel",1
AT+QMBNCFG="List"        → one row with selected=1,activated=1, "ROW_Generic_3GPP"
AT+CGDCONT?              → three contexts: "", "ims", "SOS"
AT+CGACT?                → +CGACT: 2,1   (the ims bearer is up)
```

### 3. VoLTE / IMS — set it, but know it is inert on its own

```
AT+QCFG="ims",1
```

**`ims,1` does nothing until a carrier profile is active.** The readback is
`<config>,<state>`: with no MBN you get `"ims",1,0` — configured, never
registered. Only once the profile brings up the `ims` context does it become
`"ims",1,1`. This is exactly why "we enabled VoLTE on that modem" was both true
and meaningless on shop5/shop6, and why it is set here for **parity and for the
2G sunset**, not because it fixes anything today.

**If the state digit stays `0` after ~10 minutes, the modem never requested the IMS PDN at attach**
(it attached before the carrier profile applied -- a boot race, seen on shop6 2026-09-16). Bring the
bearer up by hand on the free tty and the IMS stack registers over it on its own retry, typically
within ~10 min:

```
AT+CGACT=1,2          -> OK, then AT+CGACT? shows +CGACT: 2,1
AT+QCFG="ims"         -> "ims",1,1 a few minutes later
```

This is a write, but a small one: it activates a data context and does not touch the voice path.
**It was applied by hand, so re-check `ims` after any reboot of that modem** -- the boot race can
recur, and the remedy is this one command.

Voice currently runs over **CSFB**, which works with or without VoLTE: Telenor DK
accepts a combined EPS/IMSI attach, so `AT+CREG?` returns `1` with AcT `7`. When
Danish operators switch 2G off, CSFB disappears and IMS registration (`1,1`)
becomes the only voice path — another reason every modem should already be at
`1,1` rather than discovered to be at `1,0` on the day it matters.

> **Correction, and the risk it creates.** An earlier version of this section said
> to leave VoLTE off, because the two `ims,1` modems averaged ~1.1 false CLCC reaps
> per call while the two at default averaged ~0 across 89 calls. That correlation is
> real and was measured per call, so it is **not** a traffic-volume artifact — but the
> causal variable is IMS *actually registered* (`1,1`), not the config flag. shop5/shop6
> read `ims,1,0` or `0,0`, so their calls went over CSFB, where the EC25 reports `+CLCC`
> consistently. shop1/shop2 were at `1,1` and made real IMS calls, which the modem lists
> unreliably in the polled `AT+CLCC`.
>
> **So bringing all four modems to `1,1` moves the whole fleet into the regime where CLCC
> is flaky.** That is only safe because the reaper hardening is deployed — edge commits
> `4c861cf` (thresholds 6, and 24 ≈ 2 min while Asterisk still holds a channel) and
> `f71b1f9` (never treat a listed-but-unparsed index as absent). **Keep those deployed; a
> chan_quectel rebuild that loses them will start dropping calls on every shop, not two.**
> **Status 2026-09-16: all four modems read `1,1`.** shop6 needed the manual `AT+CGACT=1,2`
> above; the other three registered at boot. So the whole fleet is now in the flaky-CLCC regime
> and this hardening is load-bearing for every shop. After any modem gains `1,1`, check for a week:
> `grep "CLCC reap" /var/log/asterisk/dropcatch | grep channel:yes` must stay empty.
>
> The mid-call drops themselves were the reaper, not VoLTE. Do not disable VoLTE on the
> old reasoning.

### Expect these `+CLCC` lines; do not "clean them up"

Extra entries like `+CLCC: 1,1,0,1,0,"",128` are the modem's **PDP data bearers**,
mapping 1:1 onto `AT+CGACT?` — context 1 is the default internet bearer (the LTE
attach itself), context 2 is `ims`. Deactivating context 1 detaches the modem from
LTE entirely. Nothing is stuck.

### Checking a modem without disturbing calls

`chan_quectel` holds only the `:1.2` USB interface; `:1.0`, `:1.1` and `:1.3` are
free, so you can query a live modem with zero risk to calls in progress:

```bash
ls -l /dev/serial/by-path/ | grep 1.2.2     # find the modem's ttyUSBn set
sudo stty -F /dev/ttyUSB12 115200 raw -echo
sudo bash -c "exec 3<>/dev/ttyUSB12; printf 'AT+QCFG=\"ims\"\r' >&3; timeout 2 cat <&3"
```

Send **queries only** on that port — a hangup command there would drop a real
call. Note `asterisk -rx "quectel cmd ..."` is useless for this: `chan_quectel`
discards any reply it cannot parse, so you see `↳ [OK]` and no data.

### Fleet audit — is every modem configured the same?

A one-liner worth running after any modem swap. For each shop, on its free tty:

```
AT+QCFG="ims"   → "ims",1,1
AT+QMBNCFG="AutoSel"  → "AutoSel",1
AT+CGDCONT?     → "", "ims", "SOS"
```

Anything else means that shop is the odd one out, and it will be the one with the
unexplained SMS failures. As of 2026-09-16 all four are identical: `AutoSel,1`, `ROW_Generic_3GPP`
active, `""`/`ims`/`SOS` contexts, `ims,1,1` and `CGACT: 2,1`. The working idle-gated change script is `/home/phone/mbn-shop6.sh` on the
edge — copy it and change `SOCK`/`DEV`/`CARD` rather than typing AT commands by
hand, because it aborts *before* the reboot if any readback is wrong.

**Rollback**, if a modem misbehaves after the change:

```
AT+QMBNCFG="AutoSel",0 ; AT+QMBNCFG="Deactivate" ; AT+QCFG="ims",0 ; AT+CFUN=1,1
```

## 7c. Swapping a SIM

The shop is bound to its SIM **by IMSI** (`AsteriskProvisioner.assignShopToImsi`
— "the IMSI is the stable key"), so a new SIM needs a new binding. In order:

1. **Swap the card.** `QSIMDET` is `0,0` on these modems — hot-swap detection is
   off, so a SIM put into a powered modem stays invisible.
2. **Reset the modem** so it reads the new card: `AT+CFUN=1,1` on the **free**
   `:1.3` tty (chan_quectel owns `:1.2`). Do it with the trunk idle; USB
   re-enumerates. Until this runs, `quectel show device state` keeps reporting
   the **old** IMSI and ICCID — chan_quectel caches them.
3. **Unassign the shop first.** The assign UI only offers shops whose IMSI is
   blank, so a shop still holding the stale IMSI never appears in the dropdown:
   web admin → Telephony setup → unassign, then assign the new IMSI. Assigning
   provisions, which rewrites `quectel_shops.conf` and reloads chan_quectel.
4. **Re-enter the number.** `assignShopToImsi` clears `phoneNumber` whenever the
   IMSI changes, because a different SIM means a different number. Set it under
   the shop's telephony page — and set the **carrier** in the same form, which
   overwrites it from its own field: submitting without it sets `carrier` to
   NULL and the Lebara balance monitoring for that shop silently stops.
5. **Re-check `ims` once the SIM is funded.** A fresh or unfunded SIM comes up
   `ims,1,0` — enabled but inert — and `AT+CGACT=1,2` does not always clear it
   immediately. See §7b.3; an inert IMS is what caused months of intermittent
   SMS submit failures.

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
