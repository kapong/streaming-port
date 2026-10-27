# Stream Port security review

Stream Port listens on a shared Wi-Fi (a meeting room, an office, a classroom) that many people use. Every byte it
reads from the network can come from any of them. This file covers what was reviewed, what was fixed, and what is accepted on purpose.

## Threat model

- **Who can reach it**: any host on the TV's LAN, on TCP 7000 (AirPlay control), the per-sender video ports, the
  UDP audio ports, and TCP 8089 (admin page). Every port refuses addresses that aren't local (private, link-local,
  loopback, ULA, CGNAT), so a port forward from the internet gets nothing.
- **Accepted by design (the default)**: senders are let in without asking, so anyone on the Wi-Fi can put their
  screen on the TV (up to the stream limit). `ask=true` brings back on-screen approval.
- **What must not happen**: a sender reaching beyond its own tile (injecting into, tearing down or hijacking
  another sender), crashing or freezing the receiver, exhausting the 2 GB box, or anyone without the on-TV PIN
  changing settings or kicking people.
- **Out of scope**: adb over the network (turn it off once the box is set up).

## 1. Code taken from java-airplay (MIT)

Only the protocol parts were taken: FairPlay key schedule, legacy pairing, stream ciphers. Netty, jmdns, dd-plist,
HLS and the HTTP video player were left out. Bugs found in the copied code and fixed here:

| Upstream behaviour | Effect | Fix |
|---|---|---|
| Video packet length (uint32 from the sender) allocated as is | 4 GB allocation, receiver dies | Capped by `Limits.maxPacket()` (2 MB at 1080p); connection closed |
| Audio datagrams copied into a fixed 1920-byte buffer; under 12 bytes not checked | Exception on the audio thread | Length checked |
| Audio sequence compared without wrap-around | Audio stops for good after about 12 minutes | Compared modulo 2^16 |
| `/fp-setup` used request byte 14 as an array index; `data[4]` read unchecked | Crash on a short or crafted message | Lengths and both index bytes (14 and 12) checked |
| `ekey` of any length decrypted; decrypt before fp-setup | Out-of-bounds reads | Exactly 72 bytes, key message required |
| Pairing read into fixed buffers ignoring short reads; step 2 before step 1 | Zero-padded keys, NullPointerException | Lengths and order checked; invalid curve points answer 400 |
| Video cipher with a packet shorter than the leftover keystream | Read past the array | Rewritten with explicit keystream, unit-tested against continuous AES-CTR |
| Sessions looked up by the `Active-Remote` header the sender chooses | Any host could TEARDOWN or join another sender's session | Session bound to its TCP connection |
| Media ports accepted any host | Frame injection into someone else's tile | Video only from the sender's address; audio from its address and source port |
| dd-plist for network plists | Large, recursive parser | Own bounded `BPlist` (depth 16, 4096 objects, reference budget, every offset checked), fuzzed with 200k corrupted inputs |
| Keys and secrets in debug logs | Secrets in logcat | Never logged |

## 2. Independent audit, round 1 (Fable)

Verdict: session isolation holds; nothing lets a LAN host inject into another sender's tile, hijack a session or
exceed the cap. The availability findings, all fixed:

| # | Finding | Fix |
|---|---|---|
| 1 | Spoofed UDP from a sender's address could push the audio sequence ahead (about 6 min of silence) or make the decoder fail and mute that sender for good | Audio locked to the sender's source port; a jump of more than 256 needs 3 agreeing packets; a failed decoder retries after 5 s |
| 2 | Slowloris: the 60 s timeout was per byte; idle connections and sessions without video held places forever; a kicked sender's connection stayed | 10 s deadline per request; oldest idle connection evicted when full; sessions without video closed after 20 s; kicking hangs up the control connection |
| 3 | A flood of codec packets flooded the main thread with relayouts and restarted the hardware decoder per packet | Screen notified only on real size changes, notifications coalesced, decoder restarts at most once a second, 10 key exchanges per host per minute |
| 4 | A sender could claim and stream 4K, taking the shared decoder | Size checked against the configured limit on the codec packet and on the decoder's output; over-limit senders are disconnected |
| 5 | Decoder queue bounded by frame count only | Also 16 MB per tile |
| 6 | `/fp-setup` byte 12 unchecked (index into the key table) | Checked |
| 7 | Plist trailer arithmetic could overflow | Rewritten without overflow |
| 8 | About 20 MB of garbage per key exchange in the copied key schedule, reachable when full | Free place and per-host rate limit checked before the key schedule runs (schedule itself unchanged) |
| 9 | `volume: NaN` passed the clamps | NaN ignored |
| 10 | Invalid Ed25519 point threw instead of 400 | Answers 400 |

