package com.nailem.streamport.webrtc;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import com.nailem.streamport.admin.AdminServer;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * HTTPS on a port that also answers plain HTTP with a redirect, for the share page (9443) and the admin page (8089).
 * The first byte decides: 0x16 starts a TLS handshake; anything else is read as an HTTP request and gets
 * {@code 302 Found} to the same path on {@code https://}. Both servers share one self-signed certificate.
 */
public final class HttpsOnly {

    /** Request line and headers together, for the plain-HTTP side. */
    private static final int MAX_HEAD = 8 * 1024;
    private static final long CONTEXT_MS = 24 * 3600_000L;

    private static SSLContext context;
    private static List<InetAddress> contextFor;
    private static long contextUntil;

    private HttpsOnly() {
    }

    /** The shared TLS context, made on first use and again daily (renewal near expiry). Cheap per connection. */
    public static synchronized SSLContext context(Context c) throws IOException {
        if (context == null || System.currentTimeMillis() > contextUntil) build(c, SelfSignedCert.localIPv4());
        return context;
    }

    /**
     * The box's addresses may have changed (called from the network callback): remake the context when they did, so
     * the certificate names the new address. No addresses (Wi-Fi down) keeps the current one.
     */
    public static synchronized void refresh(Context c) throws IOException {
        List<InetAddress> now = SelfSignedCert.localIPv4();
        if (now.isEmpty() || now.equals(contextFor)) return;
        build(c, now);
    }

    private static void build(Context c, List<InetAddress> addresses) throws IOException {
        try {
            context = SelfSignedCert.context(c);
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS certificate: " + e, e);
        }
        contextFor = addresses;
        contextUntil = System.currentTimeMillis() + CONTEXT_MS;
    }

    /**
     * Returns the handshaken TLS socket, or null when the client spoke plain HTTP (it was redirected) or sent nothing.
     * {@code deadline} ({@link System#nanoTime}) is the connection's: it bounds the wait for the first byte, the
     * plain-HTTP request, and each read of the handshake.
     */
    public static SSLSocket accept(Socket s, SSLContext tls, long deadline) throws IOException {
        int first = peek(s, remainingMs(deadline));
        if (first < 0) return null;
        if (first == 0x16) { // a TLS handshake record: the ClientHello
            SSLSocket t = (SSLSocket) tls.getSocketFactory()
                    .createSocket(s, s.getInetAddress().getHostAddress(), s.getPort(), true);
            t.setUseClientMode(false);
            t.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            t.setSoTimeout(remainingMs(deadline));
            t.startHandshake();
            return t;
        }
        s.setSoTimeout(remainingMs(deadline));
        redirect(s, deadline);
        return null;
    }

    /** Milliseconds left until {@code deadline}, at least 1 (0 would mean no timeout). */
    public static int remainingMs(long deadline) {
        return (int) Math.max(1, (deadline - System.nanoTime()) / 1_000_000L);
    }

    /**
     * The first byte the client sent, left unread (MSG_PEEK) so TLS still sees the whole ClientHello; -1 at end of
     * stream. Android's SSLSocketFactory has no overload that takes bytes already consumed.
     */
    private static int peek(Socket s, int timeoutMs) throws IOException {
        ParcelFileDescriptor socketFd = ParcelFileDescriptor.fromSocket(s);
        if (socketFd == null) return -1;
        try (ParcelFileDescriptor dup = socketFd) {
            FileDescriptor fd = dup.getFileDescriptor();
            StructPollfd p = new StructPollfd();
            p.fd = fd;
            p.events = (short) OsConstants.POLLIN;
            if (Os.poll(new StructPollfd[]{p}, timeoutMs) == 0) throw new SocketTimeoutException("nothing sent");
            byte[] b = new byte[1]; // poll said readable: this does not block
            int n = Os.recvfrom(fd, b, 0, 1, OsConstants.MSG_PEEK, null);
            return n <= 0 ? -1 : b[0] & 0xff;
        } catch (ErrnoException e) {
            throw new IOException("peek: " + e, e);
        }
    }

    private static void redirect(Socket s, long deadline) throws IOException {
        InputStream in = new BufferedInputStream(s.getInputStream());
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        String requestLine = null;
        String host = null;
        int total = 0;
        while (true) {
            int c = in.read();
            if (c < 0) return;
            if (++total > MAX_HEAD || System.nanoTime() > deadline) return;
            if (c == '\n') {
                String l = line.toString("ISO-8859-1");
                if (l.endsWith("\r")) l = l.substring(0, l.length() - 1);
                line.reset();
                if (requestLine == null) {
                    requestLine = l;
                } else if (l.isEmpty()) {
                    break;
                } else {
                    int colon = l.indexOf(':');
                    if (colon > 0 && l.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("host")) {
                        host = l.substring(colon + 1).trim();
                    }
                }
            } else {
                line.write(c);
            }
        }
        String location = location(requestLine, authority(host, s.getLocalAddress(), s.getLocalPort()));
        if (location == null) return; // malformed: just close
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 302 Found\r\nLocation: " + location
                + "\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** The Host header when it names this box, with the port added if missing; otherwise the address dialled. */
    static String authority(String host, InetAddress local, int port) {
        if (host != null && host.matches("[0-9A-Za-z.:%\\[\\]-]+") && AdminServer.hostAllowed(host)
                && AdminServer.ownAddress(host)) {
            boolean hasPort = host.startsWith("[") ? !host.endsWith("]") : host.indexOf(':') >= 0;
            return hasPort ? host : host + ":" + port;
        }
        String a = local.getHostAddress();
        int pct = a.indexOf('%');
        if (pct >= 0) a = a.substring(0, pct);
        return (a.indexOf(':') >= 0 ? "[" + a + "]" : a) + ":" + port;
    }

    /** {@code https://authority/path?query} for an HTTP/1.x request line, or null when the line is malformed. */
    static String location(String requestLine, String authority) {
        if (requestLine == null) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) return null;
        String target = parts[1];
        for (int i = 0; i < target.length(); i++) {
            char ch = target.charAt(i);
            if (ch < 0x21 || ch > 0x7e) return null;
        }
        // Origin-form only; an absolute-form or "*" target goes to the front page.
        if (!target.startsWith("/") || target.startsWith("//")) target = "/";
        return "https://" + authority + target;
    }
}
