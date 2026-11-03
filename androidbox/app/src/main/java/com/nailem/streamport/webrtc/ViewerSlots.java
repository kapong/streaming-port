package com.nailem.streamport.webrtc;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Places for WHEP viewers: at most {@code max} in all (each costs uplink and a share of the box's CPU), and
 * {@link #PER_HOST} per address, so one device can't take every place. A place is taken before the viewer's
 * PeerConnection is made and given back when it closes.
 */
final class ViewerSlots {

    enum Result { OK, FULL, HOST_FULL }

    static final int PER_HOST = 4;

    private final Map<InetAddress, Integer> perHost = new HashMap<>();
    private int used;
    private int max;

    ViewerSlots(int max) {
        this.max = max;
    }

    /** Viewers already in keep their place; the new cap applies to the next ones. */
    synchronized void setMax(int max) {
        this.max = max;
    }

    synchronized int max() {
        return max;
    }

    synchronized Result take(InetAddress host) {
        int mine = perHost.getOrDefault(host, 0);
        if (mine >= PER_HOST) return Result.HOST_FULL;
        if (used >= max) return Result.FULL;
        perHost.put(host, mine + 1);
        used++;
        return Result.OK;
    }

    /** Once per successful {@link #take}. */
    synchronized void give(InetAddress host) {
        Integer mine = perHost.get(host);
        if (mine == null) return;
        if (mine <= 1) perHost.remove(host);
        else perHost.put(host, mine - 1);
        used--;
    }
}
