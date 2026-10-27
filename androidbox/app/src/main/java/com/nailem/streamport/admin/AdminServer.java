package com.nailem.streamport.admin;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The admin web page: http://&lt;box&gt;:8089/ on the LAN. Layout and primary screen, the receiver's name, and
 * kicking senders.
 * <p>
 * Login is a 6-digit PIN that the TV shows on request ({@link Admin#showPin}), valid 60 s, five tries. A correct
 * PIN gives the browser a random session token in an HttpOnly, SameSite=Strict cookie for 30 days; the box keeps
 * only SHA-256 hashes of tokens. Every API call needs that cookie and an {@code X-Admin: 1} header, which a
 * cross-site form can't send. Requests whose Host isn't an IP address or a {@code .local} name are refused, which
 * stops DNS-rebinding pages from reaching the API through a student's browser.
 */
public final class AdminServer {

    private static final String TAG = "StreamPort";

    public static final int PORT = 8089;
    private static final int MAX_CONNECTIONS = 8;
    private static final int MAX_CONNECTIONS_PER_HOST = 4;
    private static final int MAX_LINE = 4096;
    private static final int MAX_HEADERS = 40;
    /** The API's bodies are one flat JSON object of a few short fields. */
    private static final int MAX_BODY = 512;
    private static final int MAX_JSON_NESTING = 4;
    /** A whole request, first byte to end of body (slowloris). */
    private static final long REQUEST_MS = 10_000;
    private static final long PIN_TTL_MS = 60_000;
    /** Between any two new PINs, and between two from the same address (so nobody can keep the TV flashing). */
    private static final long PIN_GAP_MS = 15_000;
    private static final long PIN_GAP_PER_HOST_MS = 120_000;
    /** Wrong guesses one address gets before it is locked out. */
    private static final int HOST_TRIES = 5;
    /** Wrong guesses from everyone together before all logins lock (the backstop against many addresses). */
    private static final int GLOBAL_TRIES = 20;
    /** First lockout; each further one doubles, up to a day, until somebody logs in successfully. */
    private static final long LOCKOUT_MS = 15 * 60_000;
    private static final long MAX_LOCKOUT_MS = 24 * 3600_000L;
    private static final long SESSION_MS = 30L * 24 * 3600 * 1000;
    private static final int MAX_SESSIONS = 20;
    private static final String COOKIE = "sp_admin";

    /** What the page controls; implemented by the service. */
    public interface Admin {
        JSONObject state() throws JSONException;

        void setLayout(String mode);

        void setPrimary(int sessionId);

        /** Returns an error message, or null when renamed. */
        String rename(String name);

        void kick(int sessionId);

        /** Returns an error message, or null when saved. */
        String setLimits(int maxStreams, String resolution);

        /** Rebroadcast to the LAN while someone shares (applies at once). */
        void setRebroadcast(boolean on);

        /** Disconnects AirPlay and opens the Miracast receiver; returns an error message or null. */
        String openMiracast();

        /** {@code requester}: the address that asked, shown so the person at the TV can judge. */
        void showPin(String pin, long ttlMs, String requester);

        void hidePin();
    }

    private final Context context;
    private final Admin admin;
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger open = new AtomicInteger();
    private ServerSocket server;
    private volatile boolean running;

    private final Map<InetAddress, Integer> perHost = new HashMap<>();

    // PIN state, guarded by "this". The live PIN belongs to the address that asked for it.
    private String pin;
    private InetAddress pinOwner;
    private long pinExpires;
    private long lastPinRequest;
    private final Map<InetAddress, Guard> guards = new HashMap<>();
    private final Guard global = new Guard();

    /** Wrong-guess counting and escalating lockout, for one address or for everyone. */
    private static final class Guard {
        long lastPinRequest;
        int failures;
        int lockouts;
        long lockedUntil;

        /** Records a wrong guess; returns true when it triggers a lockout. */
        boolean fail(int allowed, long now) {
            if (++failures < allowed) return false;
            failures = 0;
            lockedUntil = now + Math.min(MAX_LOCKOUT_MS, LOCKOUT_MS << Math.min(lockouts, 10));
            lockouts++;
            return true;
        }

        void reset() {
            failures = 0;
            lockouts = 0;
            lockedUntil = 0;
        }
    }

    public AdminServer(Context context, Admin admin) {
        this.context = context.getApplicationContext();
        this.admin = admin;
    }

    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(PORT), 8);
        running = true;
        new Thread(this::acceptLoop, "admin-accept").start();
        Log.i(TAG, "admin page on port " + PORT);
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (!running) return;
                // A passing error (out of file descriptors, say) must not end the admin page for good.
                Log.w(TAG, "admin accept: " + e);
                android.os.SystemClock.sleep(200);
                continue;
            }
            if (!com.nailem.streamport.airplay.RtspServer.isLocal(s.getInetAddress())) {
                close(s);
                continue;
            }
            InetAddress from = s.getInetAddress();
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
                    serve(s);
                } catch (Throwable e) {
                    // Throwable, not Exception: nothing a client sends may take the whole app down.
                    Log.i(TAG, "admin " + from.getHostAddress() + ": " + e);
                } finally {
                    close(s);
                    synchronized (perHost) {
                        int mine = perHost.getOrDefault(from, 1) - 1;
                        if (mine <= 0) perHost.remove(from);
                        else perHost.put(from, mine);
                        open.decrementAndGet();
                    }
                }
            }, "admin-conn").start();
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
        String path;
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private void serve(Socket s) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        OutputStream out = s.getOutputStream();
        Request r = read(in, System.nanoTime() + REQUEST_MS * 1_000_000L);
        if (r == null) return;
        if (!hostAllowed(r.header("Host")) || !ownAddress(r.header("Host"))) {
            send(out, 421, "text/plain", "Misdirected request".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        route(r, out, s.getInetAddress());
    }

    private Request read(DataInputStream in, long deadline) throws IOException {
        String line = readLine(in, deadline);
        if (line == null) return null;
        String[] parts = line.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) throw new IOException("bad request line");
        Request r = new Request();
        r.method = parts[0];
        r.path = parts[1];
        int q = r.path.indexOf('?');
        if (q >= 0) r.path = r.path.substring(0, q);
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

    /** An IP-literal Host must be one of this box's own addresses; names were vetted by {@link #hostAllowed}. */
    public static boolean ownAddress(String host) {
        String h = host;
        if (h.startsWith("[")) {
            h = h.substring(1, h.indexOf(']'));
        } else {
            int colon = h.lastIndexOf(':');
            if (colon >= 0) h = h.substring(0, colon);
            if (!h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return true; // a .local name
        }
        int pct = h.indexOf('%');
        if (pct >= 0) h = h.substring(0, pct);
        try {
            InetAddress a = InetAddress.getByName(h); // a literal: no DNS lookup
            return java.net.NetworkInterface.getByInetAddress(a) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** IP literals (with optional port) and mDNS names only; anything else is a rebinding attempt. */
    public static boolean hostAllowed(String host) {
        if (host == null || host.isEmpty() || host.length() > 255) return false;
        String h = host;
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 1 && h.substring(1, end).matches("[0-9a-fA-F:.%a-zA-Z]+");
        }
        int colon = h.lastIndexOf(':');
        if (colon >= 0) {
            if (!h.substring(colon + 1).matches("\\d{1,5}")) return false;
            h = h.substring(0, colon);
        }
        h = h.toLowerCase(Locale.ROOT);
        if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return true;
        return h.endsWith(".local") && h.matches("[a-z0-9.-]+");
    }

    private void route(Request r, OutputStream out, InetAddress from) throws IOException {
        if (r.method.equals("GET")) {
            switch (r.path) {
                case "/":
                    sendAsset(out, "admin/index.html", "text/html; charset=utf-8");
                    return;
                case "/admin.js":
                    sendAsset(out, "admin/admin.js", "text/javascript; charset=utf-8");
                    return;
                case "/admin.css":
                    sendAsset(out, "admin/admin.css", "text/css; charset=utf-8");
                    return;
                default:
                    break;
            }
        }
        if (!r.path.startsWith("/api/")) {
            send(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        // Simple cross-site requests can't carry a custom header; fetch() from our own page does.
        if (!"1".equals(r.header("X-Admin"))) {
            json(out, 403, error("missing X-Admin header"), null);
            return;
        }
        try {
            if (nesting(r.body) > MAX_JSON_NESTING) {
                // org.json parses recursively; a deeply nested body would overflow the thread's stack.
                json(out, 400, error("bad request"), null);
                return;
            }
            if (r.method.equals("POST") && r.path.equals("/api/pin")) {
                json(out, 200, requestPin(from), null);
                return;
            }
            if (r.method.equals("POST") && r.path.equals("/api/login")) {
                login(r, out, from);
                return;
            }
            String token = cookie(r.header("Cookie"));
            if (!validSession(token)) {
                json(out, 401, error("log in first"), null);
                return;
            }
            JSONObject body = r.body.length > 0 ? new JSONObject(new String(r.body, StandardCharsets.UTF_8))
                    : new JSONObject();
            switch (r.method + " " + r.path) {
                case "GET /api/state":
                    json(out, 200, admin.state(), null);
                    return;
                case "POST /api/layout":
                    admin.setLayout(body.getString("mode"));
                    json(out, 200, admin.state(), null);
                    return;
                case "POST /api/primary":
                    admin.setPrimary(body.getInt("id"));
                    json(out, 200, admin.state(), null);
                    return;
                case "POST /api/name": {
                    String err = admin.rename(body.getString("name"));
                    json(out, err == null ? 200 : 400, err == null ? admin.state() : error(err), null);
                    return;
                }
                case "POST /api/settings": {
                    String err = admin.setLimits(body.getInt("maxStreams"), body.getString("resolution"));
                    json(out, err == null ? 200 : 400, err == null ? admin.state() : error(err), null);
                    return;
                }
                case "POST /api/rebroadcast":
                    admin.setRebroadcast(body.getBoolean("enabled"));
                    json(out, 200, admin.state(), null);
                    return;
                case "POST /api/miracast": {
                    String err = admin.openMiracast();
                    json(out, err == null ? 200 : 400, err == null ? admin.state() : error(err), null);
                    return;
                }
                case "POST /api/kick":
                    admin.kick(body.getInt("id"));
                    json(out, 200, admin.state(), null);
                    return;
                case "POST /api/logout":
                    endSession(token);
                    json(out, 200, new JSONObject(), COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
                    return;
                case "POST /api/logout-all":
                    prefs().edit().remove("admin_sessions").apply();
                    json(out, 200, new JSONObject(), COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
                    return;
                default:
                    json(out, 404, error("no such call"), null);
            }
        } catch (JSONException e) {
            json(out, 400, error("bad request"), null);
        }
    }

    // ---- PIN and sessions ----

    private Guard guard(InetAddress host) {
        if (guards.size() > 256) {
            long now = System.currentTimeMillis();
            guards.values().removeIf(g -> g.lockedUntil < now && now - g.lastPinRequest > MAX_LOCKOUT_MS);
        }
        return guards.computeIfAbsent(host, k -> new Guard());
    }

    private static String minutes(long ms) {
        return ((ms + 59_999) / 60_000) + " min";
    }

    private synchronized JSONObject requestPin(InetAddress from) {
        long now = System.currentTimeMillis();
        Guard host = guard(from);
        JSONObject o = new JSONObject();
        try {
            if (now < global.lockedUntil || now < host.lockedUntil) {
                long until = Math.max(global.lockedUntil, host.lockedUntil);
                o.put("error", "too many wrong PINs; try again in " + minutes(until - now));
                return o;
            }
            if (pin != null && now < pinExpires) {
                // One PIN at a time, and it belongs to whoever asked: others can't replace it or guess it away.
                if (from.equals(pinOwner)) {
                    o.put("ok", true);
                    o.put("expiresIn", (pinExpires - now) / 1000);
                } else {
                    o.put("error", "a PIN is already showing for another browser; try again in "
                            + ((pinExpires - now) / 1000 + 1) + " s");
                }
                return o;
            }
            if (now - lastPinRequest < PIN_GAP_MS || now - host.lastPinRequest < PIN_GAP_PER_HOST_MS) {
                long wait = Math.max(PIN_GAP_MS - (now - lastPinRequest),
                        PIN_GAP_PER_HOST_MS - (now - host.lastPinRequest));
                o.put("error", "wait " + (wait / 1000 + 1) + " s before asking for another PIN");
                return o;
            }
            lastPinRequest = now;
            host.lastPinRequest = now;
            pin = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
            pinOwner = from;
            pinExpires = now + PIN_TTL_MS;
            admin.showPin(pin, PIN_TTL_MS, from.getHostAddress());
            Log.i(TAG, "admin: PIN shown for " + from.getHostAddress());
            o.put("ok", true);
            o.put("expiresIn", PIN_TTL_MS / 1000);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private void login(Request r, OutputStream out, InetAddress from) throws IOException {
        String given;
        try {
            given = new JSONObject(new String(r.body, StandardCharsets.UTF_8)).getString("pin").trim();
        } catch (JSONException e) {
            json(out, 400, error("bad request"), null);
            return;
        }
        boolean ok;
        boolean hide = false;
        synchronized (this) {
            long now = System.currentTimeMillis();
            Guard host = guard(from);
            if (now < global.lockedUntil || now < host.lockedUntil) {
                long until = Math.max(global.lockedUntil, host.lockedUntil);
                json(out, 429, error("too many wrong PINs; try again in " + minutes(until - now)), null);
                return;
            }
            boolean live = pin != null && now <= pinExpires;
            // Only the browser that asked for the PIN can use it; a guess from anywhere else is simply wrong
            // (and counts against that address), so it can't burn the real owner's PIN.
            ok = live && from.equals(pinOwner) && MessageDigest.isEqual(
                    pin.getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.US_ASCII));
            if (ok) {
                pin = null;
                pinOwner = null;
                host.reset();
                global.reset();
            } else {
                boolean hostLocked = host.fail(HOST_TRIES, now);
                boolean globalLocked = global.fail(GLOBAL_TRIES, now);
                if (live && from.equals(pinOwner) && hostLocked) {
                    pin = null;
                    hide = true;
                }
                if (globalLocked) {
                    pin = null;
                    hide = true;
                    Log.w(TAG, "admin: logins locked after " + GLOBAL_TRIES + " wrong PINs");
                }
            }
        }
        if (!ok) {
            Log.w(TAG, "admin: wrong PIN from " + from.getHostAddress());
            if (hide) admin.hidePin();
            json(out, 401, error("wrong PIN"), null);
            return;
        }
        admin.hidePin();
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = hex(raw);
        addSession(token);
        Log.i(TAG, "admin: logged in from " + from.getHostAddress());
        json(out, 200, new JSONObject(), COOKIE + "=" + token + "; Path=/; Max-Age=" + SESSION_MS / 1000
                + "; HttpOnly; SameSite=Strict");
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("admin", Context.MODE_PRIVATE);
    }

    /** Stored as "sha256hex:expiresMillis" entries. */
    private synchronized Set<String> sessions() {
        Set<String> live = new HashSet<>();
        long now = System.currentTimeMillis();
        for (String e : prefs().getStringSet("admin_sessions", new HashSet<>())) {
            int c = e.indexOf(':');
            try {
                if (c > 0 && Long.parseLong(e.substring(c + 1)) > now) live.add(e);
            } catch (NumberFormatException ignored) {
            }
        }
        return live;
    }

    private synchronized void addSession(String token) {
        Set<String> live = sessions();
        while (live.size() >= MAX_SESSIONS) {
            String oldest = null;
            for (String e : live) {
                if (oldest == null || expiry(e) < expiry(oldest)) oldest = e;
            }
            live.remove(oldest);
        }
        live.add(sha256(token) + ":" + (System.currentTimeMillis() + SESSION_MS));
        prefs().edit().putStringSet("admin_sessions", live).apply();
    }

    private synchronized boolean validSession(String token) {
        if (token == null || token.length() != 64) return false;
        String h = sha256(token);
        for (String e : sessions()) {
            if (MessageDigest.isEqual(e.substring(0, e.indexOf(':')).getBytes(StandardCharsets.US_ASCII),
                    h.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    private synchronized void endSession(String token) {
        String h = sha256(token);
        Set<String> live = sessions();
        live.removeIf(e -> e.startsWith(h + ":"));
        prefs().edit().putStringSet("admin_sessions", live).apply();
    }

    private static long expiry(String e) {
        return Long.parseLong(e.substring(e.indexOf(':') + 1));
    }

    private static String cookie(String header) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=")) return p.substring(COOKIE.length() + 1);
        }
        return null;
    }

    // ---- responses ----

    private void sendAsset(OutputStream out, String name, String type) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            send(out, 200, type, b.toByteArray(), null);
        }
    }

    private static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", message);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static void json(OutputStream out, int status, JSONObject body, String setCookie) throws IOException {
        send(out, status, "application/json", body.toString().getBytes(StandardCharsets.UTF_8), setCookie);
    }

    private static void send(OutputStream out, int status, String type, byte[] body, String setCookie)
            throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("X-Content-Type-Options: nosniff\r\n");
        h.append("Referrer-Policy: no-referrer\r\n");
        h.append("X-Frame-Options: DENY\r\n");
        h.append("Content-Security-Policy: default-src 'self'; frame-ancestors 'none'; form-action 'none'; base-uri 'none'\r\n");
        if (setCookie != null) h.append("Set-Cookie: ").append(setCookie).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 421: return "Misdirected Request";
            case 429: return "Too Many Requests";
            default: return "Error";
        }
    }

    /** Deepest bracket nesting in a JSON body, strings ignored. */
    static int nesting(byte[] body) {
        int depth = 0;
        int max = 0;
        boolean inString = false;
        for (int i = 0; i < body.length; i++) {
            byte c = body[i];
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                max = Math.max(max, ++depth);
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return max;
    }

    private static String sha256(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
