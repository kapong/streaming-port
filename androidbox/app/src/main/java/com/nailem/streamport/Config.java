package com.nailem.streamport;

import android.content.Context;
import android.content.SharedPreferences;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;
import android.util.Range;

import com.nailem.streamport.airplay.Limits;

import java.security.SecureRandom;
import java.util.Locale;

/** Receiver settings, changed over adb through {@link SettingsReceiver}. */
final class Config {

    private Config() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    /**
     * The name iPhones list under Screen Mirroring. Until someone renames it, the box's own device name (the one
     * Chromecast shows), then its model, so a new box needs no setup to tell it apart from the others.
     */
    static String name(Context c) {
        String n = prefs(c).getString("name", null);
        if (n != null) return n;
        for (String d : new String[] {BoxName.get(c), android.os.Build.MODEL}) {
            d = cleanName(d == null ? "" : d);
            if (fits(d)) return d;
        }
        return c.getString(R.string.default_receiver_name);
    }

    // mDNS labels are 63 bytes and the AirTunes record adds "XXXXXXXXXXXX@" (13); Thai is 3 bytes a letter.
    private static boolean fits(String n) {
        return !n.isEmpty() && n.length() <= 40 && n.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 50;
    }

    /**
     * Ask on the TV before showing a sender that hasn't been approved since the receiver started. Off by default
     * (the lab wants senders to appear at once): anyone on the TV's Wi-Fi can then show their screen.
     */
    static boolean ask(Context c) {
        return prefs(c).getBoolean("ask", false);
    }

    /**
     * Rebroadcast defaults: light enough next to four 720p screens (measured 2026-10-27: CPU 78% at 12 fps, 79% at
     * 15). It is for presentations, so 12 fps is plenty (owner's call: anything over 10 is fine).
     */
    static final int REBROADCAST_HEIGHT = 720;
    static final int REBROADCAST_FPS = 12;

    /**
     * Rebroadcast what the TV shows to the LAN (UDP multicast, for VLC) while someone is sharing. Off by default:
     * when on, anyone on the network can watch whatever is shared.
     */
    static boolean rebroadcast(Context c) {
        return prefs(c).getBoolean("rebroadcast", false);
    }

    /** The rebroadcast's height: 720 or 1080 (16:9). */
    static int rebroadcastHeight(Context c) {
        return prefs(c).getInt("rebroadcast_res", REBROADCAST_HEIGHT);
    }

    /** The rebroadcast's frame rate, 5 to 30. */
    static int rebroadcastFps(Context c) {
        return prefs(c).getInt("rebroadcast_fps", REBROADCAST_FPS);
    }

    /** Pushes the stored resolution and stream limits into {@link Limits}. */
    static void applyLimits(Context c) {
        SharedPreferences p = prefs(c);
        Limits.Resolution r;
        try {
            r = Limits.Resolution.parse(p.getString("max_res", "1080p"));
        } catch (IllegalArgumentException e) {
            r = Limits.Resolution.P1080;
        }
        int streams = Math.max(Limits.MIN_STREAMS, Math.min(Limits.maxStreamsFor(r), p.getInt("max_streams", 4)));
        Limits.set(r, streams, portraitHeight(r));
        Log.i("StreamPort", "limits: " + r.key + ", " + streams + " streams, portrait up to "
                + Limits.resolution().height + "x" + portraitHeight(r));
    }

    /**
     * The tallest portrait picture the H.264 decoder takes at the resolution's short side (720, 1080, 1440 or
     * 2160 wide). Some decoders list only landscape sizes, e.g. 3840x2160 but not 2160x3840.
     */
    static int portraitHeight(Limits.Resolution r) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String name = list.findDecoderForFormat(
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, r.height, r.height));
        if (name == null) return r.height;
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.getName().equals(name)) continue;
            MediaCodecInfo.VideoCapabilities v =
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities();
            if (v.isSizeSupported(r.height, r.width)) return r.width;
            try {
                Range<Integer> heights = v.getSupportedHeightsFor(r.height);
                return Math.max(r.height, Math.min(r.width, heights.getUpper()));
            } catch (IllegalArgumentException e) {
                return r.height;
            }
        }
        return r.height;
    }

    static void set(Context c, String key, String value) {
        SharedPreferences.Editor e = prefs(c).edit();
        switch (key) {
            case "name":
                String n = cleanName(value);
                if (!fits(n)) {
                    throw new IllegalArgumentException("name: 1 to 40 characters (about 16 in Thai)");
                }
                e.putString("name", n);
                break;
            case "ask":
                e.putBoolean("ask", Boolean.parseBoolean(value));
                break;
            case "rebroadcast":
                e.putBoolean("rebroadcast", Boolean.parseBoolean(value));
                break;
            case "rebroadcast_res":
                if (!value.trim().equals("720p") && !value.trim().equals("1080p")) {
                    throw new IllegalArgumentException("rebroadcast_res: 720p or 1080p");
                }
                e.putInt("rebroadcast_res", value.trim().equals("720p") ? 720 : 1080);
                break;
            case "rebroadcast_fps": {
                int f;
                try {
                    f = Integer.parseInt(value.trim());
                } catch (NumberFormatException ex) {
                    f = -1;
                }
                if (f < 5 || f > 30) throw new IllegalArgumentException("rebroadcast_fps: a number from 5 to 30");
                e.putInt("rebroadcast_fps", f);
                break;
            }
            case "max_res":
                e.putString("max_res", Limits.Resolution.parse(value).key);
                break;
            case "max_streams": {
                int count;
                try {
                    count = Integer.parseInt(value.trim());
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("max_streams: a number from 1 to 8");
                }
                if (count < Limits.MIN_STREAMS || count > Limits.MAX_STREAMS) {
                    throw new IllegalArgumentException("max_streams: a number from 1 to 8");
                }
                e.putInt("max_streams", count);
                break;
            }
            default:
                throw new IllegalArgumentException("unknown key " + key);
        }
        e.apply();
    }

    /**
     * A name fit for an mDNS service: printable characters only (no control or invisible formatting characters,
     * which could spoof another receiver in a sender's list), trimmed.
     */
    static String cleanName(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT) sb.append(c);
        }
        return sb.toString().trim();
    }

    /**
     * AirPlay wants a MAC-style device ID. Android 11 hides the real MAC from apps, so the receiver makes one up
     * (locally administered, unicast) and keeps it, which keeps the TV's identity stable for senders.
     */
    static String deviceId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("device_id", null);
        if (id == null) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            b[0] = (byte) ((b[0] & 0xFC) | 0x02);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.ROOT, "%02X", b[i]));
            }
            id = sb.toString();
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }
}
