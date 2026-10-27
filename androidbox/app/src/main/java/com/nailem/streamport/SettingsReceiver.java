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
 * DUMP, which only the shell and the system hold. The receiver restarts to apply the change.
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
            setResultData("ok " + key);
            StreamService.restart(context);
        } catch (IllegalArgumentException e) {
            Log.w("StreamPort", "settings: " + e.getMessage());
            setResultData("error: " + e.getMessage());
        }
    }
}
