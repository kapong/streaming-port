package com.nailem.streamport.airplay;

/**
 * How well one sender's video is getting through: frame rate, bitrate, and delivery jitter.
 * <p>
 * Jitter uses the timestamp the sender stamps on every mirroring packet (NTP format: seconds in the high 32
 * bits, fraction in the low 32). Transit = arrival − send time includes the unknown clock offset, but over a
 * few seconds the spread (90th percentile − minimum) is how much later than its best case a packet arrives:
 * Wi-Fi retries, congestion, or a sender far from the access point. A still screen sends few packets; that is
 * reported as idle, not as a bad network.
 */
public final class NetStats {

    public enum Quality { GOOD, FAIR, POOR, IDLE }

    /** A snapshot for the screen. */
    public static final class Snapshot {
        public final Quality quality;
        public final float fps;
        public final float mbps;
        public final int jitterMs;

        public static Snapshot idle() {
            return new Snapshot(Quality.IDLE, 0, 0, 0);
        }

        public Snapshot(Quality quality, float fps, float mbps, int jitterMs) {
            this.quality = quality;
            this.fps = fps;
            this.mbps = mbps;
            this.jitterMs = jitterMs;
        }
    }

    private static final int WINDOW = 256;
    private static final long WINDOW_US = 3_000_000;
    private static final int GOOD_MS = 40;
    private static final int FAIR_MS = 120;

    private final long[] arrival = new long[WINDOW];
    private final long[] transit = new long[WINDOW];
    private final int[] bytes = new int[WINDOW];
    private final boolean[] frame = new boolean[WINDOW];
    private int next;
    private int count;

    /** Called by the video thread for every mirroring packet. */
    synchronized void onPacket(long ntpTimestamp, int size, boolean isFrame) {
        long now = System.nanoTime() / 1000;
        long sent = ntpToMicros(ntpTimestamp);
        arrival[next] = now;
        transit[next] = sent == 0 ? Long.MIN_VALUE : now - sent;
        bytes[next] = size;
        frame[next] = isFrame;
        next = (next + 1) % WINDOW;
        if (count < WINDOW) count++;
    }

    public synchronized Snapshot snapshot() {
        long now = System.nanoTime() / 1000;
        int frames = 0;
        long total = 0;
        long oldest = now;
        long[] t = new long[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (now - arrival[i] > WINDOW_US) continue;
            oldest = Math.min(oldest, arrival[i]);
            total += bytes[i];
            if (frame[i]) frames++;
            if (transit[i] != Long.MIN_VALUE) t[n++] = transit[i];
        }
        float seconds = WINDOW_US / 1e6f;
        float fps = frames / seconds;
        float mbps = total * 8 / seconds / 1e6f;
        if (n < 8) {
            return new Snapshot(Quality.IDLE, fps, mbps, 0);
        }
        java.util.Arrays.sort(t, 0, n);
        int jitterMs = (int) ((t[(int) (n * 0.9)] - t[0]) / 1000);
        Quality q = jitterMs <= GOOD_MS ? Quality.GOOD : jitterMs <= FAIR_MS ? Quality.FAIR : Quality.POOR;
        return new Snapshot(q, fps, mbps, jitterMs);
    }

    static long ntpToMicros(long ntp) {
        long seconds = ntp >>> 32;
        long fraction = ntp & 0xFFFFFFFFL;
        return seconds * 1_000_000L + (fraction * 1_000_000L >>> 32);
    }
}
