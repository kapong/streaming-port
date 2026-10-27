package com.nailem.streamport.rebroadcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TsMuxerTest {

    @Test
    public void crcIsMpeg2() {
        byte[] check = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x0376E6E7, TsMuxer.crc32(check, 0, check.length));
    }

    /** Every packet 188 bytes with a sync byte; PSI before keyframes; continuity counters step by one per PID. */
    @Test
    public void packetsAreWellFormed() {
        List<byte[]> packets = new ArrayList<>();
        TsMuxer m = new TsMuxer(packets::add);
        Random r = new Random(7);
        int[] sizes = {1, 150, 176, 177, 183, 184, 185, 400, 5000, 60000};
        long pts = 90_000;
        for (int i = 0; i < sizes.length; i++) {
            byte[] au = new byte[sizes[i] + 4];
            r.nextBytes(au);
            au[0] = 0; au[1] = 0; au[2] = 0; au[3] = 1;
            m.writeFrame(au, 0, au.length, pts, i % 4 == 0);
            pts += 3000;
        }
        int[] lastCc = new int[0x2000];
        java.util.Arrays.fill(lastCc, -1);
        int pat = 0;
        for (byte[] p : packets) {
            assertEquals(188, p.length);
            assertEquals(0x47, p[0] & 0xFF);
            int pid = ((p[1] & 0x1F) << 8) | (p[2] & 0xFF);
            int cc = p[3] & 0x0F;
            if (lastCc[pid] >= 0) assertEquals("pid " + pid, (lastCc[pid] + 1) & 0xF, cc);
            lastCc[pid] = cc;
            if (pid == TsMuxer.PID_PAT) {
                pat++;
                // section CRC over table bytes (after pointer field) must check out
                int len = ((p[6] & 0x0F) << 8) | (p[7] & 0xFF);
                int crc = TsMuxer.crc32(p, 5, 3 + len - 4);
                int stored = ((p[5 + 3 + len - 4] & 0xFF) << 24) | ((p[5 + 3 + len - 3] & 0xFF) << 16)
                        | ((p[5 + 3 + len - 2] & 0xFF) << 8) | (p[5 + 3 + len - 1] & 0xFF);
                assertEquals(crc, stored);
            }
            if (pid == TsMuxer.PID_VIDEO && (p[3] & 0x20) != 0) {
                int afl = p[4] & 0xFF;
                assertTrue("adaptation field fits", afl <= 183);
            }
        }
        assertTrue("PAT before every keyframe at least", pat >= 3);
    }
}
