package com.nailem.streamport.airplay;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * AirPlay screen-mirroring receiver: the RTSP control port that senders find through mDNS.
 * <p>
 * Mirroring only. No HLS video URLs, no photos, no audio-only (RAOP music) sessions beyond the AAC audio that
 * comes with a mirrored screen. Each TCP connection is its own sender; sessions are never looked up by a
 * header the sender chooses, so one sender can't tear down or join another's session.
 */
public final class RtspServer {

    private static final String TAG = "StreamPort";

    public static final int PREFERRED_PORT = 7000;
    private static final int MAX_CONNECTIONS = 16;
    private static final int MAX_CONNECTIONS_PER_HOST = 6;
    private static final int MAX_LINE = 8 * 1024;
    private static final int MAX_HEADERS = 64;
    private static final int MAX_BODY = BPlist.MAX_BYTES;
    private static final int IDLE_MS = 60_000;
    /** A whole request, first byte to end of body, must arrive within this (slowloris). */
    private static final int REQUEST_MS = 10_000;
    /** New sessions (key exchanges) per host per minute. */
    private static final int SETUPS_PER_MINUTE = 10;

    /** Feature bits: video FairPlay, screen, audio, FPSAP 2.5, auth 4, AAC/ALAC/ELD formats, legacy pairing, RAOP. */
    public static final long FEATURES = bits(2, 7, 9, 12, 14, 19, 20, 21, 27, 30);
    public static final String SOURCE_VERSION = "220.68";
    public static final String MODEL = "AppleTV3,2";
    public static final int STATUS_FLAGS = 0x44;


    private volatile String name;
    private final String deviceId;
    private final String displayUuid = UUID.randomUUID().toString();
    private final String pi = UUID.randomUUID().toString();
    final Pairing.Identity identity = new Pairing.Identity();
    private final Hub hub = Hub.get();
    private final List<Connection> connections = new ArrayList<>();
    private final Map<InetAddress, ArrayDeque<Long>> setupTimes = new HashMap<>();
    private ServerSocket server;
    private volatile boolean running;

    public RtspServer(String name, String deviceId) {
        this.name = name;
        this.deviceId = deviceId;
    }

    public String name() {
        return name;
    }

    /** New name for /info; the caller re-announces mDNS. Existing senders stay connected. */
    public void setName(String name) {
        this.name = name;
    }

    public String deviceId() {
        return deviceId;
    }

    public byte[] publicKey() {
        return identity.publicKey();
    }

    public static String featuresTxt() {
        return String.format(Locale.ROOT, "0x%X,0x%X", (int) FEATURES, (int) (FEATURES >>> 32));
    }

