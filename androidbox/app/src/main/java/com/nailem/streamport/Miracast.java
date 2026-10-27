package com.nailem.streamport;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.nailem.streamport.airplay.Hub;

/**
 * Hands the TV to Xiaomi's Miracast receiver (the only app here allowed to be a Wi-Fi Display sink). Everyone on
 * AirPlay is disconnected first: Miracast takes the whole screen.
 * <p>
 * While Miracast is open the TV leaves the Wi-Fi network entirely (its radio runs Wi-Fi Direct), so AirPlay,
 * Chromecast, the admin page and adb are all unreachable until someone presses BACK on the remote. Afterwards the
 * TV doesn't always rejoin by itself; then it has to be reconnected in Settings > Network.
 */
final class Miracast {

    static final String ACTION = "com.xiaomi.mitv.MIRACAST";

    private Miracast() {
    }

    static boolean available(Context c) {
        return new Intent(ACTION).resolveActivity(c.getPackageManager()) != null;
    }

    static boolean open(Context c) {
        if (!available(c)) return false;
        Hub.get().disconnectAll();
        try {
            c.startActivity(new Intent(ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Log.i("StreamPort", "opened Miracast; AirPlay senders disconnected");
            return true;
        } catch (RuntimeException e) {
            Log.w("StreamPort", "Miracast did not open: " + e);
            return false;
        }
    }
}
