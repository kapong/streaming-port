# Third-party code, libraries and fonts

StreamPort as a whole is distributed under GPL-3.0 (the repository's `LICENSE`). It contains or links:

| What | Where | License |
|---|---|---|
| FairPlay (SAP) handshake and legacy pairing, from [java-airplay](https://github.com/serezhka/java-airplay) by Sergei Fedorov, with input checks added (see `SECURITY.md` section 1) | `app/src/main/java/com/nailem/streamport/airplay/fairplay/`, `Pairing.java`, `StreamCiphers.java` | MIT (`fairplay/LICENSE-java-airplay`) |
| The FairPlay algorithm those Java files port: playfair, as shipped with [UxPlay](https://github.com/FDH2/UxPlay) and RPiPlay | ported in `fairplay/` | GPL-3.0 |
| [EdDSA-Java](https://github.com/str4d/ed25519-java) `net.i2p.crypto:eddsa` 0.3.0 | Gradle dependency | CC0 1.0 |
| [curve25519-java](https://github.com/signalapp/curve25519-java) `org.whispersystems:curve25519-java` 0.5.0 | Gradle dependency | GPL-3.0 |
| WebRTC as built by Stream, `io.getstream:stream-webrtc-android` 1.3.9 (libwebrtc includes libyuv) | Gradle dependency | Apache-2.0 / BSD-3-Clause (WebRTC, libyuv) |
| [ZXing](https://github.com/zxing/zxing) core 3.5.3 | Gradle dependency | Apache-2.0 |
| [Outfit](https://github.com/Outfitio/Outfit-Fonts) font | `app/src/main/assets/fonts/outfit.ttf`, `mock/` | SIL Open Font License 1.1 (`FONT-LICENSE-OFL.txt`) |

AirPlay, iPhone, iPad and Mac are trademarks of Apple Inc.; this project is not affiliated with Apple.
