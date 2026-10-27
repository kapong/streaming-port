"""Watches Stream Port's LAN rebroadcast and measures it: frames per second, bitrate, and lost packets.

Joins the multicast group, reads the MPEG-TS, and counts video frames (PES starts on the video PID) and gaps in
the TS continuity counters (each gap = lost TS packets). No video decoding.

    python3 -I streamport/tools/multicast_probe.py --seconds 30 --iface YOUR_MAC_IP
"""

import argparse
import socket
import struct
import time

VIDEO_PID = 0x100


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--group", default="239.255.42.42")
    ap.add_argument("--port", type=int, default=5004)
    ap.add_argument("--iface", default="0.0.0.0", help="this machine's LAN IPv4 address")
    ap.add_argument("--seconds", type=int, default=30)
    args = ap.parse_args()

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, "SO_REUSEPORT"):
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
    s.bind(("", args.port))
    mreq = struct.pack("4s4s", socket.inet_aton(args.group), socket.inet_aton(args.iface))
    s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    s.settimeout(1.0)

    last_cc = {}
    total = {"pkts": 0, "bytes": 0, "frames": 0, "lost": 0, "datagrams": 0}
    window = dict(total)
    start = time.time()
    tick = start
    while time.time() - start < args.seconds:
        try:
            data = s.recv(65536)
        except socket.timeout:
            continue
        total["datagrams"] += 1
        total["bytes"] += len(data)
        for i in range(0, len(data) - 187, 188):
            p = data[i:i + 188]
            if p[0] != 0x47:
                continue
            total["pkts"] += 1
            pid = ((p[1] & 0x1F) << 8) | p[2]
            has_payload = p[3] & 0x10
            cc = p[3] & 0x0F
            if has_payload:
                if pid in last_cc:
                    expected = (last_cc[pid] + 1) & 0xF
                    if cc != expected:
                        total["lost"] += (cc - expected) & 0xF or 16
                last_cc[pid] = cc
            if pid == VIDEO_PID and p[1] & 0x40:
                total["frames"] += 1
        now = time.time()
        if now - tick >= 5:
            d = {k: total[k] - window[k] for k in total}
            secs = now - tick
            print(f"{now - start:5.1f}s  {d['frames'] / secs:5.1f} fps  {d['bytes'] * 8 / secs / 1e6:5.2f} Mb/s  "
                  f"lost {d['lost']} of {d['pkts'] + d['lost']} TS packets")
            window = dict(total)
            tick = now
    secs = time.time() - start
    loss = total["lost"] / max(1, total["pkts"] + total["lost"]) * 100
    print(f"TOTAL {total['frames'] / secs:.1f} fps, {total['bytes'] * 8 / secs / 1e6:.2f} Mb/s, "
          f"lost {total['lost']} TS packets ({loss:.2f}%)")


if __name__ == "__main__":
    main()
