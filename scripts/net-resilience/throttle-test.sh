#!/bin/bash
# Severe-throughput / lossy-link test.
#
# Shapes traffic on the edge box toward ONE phone's tailnet IP and watches whether its SIP
# endpoint survives. Everything else on the tailnet (other phones, the backend) is untouched
# because the u32 filter matches only that destination.
#
# LIMITATION: this shapes EGRESS from the edge only (edge -> phone). That is the direction that
# carries qualify OPTIONS and inbound INVITEs, so it is the half that matters, but a genuinely
# bad radio link degrades both directions.
#
# The shaping is removed by a trap on EXIT/INT/TERM, and the qdisc is printed afterwards so the
# restore is VERIFIED rather than assumed. If this script is ever killed -9, remove it by hand:
#     ssh phone@192.168.0.192 'sudo tc qdisc del dev tailscale0 root'
#
# Usage: throttle-test.sh <aor> <tailnet-ip> [throttled-seconds]
#   e.g. throttle-test.sh mgr3 100.64.0.7 150
set -u
EDGE="${EDGE:-phone@192.168.0.192}"
AOR="${1:?aor required, e.g. mgr3}"
TARGET="${2:?tailnet ip required, e.g. 100.64.0.7}"
SECS="${3:-150}"
DEV="${DEV:-tailscale0}"
SHAPE="${SHAPE:-delay 400ms 100ms loss 25% rate 64kbit}"

ssh -o ConnectTimeout=30 -o ServerAliveInterval=30 "$EDGE" "bash -s" <<SH
set -u
DEV=$DEV; TARGET=$TARGET; AOR=$AOR; SECS=$SECS
cleanup() { sudo tc qdisc del dev \$DEV root 2>/dev/null; echo "--- shaping REMOVED, qdisc now: \$(sudo tc qdisc show dev \$DEV | head -1)"; }
trap cleanup EXIT INT TERM
probe() { sudo asterisk -rx 'pjsip show contacts' 2>/dev/null | grep "\$AOR/" | grep ' Avail ' | awk '{print \$NF}' | head -1; }

echo "=== baseline (unshaped) ==="
for i in 1 2 3; do echo "  \$(date +%H:%M:%S) rtt=\$(probe)"; sleep 10; done

echo "=== applying shaping to \$TARGET: $SHAPE ==="
sudo tc qdisc add dev \$DEV root handle 1: prio bands 3 2>&1 | head -2
sudo tc qdisc add dev \$DEV parent 1:3 handle 30: netem $SHAPE 2>&1 | head -2
sudo tc filter add dev \$DEV protocol ip parent 1:0 prio 1 u32 match ip dst \$TARGET/32 flowid 1:3 2>&1 | head -2
echo "  netem qdiscs active: \$(sudo tc qdisc show dev \$DEV | grep -c netem)"

n=\$(( SECS / 10 )); [ \$n -lt 1 ] && n=1
for i in \$(seq 1 \$n); do echo "  \$(date +%H:%M:%S) THROTTLED rtt=\$(probe)"; sleep 10; done

cleanup; trap - EXIT
echo "=== recovery after shaping removed ==="
for i in \$(seq 1 12); do echo "  \$(date +%H:%M:%S) rtt=\$(probe)"; sleep 10; done
SH

echo "PASS = the endpoint NEVER goes Unavailable while throttled, and RTT settles back afterwards."
echo "Verify cleanup: ssh $EDGE 'sudo tc qdisc show dev $DEV; sudo tc filter show dev $DEV'"
