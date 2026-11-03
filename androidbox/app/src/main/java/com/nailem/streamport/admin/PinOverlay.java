package com.nailem.streamport.admin;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nailem.streamport.Overlay;

/**
 * Shows the admin PIN in the top-right corner of the TV over whatever is on screen (Home, an app, the tiles).
 * Not focusable and not touchable, so it never takes the remote away from what is underneath.
 */
public final class PinOverlay {

    private final Overlay overlay;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hideNow;

    public PinOverlay(Context context) {
        overlay = new Overlay(context);
    }

    public void show(String pin, long ttlMs, String requester) {
        main.post(() -> {
            hideNow();
            LinearLayout box = overlay.card();
            TextView code = overlay.text(pin.substring(0, 3) + " " + pin.substring(3), Color.WHITE, 48);
            code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            box.addView(overlay.text("Stream Port admin PIN", 0xB3FFFFFF, 16));
            box.addView(code);
            box.addView(overlay.text("Asked for by " + requester + ". Valid for " + ttlMs / 1000
                    + " s.\nOnly enter it on a page you opened yourself.", 0xFF30E0C4, 14));
            overlay.show(box, null);
            main.postDelayed(hide, ttlMs);
        });
    }

    public void hide() {
        main.post(this::hideNow);
    }

    private void hideNow() {
        main.removeCallbacks(hide);
        overlay.hide();
    }
}
