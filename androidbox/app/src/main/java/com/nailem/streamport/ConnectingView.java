package com.nailem.streamport;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * What a tile shows until its first picture arrives: the four-square mark pulsing in brand colours, square by
 * square, and "Connecting <name>…". The animation runs only while the view is shown, so it costs nothing once the
 * picture is up.
 */
final class ConnectingView extends LinearLayout {

    private static final int[] COLORS = {0xFF30E094, 0xFF30E0C4, 0xFFFF0E36, 0xFF3679F2}; // clockwise from top-left

    private final Mark mark;
    private final ValueAnimator pulse = ValueAnimator.ofFloat(0f, 4f);

    ConnectingView(Context c, String name) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(0xFF05070A);
        mark = new Mark(c);
        int size = Math.round(56 * getResources().getDisplayMetrics().density);
        addView(mark, new LayoutParams(size, size));
        TextView label = new TextView(c);
        label.setText(c.getString(R.string.connecting, name));
        label.setTextColor(0xB3FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setGravity(Gravity.CENTER);
        label.setPadding(24, 20, 24, 0);
        addView(label);
        pulse.setDuration(1600);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new LinearInterpolator());
        pulse.addUpdateListener(a -> mark.invalidate());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) pulse.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        pulse.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE && isAttachedToWindow()) pulse.start();
        else pulse.cancel();
    }

    private final class Mark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Mark(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float t = (Float) pulse.getAnimatedValue();
            float gap = getWidth() * 0.08f;
            float cell = (getWidth() - gap) / 2f;
            // Clockwise order: top-left, top-right, bottom-right, bottom-left.
            float[][] at = {{0, 0}, {cell + gap, 0}, {cell + gap, cell + gap}, {0, cell + gap}};
            for (int i = 0; i < 4; i++) {
                // Each square brightens as the wave passes it, then fades to a dim base.
                float d = Math.abs(((t - i) % 4 + 4) % 4 - 0.5f);
                float alpha = Math.max(0.25f, 1f - d * 0.6f);
                paint.setColor(COLORS[i]);
                paint.setAlpha(Math.round(alpha * 255));
                canvas.drawRoundRect(at[i][0], at[i][1], at[i][0] + cell, at[i][1] + cell, cell * 0.12f, cell * 0.12f, paint);
            }
        }
    }
}
