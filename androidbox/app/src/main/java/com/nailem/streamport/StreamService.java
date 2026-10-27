package com.nailem.streamport;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.util.Log;

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
import java.util.Locale;

/**
 * Keeps the AirPlay receiver running in the background: the RTSP server, its mDNS records, the audio output and
 * the admin page. Brings {@link StreamActivity} up when the first sender connects.
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
    private android.net.ConnectivityManager.NetworkCallback networkCallback;
    /** The receiver's name, for the share page (read on its threads). */
    private static volatile String receiverName = "";

    public static String receiverName() {
        return receiverName;
    }
    private PinOverlay pinOverlay;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, StreamService.class));
    }

    static void restart(Context c) {
        c.stopService(new Intent(c, StreamService.class));
        start(c);
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
        hub.setAskBeforeShowing(Config.ask(this));
        Config.applyLimits(this);
        audio = new AudioPlayer();
        hub.setAudioSink(audio);
        hub.addListener(this);

        receiverName = Config.name(this);
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
        shareServer = new com.nailem.streamport.webrtc.ShareServer(this);
        try {
            shareServer.start();
        } catch (IOException e) {
            Log.e(TAG, "share page did not start", e);
        }
        // A new address (joining Wi-Fi after boot, a new DHCP lease) needs a certificate that names it.
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        networkCallback = new android.net.ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(android.net.Network network, android.net.LinkProperties lp) {
                com.nailem.streamport.webrtc.ShareServer s = shareServer;
                if (s != null) new Thread(s::onAddressesChanged, "share-readdress").start();
                // The idle screen shows the address too.
                new android.os.Handler(getMainLooper()).post(StreamService.this::sendIdleScreen);
            }
        };
        cm.registerDefaultNetworkCallback(networkCallback);
        if (Config.rebroadcast(this)) startRebroadcast();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onSessionsChanged() {
        if (!Config.rebroadcast(this)) {
            stopRebroadcast();
        } else {
            startRebroadcast();
            if (Hub.get().count() == 0) stopCapture();
        }
        if (Hub.get().count() > 0) {
            wifiLock.acquire();
            if (!StreamActivity.isShowing()) {
                Intent i = new Intent(this, StreamActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(StreamActivity.EXTRA_AUTO, true);
                startActivity(i);
            }
        } else if (wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    // ---- rebroadcast: the idle screen while nobody shares, the TV itself while someone does ----

    /** Whether the rebroadcast is switched on. */
    public static boolean rebroadcastOn(android.content.Context c) {
        return Config.rebroadcast(c);
    }

    /** True while something is sent to the LAN (the idle screen or the TV). */
    static boolean rebroadcasting() {
        StreamService s = running;
        return s != null && s.rebroadcaster != null && s.rebroadcaster.isRunning();
    }

    /** True while the TV itself is sent (someone shares). */
    static boolean mirroring() {
        StreamService s = running;
        return s != null && s.rebroadcaster != null && s.rebroadcaster.isMirroring();
    }

    /** The capture was handed to the rebroadcaster and not yet given back. Main thread. */
    private boolean capturing;

    /** Whether the activity should ask for a capture now: switched on, someone sharing, not captured yet. */
    static boolean wantsCapture(android.content.Context c) {
        StreamService s = running;
        return s != null && Config.rebroadcast(c) && Hub.get().count() > 0 && s.rebroadcaster != null && !s.capturing;
    }

    /** The activity got the capture grant (silently, thanks to the adb appop). Main thread. */
    static void onCaptureGranted(int resultCode, Intent data) {
        StreamService s = running;
        if (s != null) s.startCapture(resultCode, data);
    }

    /** Starts sending, with the idle screen until someone shares. Idempotent. Main thread. */
    private void startRebroadcast() {
        if (rebroadcaster != null) return;
        com.nailem.streamport.rebroadcast.Rebroadcaster r = new com.nailem.streamport.rebroadcast.Rebroadcaster(
                () -> new android.os.Handler(getMainLooper()).post(this::stopRebroadcast),
                Config.rebroadcastHeight(this), Config.rebroadcastFps(this));
        try {
            r.start();
        } catch (Exception e) {
            Log.w(TAG, "rebroadcast did not start: " + e);
            r.stop();
            return;
        }
        rebroadcaster = r;
        Hub.get().setEncoding(true);
        sendIdleScreen();
        Hub.get().refresh();
    }

    /** What the LAN sees while nobody shares: the idle screen, never whatever else the TV shows. Main thread. */
    private void sendIdleScreen() {
        if (rebroadcaster == null) return;
        android.graphics.Bitmap b = IdleView.render(this, Config.name(this), IdleView.ipv4(this),
                Limits.maxStreams(), true);
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

    private void stopRebroadcast() {
        Hub.get().setEncoding(false);
        if (rebroadcaster == null) return;
        com.nailem.streamport.rebroadcast.Rebroadcaster r = rebroadcaster;
        rebroadcaster = null;
        capturing = false;
        new Thread(r::stop, "rebroadcast-stop").start();
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        Hub.get().refresh();
    }

    @Override
    public void onDestroy() {
        running = null;
        stopRebroadcast();
        Hub.get().removeListener(this);
        if (adminServer != null) adminServer.stop();
        if (networkCallback != null) {
            getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        }
        if (shareServer != null) shareServer.stop();
        if (pinOverlay != null) pinOverlay.hide();
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
        JSONObject rb = new JSONObject();
        rb.put("enabled", Config.rebroadcast(this));
        rb.put("live", rebroadcasting());
        rb.put("mirroring", mirroring());
        rb.put("url", com.nailem.streamport.rebroadcast.Rebroadcaster.URL);
        o.put("rebroadcast", rb);
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
        // New mDNS records under the new name; senders already mirroring stay connected.
        new android.os.Handler(getMainLooper()).post(() -> {
            server.setName(clean);
            receiverName = clean;
            advertiser.stop();
            advertiser.start(server, port);
            sendIdleScreen();
        });
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
    public void setRebroadcast(boolean on) {
        Config.set(this, "rebroadcast", Boolean.toString(on));
        // On: the idle screen goes out at once; the activity (on screen whenever someone shares) asks for the
        // capture when it hears about this.
        new android.os.Handler(getMainLooper()).post(() -> {
            if (on) startRebroadcast();
            else stopRebroadcast();
            Hub.get().refresh();
        });
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
    public void showPin(String pin, long ttlMs, String requester) {
        pinOverlay.show(pin, ttlMs, requester);
    }

    @Override
    public void hidePin() {
        pinOverlay.hide();
    }
}
