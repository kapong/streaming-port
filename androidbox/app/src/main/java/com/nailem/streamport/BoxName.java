package com.nailem.streamport;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * The box's own name, which Chromecast (and Miracast, from its next start) announce. Stream Port keeps it equal
 * to the AirPlay name, so the TV shows up under one name in every list.
 * <p>
 * Writing it needs WRITE_SECURE_SETTINGS, which an app can't get from the user but adb can grant once:
 * {@code adb shell pm grant com.nailem.streamport android.permission.WRITE_SECURE_SETTINGS}.
 */
final class BoxName {

    private BoxName() {
    }

    static boolean canWrite(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static String get(Context c) {
        String n = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
        return n != null ? n : "";
    }

    /** Returns true when the box name now matches {@code name}. */
    static boolean set(Context c, String name) {
        if (name.equals(get(c))) return true;
        if (!canWrite(c)) {
            Log.i("StreamPort", "Chromecast/Miracast name not changed: WRITE_SECURE_SETTINGS not granted");
            return false;
        }
        boolean ok = Settings.Global.putString(c.getContentResolver(), Settings.Global.DEVICE_NAME, name);
        Log.i("StreamPort", "box name (Chromecast, Miracast) " + (ok ? "set to " + name : "not changed"));
        return ok;
    }
}
