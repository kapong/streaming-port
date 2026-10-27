package com.nailem.streamport;

import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;

import com.nailem.streamport.airplay.NetStats;
import com.nailem.streamport.airplay.Screen;

import java.util.Locale;

/**
 * One sender on screen: its video, a label with the sender's name and network quality, a ring on the primary
 * screen, and while it waits for approval an opaque card that hides the picture.
 * <p>
 * Video goes to a TextureView, not a SurfaceView: on this Amlogic box a SurfaceView decoder output lands on the
 * hardware video plane, which there are too few of for four streams and which screenshots can't read. With
 * TextureView the GPU composes every tile the same way, like the launcher's video background.
 */
final class TileView extends FrameLayout implements TextureView.SurfaceTextureListener, Screen.SizeListener {

    interface Listener {
        void onTileSizeChanged(TileView tile);
    }

    final Screen screen;
    private final Listener listener;
    private final TextureView video;
    private final TextView label;
    private final View ring;
    private final LinearLayout pendingCard;
    private final ConnectingView connecting;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Screen.VideoOutput output;
    private int videoW;
    private int videoH;

    TileView(Context c, Screen screen, Listener listener) {
        super(c);
        this.screen = screen;
        this.listener = listener;
        setBackgroundColor(Color.BLACK);

        video = new TextureView(c);
        video.setSurfaceTextureListener(this);
        video.setOpaque(true);
        addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Above the video, below the label: shown until the first picture arrives.
        connecting = new ConnectingView(c, screen.name());
        addView(connecting, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        label = new TextView(c);
        label.setTextColor(0xD9FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // Quiet on purpose: it says whose screen this is without pulling the eye away from the screen itself.
        label.setBackgroundResource(R.drawable.tile_label_bg);
        label.setPadding(dp(8), dp(3), dp(8), dp(3));
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(8), 0, 0, dp(8));
        addView(label, lp);

        ring = new View(c);
        ring.setBackgroundResource(R.drawable.focus_ring);
        ring.setVisibility(INVISIBLE);
        addView(ring, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        pendingCard = new LinearLayout(c);
        pendingCard.setOrientation(LinearLayout.VERTICAL);
        pendingCard.setGravity(Gravity.CENTER);
        pendingCard.setBackgroundColor(0xFF0B0F14);
        TextView title = new TextView(c);
        title.setText(c.getString(R.string.pending_title, screen.name()));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(24), 0, dp(24), dp(12));
        TextView keys = new TextView(c);
        keys.setText(R.string.pending_keys);
        keys.setTextColor(0xFF30E0C4);
        keys.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        pendingCard.addView(title);
        pendingCard.addView(keys);
        addView(pendingCard, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        update();
        updateStats();
    }

    /**
     * One small line: a dot coloured by delivery quality (green good, amber fair, red poor, grey idle), the
     * sender's name, and dimmed numbers when the tile is wide enough. The admin page has the full detail.
     */
    void updateStats() {
        NetStats.Snapshot st = screen.stats();
        int color;
        switch (st.quality) {
            case GOOD: color = 0xFF30E094; break;
            case FAIR: color = 0xFFF2C230; break;
            case POOR: color = 0xFFFF0E36; break;
            default: color = 0xFF8A96A6; break;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder("\u25CF ");
        sb.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(0.8f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(screen.name());
        if (st.quality != NetStats.Quality.IDLE && getWidth() >= dp(360)) {
            int detail = sb.length();
            sb.append(String.format(Locale.ROOT, "   %.1f Mb/s \u00B7 %d fps", st.mbps, Math.round(st.fps)));
            sb.setSpan(new ForegroundColorSpan(0x80FFFFFF), detail, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(0.9f), detail, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        label.setText(sb);
        label.setMaxWidth(Math.max(dp(60), getWidth() - dp(16)));
    }

    /** Video aspect ratio for the layout; the sender's codec hint until the decoder reports the real size. */
    float aspect() {
        if (videoW > 0 && videoH > 0) return (float) videoW / videoH;
        if (screen.sourceWidth() > 0 && screen.sourceHeight() > 0) {
            return (float) screen.sourceWidth() / screen.sourceHeight();
        }
        return 16f / 9f;
    }

    void update() {
        pendingCard.setVisibility(screen.state() == Screen.State.PENDING ? VISIBLE : GONE);
    }

    void setFocused(boolean focused, boolean showRing) {
        ring.setVisibility(focused && showRing ? VISIBLE : INVISIBLE);
    }

    /** A short pulse on the label so the eye finds the tile that just became primary. */
    void flashLabel() {
        label.animate().cancel();
        label.setScaleX(1.04f);
        label.setScaleY(1.04f);
        label.setPivotX(0);
        label.setPivotY(label.getHeight());
        label.animate().scaleX(1f).scaleY(1f).setDuration(300);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        output = screen.attachVideo(texture, this);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        releaseDecoder();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    void releaseDecoder() {
        if (output != null) {
            output.release();
            output = null;
        }
    }

    @Override
    public void onVideoSize(int width, int height) {
        if (connecting.getVisibility() == VISIBLE) {
            connecting.animate().alpha(0f).setDuration(250).withEndAction(() -> connecting.setVisibility(GONE));
        }
        if (width == videoW && height == videoH) return;
        videoW = width;
        videoH = height;
        listener.onTileSizeChanged(this);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
