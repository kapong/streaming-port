package com.nailem.streamport.rebroadcast;

import java.io.ByteArrayOutputStream;

/**
 * A minimal MPEG transport stream writer for one H.264 video stream, which is what VLC (and ffmpeg) expect on a
 * {@code udp://@group:port} address. Android's MediaMuxer can't write MPEG-TS, hence this.
 * <p>
 * Program 1, PMT on PID 0x1000, video on PID 0x100 (stream type 0x1B, H.264), which also carries the PCR. PAT and
 * PMT go out before every keyframe and at least every half second, so a viewer can tune in at any moment. Each frame
 * is one PES packet with a PTS; the first TS packet of a frame carries a PCR 100 ms behind it.
 */
public final class TsMuxer {

    public interface Sink {
        /** Whole 188-byte TS packets, in order. */
        void onPacket(byte[] packet188);
    }

    static final int PACKET = 188;
    static final int PID_PAT = 0x0000;
    static final int PID_PMT = 0x1000;
    static final int PID_VIDEO = 0x0100;
    private static final long PSI_EVERY_90K = 45_000; // half a second
    private static final byte[] AUD = {0, 0, 0, 1, 9, (byte) 0xF0};

    private final Sink sink;
    private final int[] continuity = new int[0x2000];
    private long lastPsi = Long.MIN_VALUE;

    public TsMuxer(Sink sink) {
        this.sink = sink;
    }

    /**
     * Writes one access unit. {@code annexB} must start with a start code; keyframes must already carry SPS and
     * PPS (the caller prepends them). {@code pts90k} is the presentation time on the 90 kHz clock.
     */
    public void writeFrame(byte[] annexB, int offset, int length, long pts90k, boolean keyframe) {
        if (keyframe || pts90k - lastPsi >= PSI_EVERY_90K || lastPsi == Long.MIN_VALUE) {
            sink.onPacket(section(PID_PAT, pat()));
            sink.onPacket(section(PID_PMT, pmt()));
            lastPsi = pts90k;
        }
        byte[] pes = pes(annexB, offset, length, pts90k);
        long pcr = Math.max(0, pts90k - 9_000) * 300; // 27 MHz, 100 ms ahead of display
        int pos = 0;
        boolean first = true;
        while (pos < pes.length) {
            byte[] p = new byte[PACKET];
            p[0] = 0x47;
            p[1] = (byte) ((first ? 0x40 : 0) | (PID_VIDEO >> 8));
            p[2] = (byte) PID_VIDEO;
            int remaining = pes.length - pos;
            int header = 4;
            int adaptationLen = -1; // -1: no adaptation field
            if (first) adaptationLen = 7; // flags + 6-byte PCR
            int room = PACKET - header - (adaptationLen >= 0 ? adaptationLen + 1 : 0);
            if (remaining < room) {
                // Pad the last packet with adaptation-field stuffing.
                int pad = room - remaining;
                adaptationLen = adaptationLen >= 0 ? adaptationLen + pad : pad - 1;
                room = remaining;
            }
            int cc = continuity[PID_VIDEO];
            continuity[PID_VIDEO] = (cc + 1) & 0xF;
            p[3] = (byte) ((adaptationLen >= 0 ? 0x30 : 0x10) | cc);
            int at = 4;
            if (adaptationLen >= 0) {
                p[at++] = (byte) adaptationLen;
                if (adaptationLen > 0) {
                    int flagsAt = at++;
                    p[flagsAt] = 0;
                    int used = 1;
                    if (first) {
                        p[flagsAt] = (byte) (keyframe ? 0x50 : 0x10); // random access + PCR
                        long base = pcr / 300;
                        long ext = pcr % 300;
                        p[at++] = (byte) (base >> 25);
                        p[at++] = (byte) (base >> 17);
                        p[at++] = (byte) (base >> 9);
                        p[at++] = (byte) (base >> 1);
                        p[at++] = (byte) (((base & 1) << 7) | 0x7E | (ext >> 8));
                        p[at++] = (byte) ext;
                        used += 6;
                    }
                    for (int i = used; i < adaptationLen; i++) p[at++] = (byte) 0xFF;
                }
            }
            System.arraycopy(pes, pos, p, at, room);
            pos += room;
            first = false;
            sink.onPacket(p);
        }
    }

    /** PES with an access unit delimiter in front (some players want one at the start of every picture). */
    private static byte[] pes(byte[] es, int offset, int length, long pts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(length + 32);
        b.write(0);
        b.write(0);
        b.write(1);
        b.write(0xE0); // video stream 0
        b.write(0);
        b.write(0); // length 0: unbounded, allowed for video
        b.write(0x80); // '10' marker, no scrambling
        b.write(0x80); // PTS only
        b.write(5);
        b.write((int) (0x21 | ((pts >> 29) & 0x0E)));
        b.write((int) (pts >> 22));
        b.write((int) (0x01 | ((pts >> 14) & 0xFE)));
        b.write((int) (pts >> 7));
        b.write((int) (0x01 | ((pts << 1) & 0xFE)));
        b.write(AUD, 0, AUD.length);
        b.write(es, offset, length);
        return b.toByteArray();
    }

    private static byte[] pat() {
        return new byte[]{
                0x00, // table_id
                (byte) 0xB0, 13, // syntax, section_length
                0x00, 0x01, // transport_stream_id
                (byte) 0xC1, 0x00, 0x00, // version 0, current, section 0 of 0
                0x00, 0x01, // program 1
                (byte) (0xE0 | (PID_PMT >> 8)), (byte) PID_PMT,
        };
    }

    private static byte[] pmt() {
        return new byte[]{
                0x02, // table_id
                (byte) 0xB0, 18,
                0x00, 0x01, // program 1
                (byte) 0xC1, 0x00, 0x00,
                (byte) (0xE0 | (PID_VIDEO >> 8)), (byte) PID_VIDEO, // PCR PID
                (byte) 0xF0, 0x00, // no program descriptors
                0x1B, (byte) (0xE0 | (PID_VIDEO >> 8)), (byte) PID_VIDEO, (byte) 0xF0, 0x00, // H.264
        };
    }

    /** One PSI section (with CRC) in one TS packet, padded with 0xFF. */
    private byte[] section(int pid, byte[] body) {
        byte[] p = new byte[PACKET];
        java.util.Arrays.fill(p, (byte) 0xFF);
        p[0] = 0x47;
        p[1] = (byte) (0x40 | (pid >> 8));
        p[2] = (byte) pid;
        int cc = continuity[pid];
        continuity[pid] = (cc + 1) & 0xF;
        p[3] = (byte) (0x10 | cc);
        p[4] = 0; // pointer field
        System.arraycopy(body, 0, p, 5, body.length);
        int crc = crc32(body, 0, body.length);
        int at = 5 + body.length;
        p[at] = (byte) (crc >>> 24);
        p[at + 1] = (byte) (crc >>> 16);
        p[at + 2] = (byte) (crc >>> 8);
        p[at + 3] = (byte) crc;
        return p;
    }

    /** CRC-32/MPEG-2: polynomial 0x04C11DB7, not reflected, initial 0xFFFFFFFF, no final XOR. */
    static int crc32(byte[] data, int off, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF) << 24;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
        }
        return crc;
    }
}
