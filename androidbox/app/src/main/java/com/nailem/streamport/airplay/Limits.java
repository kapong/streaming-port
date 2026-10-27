package com.nailem.streamport.airplay;

import java.util.Locale;

/**
 * The receiver's configurable limits: the largest picture senders may send and how many may mirror at once.
 * Set from {@code Config} at start and from the admin page; read by every connection.
 */
public final class Limits {

    public enum Resolution {
        P720("720p", 1280, 720),
        P1080("1080p", 1920, 1080),
        P1440("1440p", 2560, 1440),
        P2160("4k", 3840, 2160);

        public final String key;
        public final int width;
        public final int height;

        Resolution(String key, int width, int height) {
            this.key = key;
            this.width = width;
            this.height = height;
        }

        public static Resolution parse(String key) {
            for (Resolution r : values()) {
                if (r.key.equals(key.toLowerCase(Locale.ROOT))) return r;
            }
            throw new IllegalArgumentException("resolution: 720p, 1080p, 1440p or 4k");
        }
    }

    public static final int MIN_STREAMS = 1;
    /** The box has nine hardware H.264 decoder instances; past eight the layout and the decoder budget suffer. */
    public static final int MAX_STREAMS = 8;
    public static final int FPS = 30;

    private static volatile Resolution resolution = Resolution.P1080;
    private static volatile int maxStreams = 4;
    /** Tallest portrait picture the decoder takes at this resolution; set by the app from MediaCodec. */
    private static volatile int portraitHeight = 1920;

    private Limits() {
    }

    public static Resolution resolution() {
        return resolution;
    }

    public static int maxStreams() {
        return maxStreams;
    }

    /**
     * Most screens allowed at each resolution. Per sender the receiver may buffer about five of its largest
     * packets (packet, keyframe cache, decoder queue): about 40 MB at 4K, so four 4K senders stay near 160 MB,
     * inside the app's 256 MB heap. The decoder is the tighter limit in practice: it plays about one 4K stream
     * (or four 1080p) in real time, so several senders really sending 4K at once will stutter (owner's choice).
     */
    public static int maxStreamsFor(Resolution r) {
        switch (r) {
            case P2160:
            case P1440:
                return 4;
            default:
                return MAX_STREAMS;
        }
    }

    /** {@code portraitHeight}: the decoder's largest height at a width of the resolution's height. */
    public static void set(Resolution r, int streams, int portraitHeight) {
        if (streams < MIN_STREAMS || streams > maxStreamsFor(r)) {
            throw new IllegalArgumentException("at " + r.key + " at most " + maxStreamsFor(r) + " screens");
        }
        resolution = r;
        maxStreams = streams;
        Limits.portraitHeight = Math.max(r.height, Math.min(r.width, portraitHeight));
    }

    /**
     * The display offered in /info. Computers adopt it as their resolution, so they get the plain 16:9 size.
     * Phones and tablets fit inside it, so they get a square box whose side lets portrait reach the full height.
     */
    static int[] display(boolean computer) {
        Resolution r = resolution;
        if (computer) return new int[]{r.width, r.height};
        int side = portraitHeight;
        return new int[]{side, side};
    }

    /** Within the limit either way up; 16:10 laptops (1920x1200 at 1080p) included. */
    public static boolean sizeAllowed(Resolution r, int w, int h) {
        return w > 0 && h > 0 && Math.max(w, h) <= r.width && (long) w * h <= (long) r.width * r.height * 5 / 4;
    }

    /** Largest mirroring packet: about 2 MB per 1080p of pixels (a keyframe is usually far smaller). */
    public static int maxPacket(Resolution r) {
        return (int) (2L * 1024 * 1024 * r.width * r.height / (1920 * 1080));
    }
}
