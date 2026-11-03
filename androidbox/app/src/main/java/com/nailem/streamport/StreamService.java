package com.nailem.streamport;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.LinearLayout;

import com.nailem.streamport.admin.AdminServer;
import com.nailem.streamport.admin.PinOverlay;
import com.nailem.streamport.airplay.Hub;
import com.nailem.streamport.airplay.Limits;
import com.nailem.streamport.airplay.NetStats;
import com.nailem.streamport.airplay.RtspServer;
import com.nailem.streamport.airplay.Screen;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the AirPlay receiver running in the background: the RTSP server, its mDNS records, the audio output and
 * the admin page. Brings {@link StreamActivity} up when the first sender connects, or, when it may draw over other
 * apps, asks about a sender waiting for OK on an {@link Overlay} first and brings it up once one is approved.
 */
public final class StreamService extends Service implements Hub.Listener, AdminServer.Admin {

    private static final String TAG = "StreamPort";
    private static final String CHANNEL = "receiver";

    private RtspServer server;
    private Advertiser advertiser;
    private AudioPlayer audio;
    private WifiManager.WifiLock wifiLock;
    private int port;
    private static volatile StreamService running;
    private com.nailem.streamport.rebroadcast.Rebroadcaster rebroadcaster;
    private android.app.Notification notification;
    private AdminServer adminServer;
    private volatile com.nailem.streamport.webrtc.ShareServer shareServer;
    private com.nailem.streamport.webrtc.WatchServer watchServer;
    private android.net.ConnectivityManager.NetworkCallback networkCallback;
    /** The receiver's name, for the share page (read on its threads). */
    private static volatile String receiverName = "";

    public static String receiverName() {
        return receiverName;
    }
    private PinOverlay pinOverlay;
    /** A sender waiting on the overlay is refused after this long. */
    static final long ASK_TIMEOUT_MS = 30_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Overlay askOverlay;
    /** The waiting sender the overlay asks about, and how many wait behind it. Main thread. */
    private Screen asking;
    private int askingMore;
    /** The key pressed down on the card, so a release of a press that began before it showed is ignored. */
    private int askKeyDown = KeyEvent.KEYCODE_UNKNOWN;
    private final Runnable askTimeout = () -> {
        // Not one answered in the same main-loop turn the timeout came due.
        if (asking == null || asking.isClosed() || asking.state() != Screen.State.PENDING) return;
        Log.i(TAG, asking.name() + ": not approved within " + ASK_TIMEOUT_MS / 1000 + " s; refused");
        Hub.get().refuse(asking);
    };

    /**
     * Whether a sender waiting for OK is asked about on the overlay (the tiles come up once it is approved).
     * Otherwise the tiles come up at once, with its "OK allow / BACK refuse" card.
     */
    static boolean asksOnOverlay(Context c) {
        return android.provider.Settings.canDrawOverlays(c);
    }

    static void start(Context c) {
        c.startForegroundService(new Intent(c, StreamService.class));
    }

    /**
     * Applies settings just written to {@link Config}. While the service runs, in place: stopping it would drop the
     * app's foreground-service standing, and from a broadcast receiver Android 12+ then refuses to start it again
     * (ForegroundServiceStartNotAllowedException). Otherwise starts it; if Android refuses that from the background,
     * the settings apply at its next start and this returns false. Main thread.
     */
    static boolean restart(Context c) {
        StreamService s = running;
        if (s != null) {
            s.reload();
            return true;
        }
        try {
            start(c);
            return true;
        } catch (IllegalStateException e) { // ForegroundServiceStartNotAllowedException
            Log.w(TAG, "receiver not started from the background; the settings apply at its next start: " + e);
            return false;
        }
    }

    /** Re-reads {@link Config} and changes only what differs; senders already mirroring stay connected. Main thread. */
    private void reload() {
        if (advertiser == null) { // the receiver did not start and is stopping; the next start reads Config
            Log.w(TAG, "settings not reloaded: the receiver did not start");
            return;
        }
        Hub.get().setAskBeforeShowing(Config.ask(this), Config.askAlways(this));
        Config.applyLimits(this);
        com.nailem.streamport.webrtc.Fanout.setMaxViewers(Config.watchMax(this));
        com.nailem.streamport.webrtc.Fanout.setWatching(Config.watch(this));
        com.nailem.streamport.webrtc.Fanout.setEncodeBitrate(
                com.nailem.streamport.rebroadcast.Rebroadcaster.bitrate(Config.rebroadcastHeight(this)));
        String name = Config.name(this);
        if (!name.equals(server.name())) applyName(name);
        boolean changed = rebroadcaster != null && (rebroadcaster.height() != Config.rebroadcastHeight(this)
                || rebroadcaster.fps() != Config.rebroadcastFps(this));
        if (changed) {
            stopRebroadcast();
            // Their peers were set up for the old bitrate: reconnecting gets them the new floors. The encoder starts
            // again with the first one back.
            com.nailem.streamport.webrtc.Fanout.closeAll("encode settings changed");
        }
        sendIdleScreen();
        Hub.get().refresh();
        Log.i(TAG, "settings reloaded");
    }

