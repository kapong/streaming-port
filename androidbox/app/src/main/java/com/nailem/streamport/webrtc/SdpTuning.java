package com.nailem.streamport.webrtc;

import com.nailem.streamport.airplay.RtspServer;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks on the WHIP offer, and edits to the answer before it goes back to the sender.
 * <p>
 * Chrome starts every connection at a few hundred kb/s and ramps up over seconds; with a large screen and "keep
 * the resolution" that meant a 7-second wait for the first picture. On a LAN the TV can tell it to start high:
 * Chrome (and Edge) read {@code x-google-start-bitrate}, {@code x-google-min-bitrate} and
 * {@code x-google-max-bitrate} (kb/s) from the remote description's video fmtp lines. Other senders ignore them.
 * The TV's own local description is left as is; these parameters only matter to the sender.
 */
final class SdpTuning {

    private static final String[] VIDEO_CODECS = {"H264", "VP8", "VP9", "AV1"};

    private SdpTuning() {
    }

    /**
     * The offer as the TV will accept it, or null to refuse it. A share is one video (and optionally one audio)
     * section: more video sections would be decoded but never shown or size-checked, and a data channel has no use
     * here. Simulcast layers are stripped. ICE candidates must be on the LAN (browsers' mDNS {@code .local} names
     * are kept), so the TV never sends connectivity checks to hosts outside it.
     */
    static String sanitizeOffer(String sdp) {
        if (sdp == null || !sdp.startsWith("v=0")) return null;
        int video = 0;
        int audio = 0;
        String eol = sdp.contains("\r\n") ? "\r\n" : "\n";
        List<String> out = new ArrayList<>();
        for (String l : sdp.split("\r?\n")) {
            if (l.startsWith("m=")) {
                if (l.startsWith("m=video")) video++;
                else if (l.startsWith("m=audio")) audio++;
                else return null; // m=application (data channels) or anything else
            }
            if (l.startsWith("a=simulcast") || l.startsWith("a=rid:")) continue;
            if (l.startsWith("a=candidate:") && !lanCandidate(l)) continue;
            out.add(l);
        }
        if (video != 1 || audio > 1) return null;
        return String.join(eol, out) + eol;
    }

    /** {@code a=candidate:<foundation> <component> <transport> <priority> <address> <port> typ ...} */
    static boolean lanCandidate(String line) {
        String[] f = line.split(" ");
        if (f.length < 8) return false;
        String address = f[4];
        if (address.endsWith(".local")) return true; // the browser's own mDNS name for a LAN address
        if (!address.matches("[0-9.]+") && !address.matches("[0-9a-fA-F:]+")) return false; // no DNS lookups
        try {
            return RtspServer.isLocal(InetAddress.getByName(address));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    static String withBitrates(String sdp, int startKbps, int minKbps, int maxKbps) {
        String params = String.format(Locale.ROOT, "x-google-start-bitrate=%d;x-google-min-bitrate=%d;x-google-max-bitrate=%d",
                startKbps, minKbps, maxKbps);
        String eol = sdp.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = sdp.split("\r?\n");
        List<String> out = new ArrayList<>(lines.length + 8);
        boolean inVideo = false;
        List<String> videoPayloads = new ArrayList<>();
        List<String> withFmtp = new ArrayList<>();
        // Two passes over each video section: first learn which payload types are video codecs and which have fmtp.
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.startsWith("m=")) {
                inVideo = l.startsWith("m=video");
                if (inVideo) {
                    videoPayloads.clear();
                    withFmtp.clear();
                    for (int j = i + 1; j < lines.length && !lines[j].startsWith("m="); j++) {
                        String pt = rtpmapCodec(lines[j]);
                        if (pt != null) videoPayloads.add(pt);
                        String f = fmtpPayload(lines[j]);
                        if (f != null) withFmtp.add(f);
                    }
                }
                out.add(l);
                continue;
            }
            if (inVideo) {
                String f = fmtpPayload(l);
                if (f != null && videoPayloads.contains(f) && !l.contains("x-google-")) {
                    out.add(l + ";" + params);
                    continue;
                }
                out.add(l);
                String pt = rtpmapCodec(l);
                if (pt != null && !withFmtp.contains(pt)) {
                    out.add("a=fmtp:" + pt + " " + params);
                }
                continue;
            }
            out.add(l);
        }
        return String.join(eol, out) + eol;
    }

    /** The payload type of an {@code a=rtpmap} line for one of our video codecs, else null. */
    private static String rtpmapCodec(String line) {
        if (!line.startsWith("a=rtpmap:")) return null;
        int sp = line.indexOf(' ');
        if (sp < 0) return null;
        String codec = line.substring(sp + 1);
        for (String c : VIDEO_CODECS) {
            if (codec.regionMatches(true, 0, c + "/", 0, c.length() + 1)) return line.substring(9, sp);
        }
        return null;
    }

    private static String fmtpPayload(String line) {
        if (!line.startsWith("a=fmtp:")) return null;
        int sp = line.indexOf(' ');
        return sp < 0 ? null : line.substring(7, sp);
    }
}
