package com.nailem.streamport;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.nailem.streamport.airplay.Hub;
import com.nailem.streamport.airplay.Screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every connected screen, laid out by {@link TileLayout}. Each tile shows its sender's name and how well its
 * video is getting through.
 * <ul>
 * <li>Arrows: pick the primary screen (biggest in dynamic layout, the one you hear); in focus layout, show the
 * previous or next screen.</li>
 * <li>OK: dynamic, grid, focus (only the primary screen), and round again.</li>
 * <li>Hold OK: straight to focus, and back.</li>
 * <li>MENU: disconnect the primary sender.</li>
 * <li>BACK: refuse a waiting sender; leave focus; twice in a row, stop all and go home.</li>
 * </ul>
 * The admin page (layout, primary, name, kick) drives the same state through {@link Hub}. Opened by hand with
 * nothing connected, it shows how to connect ({@link IdleView}) and goes back to Home after 10 minutes.
 */
public final class StreamActivity extends Activity implements Hub.Listener, TileView.Listener {

    static final String EXTRA_AUTO = "auto";
    private static final long BACK_WINDOW_MS = 2500;
    private static final long STATS_MS = 1000;
    /** Nobody shares for this long: back to Home, rather than a still screen for hours. */
    private static final long IDLE_MS = 10 * 60_000;

    private static volatile boolean showing;

    static boolean isShowing() {
        return showing;
    }