    /** New mDNS records (and notification) under the new name; senders already mirroring stay connected. Main thread. */
    private void applyName(String name) {
        if (running != this) return; // posted from the admin page after the service went away
        server.setName(name);
        receiverName = name;
        advertiser.start(server, port); // closes the old records first
        notification = Notification.Builder.recoverBuilder(this, notification).setContentTitle(name).build();
        getSystemService(NotificationManager.class).notify(1, notification);
        sendIdleScreen();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle(Config.name(this))
                .setContentText(getString(R.string.notification_text))
                .build();
        notification = n;
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        running = this;

        WifiManager wifi = getSystemService(WifiManager.class);
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "streamport");
        wifiLock.setReferenceCounted(false);

        Hub hub = Hub.get();
        hub.setAskBeforeShowing(Config.ask(this), Config.askAlways(this));
        Config.applyLimits(this);
        audio = new AudioPlayer();
        hub.setAudioSink(audio);
        askOverlay = new Overlay(this);
        hub.addListener(this);

        receiverName = Config.name(this);
        // A name Stream Port stored with dots before they were mapped: the box name (Chromecast) may still have them.
        // Not a box name the owner set in Android settings with no stored name: that one stays as it is.
        if (Config.hasStoredName(this) && Config.cleanName(BoxName.get(this)).equals(receiverName)) {
            BoxName.set(this, receiverName);
        }
        server = new RtspServer(Config.name(this), Config.deviceId(this));
        try {
            port = server.start();
            advertiser = new Advertiser(this);
            advertiser.start(server, port);
        } catch (IOException e) {
            Log.e(TAG, "receiver did not start", e);
            stopSelf();
            return;
        }
        pinOverlay = new PinOverlay(this);
        adminServer = new AdminServer(this, this);
        try {
            adminServer.start();
        } catch (IOException e) {
            Log.e(TAG, "admin page did not start", e);
        }
        // WHEP viewers keep the shared encoder on; it stops with the last one.
        com.nailem.streamport.webrtc.Fanout.setMaxViewers(Config.watchMax(this));
        com.nailem.streamport.webrtc.Fanout.setWatching(Config.watch(this));
        com.nailem.streamport.webrtc.Fanout.setEncodeBitrate(
                com.nailem.streamport.rebroadcast.Rebroadcaster.bitrate(Config.rebroadcastHeight(this)));
        com.nailem.streamport.webrtc.Fanout.setViewerListener(viewers -> {
            // Here, not only where the encoder starts or stops: the TV's "N watching" mark must follow every join
            // and leave.
            Hub.get().refresh();
            if (running != this) return;
            if (viewers > 0) startRebroadcast();
            else stopRebroadcast();
        });
        shareServer = new com.nailem.streamport.webrtc.ShareServer(this);
        try {
            shareServer.start();
        } catch (IOException e) {
            Log.e(TAG, "share page did not start", e);
        }
        watchServer = new com.nailem.streamport.webrtc.WatchServer(this);
        try {
            watchServer.start();
        } catch (IOException e) {
            Log.e(TAG, "watch page did not start", e);
        }
        // A new address (joining Wi-Fi after boot, a new DHCP lease) needs a certificate that names it, and new
        // mDNS records on it.
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        networkCallback = new android.net.ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(android.net.Network network, android.net.LinkProperties lp) {
                com.nailem.streamport.webrtc.ShareServer s = shareServer;
                if (s != null) new Thread(s::onAddressesChanged, "share-readdress").start();
                if (advertiser != null) advertiser.onNetworkChanged();
                // The idle screen shows the address too (and the watch QR code): the viewers' still and the TV's.
                new android.os.Handler(getMainLooper()).post(() -> {
                    sendIdleScreen();
                    Hub.get().refresh();
                });
            }

