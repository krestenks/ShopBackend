# Asterisk Integration (Phase 2 — backend module)

The backend can run against Twilio (default) or the self-hosted Asterisk/Quectel GSM
stack. The provider is chosen at startup:

```
ASTERISK_ENABLED=true          # switch provider to Asterisk
ASTERISK_AMI_HOST=127.0.0.1    # defaults shown
ASTERISK_AMI_PORT=5038
ASTERISK_AMI_USERNAME=kotlinbackend
ASTERISK_AMI_SECRET=...        # required
ASTERISK_ARI_BASE_URL=http://127.0.0.1:8088
ASTERISK_ARI_USERNAME=kotlinbackend
ASTERISK_ARI_PASSWORD=...      # required
ASTERISK_CONFIG_PATH=/etc/asterisk
ASTERISK_BACKEND_BASE_URL=http://127.0.0.1:8080
ASTERISK_INTERNAL_SECRET=...   # required — auths the dialplan→backend callbacks
```

## What the backend does when enabled

- **AMI** (`asterisk/AmiClient.kt`): persistent connection for events, outbound SMS
  (`QuectelSendSMS` action with CLI fallback `quectel sms send ...`), call originate,
  and module/dialplan reloads. Retries login every 10 s until Asterisk is up.
- **ARI push config** (`asterisk/AriClient.kt`): creates/updates each shop's PJSIP
  endpoint/auth/aor (`shop{id}-manager`) dynamically — no file edit, no reload.
- **Generated config files** (`asterisk/AsteriskConfigWriters.kt`): the backend owns
  two files, regenerated wholesale from `shop_telephony_config` and written atomically:
  - `quectel_shops.conf` — one `[shop{id}]` trunk per shop (`data=`, `uac=on`, `alsadev=`)
  - `extensions_shops.conf` — per shop `[from-gsm-shop{id}]` (inbound call → notify
    backend → `Dial(PJSIP/shop{id}-manager)`; inbound SMS → CURL to backend) and
    `[from-sip-shop{id}]` (app outbound via `Dial(Quectel/shop{id}/${EXTEN})`)
- **Internal endpoints** (`asterisk/InternalTelephonyRoutes.kt`), secret-authenticated:
  - `POST /api/internal/telephony/sms/inbound` — dialplan hands over inbound SMS
  - `POST /api/internal/telephony/call/inbound` — creates the call log, answers
    `reject` for blacklisted callers (dialplan hook), `ok` otherwise
  - `POST /api/internal/telephony/provision` — trigger provisioning via curl until the
    admin UI gets a button, e.g.
    `curl -X POST localhost:8080/api/internal/telephony/provision -d "secret=...&shopId=3"`
- **Event handler** (`asterisk/AsteriskEventHandler.kt`): AMI DialEnd/Hangup events
  close call-log rows (keyed by Asterisk UNIQUEID stored in `voice_call.twilio_call_sid`).

## Per-shop data (`shop_telephony_config` table)

| column | meaning |
|---|---|
| `phone_number` | E.164 of the SIM in the shop's modem |
| `modem_data_device` | stable udev symlink to the AT port, e.g. `/dev/ttyQuectelShop1` |
| `modem_alsa_device` | UAC audio device, e.g. `hw:EC25EUX` |
| `sip_password` | generated on first provisioning; app fetches it later via JWT API |
| `provisioned_at` | last successful provisioning |

## One-time server prerequisites (phone server)

1. `/etc/asterisk/quectel.conf` → add `#include quectel_shops.conf`
2. `/etc/asterisk/extensions.conf` → add `#include extensions_shops.conf`
3. `manager.conf` — AMI user `kotlinbackend` (read/write all) matching the env vars
4. `ari.conf` + `http.conf` — enable HTTP + ARI user matching the env vars
5. `sorcery.conf` — ARI *push config* needs writable sorcery mappings:
   ```ini
   [res_pjsip]
   endpoint=astdb,pjsip/endpoint
   auth=astdb,pjsip/auth
   aor=astdb,pjsip/aor
   ```
6. `func_curl.so` and JSON_DECODE (`res_json`/`func_json`, present in the RoEdAl setup)
   must be loaded — the generated dialplan uses `CURL()` and `JSON_DECODE()`
7. The backend service user needs write access to `$ASTERISK_CONFIG_PATH`
8. With two modems, give each a distinct ALSA card id (udev) so `alsadev=` is unambiguous

## Still to verify on the real box

- The `QuectelSendSMS` AMI action name/parameters in the RoEdAl fork build
  (the CLI fallback `quectel sms send <dev> <num> "msg"` is validated already);
  multi-line SMS behaviour through either path.
- ARI dynamic endpoint creation end-to-end (sorcery mapping above).
