#!/bin/bash
# TRUE wire RTT of SIP qualify, measured from packet timestamps on the edge.
#
# Every latency number Asterisk prints in `pjsip show contacts` is ITS OWN measurement and
# includes its internal scheduling. This measures the real thing: it captures SIP on the wire and
# correlates each outbound OPTIONS to its response by Call-ID.
#
# Run this alongside a `pjsip show contacts` poll to compare the two. If wire is fast while
# Asterisk reports seconds, the "latency" is a measurement artifact, not a phone problem.
#
# Usage: wire-rtt.sh <tailnet-ip> [seconds]
#   e.g. wire-rtt.sh 100.64.0.11 480
set -u
EDGE="${EDGE:-phone@192.168.0.192}"
TARGET="${1:?tailnet ip required}"
SECS="${2:-480}"

ssh -o ConnectTimeout=30 -o ServerAliveInterval=30 "$EDGE" \
  "sudo timeout $SECS tcpdump -n -i any -A -s 0 'host $TARGET and udp portrange 5060-5062' 2>/dev/null" \
| python -c "
import sys, re
# TWO tcpdump gotchas, both of which fail by returning ZERO results rather than an error:
#  1. -A prints the payload prefixed with raw header bytes, so a line does NOT start with
#     'OPTIONS'/'SIP/2.0'. Take the method from tcpdump's own summary line instead.
#  2. 'In' is padded with TWO spaces to align with 'Out', so ' (Out|In) IP ' matches egress only.
head = re.compile(r'^(\d\d:\d\d:\d\d\.\d+) .*? (Out|In)\s+IP (\S+) > (\S+): SIP: (.*)\$')
cid  = re.compile(r'^Call-ID:\s*(\S+)', re.I)
def sec(t):
    h,m,s = t.split(':'); return int(h)*3600+int(m)*60+float(s)
cur=None; pend={}; rtts=[]
for line in sys.stdin:
    m = head.match(line.rstrip())
    if m:
        ts,d,src,dst,rest = m.groups()
        kind = 'OPTIONS' if rest.startswith('OPTIONS') else ('RESP' if rest.startswith('SIP/2.0') else None)
        cur = (ts, d, dst, kind); continue
    if cur:
        c = cid.match(line.strip())
        if c:
            ts,d,dst,kind = cur; cur=None; key=c.group(1)
            if kind=='OPTIONS' and d=='Out': pend[key]=(ts,dst)
            elif kind=='RESP' and d=='In' and key in pend:
                t0,dst0 = pend.pop(key)
                ms=(sec(ts)-sec(t0))*1000; rtts.append(ms)
                print('%9.1f ms  ->%s' % (ms,dst0), flush=True)
rtts.sort(); n=len(rtts)
if n: print('--- WIRE n=%d min=%.0f median=%.0f p90=%.0f max=%.0f ---'%(n,rtts[0],rtts[n//2],rtts[int(n*0.9)],rtts[-1]))
else: print('--- no matched pairs: check the parser, NOT the network ---')
"
