# Elitedesk phone server (phone-server01)

Live phone server since 2026-11-16. It replaced the old edge (`phone@192.168.0.192`, Asterisk 18), which is powered off with its services disabled and kept for rollback.

## Machine

| Item | Value |
|---|---|
| Host | `phone-server01`, 192.168.0.158 |
| OS | Ubuntu 26.04.1 LTS, kernel 7.0 |
| Asterisk | 22.5.2 (Ubuntu package). The edge runs 18.10.0. |
| Java | OpenJDK 17, the same major version as the edge |
| Logins | `kresten` and `phone`, key login, passwordless sudo, no password on `phone` |

`phone` has the same groups as on the edge: `dialout`, `audio`, `sudo`, `asterisk`.

## Staged

- Packages: Java 17, Asterisk 22 with `asterisk-modules` and `asterisk-dev`, sox, pico TTS, alsa-utils, build tools, cmake, tcpdump.
- `/etc/asterisk`: `ari`, `extensions`, `extensions_shops`, `http`, `logger`, `manager` (+ `manager.d`), `modules`, `pjsip`, `sorcery`, `quectel` copied from the edge. `quectel_shops.conf` is empty.
- `/usr/share/asterisk/sounds/shopbackend`: prompts copied from the edge.
- `/home/phone/shopbackend`: `ShopBackend.jar` (Oct 16 build), `backend.env` (mode 600), `standby/` scripts. `data/` is empty.
- systemd units installed, all disabled: `shopbackend` (with auto-rollback drop-in), `shopbackend-health` (service and timer), `shopbackend-rollback`, `dropcatch`. `/usr/local/bin/dropcatch.sh` copied.
- `/home/phone/asterisk-chan-quectel`: clone of the edge repo at `8e95f61`. The CLCC reaper hardening is in HEAD as `6c341d6` and `33e4aed`; they have the same patch ids as `4c861cf` and `f71b1f9` named in CLAUDE.md (the hashes changed when history was rewritten). Build in `build22/`, installed to `/usr/lib/x86_64-linux-gnu/asterisk/modules/chan_quectel.so`.
- udev rule that keeps ModemManager off the EC25 modems.
- journald limits and sleep targets masked, as on the edge.

## Verified

- chan_quectel compiles against the Asterisk 22 headers.
- Asterisk 22 started with the copied configuration: `chan_quectel.so`, `func_curl`, `func_json`, AMI user `kotlinbackend`, ARI and HTTP on 127.0.0.1:8088 load. The dialplan loads 79 contexts with no unknown application. `JSON_DECODE`, `CURL`, `URIENCODE` and `QUECTEL_SEND_SMS` exist.
- Asterisk was stopped and disabled afterwards.

## Not verified

- No modem is attached, so no call or SMS has run on Asterisk 22. The reaper and AT-command code has never run against it.
- The sorcery astdb mapping (dynamic PJSIP endpoints) was loaded but no endpoint was created.
- The backend was not started.

## Build detail

CMake does not find `asterisk.pc` on this box and falls back to version 180000. Configure with the real version:

```bash
cmake -DCMAKE_BUILD_TYPE=RelWithDebInfo -DASTERISK_VERSION_NUM=220000 ..
```

`ASTERISK_VERSION_NUM` is only used by the CMake files in this repo, so the module is the same either way.

## Not done (needs a decision or the cutover)

1. **Asterisk version.** Decided 2026-11-09: stay on 22. The first test with a modem attached (call, SMS, CLCC polling) is the open check.
2. **Database.** Copy `shop.db` only with the backend on the edge stopped. The backend has one connection and a live copy can be torn.
3. **Network identity.** `backend.env` still points at the edge: `ASTERISK_SIP_HOST=100.64.0.4`, `APP_BASE_URL=http://100.64.0.4:8080`, `PUBLIC_BASE_URL=http://192.168.0.192:8080`. Tailscale is not installed. The new box needs its own node, or the edge's node moved, and an offsite relay is still a prerequisite for LTE fallback.
4. **Modems.** Hub port map, `/dev/serial/by-path` names in `quectel_shops.conf`, ALSA card ids (`docs/phone-server-setup.md` §7, §7a) depend on where the USB hub is plugged in. Re-derive them on the new box.
5. **Not copied:** `derpprobe`, the `lte_watch` and `smswatch` helper scripts, `astdb.sqlite3` (registrar contacts and dynamic endpoints), the control-plane onboarding link.
6. **Starting the backend** on a second box before the edge is stopped duplicates its alerts and SMS.
7. **Recovery rules.** Before the cutover, walk `docs/recovery-code-rules.md` for the chan_quectel build that goes live.

## Cutover, 2026-11-16