            @Override
            public void onLost(android.net.Network network) {
                // Back on the same address (Wi-Fi dropped, same DHCP lease) still gets a fresh responder.
                if (advertiser != null) advertiser.onNetworkLost();
            }
        };
        cm.registerDefaultNetworkCallback(networkCallback);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onSessionsChanged() {
        if (!wantsRebroadcast()) {
            stopRebroadcast();
        } else {
            startRebroadcast();
            if (Hub.get().count() == 0) stopCapture();
        }
        List<Screen> waiting = asksOnOverlay(this) ? Hub.get().pending() : Collections.emptyList();
        ask(waiting);
        int count = Hub.get().count();
        if (count > 0) {
            wifiLock.acquire();
            if (count > waiting.size() && !StreamActivity.isShowing()) {
                Intent i = new Intent(this, StreamActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(StreamActivity.EXTRA_AUTO, true);
                startActivity(i);
            }
        } else if (wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    /**
     * Asks about the oldest waiting sender on the overlay (OK allows, BACK refuses, silence refuses), or hides it
     * when nobody waits. Rebuilt only when that changes. Main thread.
     */
    private void ask(List<Screen> waiting) {
        Screen next = waiting.isEmpty() ? null : waiting.get(0);
        int more = Math.max(0, waiting.size() - 1);
        if (next == asking && more == askingMore) return;
        if (next != asking) {
            main.removeCallbacks(askTimeout);
            if (next != null) main.postDelayed(askTimeout, ASK_TIMEOUT_MS);
        }
        asking = next;
        askingMore = more;
        askKeyDown = KeyEvent.KEYCODE_UNKNOWN;
        if (next == null) {
            askOverlay.hide();
            return;
        }
        LinearLayout card = askOverlay.card();
        card.addView(askOverlay.text(getString(R.string.pending_title, next.name()), Color.WHITE, 20));
        card.addView(askOverlay.text(getString(R.string.pending_keys), 0xFF30E0C4, 16));
        if (more > 0) card.addView(askOverlay.text(getString(R.string.pending_more, more), 0xB3FFFFFF, 14));
        // ponytail: the focusable overlay takes the D-pad from the app underneath while it is up (bounded by
        // ASK_TIMEOUT_MS); if that ever matters, an accessibility service's key filter could answer without focus.
        askOverlay.show(card, (v, keyCode, e) -> {
            boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (!ok && keyCode != KeyEvent.KEYCODE_BACK) return false;
            // On key up: answering the press would close the card and leave its release to whatever is focused next.
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) askKeyDown = keyCode;
            if (e.getAction() == KeyEvent.ACTION_UP && askKeyDown == keyCode && !e.isCanceled()) {
                askKeyDown = KeyEvent.KEYCODE_UNKNOWN;
                if (ok) Hub.get().approve(next);
                else Hub.get().refuse(next);
            }
            return true;
        });
    }

    // ---- the shared encode for WHEP viewers: the idle screen while nobody shares, the TV while someone does ----

    /** Whether the encoder should run: someone watches over WHEP. */
    private static boolean wantsRebroadcast() {
        return com.nailem.streamport.webrtc.Fanout.viewers() > 0;
    }

    /** The capture was handed to the rebroadcaster and not yet given back. Main thread. */
    private boolean capturing;

    /** Whether the activity should ask for a capture now: encoding, someone sharing, not captured yet. */
    static boolean wantsCapture(android.content.Context c) {
        StreamService s = running;
        return s != null && wantsRebroadcast() && Hub.get().count() > 0 && s.rebroadcaster != null && !s.capturing;
    }

    /** The activity got the capture grant (silently, thanks to the adb appop). Main thread. */
    static void onCaptureGranted(int resultCode, Intent data) {
        StreamService s = running;
        if (s != null) s.startCapture(resultCode, data);
    }

    /**
     * The old encoder is still being let go of (a box may have just one H.264 encoder), and whether to start again
     * once it has. Main thread.
     */
    private boolean rebroadcastStopping, restartWanted;

    /** Starts sending, with the idle screen until someone shares. Idempotent. Main thread. */
    private void startRebroadcast() {
        if (rebroadcastStopping) {
            restartWanted = true;
            return;
        }
        if (rebroadcaster != null) return;
        com.nailem.streamport.rebroadcast.Rebroadcaster r = new com.nailem.streamport.rebroadcast.Rebroadcaster(
                which -> new android.os.Handler(getMainLooper()).post(() -> {
                    if (rebroadcaster != which) return; // a replacement started since
                    stopRebroadcast();
                    // Viewers would only see a frozen picture: end their streams instead.
                    com.nailem.streamport.webrtc.Fanout.closeAll("the shared encode stopped");
                }),
                Config.rebroadcastHeight(this), Config.rebroadcastFps(this));
        try {
            r.start();
        } catch (Exception e) {
            Log.w(TAG, "rebroadcast did not start: " + e);
            r.stop();
            com.nailem.streamport.webrtc.Fanout.closeAll("the shared encode did not start");
            return;
        }
        rebroadcaster = r;
        Hub.get().setEncoding(true);
        sendIdleScreen();
        Hub.get().refresh();
    }

    /** What viewers see while nobody shares: the idle screen, never whatever else the TV shows. Main thread. */
    private void sendIdleScreen() {
        if (rebroadcaster == null) return;
        android.graphics.Bitmap b = IdleView.render(this, Config.name(this), IdleView.ipv4(this),
                Limits.maxStreams());
        rebroadcaster.setStill(b);
        b.recycle();
    }

    private void startCapture(int resultCode, Intent data) {
        if (rebroadcaster == null || capturing || Hub.get().count() == 0) return;
        try {
            // Android wants a running foreground service of type mediaProjection before the capture is handed out.
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            android.media.projection.MediaProjectionManager mpm =
                    getSystemService(android.media.projection.MediaProjectionManager.class);
            android.media.projection.MediaProjection projection = mpm.getMediaProjection(resultCode, data);
            if (projection == null) return;
            rebroadcaster.attach(projection, getResources().getDisplayMetrics().densityDpi);
            capturing = true;
        } catch (Exception e) {
            Log.w(TAG, "rebroadcast: capture did not start: " + e);
        }
        Hub.get().refresh();
    }

    /** Nobody shares any more: back to the idle screen. Main thread. */
    private void stopCapture() {
        if (!capturing) return;
        capturing = false;
        if (rebroadcaster != null) rebroadcaster.detach();
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    }

    /** Stops sending. Main thread. */
    private void stopRebroadcast() {
        Hub.get().setEncoding(false);
        if (rebroadcaster == null) return;
        com.nailem.streamport.rebroadcast.Rebroadcaster r = rebroadcaster;
        rebroadcaster = null;
        capturing = false;
        rebroadcastStopping = true;
        new Thread(() -> {
            try {
                r.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "rebroadcast stop failed: " + e);
            } finally {
                new android.os.Handler(getMainLooper()).post(() -> {
                    rebroadcastStopping = false;
                    boolean again = restartWanted;
                    restartWanted = false;
                    if (again && running == this && wantsRebroadcast()) startRebroadcast();
                });
            }
        }, "rebroadcast-stop").start();
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        Hub.get().refresh();
    }

    @Override
    public void onDestroy() {
        running = null;
        // The WebRTC engine outlives the service: its viewers must not.
        com.nailem.streamport.webrtc.Fanout.setViewerListener(null);
        com.nailem.streamport.webrtc.Fanout.closeAll("receiver stopped");
        stopRebroadcast();
        Hub.get().removeListener(this);
        if (adminServer != null) adminServer.stop();
        if (networkCallback != null) {
            getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        }
        if (shareServer != null) shareServer.stop();
        if (watchServer != null) watchServer.stop();
        if (pinOverlay != null) pinOverlay.hide();
        main.removeCallbacks(askTimeout);
        if (askOverlay != null) askOverlay.hide();
        if (advertiser != null) advertiser.stop();
        if (server != null) server.stop();
        Hub.get().setAudioSink(null);
        if (audio != null) audio.release();
        if (wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- admin page (called on its connection threads) ----

    @Override
    public JSONObject state() throws JSONException {
        Hub hub = Hub.get();
        JSONObject o = new JSONObject();
        o.put("name", server.name());
        o.put("boxName", BoxName.get(this));
        o.put("canRenameBox", BoxName.canWrite(this));
        o.put("miracast", Miracast.available(this));
        o.put("layout", hub.layout().name().toLowerCase(Locale.ROOT));
        Screen primary = hub.primary();
        o.put("primary", primary != null ? primary.id() : 0);
        o.put("max", Limits.maxStreams());
        o.put("maxStreams", Limits.maxStreams());
        o.put("maxStreamsLimit", Limits.MAX_STREAMS);
        o.put("resolution", Limits.resolution().key);
        JSONObject caps = new JSONObject();
        for (Limits.Resolution r : Limits.Resolution.values()) caps.put(r.key, Limits.maxStreamsFor(r));
        o.put("maxStreamsFor", caps);
        o.put("ask", Config.askAlways(this) ? "always" : Config.ask(this) ? "once" : "off");
        o.put("watch", Config.watch(this));
        o.put("viewers", com.nailem.streamport.webrtc.Fanout.viewers());
        o.put("watchMax", Config.watchMax(this));
        o.put("watchPort", com.nailem.streamport.webrtc.WatchServer.PORT);
        JSONArray list = new JSONArray();
        for (Screen s : hub.screens()) {
            NetStats.Snapshot st = s.stats();
            JSONObject p = new JSONObject();
            p.put("id", s.id());
            p.put("kind", s.kind() == Screen.Kind.AIRPLAY ? "AirPlay" : "Browser");
            p.put("name", s.name());
            p.put("model", s.model());
            p.put("address", s.address());
            p.put("width", s.sourceWidth());
            p.put("height", s.sourceHeight());
            p.put("waiting", s.state() == Screen.State.PENDING);
            p.put("quality", st.quality.name().toLowerCase(Locale.ROOT));
            p.put("fps", st.fps);
            p.put("mbps", st.mbps);
            p.put("jitterMs", st.jitterMs);
            list.put(p);
        }
        o.put("senders", list);
        return o;
    }

    @Override
    public void setLayout(String mode) {
        if ("grid".equals(mode)) Hub.get().setLayout(Hub.Layout.GRID);
        else if ("dynamic".equals(mode)) Hub.get().setLayout(Hub.Layout.DYNAMIC);
        else if ("focus".equals(mode)) Hub.get().setLayout(Hub.Layout.FOCUS);
    }

    @Override
    public void setPrimary(int sessionId) {
        Hub.get().setPrimary(Hub.get().find(sessionId));
    }

    @Override
    public String rename(String name) {
        try {
            Config.set(this, "name", name);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        String clean = Config.name(this);
        BoxName.set(this, clean);
        new android.os.Handler(getMainLooper()).post(() -> applyName(clean));
        Log.i(TAG, "renamed to " + clean);
        return null;
    }

    @Override
    public String setLimits(int maxStreams, String resolution) {
        try {
            Limits.Resolution r = Limits.Resolution.parse(resolution);
            if (maxStreams > Limits.maxStreamsFor(r)) {
                return "at " + r.key + " at most " + Limits.maxStreamsFor(r) + " screens (memory and decoder budget)";
            }
            Config.set(this, "max_streams", Integer.toString(maxStreams));
            Config.set(this, "max_res", resolution);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        // Senders already mirroring keep their place and size; the new limits apply to who comes next.
        Config.applyLimits(this);
        new android.os.Handler(getMainLooper()).post(this::sendIdleScreen);
        return null;
    }

    @Override
    public String openMiracast() {
        if (!Miracast.available(this)) return "Miracast isn't available on this box";
        // After the reply has gone out: the Wi-Fi gets busy the moment Miracast starts waiting.
        new android.os.Handler(getMainLooper()).postDelayed(() -> Miracast.open(this), 500);
        return null;
    }

    @Override
    public void setAsk(String setting) {
        Config.set(this, "ask", setting);
        // As reload() does: senders already showing stay; Off also lets in the senders still waiting (Hub tells
        // its listeners when it does); otherwise the new mode applies to whoever connects next.
        new android.os.Handler(getMainLooper()).post(() ->
                Hub.get().setAskBeforeShowing(Config.ask(this), Config.askAlways(this)));
    }

    @Override
    public void setWatch(boolean on) {
        Config.set(this, "watch", Boolean.toString(on));
        com.nailem.streamport.webrtc.Fanout.setWatching(on); // off: every viewer's stream ends now
        Hub.get().refresh(); // the idle screen's watch QR code
        Log.i(TAG, "admin turned watching " + (on ? "on" : "off"));
    }

    @Override
    public void kick(int sessionId) {
        Screen s = Hub.get().find(sessionId);
        if (s != null) {
            Log.i(TAG, "admin kicked " + s.name());
            s.close();
        }
    }

    @Override
    public String showPin(String pin, long ttlMs, String requester) {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            return "the TV can't show the PIN: Stream Port may not draw over other apps. Allow it once over adb: "
                    + "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow";
        }
        pinOverlay.show(pin, ttlMs, requester);
        return null;
    }

    @Override
    public void hidePin() {
        pinOverlay.hide();
    }
}
