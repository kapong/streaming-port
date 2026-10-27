# Streaming Port for Android TV boxes (StreamPort)

Screen-sharing receiver for Android TV boxes, built for the NAIL'EM lab's Xiaomi TV Box S (2nd gen). Up to **4
screens at once** from iPhones, iPads and Macs (AirPlay) and from any browser (screen or camera, WebRTC), laid out
automatically to use as much of the TV as possible. It appears under Screen Mirroring with the name you give it
(**NAIL'EM** until renamed).

## Install on a box (release APK)

Needs Android 11 or later, 32-bit ARM app support (`armeabi-v7a`, which almost every TV box has) and a hardware
H.264 decoder. On the box: Settings > Device Preferences > About > press Build 7 times, then Developer options >
USB debugging (and Network debugging, or plug in USB). From a computer with `adb`:

```bash
curl -LO https://github.com/kapong/streaming-port/releases/latest/download/streaming-port.apk
adb connect <box-ip>:5555                       # accept the prompt on the TV the first time
adb install -r streaming-port.apk
adb shell appops set com.nailem.streamport SYSTEM_ALERT_WINDOW allow            # comes to the front when someone shares
adb shell pm grant com.nailem.streamport android.permission.WRITE_SECURE_SETTINGS  # optional: same name for Cast
adb shell appops set com.nailem.streamport PROJECT_MEDIA allow                  # optional: LAN rebroadcast
adb shell am start -n com.nailem.streamport/.StreamActivity                     # first start; afterwards it starts at boot
```

Then open the admin page at `http://<box-ip>:8089` (log in with the PIN the TV shows) to rename the receiver and set
the limits. On a box like the Xiaomi TV Box S, **720p and 4 screens** keeps CPU and RAM under 80% (measurements
below); 1080p suits 1 to 3 screens. Browsers share at `https://<box-ip>:9443` (the TV shows it as a QR code).
Updates: install a newer release the same way (`adb install -r`); settings stay.

Chromecast and Miracast can't be tiled. Google signs every Cast receiver's certificate, and a Miracast receiver needs a
permission only Xiaomi's own system apps get. Both still work full screen through the box's built-in receivers, and
Stream Port works alongside them:

- **One name**: renaming the receiver also renames Chromecast (at once) and Miracast (from its next start), once
  adb has granted `WRITE_SECURE_SETTINGS` (see below).
- **Miracast on demand**: the admin page has "Switch the TV to Miracast" (no longer on the TV's idle screen: Android
  and Windows will get their own app). Xiaomi's receiver listens only while its screen is open, and **while it is open the TV leaves the Wi-Fi**:
  AirPlay, Chromecast and the admin page are unreachable until someone presses BACK on the remote. The TV doesn't
  always rejoin by itself; then reconnect it in Settings > Network.
- **One at a time**: when Chromecast or Miracast takes the screen, every AirPlay sender is disconnected.

## Browser sharing (WebRTC, WHIP)

Open `https://192.168.1.23:9443` (the TV's idle screen shows it as a QR code), type a name, then:

- **Share screen**: laptops (Windows, Mac, Linux, ChromeOS) and iPhones/iPads on iOS 27+. Pick a screen, window or tab.
- **Share camera**: any of those, and **Android phones too** (their browsers can't share the screen, but can share the
  camera; for the screen they Cast to the built-in Chromecast). Front/back switch on phones without reconnecting;
  the microphone is off unless ticked (a phone near the TV echoes).

Either becomes a tile like an AirPlay sender: same layout, primary, limits, quality dot, kick. The admin page lists it
as "Screen · Chrome on Windows" or "Camera · Chrome on Android".

The page (`assets/share/`) has the TV welcome screen's look (Outfit, served as `/outfit.ttf`): free places, two
choices (screen, camera), the microphone switch, the rebroadcast warning with its address, then a preview with a
three-step progress bar and "On the TV now". Design mock: `mock/share/` (compare old and new at
`http://localhost:8766/new/compare.html` after `python3 -I androidbox/mock/share_server.py`). To test the real
page end to end without the self-signed certificate, `mock/share_server.py --box https://192.168.1.23:9443` serves it
on `http://localhost:8767` and passes `/config` and `/whip` to the TV.

- **One way only**, WHIP style (RFC 9725): the page POSTs its SDP offer to `/whip` (`application/sdp`), gets `201`, the
  answer and a secret `Location`; DELETE on that URL ends the share. The TV sends nothing back; the page shows only a
  local preview of what it shares and its status.
- **Any WHIP sender works**, e.g. OBS Studio 30+ (Settings > Stream > Service "WHIP", server
  `https://192.168.1.23:9443/whip?name=OBS`; OBS may need the certificate trusted).
- **Certificate**: self-signed by the box (EC P-256, 397 days, the box's IP in it), so each device warns once: Advanced,
  then Proceed. Browsers only allow screen sharing on https pages.
- **Engine**: Google's libwebrtc (`stream-webrtc-android` 1.3.9), loaded on the first share. Only the box's own
  MediaCodec decoders are used: H.264 (preferred), VP9 and AV1 in hardware, VP8 through Amlogic's decoder (max
  2048x2048). No software decoders (see SECURITY.md). Screens keep text sharp (`contentHint = detail`, resolution kept,
  frames dropped first); cameras keep motion smooth. Bitrate up to 8 Mb/s at 1080p; the answer tells Chrome to start
  near that rate, so the first picture shows in about 2 s.
- **Offers** must be one video and at most one audio section (no data channels, no simulcast); only LAN ICE
  candidates are used.
- **Limits**: the same as AirPlay (max screens, max resolution, 2 per device), 10 offers per device per minute, video
  must arrive within 20 s, a dropped connection gets 8 s to come back.

## Rebroadcast to the LAN (multicast, for VLC)

Off by default. When switched on (admin page, or the `rebroadcast` setting), an MPEG-TS stream goes to the LAN all
the time: the welcome screen (`IdleView`, drawn off screen) while nobody shares, the TV itself while someone does.
So viewers never see whatever else the TV shows (Home, other apps). Anyone on the Wi-Fi can watch: VLC, Open Network
Stream, `udp://@239.255.42.42:5004` (or `ffplay udp://239.255.42.42:5004`). The welcome screen shows a red notice
with that address, the tiles a small "Live on the network" mark, and the share page warns senders while it is on.
Idle with the welcome screen on air the box uses about 12% CPU.

- TTL 1: the packets never leave the LAN segment. 7 TS packets per datagram (1316 bytes).
- 1280x720 at 12 fps by default (`rebroadcast_res`, `rebroadcast_fps`; it is for presentations, and anything over
  10 fps is fine by the owner); bitrate scales from 4 Mb/s at 1080p, and still screens cost almost nothing (the last frame is repeated every 100 ms for late joiners).
- Capture: MediaProjection mirror display into an RGBA ImageReader, libyuv to I420, the hardware AVC encoder,
  `rebroadcast/TsMuxer.java`. The encoder's own surface input manages only about 10 MP/s on this box (1080p at
  5 fps), hence the CPU conversion.
- Android asks for screen-capture consent; one adb grant makes it silent (needed, the TV has no one to click OK):

```bash
adb shell appops set com.nailem.streamport PROJECT_MEDIA allow
```

Check what the LAN receives with `tools/multicast_probe.py --iface <this Mac's LAN IPv4> --seconds 30`: frame rate,
Mb/s and lost TS packets (from the continuity counters).

## How it behaves

- Runs in the background from boot. When a sender connects, the TV switches to Stream Port; when the last one
  leaves, it goes back to Home.
- Senders are accepted automatically (owner's choice). Anyone on the TV's Wi-Fi can show their screen; only LAN
  addresses are accepted, and at most 4 at a time by default (others are refused until a place frees up).
- **Layouts**: *dynamic* (default) makes the primary screen the biggest and fits the rest around it to use the most
  of the TV; *grid* gives every screen an equal cell. Pictures keep their aspect ratio and are never cropped.
- **Resolution**: 1080p by default at 30 fps. iPhones and iPads get a square display to fit into, so portrait
  reaches 1080x1920; Macs adopt the offered display as their resolution, so they get 1920x1080. 1440p and 4K can be
  chosen on the admin page (the box decodes about one 4K or four 1080p streams; the TV shows everything at 1080p).
- **Each tile** shows the sender's name and a small dot for its network quality (green good, amber fair, red poor,
  grey idle), with bitrate and frame rate when the tile is wide enough.
- **Sound**: you hear the primary screen only.

| Remote | Does |
|---|---|
| Arrows | Pick the primary screen (biggest in dynamic layout, and the one you hear); in focus, show the previous / next screen |
| OK | Dynamic (default) → grid → focus (only the primary screen) → dynamic |
| Hold OK | Straight to focus / back |
| MENU | Disconnect the primary sender |
| BACK | Leave focus; twice in a row: stop all and go Home |

With nobody sharing, the welcome screen (`IdleView.java`, designed in `mock/idle.html`) shows how to connect:
Apple devices (Control Center → Screen Mirroring) and any browser (QR code, `https://` address). Opened by hand, it
goes back to Home after 10 minutes with nobody sharing.

## Admin page

`http://192.168.1.23:8089` from any browser on the LAN (the TV's idle screen shows the address). Press "Show PIN on
TV", type the 6-digit code from the TV, and the browser stays logged in for 30 days. From there: rename the
receiver, switch layout, pick the primary screen, set the max resolution and max screens, see each sender's
address, resolution and network quality (Mb/s, fps, jitter), kick a sender, and switch the TV to Miracast. Every
save shows what changed and when it applies. Details in `SECURITY.md`.

## Build and install

JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). A release build is signed with the key named in
`~/.gradle/gradle.properties` (`STREAMPORT_KEYSTORE`, `STREAMPORT_KEYSTORE_PASSWORD`, `STREAMPORT_KEY_ALIAS`), which
is never in this repository; without it, with the debug key (fine for your own box, but it can't update an APK from
the releases).

```bash
cd androidbox && ./gradlew --no-daemon testReleaseUnitTest assembleRelease -q --console=plain
adb push app/build/outputs/apk/release/app-release.apk /data/local/tmp/streamport.apk
adb shell pm install -r /data/local/tmp/streamport.apk && adb shell rm -f /data/local/tmp/streamport.apk
adb shell appops set com.nailem.streamport SYSTEM_ALERT_WINDOW allow   # once: lets it come to the front
adb shell pm grant com.nailem.streamport android.permission.WRITE_SECURE_SETTINGS   # once: one name for Cast too
adb shell am start -n com.nailem.streamport/.StreamActivity              # first start (afterwards: boot)
```

Installing restarts the receiver and cuts anyone mirroring, so check first:
`adb shell dumpsys window | grep mCurrentFocus` should not show `streamport`.

## Settings (adb only)

```bash
adb shell am broadcast -n com.nailem.streamport/.SettingsReceiver -a com.nailem.streamport.SET --es key name --es value "NAIL'EM"
adb shell am broadcast -n com.nailem.streamport/.SettingsReceiver -a com.nailem.streamport.SET --es key ask --es value true
```

Keys: `name` (what senders see), `ask` (`true` holds each new sender behind an "OK allow / BACK refuse" card),
`max_res` (`1080p`, `1440p`, `4k`), `max_streams` (1 to 8), `rebroadcast` (`true`/`false`), `rebroadcast_res`
(`720p`, `1080p`), `rebroadcast_fps` (5 to 30). The receiver restarts to apply a change made this way;
the admin page applies them without a restart.

## Testing without an iPhone or a laptop

`tools/whip_sender.py` is a WHIP sender (aiortc) with an ffmpeg test pattern and a tone:

```bash
pip install aiortc
python3 -I androidbox/tools/whip_sender.py 192.168.1.23 --name "WHIP 1" --size 1920x1080 --seconds 30
python3 -I androidbox/tools/whip_sender.py 192.168.1.23 --name "VP8" --codec VP8
```

AirPlay:

`tools/test_sender.py` speaks the sender side (pairing, FairPlay, encrypted H.264) with ffmpeg test patterns:

```bash
pip install cryptography
python3 -I androidbox/tools/test_sender.py 192.168.1.23 --name L1 --size 1920x1080 --seconds 30
python3 -I androidbox/tools/test_sender.py 192.168.1.23 --name P1 --size 886x1920 --pattern 1
```

One machine can show at most 2 senders (a per-address limit). For more, run senders from a second machine, or
point some at the box's IPv6 link-local address (`adb shell ip -6 addr show wlan0`), which counts as another
address.

Screenshots work (`adb exec-out screencap`) because tiles render through TextureView. A SurfaceView would land on
the Amlogic video plane, which screencap shows as white and which the chip has too few of for four streams.

## Measured on the box

3 streams (2 AirPlay at 1080p and portrait, 1 browser VP8 720p in software): the app about 78% of one core, decoder
service 48%, PSS 58 MB. 4 streams at 30 fps (3 test senders plus a Mac): the app about 47% of one core (6 streams: about 81%), almost all of it GPU composition
(RenderThread and the Mali driver); network, decryption and parsing threads under 0.5% each. Hardware decoder
service about 22%, PSS about 52 MB. A single idle Mac desktop costs about 0%.

**Hardware decoder limits** (2026-10-27, test senders at 30 fps full motion, the worst case):
- One stream above 1440p at a time. A second 4K stream, or more than one 1080p stream next to a 4K one, is refused
  by the decoder ("insufficient resources", OMX `0x80001000`). Past that Android's codec layer even crashed the app,
  so `Hub.fitsDecoder` now turns a screen away as soon as its size is known: budget 4K + 1080p, or 4K (four 1080p)
  while the rebroadcast encoder runs.
- 4 x 1080p30 fit in memory but decode only about 20 fps each (frames dropped); 3 x 1080p30 about 27 fps. Real
  screens send far fewer frames than a test pattern.
- The box's display is 1920x1080 at 59.94 Hz, so a 4K stream is scaled down on the GPU: 20% of UI frames janky with
  one 4K tile, with or without the rebroadcast. `max_res` 1080p (or 1440p) is the sensible setting here.

**Acceptance test** (pass: CPU and RAM under 80%, no stutter, no lost packets; rebroadcast on at 720p, 10-15 fps).
Senders at 30 fps are full-motion test patterns, the worst case; 10 fps is closer to a presentation (a Mac showing
slides sends a few frames a second):

| Senders | CPU avg/max | RAM | Tiles shown | UI janky | Multicast | Result |
|---|---|---|---|---|---|---|
| 4 x 720p, 10 fps | 54 / 54% | 72% | 10 of 10 fps, 0 dropped | 0% | 10.9 fps, 0 lost | **PASS** |
| 4 x 1080p, 10 fps | 59 / 61% | 79% | 10 of 10 fps, 0 dropped | 1% | 10.7 fps, 0 lost | PASS (RAM at the line) |
| 4 x 720p, 30 fps | 78 / 82-87% | 70% | 30 of 30 fps, 0 dropped | 4-9% | 10-12 fps, 0 lost | borderline (peaks over 80%) |
| 4 x 720p, 30 fps, no multicast | 65 / 65% | 69% | 30 of 30 fps | 0.1% | - | **PASS** |
| 4 x 1080p, 30 fps, no multicast | 68 / 69% | 76% | 21 of 29 fps, 968 dropped | 2% | - | FAIL (decoder) |
| 4 x 4K | 57 / 58% | 74% | 1 at 30/30 fps, 3 refused | 23% | 15.3 fps, 0 lost | FAIL (hardware: one 4K decoder) |
| 4 x 1080p | 82 / 82% | 78% | 19.5 of 28.4 fps, 968 dropped | 18% | 14.7 fps, 0 lost | FAIL |
| 3 x 1080p | 79 / 80% | 72% | 27.1 of 30.1 fps | 11% | 15.2 fps, 0 lost | FAIL (borderline) |
| 4K + 1080p | 57 / 57% | 74% | 4K at 30 fps, 1080p refused | 22% | 15.2 fps, 0 lost | FAIL |
| 2 x 1080p | 66 / 67% | 68% | 30.2 of 30.2 fps, 0 dropped | 0.6% | 15.5 fps, 0 lost | **PASS** |

The rebroadcast at its first setting (1080p30) cost about a core (2 x 1080p: 85% CPU, 26% janky); 720p at 15 fps
costs about 12 points of CPU, and its frame rate hardly matters (10 or 15 fps cost the same): most of it is the
system compositor drawing the mirror. Idle box: 27.5% CPU, 59% RAM.

**Box setting chosen (2026-10-27): max resolution 720p, 4 screens, rebroadcast 720p at 12 fps.** With four tiles each
is 960x540 on the TV, so 720p senders lose nothing; 1080p only helps a single full-screen sender.

## Code

| Path | What |
|---|---|
| `airplay/RtspServer.java` | RTSP control port, `/info`, pairing, FairPlay, SETUP/TEARDOWN; limits on connections, lines, bodies |
| `airplay/Session.java` | One sender: video TCP and audio UDP sockets (sender's address only), decryption, keyframe cache |
| `airplay/Hub.java` | Live screens (max streams), approval, which one is heard, the hardware decoder budget |
| `airplay/BPlist.java` | Bounded binary plist reader/writer (replaces dd-plist) |
| `airplay/Pairing.java`, `StreamCiphers.java`, `fairplay/` | Legacy pairing and FairPlay, from [java-airplay](https://github.com/serezhka/java-airplay) (MIT, `fairplay/LICENSE-java-airplay`), with input checks added |
| `airplay/Limits.java`, `airplay/NetStats.java` | Configurable resolution and stream limits; per-sender network quality |
| `admin/AdminServer.java`, `admin/PinOverlay.java`, `assets/admin/` | Admin page: PIN login, JSON API, the page itself |
| `airplay/Screen.java` | What a tile shows, whatever it is: an AirPlay `Session` or a `BrowserScreen` |
| `webrtc/ShareServer.java`, `webrtc/BrowserScreen.java`, `webrtc/WebRtcEngine.java` | Browser sharing: HTTPS share page and WHIP endpoint (port 9443), one share, the WebRTC engine |
| `webrtc/SelfSignedCert.java`, `assets/share/` | The share page's certificate (hand-written DER), the page itself |
| `StreamActivity.java`, `TileView.java`, `TileLayout.java` | Screen, tiles, dynamic and grid layouts |
| `VideoDecoder.java`, `AudioPlayer.java` | Hardware H.264 decode per tile; AAC-ELD audio for the focused tile |
| `rebroadcast/Rebroadcaster.java`, `rebroadcast/TsMuxer.java` | LAN rebroadcast: mirror capture, libyuv, encoder, MPEG-TS over UDP multicast |
| `StreamService.java`, `Advertiser.java` | Background service, mDNS (`_airplay._tcp`, `_raop._tcp`) via Android's NsdManager |

Security review: see `SECURITY.md`.
