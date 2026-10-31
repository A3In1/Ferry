#!/usr/bin/env python3
# Raw UDP loopback packet rate: the ceiling any UDP transport (QUIC) can reach on this machine.
# Socket-filter VPN/security extensions can cap this far below the hardware limit.
import socket, threading, time
N, SZ = 300000, 1200
r = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); r.bind(("127.0.0.1", 0))
r.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8 << 20); r.settimeout(2)
port = r.getsockname()[1]; got = [0]
def rx():
    try:
        while True: r.recv(2048); got[0] += 1
    except socket.timeout: pass
t = threading.Thread(target=rx); t.start()
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); buf = b"x" * SZ
t0 = time.time()
for _ in range(N): s.sendto(buf, ("127.0.0.1", port))
el = time.time() - t0; t.join()
print(f"sent {N} pkts in {el:.2f}s = {N/el:,.0f} pkt/s = {N*SZ/el/2**20:.0f} MiB/s; received {got[0]}")
