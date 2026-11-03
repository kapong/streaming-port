package com.nailem.streamport.webrtc;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.nailem.streamport.admin.AdminServer;
import com.nailem.streamport.airplay.RtspServer;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The watch page: {@code http://<box>:9080/}, plain HTTP on purpose. Receiving WebRTC works in an insecure context,
 * so viewers get the TV without the self-signed certificate's warning; sharing needs https and stays on
 * {@link ShareServer}. Serves the page, {@link Whep} on the page's own origin, and {@code GET /watch/status}
 * ({@code {name, viewers, max, enabled, sharePort}}) for the page's "N watching". Nothing here is secret: the WHEP
 * resource URL is, and a page from another origin can't read it (no CORS) nor POST an SDP offer (preflight).
 * Same LAN-only and connection limits as the share page, and the same Host check against DNS rebinding.
 */
public final class WatchServer {

    private static final String TAG = "StreamPort";

    /** Next to the share page's 9443; not used by AirPlay (7000), the admin page (8089) or the share page. */
    public static final int PORT = 9080;
    /** Nothing on this page may capture. */
    private static final String PERMISSIONS = "display-capture=(), camera=(), microphone=(), geolocation=()";

    private final Context context;
    private final AtomicInteger open = new AtomicInteger();
    private final Map<InetAddress, Integer> perHost = new HashMap<>();
    private ServerSocket server;
    private volatile boolean running;

    public WatchServer(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The watch page's address for the TV to show (stage 4's QR code); null without an IPv4 address. */
    public static String url(String ipv4) {
        return ipv4 == null || ipv4.isEmpty() ? null : "http://" + ipv4 + ":" + PORT + "/";
    }

    public synchronized void start() throws IOException {
        ServerSocket srv = new ServerSocket();
        server = srv;
        server.setReuseAddress(true);
        // IPv4 only, as the share page.
        server.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT), 16);
        running = true;
        new Thread(() -> acceptLoop(srv), "watch-accept").start();
        Log.i(TAG, "watch page on http port " + PORT);
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop(ServerSocket srv) {
        while (running && !srv.isClosed()) {
            Socket s;
            try {
                s = srv.accept();
            } catch (IOException e) {
                if (!running || srv.isClosed()) return;
                Log.w(TAG, "watch accept: " + e);
                SystemClock.sleep(200);
                continue;
            }
            InetAddress from = s.getInetAddress();
            if (!RtspServer.isLocal(from)) {
                close(s);
                continue;
            }
            synchronized (perHost) {
                int mine = perHost.getOrDefault(from, 0);
                if (mine >= ShareServer.MAX_CONNECTIONS_PER_HOST || open.get() >= ShareServer.MAX_CONNECTIONS) {
                    close(s);
                    continue;
                }
                perHost.put(from, mine + 1);
                open.incrementAndGet();
            }
            new Thread(() -> {
                long deadline = System.nanoTime() + ShareServer.REQUEST_MS * 1_000_000L;
                try {
                    s.setSoTimeout(HttpsOnly.remainingMs(deadline));
                    serve(s, from, deadline);
                } catch (Throwable e) {
                    // Throwable, not Exception: nothing a client sends may take the whole app down.
                    Log.i(TAG, "watch " + from.getHostAddress() + ": " + e);
                } finally {
                    close(s);
                    synchronized (perHost) {
                        int mine = perHost.getOrDefault(from, 1) - 1;
                        if (mine <= 0) perHost.remove(from);
                        else perHost.put(from, mine);
                        open.decrementAndGet();
                    }
                }
            }, "watch-conn").start();
        }
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private void serve(Socket s, InetAddress from, long deadline) throws IOException {
        OutputStream out = s.getOutputStream();
        ShareServer.Request r = ShareServer.read(new BufferedInputStream(s.getInputStream()), deadline);
        if (r == null) return;
        String host = r.header("Host");
        if (!AdminServer.hostAllowed(host) || !AdminServer.ownAddress(host)) {
            send(out, 421, "text/plain", ShareServer.bytes("Misdirected request"), null);
            return;
        }
        if (Whep.route(context, r.method, r.path, r.header("Content-Type"), r.body, from,
                (status, type, body, extra) -> send(out, status, type, body, extra))) {
            return;
        }
        switch (r.method + " " + r.path) {
            case "GET /":
                asset(out, "watch/index.html", "text/html; charset=utf-8");
                return;
            case "GET /watch.js":
                asset(out, "watch/watch.js", "text/javascript; charset=utf-8");
                return;
            case "GET /watch.css":
                asset(out, "watch/watch.css", "text/css; charset=utf-8");
                return;
            case "GET /share.css":
                asset(out, "share/share.css", "text/css; charset=utf-8");
                return;
            case "GET /outfit.ttf":
                asset(out, "fonts/outfit.ttf", "font/ttf");
                return;
            case "GET /watch/status":
                send(out, 200, "application/json", ShareServer.bytes(status().toString()), null);
                return;
            default:
                send(out, 404, "text/plain", ShareServer.bytes("Not found"), null);
        }
    }

    private static JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", com.nailem.streamport.StreamService.receiverName());
            o.put("viewers", Fanout.viewers());
            o.put("max", Fanout.maxViewers());
            o.put("enabled", Fanout.watching());
            o.put("sharePort", ShareServer.PORT);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private void asset(OutputStream out, String name, String type) throws IOException {
        send(out, 200, type, ShareServer.load(context, name), null);
    }

    private static void send(OutputStream out, int status, String type, byte[] body, String[] extra)
            throws IOException {
        ShareServer.send(out, status, type, body, extra, PERMISSIONS);
    }
}
