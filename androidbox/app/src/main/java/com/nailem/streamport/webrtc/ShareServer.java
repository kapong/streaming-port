package com.nailem.streamport.webrtc;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import com.nailem.streamport.admin.AdminServer;
import com.nailem.streamport.airplay.Hub;
import com.nailem.streamport.airplay.Limits;
import com.nailem.streamport.airplay.RtspServer;
import com.nailem.streamport.airplay.Screen;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Browser sharing: {@code https://<box>:9443/}. Serves the share page and a WHIP endpoint (RFC 9725), so any
 * WHIP sender works too (OBS Studio, GStreamer's whipsink):
 * <ul>
 * <li>{@code POST /whip?name=..} with {@code Content-Type: application/sdp} and the offer: {@code 201 Created},
 * the answer, and a {@code Location} that is the share's secret resource URL.</li>
 * <li>{@code DELETE} that URL ends the share. Nobody else knows it.</li>
 * <li>No trickle ICE ({@code PATCH} is 405): offers and answers carry all their candidates.</li>
 * </ul>
 * Like AirPlay, senders are accepted without asking (the default) within the same limits: LAN only,
 * {@link Limits#maxStreams} screens, {@link Hub#MAX_PER_HOST} per address, ten offers per address per minute.
 */
public final class ShareServer {

    private static final String TAG = "StreamPort";

    public static final int PORT = 9443;
    private static final int MAX_CONNECTIONS = 16;
    private static final int MAX_CONNECTIONS_PER_HOST = 4;
    private static final int MAX_LINE = 4096;
    private static final int MAX_HEADERS = 40;
    /** A browser offer with audio and video is 4 to 10 KB. */
    private static final int MAX_BODY = 64 * 1024;
    private static final long REQUEST_MS = 10_000;
    private static final int OFFERS_PER_MINUTE = 10;

    private final Context context;
    private final Hub hub = Hub.get();
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger open = new AtomicInteger();
    private final Map<InetAddress, Integer> perHost = new HashMap<>();
    private final Map<InetAddress, ArrayDeque<Long>> offers = new HashMap<>();
    private SSLServerSocket server;
    private volatile boolean running;

    private List<InetAddress> certifiedFor = new java.util.ArrayList<>();

    public ShareServer(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * The box's address changed (it joined a network after boot, say): restart with a certificate that names the
     * new address, so the page doesn't show a name-mismatch warning on top of the expected one.
     */
    public synchronized void onAddressesChanged() {
        if (!running || SelfSignedCert.localIPv4().equals(certifiedFor)) return;
        Log.i(TAG, "share page: address changed, restarting with a new certificate");
        stop();
        try {
            start();
        } catch (IOException e) {
            Log.e(TAG, "share page did not restart", e);
        }
    }

    public synchronized void start() throws IOException {
        certifiedFor = SelfSignedCert.localIPv4();
        SSLContext tls;
        try {
            tls = SelfSignedCert.context(context);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("TLS certificate: " + e, e);
        }
        SSLServerSocket srv = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket();
        server = srv;
        server.setReuseAddress(true);
        // IPv4 only: per-address limits mean little when a host can add any number of IPv6 link-local addresses,
        // and the QR code and certificate are IPv4 anyway.
        server.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT), 16);
        server.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        running = true;
        new Thread(() -> acceptLoop(srv), "share-accept").start();
        Log.i(TAG, "share page on https port " + PORT);
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    /** Bound to its own socket: after a restart the old loop ends with the old socket. */
    private void acceptLoop(SSLServerSocket srv) {
        while (running && !srv.isClosed()) {
            Socket s;
            try {
                s = srv.accept();
            } catch (IOException e) {
                if (!running || srv.isClosed()) return;
                // A passing error (out of file descriptors, say) must not end browser sharing for good.
                Log.w(TAG, "share accept: " + e);
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
                if (mine >= MAX_CONNECTIONS_PER_HOST || open.get() >= MAX_CONNECTIONS) {
                    close(s);
                    continue;
                }
                perHost.put(from, mine + 1);
                open.incrementAndGet();
            }
            new Thread(() -> {
                try {
                    s.setSoTimeout((int) REQUEST_MS);
                    ((SSLSocket) s).startHandshake();
                    serve(s, from);
                } catch (Throwable e) {
                    // Throwable, not Exception: nothing a client sends may take the whole app down.
                    Log.i(TAG, "share " + from.getHostAddress() + ": " + e);
                } finally {
                    close(s);
                    synchronized (perHost) {
                        int mine = perHost.getOrDefault(from, 1) - 1;
                        if (mine <= 0) perHost.remove(from);
                        else perHost.put(from, mine);
                        open.decrementAndGet();
                    }
                }
            }, "share-conn").start();
        }
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    // ---- HTTP ----

    private static final class Request {
        String method;
        String target;
        String path;
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private void serve(Socket s, InetAddress from) throws IOException {
        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();
        long deadline = System.nanoTime() + REQUEST_MS * 1_000_000L;
        Request r = read(in, deadline);
        if (r == null) return;
        String host = r.header("Host");
        if (!AdminServer.hostAllowed(host) || !AdminServer.ownAddress(host)) {
            send(out, 421, "text/plain", bytes("Misdirected request"), null);
            return;
        }
        route(r, out, from);
    }

    private Request read(InputStream in, long deadline) throws IOException {
        String line = readLine(in, deadline);
        if (line == null) return null;
        String[] parts = line.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) throw new IOException("bad request line");
        Request r = new Request();
        r.method = parts[0];
        r.target = parts[1];
        int q = r.target.indexOf('?');
        r.path = q >= 0 ? r.target.substring(0, q) : r.target;
        for (int i = 0; ; i++) {
            String h = readLine(in, deadline);
            if (h == null) throw new IOException("eof in headers");
            if (h.isEmpty()) break;
            if (i >= MAX_HEADERS) throw new IOException("too many headers");
            int c = h.indexOf(':');
            if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
        }
        String len = r.header("Content-Length");
        if (len != null) {
            int n;
            try {
                n = Integer.parseInt(len);
            } catch (NumberFormatException e) {
                throw new IOException("bad Content-Length");
            }
            if (n < 0 || n > MAX_BODY) throw new IOException("body too big");
            r.body = new byte[n];
            int got = 0;
            while (got < n) {
                if (System.nanoTime() > deadline) throw new IOException("request too slow");
                int k = in.read(r.body, got, n - got);
                if (k < 0) throw new IOException("eof in body");
                got += k;
            }
        }
        return r;
    }

    private static String readLine(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(64);
        while (true) {
            if (System.nanoTime() > deadline) throw new IOException("request too slow");
            int c = in.read();
            if (c < 0) return b.size() == 0 ? null : b.toString("ISO-8859-1");
            if (c == '\n') break;
            if (b.size() >= MAX_LINE) throw new IOException("line too long");
            b.write(c);
        }
        String s = b.toString("ISO-8859-1");
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private void route(Request r, OutputStream out, InetAddress from) throws IOException {
        switch (r.method + " " + r.path) {
            case "GET /":
                asset(out, "share/index.html", "text/html; charset=utf-8");
                return;
            case "GET /share.js":
                asset(out, "share/share.js", "text/javascript; charset=utf-8");
                return;
            case "GET /share.css":
                asset(out, "share/share.css", "text/css; charset=utf-8");
                return;
            case "GET /outfit.ttf":
                asset(out, "fonts/outfit.ttf", "font/ttf");
                return;
            case "GET /config":
                send(out, 200, "application/json", bytes(config().toString()), null);
                return;
            case "OPTIONS /whip":
                send(out, 204, null, new byte[0], new String[]{"Accept-Post", "application/sdp"});
                return;
            case "POST /whip":
                whipOffer(r, out, from);
                return;
            default:
                break;
        }
        if (r.path.startsWith("/whip/")) {
            if (r.method.equals("DELETE")) {
                whipDelete(r, out);
            } else {
                // PATCH (trickle ICE) and anything else: offers and answers already carry every candidate.
                send(out, 405, "text/plain", bytes("Not supported"), new String[]{"Allow", "DELETE"});
            }
            return;
        }
        send(out, 404, "text/plain", bytes("Not found"), null);
    }

    private JSONObject config() {
        JSONObject o = new JSONObject();
        try {
            Limits.Resolution r = Limits.resolution();
            o.put("name", com.nailem.streamport.StreamService.receiverName());
            o.put("maxWidth", r.width);
            o.put("maxHeight", r.height);
            o.put("fps", Limits.FPS);
            o.put("free", Math.max(0, Limits.maxStreams() - hub.count()));
            o.put("max", Limits.maxStreams());
            o.put("rebroadcast", com.nailem.streamport.StreamService.rebroadcastOn(context));
        } catch (JSONException ignored) {
        }
        return o;
    }

    private void whipOffer(Request r, OutputStream out, InetAddress from) throws IOException {
        String type = r.header("Content-Type");
        // Only application/sdp: a cross-site form can't send it, so other web pages can't start a share.
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/sdp")) {
            send(out, 415, "text/plain", bytes("Content-Type must be application/sdp"), null);
            return;
        }
        String sdp = SdpTuning.sanitizeOffer(new String(r.body, StandardCharsets.UTF_8));
        if (sdp == null) {
            send(out, 400, "text/plain", bytes("One video (and at most one audio) stream, nothing else"), null);
            return;
        }
        if (!allowOffer(from)) {
            send(out, 429, "text/plain", bytes("Too many attempts; wait a minute"), new String[]{"Retry-After", "60"});
            return;
        }
        if (!hub.hasRoomFor(from)) {
            String why = hub.countFrom(from) >= Hub.MAX_PER_HOST
                    ? "This device already shares " + Hub.MAX_PER_HOST + " screens"
                    : "All places are taken";
            send(out, 503, "text/plain", bytes(why), new String[]{"Retry-After", "30"});
            return;
        }
        Uri query = Uri.parse("https://x" + r.target);
        // The share page says what it sends; other WHIP senders (OBS) don't, and are listed by their software.
        String source = query.getQueryParameter("source");
        String model = describe(r.header("User-Agent"));
        if ("camera".equals(source)) model = "Camera \u00B7 " + model;
        else if ("screen".equals(source)) model = "Screen \u00B7 " + model;
        String name = RtspServer.clean(query.getQueryParameter("name"), model);
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        StringBuilder token = new StringBuilder();
        for (byte b : raw) token.append(String.format(Locale.ROOT, "%02x", b));

        BrowserScreen screen = new BrowserScreen(WebRtcEngine.get(context), hub, name, model, from, token.toString());
        String answer = null;
        try {
            answer = screen.answer(sdp);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.w(TAG, "share " + from.getHostAddress() + ": offer refused: " + e);
        } finally {
            // Whatever went wrong (an Error included), never leave a PeerConnection behind outside the hub.
            if (answer == null) screen.close();
        }
        if (answer == null) {
            send(out, 400, "text/plain", bytes("Could not answer this offer (no common video codec?)"), null);
            return;
        }
        if (!hub.add(screen)) {
            screen.close();
            send(out, 503, "text/plain", bytes("All places are taken"), new String[]{"Retry-After", "30"});
            return;
        }
        screen.started();
        Log.i(TAG, "browser " + screen.id() + ": " + name + " (" + model + ") from " + from.getHostAddress());
        send(out, 201, "application/sdp", bytes(answer),
                new String[]{"Location", "/whip/" + screen.id() + "/" + screen.token});
    }

    private void whipDelete(Request r, OutputStream out) throws IOException {
        String[] parts = r.path.split("/");
        // "", "whip", id, token
        if (parts.length == 4) {
            try {
                Screen s = hub.find(Integer.parseInt(parts[2]));
                if (s instanceof BrowserScreen && MessageDigest.isEqual(
                        ((BrowserScreen) s).token.getBytes(StandardCharsets.US_ASCII),
                        parts[3].getBytes(StandardCharsets.US_ASCII))) {
                    s.close();
                    send(out, 200, null, new byte[0], null);
                    return;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        send(out, 404, "text/plain", bytes("No such share"), null);
    }

    private boolean allowOffer(InetAddress host) {
        long now = System.currentTimeMillis();
        synchronized (offers) {
            offers.values().removeIf(q -> !q.isEmpty() && now - q.peekLast() > 60_000);
            ArrayDeque<Long> q = offers.computeIfAbsent(host, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
            if (q.size() >= OFFERS_PER_MINUTE) return false;
            q.addLast(now);
            return true;
        }
    }

    /** "Chrome on Windows", "Safari on iOS", "OBS"... from the User-Agent, for the admin page. */
    static String describe(String ua) {
        if (ua == null) return "Browser";
        String browser = ua.contains("OBS") ? "OBS"
                : ua.contains("Edg/") ? "Edge"
                : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Chrome/") ? "Chrome"
                : ua.contains("Safari/") ? "Safari"
                : "Browser";
        String os = ua.contains("Windows") ? "Windows"
                : ua.contains("iPhone") || ua.contains("iPad") ? "iOS"
                : ua.contains("Mac OS X") || ua.contains("Macintosh") ? "macOS"
                : ua.contains("CrOS") ? "ChromeOS"
                : ua.contains("Android") ? "Android"
                : ua.contains("Linux") ? "Linux"
                : null;
        return os == null ? browser : browser + " on " + os;
    }

    // ---- responses ----

    private void asset(OutputStream out, String name, String type) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            send(out, 200, type, b.toByteArray(), null);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void send(OutputStream out, int status, String type, byte[] body, String[] extra)
            throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("X-Content-Type-Options: nosniff\r\n");
        h.append("Referrer-Policy: no-referrer\r\n");
        h.append("X-Frame-Options: DENY\r\n");
        h.append("Content-Security-Policy: default-src 'self'; media-src 'self' blob: mediastream:; "
                + "frame-ancestors 'none'; form-action 'none'; base-uri 'none'\r\n");
        // Screen, camera and microphone for this page only; nothing for frames or other origins.
        h.append("Permissions-Policy: display-capture=(self), camera=(self), microphone=(self), geolocation=()\r\n");
        if (extra != null) {
            for (int i = 0; i + 1 < extra.length; i += 2) h.append(extra[i]).append(": ").append(extra[i + 1]).append("\r\n");
        }
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 415: return "Unsupported Media Type";
            case 421: return "Misdirected Request";
            case 429: return "Too Many Requests";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }
}
