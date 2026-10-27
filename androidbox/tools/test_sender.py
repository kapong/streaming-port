"""Legacy AirPlay screen-mirroring test sender, for testing Stream Port without an iPhone.

Does what an iPhone does on the wire: GET /info, pair-setup, pair-verify, fp-setup (a recorded FairPlay
exchange from java-airplay's test client), SETUP with ekey/eiv, SETUP video, then streams an H.264 test pattern
over the encrypted mirroring connection, with /feedback keep-alives on the control connection.

    .venv/bin/python -I streamport/tools/test_sender.py 192.168.1.23 --name "Test 1" --size 1280x720
    .venv/bin/python -I streamport/tools/test_sender.py 192.168.1.23 --name "Phone" --size 886x1920 --seconds 30

Needs ffmpeg on PATH and the `cryptography` package.
"""

import argparse
import hashlib
import os
import plistlib
import random
import socket
import struct
import subprocess
import tempfile
import threading
import time

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import serialization

# java-airplay client fixture (MIT): a recorded fp-setup exchange and the ekey that goes with it.
FIXTURE_EKEY = bytes(b & 0xFF for b in [
    70, 80, 76, 89, 1, 2, 1, 0, 0, 0, 0, 60, 0, 0, 0, 0, 63, 121, 70, -69, 3, -8, 117, -13, 83, 72,
    105, -51, -11, -43, -1, 17, 0, 0, 0, 16, 24, -109, 13, 105, -32, -125, -73, -128, 21, 29, -31, 72,
    -41, 112, -36, -75, 57, 110, 71, -72, -25, -59, 102, 22, 19, -43, 35, 74, -20, 86, 15, 16, 126, 5,
    15, -45])
FP_SETUP_1 = bytes(b & 0xFF for b in [70, 80, 76, 89, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, -69])
FP_SETUP_2 = bytes(b & 0xFF for b in [
    70, 80, 76, 89, 3, 1, 3, 0, 0, 0, 0, -104, 0, -113, 26, -100, -40, -92, -10, 52, 109, 20, 120, 6,
    -62, -67, -118, 75, -47, -71, -109, -45, -61, 106, -95, 1, 36, -104, -7, 78, -1, -13, 70, 123,
    -49, 27, 49, -104, 98, 92, -94, 69, -114, 62, -48, 30, -35, 53, -25, 41, 53, 125, -7, 75, -128,
    -51, 10, -50, 35, 84, -42, -116, -29, 127, 94, 24, -16, -49, -46, 109, 65, 103, 21, 63, -64, -76,
    54, 35, 22, 111, 8, -58, 111, -45, 1, 56, 14, -80, -98, -97, -115, -24, 59, -46, -82, -57, -92,
    1, -15, -5, -67, -13, 46, 10, -43, 81, -24, 121, 63, -25, -63, 25, 35, 51, -103, -91, 53, 76,
    -59, 67, 7, 30, -68, -50, -32, -84, -123, 34, -82, 27, -85, 51, -44, 65, -60, 120, -11, 99, -50,
    -3, 66, 117, -5, 85, 90, 58, -29, 58, -40, -71, -7, -108, -7, -75])
# What the receiver unwraps FIXTURE_EKEY to after those two fp-setup messages (FairPlayTest prints it).
FIXTURE_AES_KEY = bytes.fromhex("335c0da6c20d3ab07c3e8338589c7dd6")
EIV = os.urandom(16)


class Rtsp:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=10)
        self.cseq = 0
        self.lock = threading.Lock()

    def request(self, method, uri, body=b"", content_type=None, proto="RTSP/1.0"):
        with self.lock:
            self.cseq += 1
            head = f"{method} {uri} {proto}\r\nCSeq: {self.cseq}\r\nUser-Agent: AirPlay/770.10.1\r\n"
            if content_type:
                head += f"Content-Type: {content_type}\r\n"
            head += f"Content-Length: {len(body)}\r\n\r\n"
            self.sock.sendall(head.encode() + body)
            return self._response()

    def _response(self):
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("receiver closed the connection")
            data += chunk
        head, rest = data.split(b"\r\n\r\n", 1)
        lines = head.decode("latin-1").split("\r\n")
        status = int(lines[0].split(" ")[1])
        length = 0
        for line in lines[1:]:
            k, _, v = line.partition(":")
            if k.strip().lower() == "content-length":
                length = int(v.strip())
        while len(rest) < length:
            rest += self.sock.recv(length - len(rest))
        return status, rest[:length]


