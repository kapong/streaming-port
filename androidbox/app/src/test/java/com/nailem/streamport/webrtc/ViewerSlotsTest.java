package com.nailem.streamport.webrtc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.net.InetAddress;

public class ViewerSlotsTest {

    private static InetAddress host(int last) throws Exception {
        return InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, (byte) last});
    }

    @Test
    public void perHostCapThenGlobalCap() throws Exception {
        ViewerSlots s = new ViewerSlots(6);
        for (int i = 0; i < ViewerSlots.PER_HOST; i++) assertEquals(ViewerSlots.Result.OK, s.take(host(1)));
        assertEquals(ViewerSlots.Result.HOST_FULL, s.take(host(1)));
        assertEquals(ViewerSlots.Result.OK, s.take(host(2)));
        assertEquals(ViewerSlots.Result.OK, s.take(host(3)));
        assertEquals(ViewerSlots.Result.FULL, s.take(host(4)));
    }

    @Test
    public void givingBackFreesBothCounts() throws Exception {
        ViewerSlots s = new ViewerSlots(4);
        for (int i = 0; i < 4; i++) s.take(host(1));
        assertEquals(ViewerSlots.Result.FULL, s.take(host(2)));
        s.give(host(1));
        assertEquals(ViewerSlots.Result.OK, s.take(host(2)));
        s.give(host(1));
        assertEquals(ViewerSlots.Result.OK, s.take(host(1)));
    }

    @Test
    public void unknownHostGivesNothingBack() throws Exception {
        ViewerSlots s = new ViewerSlots(1);
        s.take(host(1));
        s.give(host(9));
        assertEquals(ViewerSlots.Result.FULL, s.take(host(2)));
    }

    @Test
    public void lowerCapKeepsViewersRefusesNewOnes() throws Exception {
        ViewerSlots s = new ViewerSlots(3);
        s.take(host(1));
        s.take(host(2));
        s.setMax(1);
        assertEquals(ViewerSlots.Result.FULL, s.take(host(3)));
        s.give(host(1));
        assertEquals(ViewerSlots.Result.FULL, s.take(host(3)));
        s.give(host(2));
        assertEquals(ViewerSlots.Result.OK, s.take(host(3)));
    }
}
