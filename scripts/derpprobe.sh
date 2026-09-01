#!/usr/bin/env bash
# derpprobe — decomposes manager-phone round-trip latency into RELAY cost vs APP cost,
# and measures UDP against TCP over the identical path. Run from the edge box.
#
# Why this exists: `pjsip show contacts` reports multi-second qualify RTTs, which reads as
# "the DERP relay is slow". It is not. The relay and the application are two different
# latency sources stacked on top of each other, and only one of them is worth fixing.
# This probe separates them by timing four round trips to the SAME phone over the SAME
# tailnet path, differing only in WHO answers:
#
#   tsping   tailscale disco ping     answered by libtailscale   -> relay only
#   icmp     ICMP echo (2 sizes)      answered by the netstack    -> relay only, + loss/MTU
#   tcp      TCP SYN -> RST on :9     answered by the netstack    -> relay only, TCP transport
#   sip      raw SIP OPTIONS on :5062 answered by the APP         -> relay + app
#
# The first three are app-independent and agree with each other; their median is the true
# relay floor. `app_added_ms = sip - relay` is the headline number: everything the phone's
# SIP stack adds on top of the network.
#
# UDP vs TCP: DERP encapsulates every packet, UDP and TCP alike, inside one TLS/TCP
# connection to the relay, so there is no transport-level difference to find at the relay
# (icmp and tcp columns track each other — verify, don't assume). Any UDP-looks-worse-than-TCP
# effect lives in the app: SIP is gated behind SipEngine's 500 ms iterate() pump, HTTP is not.
# The `burst_gap_ms` column measures that pump directly.
#
# Usage:  ./derpprobe.sh [targets.csv]     default targets = the S26 and the S21
#         SAMPLE_S=60 BURST_EVERY=10 ./derpprobe.sh
# Stop:   pkill -f derpprobe.sh
set -uo pipefail

LOG="${DERPPROBE_LOG:-/home/phone/derpprobe.csv}"
SAMPLE_S="${SAMPLE_S:-60}"
BURST_EVERY="${BURST_EVERY:-10}"   # run the (slower) pump-serialisation burst every Nth cycle
BURST_N="${BURST_N:-5}"
SRC_IP="${SRC_IP:-100.64.0.4}"
HELPERS="${HELPERS:-/home/phone/probe}"

# name:tailnet-ip — override by passing a file of the same shape
TARGETS_DEFAULT="s26:100.64.0.11
s21:100.64.0.7"
TARGETS="$( [ $# -ge 1 ] && [ -f "$1" ] && cat "$1" || echo "$TARGETS_DEFAULT" )"

# Never let a probe wedge the box: every helper is timeout-bounded, every failure is an
# empty field rather than a stall. (Same discipline as linkprobe.sh.)
run() { timeout "${2:-15}" bash -c "$1" 2>/dev/null; }

med() { tr ' ' '\n' <<<"$*" | grep -E '^[0-9.]+$' | sort -n | awk '{a[NR]=$1} END{if(NR)printf "%.0f", a[int((NR+1)/2)]}'; }

if [ ! -f "$LOG" ]; then
  echo "ts,target,ip,path,tsping_ms,icmp64_ms,icmp64_loss,icmp1240_ms,icmp1240_loss,tcp_ms,sip_udp_ms,relay_ms,app_added_ms,burst_gap_ms,qualify_ms,sip_status,derp_ms" >> "$LOG"
fi