def ctr(key, iv):
    return Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()


def handshake(rtsp, name):
    status, info = rtsp.request("GET", "/info")
    assert status == 200, status
    disp = plistlib.loads(info)["displays"][0]
    print(f"[{name}] /info: display {disp['width']}x{disp['height']} @ {disp['maxFPS']} fps")

    status, pk = rtsp.request("POST", "/pair-setup", os.urandom(32), "application/octet-stream")
    assert status == 200 and len(pk) == 32, (status, len(pk))

    ed = Ed25519PrivateKey.generate()
    x = X25519PrivateKey.generate()
    x_pub = x.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    ed_pub = ed.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    status, m2 = rtsp.request("POST", "/pair-verify", b"\x01\x00\x00\x00" + x_pub + ed_pub,
                              "application/octet-stream")
    assert status == 200 and len(m2) == 96, (status, len(m2))
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PublicKey
    secret = x.exchange(X25519PublicKey.from_public_bytes(m2[:32]))
    key = hashlib.sha512(b"Pair-Verify-AES-Key" + secret).digest()[:16]
    iv = hashlib.sha512(b"Pair-Verify-AES-IV" + secret).digest()[:16]
    enc = ctr(key, iv)
    enc.update(m2[32:96])
    sig = enc.update(ed.sign(x_pub + m2[:32]))
    status, _ = rtsp.request("POST", "/pair-verify", b"\x00\x00\x00\x00" + sig, "application/octet-stream")
    assert status == 200, status

    for msg in (FP_SETUP_1, FP_SETUP_2):
        status, _ = rtsp.request("POST", "/fp-setup", msg, "application/octet-stream")
        assert status == 200, status
    return secret


PATTERNS = ["testsrc2", "smptehdbars", "testsrc", "rgbtestsrc"]


def encode(size, seconds_of_video, fps, pattern_index):
    w, h = size
    out = tempfile.NamedTemporaryFile(suffix=".h264", delete=False).name
    pattern = f"{PATTERNS[pattern_index % len(PATTERNS)]}=size={w}x{h}:rate={fps}"
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", pattern, "-t", str(seconds_of_video),
                    "-c:v", "libx264", "-profile:v", "high", "-preset", "veryfast", "-tune", "zerolatency",
                    "-pix_fmt", "yuv420p", "-g", str(fps * 2), "-bf", "0", "-x264-params", "aud=1",
                    "-f", "h264", out], check=True)
    data = open(out, "rb").read()
    os.unlink(out)
    return data


def split_nals(data):
    out, i, n = [], 0, len(data)
    starts = []
    while i < n - 3:
        if data[i] == 0 and data[i + 1] == 0 and (data[i + 2] == 1 or (data[i + 2] == 0 and i + 3 < n and data[i + 3] == 1)):
            sc = 3 if data[i + 2] == 1 else 4
            starts.append((i, i + sc))
            i += sc
        else:
            i += 1
    for k, (s, body) in enumerate(starts):
        end = starts[k + 1][0] if k + 1 < len(starts) else n
        out.append(data[body:end])
    return out


def access_units(nals):
    units, cur = [], []
    sps = pps = None
    for nal in nals:
        t = nal[0] & 0x1F
        if t == 9:  # AUD starts a new access unit
            if cur:
                units.append(cur)
            cur = []
        elif t == 7:
            sps = sps or nal
        elif t == 8:
            pps = pps or nal
        else:
            cur.append(nal)
    if cur:
        units.append(cur)
    return sps, pps, units


def ntp_now():
    """Send time as Apple stamps it: NTP format, seconds in the high 32 bits, fraction in the low 32."""
    t = time.time() + 2208988800
    return (int(t) << 32) | int((t % 1) * (1 << 32))


