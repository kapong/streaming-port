package com.nailem.streamport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * {@code adb shell am broadcast -n com.nailem.streamport/.SettingsReceiver -a com.nailem.streamport.SET
 * --es key name --es value "Meeting Room"}. Keys: {@code name}, {@code ask} (true/false), {@code max_res} (720p, 1080p, 1440p, 4k),
 * {@code max_streams} (1 to 8), {@code rebroadcast} (true/false),
 * {@code rebroadcast_res} (720p, 1080p), {@code rebroadcast_fps} (5 to 30). The manifest guards it with
 * DUMP, which only the shell and the system hold. A running receiver applies the change in place (senders stay
 * connected); one that isn't running starts with it. If Android won't let a background app start it now, the
 * setting is stored, the result says so, and the receiver picks it up at its next start.
 */
public final class SettingsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String key = intent.getStringExtra("key");
        String value = intent.getStringExtra("value");
        try {
            if (key == null || value == null) throw new IllegalArgumentException("need --es key and --es value");
            Config.set(context, key, value);
            if (key.equals("name")) BoxName.set(context, Config.name(context));
            boolean applied = StreamService.restart(context);
            setResultData(applied ? "ok " + key : "ok " + key + " (stored; applies when the receiver next starts)");
        } catch (IllegalArgumentException e) {
            Log.w("StreamPort", "settings: " + e.getMessage());
            setResultData("error: " + e.getMessage());
        }
    }
}