CYCLE=0
while true; do
  CYCLE=$((CYCLE + 1))
  TS="$(date -Iseconds)"

  # Edge -> DERP server leg. Cheap, shared by every target, and near-zero here because the
  # edge and the relay share a LAN — so the whole relay cost sits on the phone's leg.
  DERP_MS="$(run "tailscale netcheck" 25 | awk '/- headscale:/ {print $3}' | grep -oE '^[0-9.]+')"

  while IFS=: read -r NAME IP; do
    [ -z "${IP:-}" ] && continue

    # --- relay layer, three independent answerers -----------------------------------
    TP="$(run "tailscale ping -c 4 $IP" 25)"
    if   grep -q "via DERP" <<<"$TP"; then PATH_KIND="derp"
    elif grep -q "direct"   <<<"$TP"; then PATH_KIND="direct"
    else                                   PATH_KIND="none"; fi
    TSPING="$(med "$(grep -oE 'in [0-9]+ms' <<<"$TP" | grep -oE '[0-9]+' | tr '\n' ' ')")"

    # Two sizes: 64 B catches plain RTT, 1240 B sits just under the 1280 B tailnet MTU and
    # catches the size-dependent loss that has bitten this fleet before (S21 / DFS channel).
    for S in 64 1240; do
      P="$(run "ping -c 6 -i 0.3 -W 4 -s $S $IP" 20)"
      L="$(grep -oE '[0-9]+% packet loss' <<<"$P" | grep -oE '^[0-9]+')"
      R="$(awk -F'/' '/rtt|round-trip/ {printf "%.0f", $5}' <<<"$P")"
      if [ "$S" = 64 ]; then ICMP64="$R"; LOSS64="$L"; else ICMP1240="$R"; LOSS1240="$L"; fi
    done

    # TCP round trip with the app excluded: port 9 is closed, so the phone's tailnet netstack
    # answers the SYN with a RST. Same path, same relay, TCP instead of UDP.
    TCP_MS="$(run "python3 $HELPERS/tcprtt.py $IP 9 6" 30 | grep -oE 'med=[0-9]+' | cut -d= -f2)"

    # --- app layer ------------------------------------------------------------------
    # One raw OPTIONS straight at the app's SIP port, bypassing Asterisk's qualify scheduler,
    # so a slow number here means the PHONE is slow and not that Asterisk queued the check.
    SIP_MS="$(run "python3 $HELPERS/sipprobe.py $IP --src $SRC_IP --count 5 --quiet" 40 | grep -oE 'med=[0-9]+' | cut -d= -f2)"

    # Occasionally: fire BURST_N OPTIONS at once. If the phone answers them one per iterate()
    # tick the replies come back evenly spaced, and that spacing IS the pump interval — the
    # mechanism that turns N registered AORs into N x tick of qualify latency.
    BURST_GAP=""
    if [ $(( (CYCLE - 1) % BURST_EVERY )) -eq 0 ]; then
      BURST_GAP="$(run "python3 $HELPERS/burst.py $IP $BURST_N" 45 | awk '/gaps=/{sub(/.*gaps=/,""); n=split($0,g,", "); s=0; for(i=1;i<=n;i++) s+=g[i]; if(n) printf "%.0f", s/n}')"
    fi

    # --- what Asterisk currently believes, for cross-reference ----------------------
    PJ="$(run "sudo -n asterisk -rx 'pjsip show contacts'" 15 | grep -E "@$IP:5062")"
    if   grep -q 'Avail' <<<"$PJ"; then SIP_STATUS="Avail"
    elif [ -n "$PJ" ];              then SIP_STATUS="Unavail"
    else                                 SIP_STATUS="none"; fi
    QUALIFY="$(grep 'Avail' <<<"$PJ" | awk '{print $NF}' | sort -n | tail -1 | cut -d. -f1)"

    # --- derived --------------------------------------------------------------------
    # The relay floor is what the three app-independent probes agree on; anything the SIP
    # probe costs beyond that was added by the phone's application.
    RELAY="$(med "${TSPING:-} ${ICMP64:-} ${TCP_MS:-}")"
    APP_ADDED=""
    [ -n "${SIP_MS:-}" ] && [ -n "${RELAY:-}" ] && APP_ADDED=$((SIP_MS - RELAY))

    echo "$TS,$NAME,$IP,$PATH_KIND,${TSPING:-},${ICMP64:-},${LOSS64:-},${ICMP1240:-},${LOSS1240:-},${TCP_MS:-},${SIP_MS:-},${RELAY:-},${APP_ADDED:-},${BURST_GAP:-},${QUALIFY:-},$SIP_STATUS,${DERP_MS:-}" >> "$LOG"
  done <<< "$TARGETS"

  sleep "$SAMPLE_S"
done