def header(size, ptype, w=0.0, h=0.0):
    hd = bytearray(128)
    struct.pack_into("<IHHQ", hd, 0, size, ptype, 286 if ptype == 1 else 0, ntp_now())
    if ptype == 1:
        struct.pack_into("<ff", hd, 16, w, h)
        struct.pack_into("<ff", hd, 56, w, h)
    return bytes(hd)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("host")
    ap.add_argument("--port", type=int, default=7000)
    ap.add_argument("--name", default="Test sender")
    ap.add_argument("--size", default="1280x720")
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--seconds", type=int, default=20)
    ap.add_argument("--pattern", type=int, default=0, help="0-3: which ffmpeg test pattern")
    ap.add_argument("--jitter", type=int, default=0, help="add up to this many ms of random send delay")
    args = ap.parse_args()
    w, h = map(int, args.size.split("x"))

    video = encode((w, h), 4, args.fps, args.pattern)
    sps, pps, units = access_units(split_nals(video))

    rtsp = Rtsp(args.host, args.port)
    secret = handshake(rtsp, args.name)
    setup1 = plistlib.dumps({"ekey": FIXTURE_EKEY, "eiv": EIV, "name": args.name, "model": "TestSender1,1",
                             "deviceID": "02:" + os.urandom(5).hex(":").upper(),
                             "isScreenMirroringSession": True, "timingPort": 0}, fmt=plistlib.FMT_BINARY)
    status, _ = rtsp.request("SETUP", f"rtsp://{args.host}/1", setup1, "application/x-apple-binary-plist")
    if status != 200:
        print(f"[{args.name}] SETUP refused: {status}")
        return
    conn_id = int.from_bytes(os.urandom(8), "big", signed=True)
    setup2 = plistlib.dumps({"streams": [{"type": 110, "streamConnectionID": conn_id}]}, fmt=plistlib.FMT_BINARY)
    status, body = rtsp.request("SETUP", f"rtsp://{args.host}/1", setup2, "application/x-apple-binary-plist")
    assert status == 200, status
    data_port = plistlib.loads(body)["streams"][0]["dataPort"]
    print(f"[{args.name}] video port {data_port}, streaming {w}x{h} for {args.seconds}s")
    rtsp.request("RECORD", f"rtsp://{args.host}/1")

    master = hashlib.sha512(FIXTURE_AES_KEY + secret).digest()[:16]
    sid = str(conn_id & 0xFFFFFFFFFFFFFFFF)
    key = hashlib.sha512(f"AirPlayStreamKey{sid}".encode() + master).digest()[:16]
    iv = hashlib.sha512(f"AirPlayStreamIV{sid}".encode() + master).digest()[:16]
    enc = ctr(key, iv)

    stop = threading.Event()

    def keepalive():
        while not stop.wait(2):
            try:
                rtsp.request("POST", "/feedback")
            except Exception as e:
                print(f"[{args.name}] feedback: {e}")
                return

    threading.Thread(target=keepalive, daemon=True).start()

    v = socket.create_connection((args.host, data_port), timeout=10)
    codec = bytes([1, sps[1], sps[2], sps[3], 0xFF, 0xE1]) + struct.pack(">H", len(sps)) + sps \
        + bytes([1]) + struct.pack(">H", len(pps)) + pps
    v.sendall(header(len(codec), 1, w, h) + codec)
    sent = 0
    start = time.time()
    try:
        while time.time() - start < args.seconds:
            for nal_list in units:
                payload = b"".join(struct.pack(">I", len(n)) + n for n in nal_list)
                packet = header(len(payload), 0) + enc.update(payload)
                if args.jitter:
                    # Stamp first, then hold the packet back, like a congested Wi-Fi link would.
                    time.sleep(random.random() * args.jitter / 1000)
                v.sendall(packet)
                sent += 1
                delay = start + sent / args.fps - time.time()
                if delay > 0:
                    time.sleep(delay)
                if time.time() - start >= args.seconds:
                    break
    finally:
        stop.set()
        print(f"[{args.name}] sent {sent} frames; tearing down")
        try:
            rtsp.request("TEARDOWN", f"rtsp://{args.host}/1")
        except Exception:
            pass
        v.close()


if __name__ == "__main__":
    main()
