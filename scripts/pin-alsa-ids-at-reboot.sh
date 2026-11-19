#!/bin/bash
# Pins the ALSA card id of every EC25 modem to its USB socket, with one host reboot.
#
# Why a reboot: the kernel refuses to rename a sound card that a trunk uses (verified 2026-11-19), and a card
# takes its pinned name from the udev rule only when it is created. So the trunk config and the database are
# changed to the new names first, the rule is installed, and the host restarts; every card then enumerates under
# the pinned name that the config already names.
#
# Usage:
#   pin-alsa-ids-at-reboot.sh            dry run: prints what would change, changes nothing
#   pin-alsa-ids-at-reboot.sh --apply    makes the change and reboots the host
#
# Run it on the phone server, with no call active and the shops closed. The outage is about 3 minutes.
# Rollback: restore the three backup files it prints, delete the rule file, reboot.

set -u
APPLY=0
[ "${1:-}" = "--apply" ] && APPLY=1

DB=/home/phone/shopbackend/data/shop.db
CONF=/etc/asterisk/quectel_shops.conf
RULE=/etc/udev/rules.d/89-ec25-alsa-names.rules
STAMP=$(date +%Y%m%d-%H%M%S)

idle() {
  [ "$(sudo asterisk -rx 'core show channels' | grep -c '^0 active channels')" = 1 ] || return 1
  [ "$(sudo asterisk -rx 'quectel show devices' | awk 'NR>1 && $3!="Free"' | wc -l)" = 0 ] || return 1
}

# A card's new id from its USB device name: 1-1.4.3.2 -> ec_1_1_4_3_2 (12 characters, the limit is 15).
new_id() { echo "ec_${1//[-.]/_}"; }

declare -A CARD_KERNEL
for c in /sys/class/sound/card*; do
  k=$(readlink -f "$c/device" | grep -oE '/1-1\.4\.[0-9]\.[0-9]/' | tr -d /)
  [ -n "$k" ] && CARD_KERNEL[$(basename "$c")]=$k
done
echo "EC25 sound cards found: ${#CARD_KERNEL[@]}"

# Config: alsadev= of each trunk follows the socket in its data= line.
NEW_CONF=$(mktemp)
sudo awk '
  /^data=/ { match($0, /:1\.4\.[0-9]\.[0-9]:/); s=substr($0, RSTART+1, RLENGTH-2); gsub(/\./, "_", s); cur="hw:CARD=ec_1_" s; if (RSTART==0) cur="" }
  /^alsadev=/ && cur != "" { print "alsadev=" cur; cur=""; next }
  { print }' "$CONF" > "$NEW_CONF"

echo "--- trunk config changes ---"
diff <(sudo cat "$CONF") "$NEW_CONF" | grep '^[<>]' || echo "(none)"

# Every trunk must sit on a socket that currently has a card, or its audio would be lost by this change.
bad=0
for sock in $(grep -oE '^data=.*:1\.4\.[0-9]\.[0-9]:' "$CONF" | grep -oE '1\.4\.[0-9]\.[0-9]'); do
  found=0
  for k in "${CARD_KERNEL[@]}"; do [ "$k" = "1-$sock" ] && found=1; done
  [ $found = 1 ] || { echo "STOP: trunk socket $sock has no sound card now"; bad=1; }
done

echo "--- database changes ---"
DB_SQL=$(mktemp)
for row in $(sqlite3 "$DB" "select shop_id||'|'||modem_data_device||'|'||ifnull(modem_alsa_device,'') from shop_telephony_config where modem_data_device is not null and modem_data_device != ''"); do
  sid=${row%%|*}; rest=${row#*|}; dev=${rest%%|*}; old=${rest#*|}
  s=$(echo "$dev" | grep -oE ':1\.4\.[0-9]\.[0-9]:' | tr -d ':' | tr . _)
  [ -n "$s" ] || { echo "STOP: shop $sid has no socket in its data path"; bad=1; continue; }
  new="hw:CARD=ec_1_$s"
  echo "shop $sid: $old -> $new"
  echo "update shop_telephony_config set modem_alsa_device='$new' where shop_id=$sid;" >> "$DB_SQL"
done

echo "--- udev rule ($RULE) ---"
RULE_TXT=$(mktemp)
{
  echo '# Pin each EC25 ALSA card id to its USB socket. Regenerate after re-cabling (scripts/alsa-socket-names.sh).'
  for k in "${CARD_KERNEL[@]}"; do
    echo "SUBSYSTEM==\"sound\", KERNEL==\"card*\", KERNELS==\"$k\", ATTR{id}=\"$(new_id "$k")\""
  done | sort
} > "$RULE_TXT"
cat "$RULE_TXT"

[ $bad = 0 ] || { echo "Not safe to apply."; exit 1; }
if [ $APPLY = 0 ]; then echo; echo "DRY RUN: nothing changed. Run with --apply to make the change and reboot."; exit 0; fi

idle || { echo "NOT IDLE: a call or a trunk that is not Free. Nothing changed."; exit 1; }

echo "--- applying ---"
sudo cp "$CONF" "/home/phone/quectel_shops.conf.before-pin-$STAMP"
sudo systemctl stop shopbackend
cp "$DB" "/home/phone/shop.db.before-pin-$STAMP"
echo "backups: /home/phone/quectel_shops.conf.before-pin-$STAMP  /home/phone/shop.db.before-pin-$STAMP"
sudo systemctl stop asterisk
sudo tee "$CONF" < "$NEW_CONF" > /dev/null
sqlite3 "$DB" < "$DB_SQL"
sudo install -m 644 "$RULE_TXT" "$RULE"
sudo udevadm control --reload
sync
echo "done. Rebooting in 5 seconds."
sleep 5
sudo systemctl reboot