    /** Binds (port 7000 if free) and starts accepting. Returns the port. */
    public int start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        try {
            server.bind(new InetSocketAddress(PREFERRED_PORT), 16);
        } catch (IOException e) {
            server.close();
            server = new ServerSocket();
            server.bind(new InetSocketAddress(0), 16);
        }
        running = true;
        Thread t = new Thread(this::acceptLoop, "rtsp-accept");
        t.start();
        Log.i(TAG, "AirPlay receiver '" + name + "' on port " + server.getLocalPort());
        return server.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        List<Connection> all;
        synchronized (connections) {
            all = new ArrayList<>(connections);
        }
        for (Connection c : all) c.close();
        hub.disconnectAll();
    }

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (!running) return;
                // A passing error (out of file descriptors, say) must not end AirPlay for good.
                Log.w(TAG, "accept: " + e);
                android.os.SystemClock.sleep(200);
                continue;
            }
            InetAddress from = s.getInetAddress();
            String refusal = null;
            if (!isLocal(from)) {
                refusal = "not a local address";
            }
            Connection c = new Connection(s);
            Connection evict = null;
            synchronized (connections) {
                int fromHost = 0;
                for (Connection o : connections) {
                    if (o.socket.getInetAddress().equals(from)) fromHost++;
                }
                if (refusal == null && fromHost >= MAX_CONNECTIONS_PER_HOST) refusal = "too many from one host";
                if (refusal == null && connections.size() >= MAX_CONNECTIONS) {
                    // Full: make room by dropping the longest-idle connection that carries no sender, so idle
                    // or dribbling connections can't lock real senders out.
                    for (Connection o : connections) {
                        if (o.session == null && (evict == null || o.lastActivity < evict.lastActivity)) evict = o;
                    }
                    if (evict == null) refusal = "too many connections";
                    else connections.remove(evict);
                }
                if (refusal == null) connections.add(c);
            }
            if (evict != null) {
                Log.w(TAG, "evicted idle connection from " + evict.socket.getInetAddress());
                evict.close();
            }
            if (refusal != null) {
                Log.w(TAG, "refused " + from + ": " + refusal);
                c.close();
                continue;
            }
            new Thread(c, "rtsp-" + from.getHostAddress()).start();
        }
    }

    /** Senders must be on the LAN: private, link-local or loopback. Port forwarding from the internet gets nothing. */
    public static boolean isLocal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()) return true;
        if (a instanceof Inet6Address) {
            byte b0 = a.getAddress()[0];
            return (b0 & 0xFE) == 0xFC; // fc00::/7 unique local
        }
        if (a instanceof Inet4Address) {
            byte[] b = a.getAddress();
            return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64; // 100.64/10 carrier-grade NAT (some mesh Wi-Fi)
        }
        return false;
    }

    private boolean hasSessionFrom(InetAddress peer) {
        return hub.countFrom(peer) > 0;
    }

    /** True when this host may start another key exchange now (at most {@link #SETUPS_PER_MINUTE}). */
    private boolean allowSetup(InetAddress host) {
        long now = System.currentTimeMillis();
        synchronized (setupTimes) {
            setupTimes.values().removeIf(q -> !q.isEmpty() && now - q.peekLast() > 60_000);
            ArrayDeque<Long> q = setupTimes.computeIfAbsent(host, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
            if (q.size() >= SETUPS_PER_MINUTE) return false;
            q.addLast(now);
            return true;
        }
    }

    private static long bits(int... bits) {
        long m = 0;
        for (int b : bits) m |= 1L << b;
        return m;
    }

    // ---- one control connection ----

    private static final class Request {
        String method;
        String uri;
        String protocol;
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final class Connection implements Runnable {
        final Socket socket;
        final Pairing pairing = new Pairing(identity);
        final com.nailem.streamport.airplay.fairplay.FairPlay fairPlay =
                new com.nailem.streamport.airplay.fairplay.FairPlay();
        volatile Session session;
        volatile long lastActivity = System.currentTimeMillis();
        private boolean endAfterReply;
        private long deadline = Long.MAX_VALUE;

        Connection(Socket socket) {
            this.socket = socket;
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void run() {
            InetAddress peer = socket.getInetAddress();
            try {
                socket.setSoTimeout(IDLE_MS);
                socket.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                OutputStream out = socket.getOutputStream();
                while (running && !socket.isClosed()) {
                    Request req;
                    try {
                        socket.setSoTimeout(IDLE_MS);
                        deadline = Long.MAX_VALUE;
                        req = read(in);
                    } catch (SocketTimeoutException e) {
                        if (deadline != Long.MAX_VALUE) break; // stalled mid-request
                        // The extra event connection a sender opens stays silent; keep it while its sender streams.
                        if (session != null ? !session.isClosed() : hasSessionFrom(peer)) continue;
                        break;
                    }
                    if (req == null) break;
                    lastActivity = System.currentTimeMillis();
                    handle(req, out);
                    if (endAfterReply || (session != null && session.isClosed())) break;
                }
            } catch (Throwable e) {
                // Throwable, not Exception: nothing a sender sends may take the whole app down.
                Log.i(TAG, "rtsp " + peer.getHostAddress() + ": " + e);
            } finally {
                close();
                synchronized (connections) {
                    connections.remove(this);
                }
                if (session != null) session.close();
            }
        }

        private Request read(DataInputStream in) throws IOException {
            int first = in.read();
            if (first < 0) return null;
            // The request has started: from here it has REQUEST_MS to arrive whole.
            deadline = System.nanoTime() + REQUEST_MS * 1_000_000L;
            socket.setSoTimeout(REQUEST_MS);
            String line = first == '\n' ? "" : readLine(in, first);
            if (line == null) return null;
            while (line.isEmpty()) {
                line = readLine(in, -1);
                if (line == null) return null;
            }
            String[] parts = line.split(" ");
            if (parts.length != 3) throw new IOException("bad request line");
            Request r = new Request();
            r.method = parts[0];
            r.uri = parts[1];
            r.protocol = parts[2];
            if (!r.protocol.equals("RTSP/1.0") && !r.protocol.equals("HTTP/1.1")) {
                throw new IOException("bad protocol");
            }
            for (int i = 0; ; i++) {
                String h = readLine(in, -1);
                if (h == null) throw new IOException("eof in headers");
                if (h.isEmpty()) break;
                if (i >= MAX_HEADERS) throw new IOException("too many headers");
                int colon = h.indexOf(':');
                if (colon <= 0) continue;
                r.headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
            }
            String len = r.header("Content-Length");
            if (len != null) {
                int n;
                try {
                    n = Integer.parseInt(len);
                } catch (NumberFormatException e) {
                    throw new IOException("bad Content-Length");
                }
                if (n < 0 || n > MAX_BODY) throw new IOException("body too big: " + n);
                r.body = new byte[n];
                int got = 0;
                while (got < n) {
                    checkDeadline();
                    int k = in.read(r.body, got, Math.min(n - got, 16 * 1024));
                    if (k < 0) throw new IOException("eof in body");
                    got += k;
                }
            }
            return r;
        }

        private void checkDeadline() throws IOException {
            if (System.nanoTime() > deadline) throw new IOException("request too slow");
        }

        /** Reads one line; {@code first} is a byte already read for it, or -1. */
        private String readLine(InputStream in, int first) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream(128);
            if (first >= 0 && first != '\r') line.write(first);
            while (true) {
                checkDeadline();
                int b = in.read();
                if (b < 0) return line.size() == 0 ? null : line.toString("ISO-8859-1");
                if (b == '\n') break;
                if (line.size() >= MAX_LINE) throw new IOException("line too long");
                line.write(b);
            }
            String s = line.toString("ISO-8859-1");
            return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
        }

        private void handle(Request r, OutputStream out) throws Exception {
            String m = r.method;
            String path = r.uri;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            switch (m) {
                case "GET":
                    if (path.equals("/info")) {
                        boolean computer = session != null && session.model.contains("Mac");
                        reply(out, r, 200, "application/x-apple-binary-plist", BPlist.write(info(computer)), null);
                    } else {
                        reply(out, r, 404, null, null, null);
                    }
                    return;
                case "POST":
                    switch (path) {
                        case "/pair-setup":
                            reply(out, r, 200, "application/octet-stream", pairing.setup(), null);
                            return;
                        case "/pair-verify": {
                            byte[] res;
                            try {
                                res = pairing.verify(r.body);
                            } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
                                res = null; // e.g. a key that isn't a valid curve point
                            }
                            reply(out, r, res == null ? 400 : 200, "application/octet-stream", res, null);
                            return;
                        }
                        case "/fp-setup": {
                            byte[] res = fairPlay.setup(r.body);
                            reply(out, r, res == null ? 400 : 200, "application/octet-stream", res, null);
                            return;
                        }
                        case "/feedback":
                        case "/audioMode":
                            reply(out, r, 200, null, null, null);
                            return;
                        default:
                            reply(out, r, 404, null, null, null);
                            return;
                    }
                case "SETUP":
                    setup(r, out);
                    return;
                case "GET_PARAMETER": {
                    float v = session != null ? session.volume : 1f;
                    double db = v <= 0.0001f ? -144.0 : 20.0 * Math.log10(v);
                    byte[] body = String.format(Locale.ROOT, "volume: %.6f\r\n", db).getBytes(StandardCharsets.US_ASCII);
                    reply(out, r, 200, "text/parameters", body, null);
                    return;
                }
                case "SET_PARAMETER":
                    setParameter(r);
                    reply(out, r, 200, null, null, jack());
                    return;
                case "RECORD": {
                    Map<String, String> h = jack();
                    h.put("Audio-Latency", "11025");
                    reply(out, r, 200, null, null, h);
                    return;
                }
                case "OPTIONS": {
                    Map<String, String> h = jack();
                    h.put("Public", "SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET");
                    reply(out, r, 200, null, null, h);
                    return;
                }
                case "FLUSH":
                    reply(out, r, 200, null, null, null);
                    return;
                case "TEARDOWN":
                    teardown(r, out);
                    return;
                default:
                    reply(out, r, 501, null, null, null);
            }
        }

        private Map<String, String> jack() {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Audio-Jack-Status", "connected; type=analog");
            return h;
        }

        private void setup(Request r, OutputStream out) throws Exception {
            Map<String, Object> plist;
            try {
                plist = BPlist.parseDict(r.body);
            } catch (IllegalArgumentException e) {
                reply(out, r, 400, null, null, null);
                return;
            }
            Object ekey = plist.get("ekey");
            if (ekey != null) {
                if (session != null) {
                    reply(out, r, 400, null, null, null);
                    return;
                }
                // Cheap checks before the costly key schedule: a place free, and not too many tries from this host.
                if (!hub.hasRoomFor(socket.getInetAddress()) || !allowSetup(socket.getInetAddress())) {
                    Log.i(TAG, "refused " + clean(plist.get("name"), "?") + ": full or too many attempts");
                    endAfterReply = true;
                    reply(out, r, 453, null, null, null);
                    return;
                }
                Object eiv = plist.get("eiv");
                if (!(ekey instanceof byte[]) || ((byte[]) ekey).length != com.nailem.streamport.airplay.fairplay.FairPlay.EKEY_LEN
                        || !(eiv instanceof byte[]) || ((byte[]) eiv).length != 16) {
                    reply(out, r, 400, null, null, null);
                    return;
                }
                byte[] aesKey;
                try {
                    aesKey = fairPlay.decryptAesKey((byte[]) ekey);
                } catch (IllegalStateException e) {
                    reply(out, r, 400, null, null, null);
                    return;
                }
                byte[] master = StreamCiphers.masterKey(aesKey, pairing.sharedSecret());
                session = hub.add(clean(plist.get("name"), "AirPlay device"), clean(plist.get("model"), ""),
                        clean(plist.get("deviceID"), null), socket.getInetAddress(), master, (byte[]) eiv);
                if (session != null) session.trackControl(socket);
                if (session == null) {
                    Log.i(TAG, "refused " + clean(plist.get("name"), "?") + ": all " + Limits.maxStreams() + " places taken");
                    endAfterReply = true;
                    reply(out, r, 453, null, null, null);
                    return;
                }
                Log.i(TAG, "session " + session.id + ": " + session.name + " (" + session.model + "), paired="
                        + pairing.isVerified());
            }
            Object streams = plist.get("streams");
            if (!(streams instanceof List)) {
                reply(out, r, 200, null, null, null);
                return;
            }
            if (session == null) {
                reply(out, r, 400, null, null, null);
                return;
            }
            List<Object> answer = new ArrayList<>();
            Map<String, Object> top = new LinkedHashMap<>();
            for (Object o : (List<?>) streams) {
                if (!(o instanceof Map) || answer.size() >= 2) continue;
                Map<?, ?> st = (Map<?, ?>) o;
                long type = num(st.get("type"), -1);
                if (type == 110) {
                    Object rawId = st.get("streamConnectionID");
                    if (!(rawId instanceof Long)) {
                        reply(out, r, 400, null, null, null);
                        return;
                    }
                    int port = session.startVideo(Long.toUnsignedString((Long) rawId));
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("dataPort", port);
                    a.put("type", 110);
                    answer.add(a);
                    top.put("eventPort", socket.getLocalPort());
                    top.put("timingPort", 0);
                } else if (type == 96) {
                    long ct = num(st.get("ct"), 0);
                    Session.AudioCodec codec = ct == 8 ? Session.AudioCodec.AAC_ELD
                            : ct == 4 ? Session.AudioCodec.AAC_LC : Session.AudioCodec.UNSUPPORTED;
                    long spf = num(st.get("spf"), 480);
                    int[] ports = session.startAudio(codec, spf > 0 && spf <= 4096 ? (int) spf : 480);
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("dataPort", ports[0]);
                    a.put("type", 96);
                    a.put("controlPort", ports[1]);
                    answer.add(a);
                    Log.i(TAG, "session " + session.id + ": audio " + codec + " spf " + spf);
                }
            }
            top.put("streams", answer);
            reply(out, r, 200, "application/x-apple-binary-plist", BPlist.write(top), null);
        }

        private void teardown(Request r, OutputStream out) throws IOException {
            List<?> streams = null;
            if (r.body.length > 0) {
                try {
                    Object s = BPlist.parseDict(r.body).get("streams");
                    if (s instanceof List) streams = (List<?>) s;
                } catch (IllegalArgumentException e) {
                    reply(out, r, 400, null, null, null);
                    return;
                }
            }
            if (streams == null || streams.isEmpty()) {
                endAfterReply = true;
                reply(out, r, 200, null, null, null);
                if (session != null) session.close();
                return;
            }
            if (session != null) {
                for (Object o : streams) {
                    if (!(o instanceof Map)) continue;
                    long type = num(((Map<?, ?>) o).get("type"), -1);
                    if (type == 110) session.stopVideo();
                    if (type == 96) session.stopAudio();
                }
            }
            reply(out, r, 200, null, null, null);
        }

        private void setParameter(Request r) {
            String type = r.header("Content-Type");
            if (session == null || type == null || !type.contains("text/parameters")) return;
            String body = new String(r.body, StandardCharsets.US_ASCII);
            for (String line : body.split("\r?\n")) {
                if (!line.regionMatches(true, 0, "volume:", 0, 7)) continue;
                try {
                    double db = Double.parseDouble(line.substring(7).trim());
                    if (Double.isNaN(db)) continue;
                    float v = db <= -144 ? 0f : (float) Math.pow(10, Math.max(-30, Math.min(0, db)) / 20);
                    session.volume = v;
                } catch (NumberFormatException ignored) {
                }
            }
        }

        private void reply(OutputStream out, Request r, int status, String contentType, byte[] body,
                           Map<String, String> extra) throws IOException {
            if (body == null) body = new byte[0];
            StringBuilder sb = new StringBuilder();
            sb.append(r.protocol).append(' ').append(status).append(' ').append(reason(status)).append("\r\n");
            String cseq = r.header("CSeq");
            if (cseq != null) sb.append("CSeq: ").append(sanitize(cseq)).append("\r\n");
            sb.append("Server: AirTunes/").append(SOURCE_VERSION).append("\r\n");
            if (extra != null) {
                for (Map.Entry<String, String> e : extra.entrySet()) {
                    sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            if (contentType != null && body.length > 0) sb.append("Content-Type: ").append(contentType).append("\r\n");
            sb.append("Content-Length: ").append(body.length).append("\r\n\r\n");
            byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
            byte[] all = new byte[head.length + body.length];
            System.arraycopy(head, 0, all, 0, head.length);
            System.arraycopy(body, 0, all, head.length, body.length);
            out.write(all);
            out.flush();
        }
    }

    private Map<String, Object> info(boolean computer) {
        // iPhones and iPads fit their screen inside the offered display; a Mac adopts it as its resolution
        // (a square one made it square). A sender asks for /info again right after its first SETUP, which is
        // when we know its model. See Limits.display.
        int[] size = Limits.display(computer);
        int width = size[0];
        int height = size[1];
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("features", 14);
        display.put("height", height);
        display.put("heightPhysical", false);
        display.put("heightPixels", height);
        display.put("maxFPS", Limits.FPS);
        display.put("overscanned", false);
        display.put("refreshRate", 60);
        display.put("rotation", false);
        display.put("uuid", displayUuid);
        display.put("width", width);
        display.put("widthPhysical", false);
        display.put("widthPixels", width);

        List<Object> formats = new ArrayList<>();
        List<Object> latencies = new ArrayList<>();
        for (int type : new int[]{100, 101}) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("audioInputFormats", 0x3FFFFFC);
            f.put("audioOutputFormats", 0x3FFFFFC);
            f.put("type", type);
            formats.add(f);
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("audioType", "default");
            l.put("inputLatencyMicros", false);
            l.put("type", type);
            latencies.add(l);
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("audioFormats", formats);
        info.put("audioLatencies", latencies);
        info.put("displays", java.util.Collections.singletonList(display));
        info.put("features", FEATURES);
        info.put("keepAliveSendStatsAsBody", 1);
        info.put("model", MODEL);
        info.put("name", name);
        info.put("pi", pi);
        info.put("sourceVersion", SOURCE_VERSION);
        info.put("statusFlags", STATUS_FLAGS);
        info.put("vv", 2);
        info.put("pk", identity.publicKey());
        return info;
    }

    private static long num(Object o, long fallback) {
        return o instanceof Long ? (Long) o : fallback;
    }

    /** Sender-supplied text shown on screen: printable characters only, at most 40. */
    public static String clean(Object o, String fallback) {
        if (!(o instanceof String)) return fallback;
        StringBuilder sb = new StringBuilder();
        String s = (String) o;
        for (int i = 0; i < s.length() && sb.length() < 40; i++) {
            char c = s.charAt(i);
            if (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT) sb.append(c);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? fallback : out;
    }

    /** Header values echoed back must not carry CR/LF (response splitting). */
    private static String sanitize(String v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length() && sb.length() < 32; i++) {
            char c = v.charAt(i);
            if (c >= 0x20 && c < 0x7F) sb.append(c);
        }
        return sb.toString();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 453: return "Not Enough Bandwidth";
            case 501: return "Not Implemented";
            default: return "Error";
        }
    }
}
