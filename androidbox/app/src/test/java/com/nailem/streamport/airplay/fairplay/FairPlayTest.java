package com.nailem.streamport.airplay.fairplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Uses the legacy-sender fixture from java-airplay's client (a recorded fp-setup exchange and its ekey).
 * tools/test_sender.py hardcodes the key printed here.
 */
public class FairPlayTest {

    static final byte[] FIXTURE_EKEY = {
            70, 80, 76, 89, 1, 2, 1, 0, 0, 0, 0, 60, 0, 0, 0, 0, 63, 121, 70, -69, 3, -8, 117, -13, 83, 72,
            105, -51, -11, -43, -1, 17, 0, 0, 0, 16, 24, -109, 13, 105, -32, -125, -73, -128, 21, 29, -31, 72,
            -41, 112, -36, -75, 57, 110, 71, -72, -25, -59, 102, 22, 19, -43, 35, 74, -20, 86, 15, 16, 126, 5,
            15, -45};
    static final byte[] FP_SETUP_1 = {70, 80, 76, 89, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, -69};
    static final byte[] FP_SETUP_2 = {
            70, 80, 76, 89, 3, 1, 3, 0, 0, 0, 0, -104, 0, -113, 26, -100, -40, -92, -10, 52, 109, 20, 120, 6,
            -62, -67, -118, 75, -47, -71, -109, -45, -61, 106, -95, 1, 36, -104, -7, 78, -1, -13, 70, 123,
            -49, 27, 49, -104, 98, 92, -94, 69, -114, 62, -48, 30, -35, 53, -25, 41, 53, 125, -7, 75, -128,
            -51, 10, -50, 35, 84, -42, -116, -29, 127, 94, 24, -16, -49, -46, 109, 65, 103, 21, 63, -64, -76,
            54, 35, 22, 111, 8, -58, 111, -45, 1, 56, 14, -80, -98, -97, -115, -24, 59, -46, -82, -57, -92,
            1, -15, -5, -67, -13, 46, 10, -43, 81, -24, 121, 63, -25, -63, 25, 35, 51, -103, -91, 53, 76,
            -59, 67, 7, 30, -68, -50, -32, -84, -123, 34, -82, 27, -85, 51, -44, 65, -60, 120, -11, 99, -50,
            -3, 66, 117, -5, 85, 90, 58, -29, 58, -40, -71, -7, -108, -7, -75};

    @Test
    public void fixtureKey() {
        FairPlay fp = new FairPlay();
        assertEquals(142, fp.setup(FP_SETUP_1).length);
        assertEquals(32, fp.setup(FP_SETUP_2).length);
        byte[] key = fp.decryptAesKey(FIXTURE_EKEY);
        StringBuilder sb = new StringBuilder();
        for (byte b : key) sb.append(String.format("%02x", b));
        System.out.println("FIXTURE_AES_KEY=" + sb);
    }

    @Test
    public void hostileSetupIsRefused() {
        FairPlay fp = new FairPlay();
        byte[] bad = FP_SETUP_1.clone();
        bad[14] = 9; // mode index the original code used unchecked
        assertNull(fp.setup(bad));
        bad[14] = -1;
        assertNull(fp.setup(bad));
        assertNull(fp.setup(new byte[3]));
        assertNull(fp.setup(new byte[100]));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shortEkeyIsRefused() {
        FairPlay fp = new FairPlay();
        fp.setup(FP_SETUP_1);
        fp.setup(FP_SETUP_2);
        fp.decryptAesKey(new byte[10]);
    }
}
