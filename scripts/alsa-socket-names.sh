#!/bin/bash
# Generate a udev rule pinning each EC25's ALSA card id to its physical USB socket,
# and detect when the live card<->socket map has drifted from the trunk config.
#
# WHY. chan_quectel's alsadev= is written as hw:CARD=EC25EUX_N. The _N suffix is assigned in
# USB *registration* order, not by socket, so a re-enumeration can move a name to a different
# modem. Proof on this box: EC25EUX_2 sits on socket 1.2.6 while 1.2.3 holds _3.
# It bit us on 2026-09-12 (power-cycling 1.2.6 swapped _2 and _3 and broke shop5's audio).
#
# The data= path was already made immune by pinning it to /dev/serial/by-path (db8a1e1,
# 2026-09-05). This does the same for the audio device. Once the ids are pinned the backend
# needs NO code change: ModemResolver.resolveAlsaCard() reads the live id from sysfs, so the
# next provision writes the stable name by itself.
#
# This script only READS. It prints a rule for you to install; it never touches /etc or /sys.
#
# Usage:
#   ./alsa-socket-names.sh          # live map + any drift, then print the rule
#   ./alsa-socket-names.sh --check  # map + drift only (exit 1 if drifted)
#
# Run it on the edge box. Re-run after re-cabling (e.g. onto a hub): the socket paths change,
# so the rule must be regenerated. That is why this is a generator and not a checked-in file.

set -uo pipefail

EC25_VID=2c7c
EC25_PID=0125
MAX_ID_LEN=15          # struct snd_card { char id[16]; } -> 15 chars + NUL
TRUNK_CONF=${TRUNK_CONF:-/etc/asterisk/quectel_shops.conf}   # overridable so the drift check can be tested
RULE_PATH=/etc/udev/rules.d/89-ec25-alsa-names.rules

check_only=0
[ "${1:-}" = "--check" ] && check_only=1

# ---- collect the live map ---------------------------------------------------
# Keyed on /dev/snd/by-path, which encodes the physical socket in the same scheme as the
# /dev/serial/by-path node already used for data=.

declare -a SOCKETS KERNELS CUR_IDS NEW_IDS CARDS

if [ ! -d /dev/snd/by-path ]; then
    echo "ERROR: /dev/snd/by-path does not exist - cannot identify sockets by path." >&2
    exit 2
fi