Notes from the same audit, accepted: with `ask=true`, approval is remembered by the sender-claimed device ID
(spoofable; not the default). Any sender brings Stream Port to the front (that is the feature). The two crypto
libraries (`eddsa` 0.3.0, `curve25519-java` 0.5.0) are unmaintained with no CVE known to the auditor; nothing here
trusts them for authentication.

## 3. Admin page (port 8089)

- **Login**: a 6-digit PIN shown on the TV for 60 s on request. Whoever can see the TV can log in (by
  design). Five tries per PIN; a new PIN at most every 15 s; 20 wrong guesses lock logins for 15 minutes.
  Comparison is constant-time.
- **Sessions**: 32 random bytes in an `HttpOnly; SameSite=Strict` cookie for 30 days. The box stores only SHA-256
  hashes, at most 20. "Log out every browser" clears them all.
- **Cross-site requests**: every API call needs the `X-Admin: 1` header, which a cross-site form or image can't
  send. A `Host` that isn't an IP address or a `.local` name is refused, which stops DNS-rebinding pages.
- **Browser hardening**: CSP `default-src 'self'` (no inline script), `frame-ancestors 'none'`,
  `X-Frame-Options: DENY`, `nosniff`, `no-store`, `no-referrer`. Sender names are inserted with `textContent`
  only.
- **Parsing**: 4 KB lines, 40 headers, 4 KB bodies, 10 s per connection, 8 connections at once, LAN only.
- **Accepted**: plain HTTP. On a shared WPA2 network someone who captures traffic could read the session cookie.
  Log out when done, or use "Log out every browser" if in doubt.

## 4. Permissions granted over adb

