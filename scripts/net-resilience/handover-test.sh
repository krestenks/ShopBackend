#!/bin/bash
# WiFi <-> cellular handover test.
#
# Cycles WiFi off/on on a manager phone that ALSO has cellular, and watches whether the
# phone's SIP endpoint stays reachable across every transition -- and, critically, whether
# each handover mints a NEW contact (the ghost-contact bug, see docs/network-resilience-tests.md).
#
# Usage: handover-test.sh <adb-serial> <aor> [cycles]
#   e.g. handover-test.sh RFGL40Q90NN mgr2 10
set -u
ADB="${ADB:-/c/Users/krest/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
EDGE="${EDGE:-phone@192.168.0.192}"
SERIAL="${1:?adb serial required}"
AOR="${2:?aor required, e.g. mgr2}"
CYCLES="${3:-10}"
SETTLE="${SETTLE:-55}"

contacts() { ssh -o ConnectTimeout=8 "$EDGE" "sudo asterisk -rx 'pjsip show contacts' 2>/dev/null | grep '$AOR/'" 2>/dev/null; }

phase() { # $1=label $2=seconds
  local t0 first drop rtt ports s a
  t0=$(date +%s); first=""; drop=0; rtt=""; ports=""
  while [ $(( $(date +%s) - t0 )) -lt "$2" ]; do
    s=$(contacts); a=$(echo "$s" | grep ' Avail ' | head -1)
    if [ -n "$a" ]; then
      [ -z "$first" ] && first=$(( $(date +%s) - t0 ))
      rtt=$(echo "$a" | awk '{print $NF}')
    else
      drop=1
    fi
    ports=$(echo "$s" | grep -oE ':[0-9]+ ' | tr -d ': ' | sort -u | tr '\n' ',')
    sleep 5
  done
  echo "    $1 firstAvail=${first:-NEVER}s dropped=$drop rtt=${rtt:-NA}ms ports=[$ports]"
}

echo "handover test: serial=$SERIAL aor=$AOR cycles=$CYCLES"
for c in $(seq 1 "$CYCLES"); do
  echo "=== CYCLE $c === $(date +%H:%M:%S)"
  "$ADB" -s "$SERIAL" shell svc wifi disable >/dev/null 2>&1; phase "->CELL" "$SETTLE"
  "$ADB" -s "$SERIAL" shell svc wifi enable  >/dev/null 2>&1; phase "->WIFI" "$SETTLE"
done
echo "=== COMPLETE $(date +%H:%M:%S) ==="
echo "PASS = every phase firstAvail 0-2s, dropped=0, and ports NEVER grow beyond the fixed 5062."
