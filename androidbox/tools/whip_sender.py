"""WHIP test sender for Stream Port's browser sharing, without a browser.

Sends an ffmpeg test pattern (and a tone) the way the share page does: SDP offer by POST /whip, answer back,
media one way, DELETE the resource at the end. Uses aiortc (pip install aiortc).

    .venv/bin/python -I streamport/tools/whip_sender.py BOX_IP --name "WHIP 1" --size 1280x720 --seconds 20
"""

import argparse
import asyncio
import ssl
import urllib.parse
import urllib.request

from aiortc import RTCPeerConnection, RTCSessionDescription
from aiortc.contrib.media import MediaPlayer
from aiortc.rtcrtpsender import RTCRtpSender

PATTERNS = ["testsrc2", "smptehdbars", "testsrc", "rgbtestsrc"]


def http(method, url, body=None, content_type=None):
    """Plain urllib with the TV's self-signed certificate accepted (a test tool on our own LAN)."""
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    req = urllib.request.Request(url, data=body, method=method)
    if content_type:
        req.add_header("Content-Type", content_type)
    req.add_header("User-Agent", "whip_sender.py (aiortc)")
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=15) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("host")
    ap.add_argument("--port", type=int, default=9443)
    ap.add_argument("--name", default="WHIP test")
    ap.add_argument("--size", default="1280x720")
    ap.add_argument("--seconds", type=int, default=20)
    ap.add_argument("--pattern", type=int, default=0)
    ap.add_argument("--codec", default="H264", help="H264 or VP8")
    ap.add_argument("--show-answer", action="store_true", help="print the TV's SDP answer")
    args = ap.parse_args()
    base = f"https://{args.host}:{args.port}"

    video = MediaPlayer(f"{PATTERNS[args.pattern % 4]}=size={args.size}:rate=30", format="lavfi")
    audio = MediaPlayer("sine=frequency=440:sample_rate=48000", format="lavfi")
    pc = RTCPeerConnection()
    vt = pc.addTransceiver(video.video, direction="sendonly")
    pc.addTransceiver(audio.audio, direction="sendonly")
    want = "video/" + args.codec.lower()
    caps = RTCRtpSender.getCapabilities("video").codecs
    vt.setCodecPreferences([c for c in caps if c.mimeType.lower() == want] +
                           [c for c in caps if c.mimeType.lower() == "video/rtx"])

    @pc.on("connectionstatechange")
    async def on_state():
        print(f"[{args.name}] connection {pc.connectionState}")

    await pc.setLocalDescription(await pc.createOffer())  # aiortc gathers before returning
    url = f"{base}/whip?name={urllib.parse.quote(args.name)}"
    status, headers, body = await asyncio.to_thread(http, "POST", url, pc.localDescription.sdp.encode(), "application/sdp")
    if status != 201:
        print(f"[{args.name}] refused: {status} {body.decode(errors='replace')}")
        await pc.close()
        return
    location = headers.get("Location")
    if args.show_answer:
        print(body.decode())
    print(f"[{args.name}] 201 Created, resource {location.rsplit('/', 1)[0]}/<secret>")
    await pc.setRemoteDescription(RTCSessionDescription(sdp=body.decode(), type="answer"))
    await asyncio.sleep(args.seconds)
    status, _, _ = await asyncio.to_thread(http, "DELETE", base + location)
    print(f"[{args.name}] DELETE {status}")
    await pc.close()


if __name__ == "__main__":
    asyncio.run(main())