| Permission | Why | What it could do |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` (appop) | Bring the tiles to the front when a sender connects; show the admin PIN over other apps | Draw over other apps. The PIN overlay is not focusable or touchable and shows fixed text |
| `WRITE_SECURE_SETTINGS` | Keep the Chromecast/Miracast name (`device_name`) equal to the AirPlay name | Change system settings. The app writes only `device_name`, only from the logged-in admin page or the adb settings broadcast, with a cleaned name (printable, at most 50 bytes) |

| `PROJECT_MEDIA` (appop) | Start the LAN rebroadcast without a consent dialog nobody is there to click | Capture the screen. Used only while `rebroadcast` is on and someone is sharing; the TV shows "Live on the network" meanwhile |

Revoke either with `adb shell pm revoke com.nailem.streamport android.permission.WRITE_SECURE_SETTINGS` or
`adb shell appops set com.nailem.streamport SYSTEM_ALERT_WINDOW deny`; the app keeps working without the feature.

## 5. Limits worth knowing

| Limit | Value |
|---|---|
| Senders at once | 1 to 8, default 4 (admin page); the box runs 4 at 720p |
| Largest picture | 720p, 1080p, 1440p or 4K (admin page); checked on every codec packet and decoder output |
| Connections | 16 in total, 6 per host; idle ones evicted when full |
| Request | 8 KB line, 64 headers, 256 KB body, 10 s from first byte |
| New key exchanges | 10 per host per minute |
| Video packet | 2 MB per 1080p of the limit (8 MB at 4K) |
| Decoder queue | 120 frames or 16 MB per tile |
| Hardware decoders | Pixels of all screens together at most 4K + 1080p (4K while the rebroadcast encodes); a screen past that is disconnected when its size is known, before it reaches a decoder (past the limit Android's codec layer crashed the app) |

## 6. Independent audit, round 2 (Fable)

A second pass over everything, the admin page included. Session tokens, CSRF defences, XSS, HTTP parsing, RTSP
isolation, BPlist and FairPlay checks were found sound. Findings and fixes:

| # | Finding | Fix |
|---|---|---|
| 1 | PIN brute force: the 15-minute lockout never escalated (about 20% odds over a semester of scripted guessing) | Lockouts double each time up to 24 h, per address and globally, and reset only on a successful login |
| 2 | Anyone could request a PIN: replace the admin's PIN, burn it with wrong guesses, trigger the global lock, and flash the overlay over whatever is on the TV | The PIN belongs to the address that asked: others can't replace it, and their guesses can't burn it. Each address may make the TV show a PIN at most every 2 minutes. The overlay shows who asked |
| 3 | Admin page slowloris: 10 s timeout per byte, 8 connections | Whole-request deadline (10 s); 4 connections per address |
| 4 | Deeply nested JSON could overflow the stack and kill the app | Bodies capped at 512 bytes, nesting over 4 refused before parsing; both servers' connection threads catch `Throwable` |
| 5 | One host could fill every place with empty sessions | At most 2 senders per address; a session must send real video (codec or frame packets) within 20 s |
| 6 | Worst-case buffering at 4K × 8 streams exceeded the 256 MB heap | 1440p and 4K allow at most 4 screens (about 160 MB worst case at 4K; first set to 2 for 4K, raised to 4 later); decoder queue sized to two of the sender's largest packets |
| 7 | Lowering the limits cut off senders already mirroring (contrary to the page's own text) | Each session keeps the limits in force when it joined |
| 8 | With `ask=true`, approval was remembered by the sender-claimed device ID | Remembered by device ID and address together |
| 9 | UDP audio can still be spoofed by someone who captured the session setup on the shared Wi-Fi | Inherent (AirPlay audio has no MAC); the source-port lock is the practical defence. Accepted |
| 10 | `.local` names in Host aren't a rebinding barrier | Moot (cookies are host-scoped; only the PIN request is reachable). IP-literal Hosts must now be one of the box's own addresses |
| 11 | Plain HTTP: one captured cookie gives 30 days of admin | Accepted; "Log out every browser" ends every session |
| 12 | Long Thai names could exceed the mDNS label length | Names capped at 50 bytes of UTF-8 (about 16 Thai letters) |

Verified on the box after the fixes: a Host with another IP gets 421; a second address can't replace or burn the
PIN another browser asked for and is locked out after 5 wrong guesses while that PIN stays valid; a 200-deep JSON body gets
400 and the app keeps running; a third sender from one address is refused.

## 7. Browser sharing (WebRTC, WHIP) and its audit (Fable)

Port 9443, HTTPS with a certificate the box makes itself (by design: no setup, each device warns once). One way: the TV
only receives. A share is a screen or a camera, sent by the share page or any WHIP sender.

Built in from the start: LAN-only addresses, Host must be the box's own IP (no DNS rebinding), POST needs
`Content-Type: application/sdp` (a cross-site form can't send it), DELETE needs a 128-bit secret resource URL compared
in constant time, strict CSP and `Permissions-Policy` (display capture, camera and microphone for this page only),
request deadline and size caps, the same screen limits as AirPlay, 10 offers per address per minute.

Audit findings and fixes:

| # | Finding | Fix |
|---|---|---|
| 1 | High: the size limit was checked only after decoding, and VP9/AV1 fell back to software decoders (libvpx, dav1d) that would decode any size a sender claims, enough to run the 32-bit app out of memory | Software decoders removed: only the box's MediaCodec decoders (H.264, VP9, AV1 in hardware; Amlogic's VP8, capped at 2048x2048), each behind a guard that refuses any keyframe over the limit before decoding |
| 2 | One share could carry several video tracks, all decoded, only the first shown and checked | An offer must be exactly one video and at most one audio section; simulcast and RID lines are stripped |
| 3 | With `ask=true`, a browser share was heard before it was approved | Audio plays only for the primary screen once it is approved (as AirPlay) |
| 4 | Data channels were accepted and leaked | Offers with a data channel (`m=application`) are refused |
| 5 | The TV would send ICE checks to any address an offer listed | Candidates outside the LAN are dropped from the offer (browsers' `.local` names are kept) |
| 6 | Per-address limits could be dodged with extra IPv6 link-local addresses | The share server listens on IPv4 only |
| 7 | One accept error ended the server for good (share, and the same pattern in AirPlay and admin) | All three accept loops log, pause briefly and carry on |
| 8 | An `Error` while answering leaked a PeerConnection | The share is always closed if no answer was made |
| 9 | A certificate made before Wi-Fi was up named no IP, and every renewal changed the key | Renewals keep the key; the certificate is reissued when the box's address changes while running |
| 10 | Info: `pc` not volatile; a frozen picture keeps a place (as with AirPlay); people are taught to click through a certificate warning | `pc` volatile; the rest accepted and noted here |

Accepted: the self-signed certificate means someone able to intercept traffic on the Wi-Fi (ARP spoofing) could proxy
the page and learn a share's secret URL. Same class as the plain-HTTP admin page: LAN only, documented.

## 8. LAN rebroadcast (multicast)

Off by default (`rebroadcast` setting, admin page). When on, the TV is sent unencrypted to
`udp://239.255.42.42:5004` while someone shares: **anyone on the Wi-Fi can watch it**, including screens shared by
people who don't know. While nobody shares only the welcome screen is sent, drawn off screen, so nothing else the TV
shows (Home, other apps) goes out, and the screen capture is held only while someone shares. Mitigations: off by
default, the welcome screen says it is on and gives the address, the tiles show "Live on the network", the share page
tells browser senders before they share, TTL 1 (never routed off the segment), nothing is received on that port
(send only). The capture needs the `PROJECT_MEDIA` appop (section 4). Encoder and capture errors stop the rebroadcast
instead of the app.
