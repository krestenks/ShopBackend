#!/bin/bash
# Total network loss / recovery test.
#
# For a phone with NO cellular fallback (check `getprop gsm.sim.state` -- the spare S21 reports
# ABSENT), turning WiFi off removes the network entirely. Going Unavailable is then CORRECT; what
# this measures is how long the phone takes to come BACK once the network returns, and whether the
# reconnection mints new contacts.
#
# Usage: network-loss-test.sh <adb-serial> <aor> [cycles]
#   e.g. network-loss-test.sh R3CRA01MRVX mgr3 10
set -u
ADB="${ADB:-/c/Users/krest/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
EDGE="${EDGE:-phone@192.168.0.192}"
SERIAL="${1:?adb serial required}"
AOR="${2:?aor required, e.g. mgr3}"
CYCLES="${3:-10}"

avail() { ssh -o ConnectTimeout=8 "$EDGE" "sudo asterisk -rx 'pjsip show contacts' 2>/dev/null | grep '$AOR/' | grep ' Avail ' | awk '{print \$NF}' | head -1" 2>/dev/null; }
ports() { ssh -o ConnectTimeout=8 "$EDGE" "sudo asterisk -rx 'pjsip show contacts' 2>/dev/null | grep '$AOR/' | grep -oE ':[0-9]+ ' | tr -d ': '" 2>/dev/null | sort -u | tr '\n' ','; }

echo "network-loss test: serial=$SERIAL aor=$AOR cycles=$CYCLES"
for c in $(seq 1 "$CYCLES"); do
  echo "=== CYCLE $c === $(date +%H:%M:%S)"
  "$ADB" -s "$SERIAL" shell svc wifi disable >/dev/null 2>&1
  gone=""; t0=$(date +%s)
  for _ in $(seq 1 9); do sleep 5; [ -z "$(avail)" ] && { gone=$(( $(date +%s) - t0 )); break; }; done
  echo "    network OFF: wentUnavail=${gone:-STILL_AVAIL_AFTER_45s}s"

  "$ADB" -s "$SERIAL" shell svc wifi enable >/dev/null 2>&1
  t1=$(date +%s); back=""; r=""
  for _ in $(seq 1 24); do sleep 5; r=$(avail); [ -n "$r" ] && { back=$(( $(date +%s) - t1 )); break; }; done
  echo "    network ON : recovered=${back:-NEVER_IN_120s}s rtt=${r:-NA}ms ports=[$(ports)]"
done
echo "=== COMPLETE $(date +%H:%M:%S) ==="
echo "PASS = every cycle recovers (never NEVER_IN_120s) and ports stay [5062]."
echo "NOTE: the rtt printed is sampled AT the moment of recovery and is routinely multi-second."
echo "      That is a recovery artifact, not steady state -- measure again after ~2 min to compare."