- The tailnet node `edge-1` (100.64.0.4) runs here: the old edge's `tailscaled.state` was copied and its Tailscale stopped and disabled. The phones' base URL is unchanged.
- `shop.db` was copied with the old backend stopped (sha256 checked). The jar is the commit `0380299` build (sha256 checked).
- `PUBLIC_BASE_URL` in `backend.env` is `http://192.168.0.158:8080`.
- Five modems are connected: shop1, shop2, shop5, shop6, shop7. The USB controller cannot start 11 modems at once (`can't set config #1, error -12`); the six spares stay unplugged.
- `/etc/asterisk` is group-writable by `asterisk`, so the backend can write the modem config.
- The Asterisk unit runs `asterisk -U asterisk` without `-G` (drop-in `/etc/systemd/system/asterisk.service.d/groups.conf`), so the process keeps the `dialout` and `audio` groups.
- Tailscale on this box is the apt package, version 1.104.7.

## Verified on 2026-11-16

- All five modems are Free with the IMSI of the shop in the database.
- SMS sent from each line arrived.
- A bridged call from each line to a mobile phone and the S21 app had audio both ways. Audio from the phone into Asterisk runs only while audio is also written to the phone, because chan_quectel links the capture and playback streams; a recording test with nothing playing captures nothing.
- `/health` is ok and the duty phone registers over the tailnet.

## USB layout of the modems, 2026-11-17

- The standard layout (diag, NMEA, AT, modem, network, audio) needs more endpoints than the AMD xHCI controller (1022:7914) provides for 11 modems. Each modem is set to a reduced layout with `AT+QCFG="usbcfg"` and restarted with `AT+CFUN=1,1`.
- Shop modems: `0x2C7C,0x0125,0,0,1,1,0,0,1` (AT and modem port) gives 5 interfaces. The AT port is interface `:1.0`. The modem sends call-progress messages (`+QIND: "ccinfo"`) only to the AT port (`urcport` is `usbat`), so chan_quectel must use `:1.0`; on `:1.1` it never sees the call index change from 1 to 3 and does not start the Dial to the app.
- Spares without a SIM: the same setting gives 4 interfaces with the AT port on `:1.0`. Spares 1-1.4.2.4 and 1-1.4.3.2 have ADB enabled and take `0,0,1,0,0,1,1` (5 interfaces, AT on `:1.0`). The `usbcfg` write with ADB off is rejected on them.
- `ModemResolver` tries interfaces `:1.2`, then `:1.0` for the tty.
- All 11 modems enumerate and answer `AT` after a hub power cycle, and the five shop trunks are Free.
- A modem that times out at set-config (`error -110`) is revived by writing 1 then 0 to the hub port `disable` file, `/sys/bus/usb/devices/1-1.4.<chip>:1.0/1-1.4.<chip>-port<n>/disable`.
- PipeWire and WirePlumber are masked and the graphical session is stopped, because they open the modem audio devices.
- Verified on 2026-11-17: a bridged call on each of the five shop lines reaches the S21 and the user heard audio. Not tested: a reboot of the host.

## Monitoring on the EliteDesk

- Services (enabled, run at boot): `shopbackend` (contains the SIP monitor, modem watchdog, one-way audio check, system health and Lebara balance), `shopbackend-health.timer`, `dropcatch`, `derpprobe` (relay and app latency to the phones, `/home/phone/derpprobe.csv`) and `linkprobe` (layered reachability of the duty phone, `/home/phone/linkprobe.csv`).
- `/home/phone/syswatch.sh <shops with a proven first SMS> [seconds]` prints one line per abnormal event (SMS failures, trunk states, duty phone, restarts, recovery actions) and a summary every 25 minutes. It ends itself after the given lifetime (default 1790 s), so it is started by the Claude monitor and re-armed when it expires.
- `smswatch.sh` and `syswatch-journal.awk` are the pieces `syswatch.sh` uses.

## Open

- `src/smsdb.c: cannot start a transaction within a transaction` is logged three times at modem start-up. Its effect is not examined.
- Asterisk 22 logs `Option 'stir_shaken' is no longer supported` for every endpoint written by the backend.
- `astdb.sqlite3` is not copied. `lte_watch` is not copied.
- Incoming calls from outside have not been tested.

## Old edge decommission, 2026-11-20

The old edge is powered off, at 20:03. Its disk is untouched.

State when it was powered off: `shopbackend`, `shopbackend-health.timer`, `dropcatch` and `asterisk`
inactive and disabled, no Asterisk or JVM process, no EC25 modem on the USB bus, `tailscaled` not
running, and nothing listening on 8080, 5060, 5038 or 8088. The live box held the tailnet node
`edge-1` (100.64.0.4), eight trunks Free, ten manager contacts Available and `/health` ok, before
and after.

`update-manager` holds a blocking shutdown inhibitor on a login session, and the box has a running
graphical session, so `systemctl poweroff` takes about two minutes to complete and the host answers
on the network while it runs. Read reachability, not the exit status, to tell whether it is down.

Powering it on again needs the power button. `ethtool` is not installed, so wake-on-LAN is
unverified. `HandleLidSwitch` is `ignore`, so it runs with the lid closed.

Rollback to this box costs more than it did before the cutover: power it on by hand, re-enable the
four units, move the modems and the USB hub back, and bring up Tailscale. Its `tailscaled.state` was
copied to the EliteDesk, so only one of the two boxes can be `edge-1` at a time.