for link in /dev/snd/by-path/*; do
    [ -e "$link" ] || continue
    control=$(basename "$(readlink -f "$link")")        # controlC4
    case "$control" in controlC*) ;; *) continue ;; esac
    card=${control#controlC}
    # A card can appear under two by-path names (usb-0 and usbv2-0); take it once.
    case " ${CARDS[*]:-} " in *" $card "*) continue ;; esac

    syspath=$(readlink -f "/sys/class/sound/card$card" 2>/dev/null) || continue
    iface=$(dirname "$(dirname "$syspath")")            # .../1-1.2.6:1.5
    usbdev=$(dirname "$iface")                          # .../1-1.2.6
    vid=$(cat "$usbdev/idVendor" 2>/dev/null)
    pid=$(cat "$usbdev/idProduct" 2>/dev/null)
    [ "$vid" = "$EC25_VID" ] && [ "$pid" = "$EC25_PID" ] || continue

    kernel=$(basename "$usbdev")                        # 1-1.2.6
    cur=$(cat "/sys/class/sound/card$card/id" 2>/dev/null)
    # Socket as it appears in the by-path name: pci-...-usb-0:1.2.6:1.5 -> 1.2.6
    sock=$(basename "$link"); sock=${sock##*usb-0:}; sock=${sock%:*}
    new="ec_${kernel//[-.]/_}"                          # 1-1.2.6 -> ec_1_1_2_6

    SOCKETS+=("$sock"); KERNELS+=("$kernel"); CUR_IDS+=("$cur")
    NEW_IDS+=("$new");  CARDS+=("$card")
done

n=${#SOCKETS[@]}
if [ "$n" -eq 0 ]; then
    echo "ERROR: no EC25 sound cards found. Are the modems attached and UAC enabled?" >&2
    exit 2
fi

# ---- validate the proposed names before suggesting them --------------------
fail=0
for i in $(seq 0 $((n-1))); do
    if [ ${#NEW_IDS[$i]} -gt $MAX_ID_LEN ]; then
        echo "ERROR: proposed id '${NEW_IDS[$i]}' is ${#NEW_IDS[$i]} chars, limit $MAX_ID_LEN." >&2
        echo "       The USB path is too deep - shorten the prefix in this script." >&2
        fail=1
    fi
    if ! [[ ${NEW_IDS[$i]} =~ ^[A-Za-z][A-Za-z0-9_]*$ ]]; then
        echo "ERROR: proposed id '${NEW_IDS[$i]}' is not a safe ALSA id." >&2
        fail=1
    fi
done
dupes=$(printf '%s\n' "${NEW_IDS[@]}" | sort | uniq -d)
[ -n "$dupes" ] && { echo "ERROR: proposed ids collide: $dupes" >&2; fail=1; }
for i in $(seq 0 $((n-1))); do
    for other in /sys/class/sound/card*/id; do
        oc=$(basename "$(dirname "$other")"); oc=${oc#card}
        [ "$oc" = "${CARDS[$i]}" ] && continue
        if [ "$(cat "$other" 2>/dev/null)" = "${NEW_IDS[$i]}" ]; then
            echo "ERROR: '${NEW_IDS[$i]}' already used by card$oc" >&2; fail=1
        fi
    done
done
[ "$fail" -eq 1 ] && exit 2

# ---- what the trunk config believes, and whether it still holds -------------
echo "Live EC25 audio map"
echo "-------------------"
printf "%-8s %-10s %-14s %-14s %s\n" "socket" "kernel" "current id" "pinned id" "trunk using it"
drift=0
for i in $(seq 0 $((n-1))); do
    shop=""
    if [ -r "$TRUNK_CONF" ]; then
        # The trunk whose data= points at this same socket is the one that owns this card.
        shop=$(awk -v s=":${SOCKETS[$i]}:" '
            /^\[/    { t=substr($0,2,length($0)-2) }
            /^data=/ { if (index($0,s)) print t }' "$TRUNK_CONF" | head -1)
    fi
    note=""
    if [ -n "$shop" ]; then
        want=$(awk -v t="[$shop]" '
            $0==t {f=1; next} /^\[/{f=0} f&&/^alsadev=/{sub(/^alsadev=/,""); print; exit}' "$TRUNK_CONF")
        if [ "$want" != "hw:CARD=${CUR_IDS[$i]}" ]; then
            note="  <-- DRIFT: config says $want"
            drift=1
        fi
    else
        shop="(spare)"
    fi
    printf "%-8s %-10s %-14s %-14s %s%s\n" \
        "${SOCKETS[$i]}" "${KERNELS[$i]}" "${CUR_IDS[$i]}" "${NEW_IDS[$i]}" "$shop" "$note"
done

echo
if [ "$drift" -eq 1 ]; then
    echo "DRIFT DETECTED: a trunk's alsadev= no longer matches the card at its socket."
    echo "Its audio is pointing at the WRONG modem. A backend restart re-provisions and fixes it"
    echo "(Rule 2: check 'asterisk -rx \"core show channels\"' first, act when idle)."
else
    echo "No drift: every trunk's alsadev= matches the card at its own socket."
fi

if [ "$check_only" -eq 1 ]; then
    [ "$drift" -eq 1 ] && exit 1
    exit 0
fi

# ---- the rule ---------------------------------------------------------------
echo
echo "Proposed rule -- NOT installed. Review, then write it to $RULE_PATH yourself."
echo "================================================================================"
echo "# Pin each EC25's ALSA card id to its physical USB socket, so the name cannot"
echo "# follow USB registration order onto a different modem. Generated $(date -I) by"
echo "# scripts/alsa-socket-names.sh -- REGENERATE after re-cabling, the paths change."
for i in $(seq 0 $((n-1))); do
    echo "SUBSYSTEM==\"sound\", KERNEL==\"card*\", KERNELS==\"${KERNELS[$i]}\", ATTR{id}=\"${NEW_IDS[$i]}\""
done
echo "================================================================================"

cat <<'EOF'

UNVERIFIED STEP. Whether udev can rename a sound card via ATTR{id} on this kernel is NOT
proven. The attribute is root-writable and the match path is confirmed, but the rename
itself has never been tested here. Test on a SPARE socket first:

  1. Pick a socket no trunk uses (a "(spare)" row above).
  2. Install a rule for that ONE socket only.
  3. sudo udevadm control --reload
     sudo udevadm trigger --subsystem-match=sound --action=add
  4. cat /proc/asound/cards     - did that card's id change?

If it did NOT change, stop: the approach does not work on this kernel, remove the rule.
Do not add a live shop's socket until the spare has proved it.

If it DID change, add the remaining sockets, then restart the backend when the trunks are
idle so its startup re-provision rewrites every alsadev= to the pinned names. No backend
code change is needed - resolveAlsaCard() already reads the live id from sysfs.

Note: a card whose PCM is currently open by chan_quectel may refuse to rename. The live
shops' cards are held open continuously, so expect to need either a backend stop or a
reboot for those - which is why hub-recabling day, with its re-enumeration, is the moment
to do this.

Rollback: delete the rule file, 'udevadm control --reload', restart the backend when idle.
The names revert to EC25EUX_N and the re-provision writes them back.
EOF
