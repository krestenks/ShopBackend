#!/usr/bin/env python3
"""Raw SIP OPTIONS prober: times a UDP round trip to a phone's SIP port,
independent of Asterisk's qualify scheduler. Optional padding sweeps packet size."""
import socket, sys, time, uuid, argparse

def probe(ip, port, src_ip, pad, timeout):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind((src_ip, 0))
    s.settimeout(timeout)
    lport = s.getsockname()[1]
    tag = uuid.uuid4().hex[:12]
    msg = (
        f"OPTIONS sip:probe@{ip}:{port} SIP/2.0\r\n"
        f"Via: SIP/2.0/UDP {src_ip}:{lport};branch=z9hG4bK{tag};rport\r\n"
        f"Max-Forwards: 70\r\n"
        f"From: <sip:probe@{src_ip}>;tag={tag}\r\n"
        f"To: <sip:probe@{ip}>\r\n"
        f"Call-ID: {tag}@{src_ip}\r\n"
        f"CSeq: 1 OPTIONS\r\n"
        f"Contact: <sip:probe@{src_ip}:{lport}>\r\n"
        f"User-Agent: linkprobe\r\n"
    )
    if pad:
        msg += "X-Pad: " + ("a" * pad) + "\r\n"
    msg += "Content-Length: 0\r\n\r\n"
    raw = msg.encode()
    t0 = time.monotonic()
    try:
        s.sendto(raw, (ip, port))
        data, _ = s.recvfrom(4096)
        ms = (time.monotonic() - t0) * 1000.0
        return ms, len(raw), data.split(b"\r\n", 1)[0].decode(errors="replace")
    except socket.timeout:
        return None, len(raw), "TIMEOUT"
    finally:
        s.close()

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("ip"); p.add_argument("--port", type=int, default=5062)
    p.add_argument("--src", default="100.64.0.4")
    p.add_argument("--pad", type=int, default=0)
    p.add_argument("--count", type=int, default=5)
    p.add_argument("--timeout", type=float, default=5.0)
    p.add_argument("--quiet", action="store_true")
    a = p.parse_args()
    rtts = []
    for i in range(a.count):
        ms, size, status = probe(a.ip, a.port, a.src, a.pad, a.timeout)
        if not a.quiet:
            print(f"  {size:5d}B -> {('%8.1f ms' % ms) if ms else '  TIMEOUT'}  {status[:40]}")
        if ms: rtts.append(ms)
        time.sleep(0.4)
    if rtts:
        rtts.sort()
        print(f"  n={len(rtts)}/{a.count} min={rtts[0]:.0f} med={rtts[len(rtts)//2]:.0f} max={rtts[-1]:.0f} ms")
    else:
        print(f"  n=0/{a.count} all timed out")
