#!/usr/bin/env python3
"""Fire N SIP OPTIONS simultaneously and time each reply, to test whether the
phone's SIP stack serialises messages across its iterate() ticks."""
import socket, sys, time, uuid, threading

ip = sys.argv[1]; n = int(sys.argv[2]) if len(sys.argv) > 2 else 5
port = 5062; src = "100.64.0.4"
res = {}
start = threading.Barrier(n)

def one(i):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind((src, 0)); s.settimeout(8.0)
    lp = s.getsockname()[1]; tag = uuid.uuid4().hex[:12]
    m = (f"OPTIONS sip:probe@{ip}:{port} SIP/2.0\r\n"
         f"Via: SIP/2.0/UDP {src}:{lp};branch=z9hG4bK{tag};rport\r\n"
         f"Max-Forwards: 70\r\nFrom: <sip:probe@{src}>;tag={tag}\r\n"
         f"To: <sip:probe@{ip}>\r\nCall-ID: {tag}@{src}\r\n"
         f"CSeq: 1 OPTIONS\r\nContact: <sip:probe@{src}:{lp}>\r\n"
         f"User-Agent: linkprobe\r\nContent-Length: 0\r\n\r\n").encode()
    start.wait()
    t0 = time.monotonic()
    try:
        s.sendto(m, (ip, port)); s.recvfrom(4096)
        res[i] = (time.monotonic() - t0) * 1000.0
    except socket.timeout:
        res[i] = None
    finally:
        s.close()

ts = [threading.Thread(target=one, args=(i,)) for i in range(n)]
[t.start() for t in ts]; [t.join() for t in ts]
ok = sorted(v for v in res.values() if v)
print(f"  burst of {n} -> " + ", ".join(f"{v:.0f}" for v in ok) + " ms")
if len(ok) > 1:
    print(f"  spread={ok[-1]-ok[0]:.0f} ms  gaps=" + ", ".join(f"{ok[i+1]-ok[i]:.0f}" for i in range(len(ok)-1)))
