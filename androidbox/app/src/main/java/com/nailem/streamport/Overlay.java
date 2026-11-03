package com.nailem.streamport;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A small card in the top-right corner of the TV over whatever is on screen (Home, an app, the tiles): the admin
 * PIN, and the share approval. Needs "draw over other apps" ({@link android.provider.Settings#canDrawOverlays}).
 * Main thread.
 */
public final class Overlay {

    private final Context context;
    private final WindowManager wm;
    private View view;

    public Overlay(Context context) {
        this.context = context.getApplicationContext();
        wm = this.context.getSystemService(WindowManager.class);
    }

    /** An empty card: dark, rounded, with a teal edge. */
    public LinearLayout card() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF00B0F14);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(2), 0xFF30E0C4);
        box.setBackground(bg);
        return box;
    }

    public TextView text(CharSequence s, int color, int sp) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    /**
     * Shows card in place of what was shown. Without keys it is not focusable, so it never takes the remote away
     * from what is underneath; with keys it takes the remote's focus while it is up.
     */
    public void show(View card, View.OnKeyListener keys) {
        hide();
        // Not FLAG_NOT_TOUCHABLE: Android 12+ caps such overlays at 0.8 alpha, so Home showed through the text.
        // Not touch-modal instead: touches outside the small card still reach what is underneath.
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (keys == null) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);
            card.setOnKeyListener(keys);
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(32);
        lp.y = dp(32);
        try {
            wm.addView(card, lp);
            view = card;
            if (keys != null) card.requestFocus();
        } catch (RuntimeException e) {
            Log.w("StreamPort", "overlay: " + e);
        }
    }

    public void hide() {
        if (view != null) {
            try {
                wm.removeView(view);
            } catch (RuntimeException ignored) {
            }
            view = null;
        }
    }

    public int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