    private final Hub hub = Hub.get();
    private final Map<Screen, TileView> tiles = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private IdleView idle;
    private TextView hint;
    private TextView liveMark;
    private boolean captureAsked;
    private static final int REQUEST_CAPTURE = 1;
    /** The layout to go back to when leaving focus with BACK or a long OK. */
    private Hub.Layout beforeFocus = Hub.Layout.DYNAMIC;
    private boolean openedAutomatically;
    private long lastBack;
    private boolean okLongPressed;
    private final Runnable idleTimeout = () -> {
        if (tiles.isEmpty()) finish();
    };
    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            for (TileView t : tiles.values()) t.updateStats();
            main.postDelayed(this, STATS_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StreamService.start(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05070A);
        idle = new IdleView(this);
        idle.setPivotX(0);
        idle.setPivotY(0);
        root.addView(idle, new FrameLayout.LayoutParams(IdleView.W, IdleView.H));
        hint = new TextView(this);
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hint.setBackgroundResource(R.drawable.label_bg);
        hint.setPadding(32, 12, 32, 12);
        hint.setAlpha(0f);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = 40;
        root.addView(hint, hp);
        // Shown while the TV is rebroadcast to the LAN, so everyone in the room knows it is being sent on.
        liveMark = new TextView(this);
        liveMark.setText(R.string.live_mark);
        liveMark.setTextColor(0xE6FFFFFF);
        liveMark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        liveMark.setBackgroundResource(R.drawable.tile_label_bg);
        liveMark.setPadding(20, 6, 20, 6);
        liveMark.setVisibility(View.GONE);
        FrameLayout.LayoutParams lmp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lmp.setMargins(0, 24, 24, 0);
        root.addView(liveMark, lmp);
        setContentView(root);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                float scale = Math.min((r - l) / (float) IdleView.W, (b - t) / (float) IdleView.H);
                idle.setScaleX(scale);
                idle.setScaleY(scale);
                relayout();
            }
        });
        openedAutomatically = getIntent().getBooleanExtra(EXTRA_AUTO, false);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_AUTO, false) && tiles.isEmpty()) openedAutomatically = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        showing = true;
        hub.addListener(this);
        onSessionsChanged();
        main.post(statsTick);
    }

    @Override
    protected void onPause() {
        showing = false;
        hub.removeListener(this);
        main.removeCallbacks(statsTick);
        main.removeCallbacks(idleTimeout);
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // The screen is gone (Home, another app): stop drawing and let the senders know, rather than decode
        // four streams nobody sees. They can reconnect at any time.
        if (!isChangingConfigurations()) {
            hub.disconnectAll();
            clearTiles();
            finish();
        }
    }

    @Override
    public void onSessionsChanged() {
        List<Screen> live = hub.screens();
        boolean added = false;
        for (Screen s : new ArrayList<>(tiles.keySet())) {
            if (!live.contains(s)) {
                TileView t = tiles.remove(s);
                t.releaseDecoder();
                root.removeView(t);
            }
        }
        for (Screen s : live) {
            TileView t = tiles.get(s);
            if (t == null) {
                t = new TileView(this, s, this);
                tiles.put(s, t);
                root.addView(t, root.indexOfChild(hint), new FrameLayout.LayoutParams(1, 1));
                added = true;
                // A new sender waiting for approval becomes primary, so OK answers it.
                if (s.state() == Screen.State.PENDING) hub.setPrimary(s);
            } else {
                t.update();
            }
        }
        if (tiles.isEmpty()) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (openedAutomatically) {
                finish();
                return;
            }
            idle.bind(Config.name(this), ipv4(), com.nailem.streamport.airplay.Limits.maxStreams(),
                    Config.rebroadcast(this));
            idle.setVisibility(View.VISIBLE);
            main.removeCallbacks(idleTimeout);
            main.postDelayed(idleTimeout, IDLE_MS);
            return;
        }
        main.removeCallbacks(idleTimeout);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        liveMark.setVisibility(Config.rebroadcast(this) ? View.VISIBLE : View.GONE);
        liveMark.bringToFront();
        if (!captureAsked && StreamService.wantsCapture(this)) {
            // With the adb grant (appops PROJECT_MEDIA allow) Android answers at once, without a dialog.
            captureAsked = true;
            android.media.projection.MediaProjectionManager mpm =
                    getSystemService(android.media.projection.MediaProjectionManager.class);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE);
        }
        idle.setVisibility(View.GONE);
        relayout();
        if (added && tiles.size() > 1) {
            // In focus a newcomer is off screen: say so, and how to get to it.
            showHint(focus() ? getString(R.string.joined_focus) : getString(R.string.hint_keys));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        captureAsked = false;
        if (resultCode == RESULT_OK && data != null) {
            StreamService.onCaptureGranted(resultCode, data);
        } else {
            android.util.Log.w("StreamPort", "rebroadcast: capture not allowed (adb shell appops set "
                    + getPackageName() + " PROJECT_MEDIA allow)");
        }
    }

    @Override
    public void onTileSizeChanged(TileView tile) {
        relayout();
    }

    private TileView primaryTile(List<TileView> list) {
        Screen p = hub.primary();
        for (TileView t : list) {
            if (t.screen == p) return t;
        }
        return list.get(0);
    }

    private void relayout() {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0 || tiles.isEmpty()) return;
        List<TileView> list = new ArrayList<>(tiles.values());
        TileView primary = primaryTile(list);
        int p = list.indexOf(primary);
        float[] aspects = new float[list.size()];
        for (int i = 0; i < aspects.length; i++) aspects[i] = list.get(i).aspect();
        int gap = Math.round(h * 0.022f);
        int[][] rects;
        if (focus() || list.size() == 1) {
            rects = new int[list.size()][];
            int[] full = TileLayout.fill(new float[]{primary.aspect()}, w, h, 0)[0];
            for (int i = 0; i < list.size(); i++) {
                // Other tiles park just off screen: their decoders keep running for the way back.
                rects[i] = i == p ? full : new int[]{w + 16, 0, 16, 16};
            }
        } else if (hub.layout() == Hub.Layout.GRID) {
            rects = TileLayout.grid(aspects, w, h, gap);
        } else {
            rects = TileLayout.dynamic(aspects, p, w, h, gap);
        }
        for (int i = 0; i < list.size(); i++) {
            TileView t = list.get(i);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) t.getLayoutParams();
            int[] r = rects[i];
            if (lp.leftMargin != r[0] || lp.topMargin != r[1] || lp.width != r[2] || lp.height != r[3]) {
                lp.leftMargin = r[0];
                lp.topMargin = r[1];
                lp.width = r[2];
                lp.height = r[3];
                lp.gravity = Gravity.TOP | Gravity.START;
                t.setLayoutParams(lp);
            }
            t.setFocused(i == p, list.size() > 1 && !focus());
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (tiles.isEmpty()) return super.onKeyDown(keyCode, event);
        List<TileView> list = new ArrayList<>(tiles.values());
        TileView primary = primaryTile(list);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focus()) stepPrimary(list, primary, keyCode);
                else movePrimary(list, primary, keyCode);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Short press acts on key up; holding it acts in onKeyLongPress instead.
                if (event.getRepeatCount() == 0) {
                    event.startTracking();
                    okLongPressed = false;
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                primary.screen.close();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (primary.screen.state() == Screen.State.PENDING) {
                    hub.refuse(primary.screen);
                } else if (focus()) {
                    setLayout(beforeFocus);
                } else if (event.getEventTime() - lastBack < BACK_WINDOW_MS) {
                    hub.disconnectAll();
                } else {
                    lastBack = event.getEventTime();
                    showHint(getString(R.string.back_again));
                }
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (isOk(keyCode) && !tiles.isEmpty()) {
            okLongPressed = true;
            if (tiles.size() > 1) setLayout(focus() ? beforeFocus : Hub.Layout.FOCUS);
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isOk(keyCode) && !tiles.isEmpty()) {
            if (!okLongPressed && !event.isCanceled()) onOkPressed();
            okLongPressed = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void onOkPressed() {
        List<TileView> list = new ArrayList<>(tiles.values());
        TileView primary = primaryTile(list);
        if (primary.screen.state() == Screen.State.PENDING) {
            hub.approve(primary.screen);
            return;
        }
        setLayout(hub.layout().next());
    }

    private boolean focus() {
        return hub.layout() == Hub.Layout.FOCUS;
    }

    /** Switches layout (the hub tells every listener, the admin page included) and says which one it is. */
    private void setLayout(Hub.Layout next) {
        if (next == Hub.Layout.FOCUS && !focus()) beforeFocus = hub.layout();
        hub.setLayout(next);
        int name = next == Hub.Layout.GRID ? R.string.layout_grid
                : next == Hub.Layout.FOCUS ? R.string.layout_focus : R.string.layout_dynamic;
        showHint(getString(name));
    }

    /** Focus layout: left/up shows the previous screen, right/down the next. */
    private void stepPrimary(List<TileView> list, TileView from, int keyCode) {
        if (list.size() < 2) return;
        boolean back = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP;
        int i = list.indexOf(from);
        TileView to = list.get((i + (back ? list.size() - 1 : 1)) % list.size());
        hub.setPrimary(to.screen);
        to.flashLabel();
    }

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** Makes the nearest tile in the pressed direction the primary. */
    private void movePrimary(List<TileView> list, TileView from, int keyCode) {
        float fx = from.getX() + from.getWidth() / 2f;
        float fy = from.getY() + from.getHeight() / 2f;
        TileView best = null;
        float bestScore = Float.MAX_VALUE;
        for (TileView t : list) {
            if (t == from) continue;
            float dx = t.getX() + t.getWidth() / 2f - fx;
            float dy = t.getY() + t.getHeight() / 2f - fy;
            float along;
            float across;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT: along = -dx; across = dy; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: along = dx; across = dy; break;
                case KeyEvent.KEYCODE_DPAD_UP: along = -dy; across = dx; break;
                default: along = dy; across = dx; break;
            }
            if (along <= 1) continue;
            float score = along + 2 * Math.abs(across);
            if (score < bestScore) {
                bestScore = score;
                best = t;
            }
        }
        if (best != null) {
            hub.setPrimary(best.screen);
            best.flashLabel();
        }
    }

    private void showHint(String text) {
        hint.setText(text);
        hint.animate().cancel();
        hint.setAlpha(1f);
        hint.animate().alpha(0f).setStartDelay(3500).setDuration(500);
    }

    private void clearTiles() {
        for (TileView t : tiles.values()) {
            t.releaseDecoder();
            root.removeView(t);
        }
        tiles.clear();
    }

    private String ipv4() {
        return IdleView.ipv4(this);
    }
}
