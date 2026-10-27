package com.nailem.streamport.admin;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shows the admin PIN in the top-right corner of the TV over whatever is on screen (Home, an app, the tiles).
 * Not focusable and not touchable, so it never takes the remote away from what is underneath.
 */
public final class PinOverlay {

    private final Context context;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View view;
    private final Runnable hide = this::hideNow;

    public PinOverlay(Context context) {
        this.context = context.getApplicationContext();
        wm = this.context.getSystemService(WindowManager.class);
    }

    public void show(String pin, long ttlMs, String requester) {
        main.post(() -> {
            hideNow();
            if (!Settings.canDrawOverlays(context)) {
                Log.w("StreamPort", "admin PIN: no overlay permission (adb shell appops set "
                        + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow)");
                return;
            }
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            int p = dp(20);
            box.setPadding(p, p, p, p);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF00B0F14);
            bg.setCornerRadius(dp(16));
            bg.setStroke(dp(2), 0xFF30E0C4);
            box.setBackground(bg);
            TextView title = new TextView(context);
            title.setText("Stream Port admin PIN");
            title.setTextColor(0xB3FFFFFF);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            TextView code = new TextView(context);
            code.setText(pin.substring(0, 3) + " " + pin.substring(3));
            code.setTextColor(Color.WHITE);
            code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
            code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            TextView note = new TextView(context);
            note.setText("Asked for by " + requester + ". Valid for " + ttlMs / 1000
                    + " s.\nOnly enter it on a page you opened yourself.");
            note.setTextColor(0xFF30E0C4);
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            box.addView(title);
            box.addView(code);
            box.addView(note);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = dp(32);
            lp.y = dp(32);
            try {
                wm.addView(box, lp);
                view = box;
                main.postDelayed(hide, ttlMs);
            } catch (RuntimeException e) {
                Log.w("StreamPort", "admin PIN overlay: " + e);
            }
        });
    }

    public void hide() {
        main.post(this::hideNow);
    }

    private void hideNow() {
        main.removeCallbacks(hide);
        if (view != null) {
            try {
                wm.removeView(view);
            } catch (RuntimeException ignored) {
            }
            view = null;
        }
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
