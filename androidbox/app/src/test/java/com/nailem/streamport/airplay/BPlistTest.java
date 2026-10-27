package com.nailem.streamport.airplay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class BPlistTest {

    @Test
    public void roundTrip() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("int", 7L);
        d.put("big", 0x1_0000_0000L);
        d.put("neg", -5L);
        d.put("str", "hello");
        d.put("utf", "สวัสดี");
        d.put("data", new byte[]{1, 2, 3});
        d.put("t", true);
        d.put("f", false);
        d.put("real", 1.5);
        d.put("list", Arrays.asList(1L, "two", Arrays.asList(3L)));
        StringBuilder longStr = new StringBuilder();
        for (int i = 0; i < 300; i++) longStr.append('x');
        d.put("long", longStr.toString());
        Map<String, Object> back = BPlist.parseDict(BPlist.write(d));
        assertEquals(7L, back.get("int"));
        assertEquals(0x1_0000_0000L, back.get("big"));
        assertEquals(-5L, back.get("neg"));
        assertEquals("hello", back.get("str"));
        assertEquals("สวัสดี", back.get("utf"));
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) back.get("data"));
        assertEquals(true, back.get("t"));
        assertEquals(false, back.get("f"));
        assertEquals(1.5, (Double) back.get("real"), 0);
        assertEquals("two", ((List<?>) back.get("list")).get(1));
        assertEquals(longStr.toString(), back.get("long"));
    }

    @Test
    public void unsigned64InSixteenByteInt() {
        // { "id": 0xFEDCBA9876543210 } as an Apple writer encodes it: a 16-byte int.
        byte[] body = {
                'b', 'p', 'l', 'i', 's', 't', '0', '0',
                (byte) 0xD1, 1, 2,
                0x52, 'i', 'd',
                0x14, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) 0xFE, (byte) 0xDC, (byte) 0xBA, (byte) 0x98, 0x76, 0x54, 0x32, 0x10,
                8, 11, 14,
                0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 31};
        Map<String, Object> d = BPlist.parseDict(body);
        assertEquals("18364758544493064720", Long.toUnsignedString((Long) d.get("id")));
    }

    @Test
    public void selfReferenceIsRejected() {
        // Array object 0 whose only element is object 0.
        byte[] body = {'b', 'p', 'l', 'i', 's', 't', '0', '0', (byte) 0xA1, 0, 8,
                0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10};
        try {
            BPlist.parse(body);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    /** Random corruption must only ever produce IllegalArgumentException, never another exception or a hang. */
    @Test
    public void fuzz() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ekey", new byte[72]);
        d.put("eiv", new byte[16]);
        d.put("name", "iPhone");
        d.put("streams", Arrays.asList(Map.of("type", 110L, "streamConnectionID", 123L)));
        byte[] good = BPlist.write(d);
        Random r = new Random(1);
        for (int i = 0; i < 200_000; i++) {
            byte[] b = good.clone();
            int flips = 1 + r.nextInt(4);
            for (int k = 0; k < flips; k++) b[r.nextInt(b.length)] = (byte) r.nextInt(256);
            if (r.nextInt(10) == 0) b = Arrays.copyOf(b, r.nextInt(b.length + 1));
            try {
                BPlist.parse(b);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
