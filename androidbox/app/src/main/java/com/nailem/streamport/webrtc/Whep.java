package com.nailem.streamport.webrtc;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Watching the TV over WHEP (draft-ietf-wish-whep), for any of the app's HTTP listeners to mount:
 * <ul>
 * <li>{@code POST /whep} with {@code Content-Type: application/sdp} and the viewer's offer (receive video):
 * {@code 201 Created}, the answer, and a {@code Location} that is the viewer's secret resource URL.</li>
 * <li>{@code DELETE} that URL stops watching. No trickle ICE ({@code PATCH} is 405).</li>
 * </ul>
 * Anyone on the LAN may watch (the listener checks LAN), within {@link Fanout}'s viewer cap and
 * {@link ViewerSlots#PER_HOST} per address; past either, {@code 503}. While watching is turned off
 * ({@link Fanout#setWatching}), {@code 403}.
 */
public final class Whep {

    private static final String TAG = "StreamPort";
    private static final SecureRandom random = new SecureRandom();
    private static final String OFF = "Watching is turned off on this TV";

    /** The listener's way to answer. */
    public interface Reply {
        void send(int status, String type, byte[] body, String[] headers) throws IOException;
    }

    private Whep() {
    }

    /** Answers {@code /whep} and {@code /whep/...} requests; false for any other path. */
    public static boolean route(Context context, String method, String path, String contentType, byte[] body,
                                InetAddress from, Reply out) throws IOException {
        if (path.equals("/whep")) {
            if (method.equals("POST")) {
                offer(context, contentType, body, from, out);
            } else if (method.equals("OPTIONS")) {
                out.send(204, null, new byte[0], new String[]{"Accept-Post", "application/sdp"});
            } else {
                out.send(405, "text/plain", bytes("Not supported"), new String[]{"Allow", "POST, OPTIONS"});
            }
            return true;
        }
        if (!path.startsWith("/whep/")) return false;
        if (!method.equals("DELETE")) {
            // PATCH (trickle ICE) and anything else: offers and answers already carry every candidate.
            out.send(405, "text/plain", bytes("Not supported"), new String[]{"Allow", "DELETE"});
            return true;
        }
        WhepViewer v = Fanout.find(path.substring("/whep/".length()));
        if (v == null) {
            out.send(404, "text/plain", bytes("No such viewer"), null);
            return true;
        }
        v.close();
        out.send(200, null, new byte[0], null);
        return true;
    }

    private static void offer(Context context, String contentType, byte[] body, InetAddress from, Reply out)
            throws IOException {
        // Only application/sdp: a cross-site form can't send it.
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/sdp")) {
            out.send(415, "text/plain", bytes("Content-Type must be application/sdp"), null);
            return;
        }
        if (!Fanout.watching()) {
            out.send(403, "text/plain", bytes(OFF), null);
            return;
        }
        // First: it may fail to load, and no viewer place may be taken yet then.
        WebRtcEngine engine = WebRtcEngine.get(context);
        if (Fanout.isBroken()) {
            out.send(503, "text/plain", bytes("Watching is not available (see the receiver's log)"), null);
            return;
        }
        switch (Fanout.admit(from)) {
            case HOST_FULL:
                out.send(503, "text/plain", bytes("This device already watches " + ViewerSlots.PER_HOST + " times"),
                        new String[]{"Retry-After", "30"});
                return;
            case FULL:
                out.send(503, "text/plain", bytes("All " + Fanout.maxViewers() + " viewer places are taken"),
                        new String[]{"Retry-After", "30"});
                return;
            default:
                break;
        }
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        StringBuilder token = new StringBuilder();
        for (byte b : raw) token.append(String.format(Locale.ROOT, "%02x", b));
        WhepViewer v = new WhepViewer(engine, token.toString(), from);
        String answer = null;
        try {
            answer = v.answer(new String(body, StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.w(TAG, "whep " + from.getHostAddress() + ": offer refused: " + e);
        } finally {
            // Whatever went wrong (an Error included), never keep a PeerConnection or a viewer place.
            if (answer == null) v.close();
        }
        if (answer != null && !Fanout.watching()) { // turned off while answering, after closeAll looked
            v.close();
            out.send(403, "text/plain", bytes(OFF), null);
            return;
        }
        if (answer == null) {
            out.send(400, "text/plain", bytes("Could not answer this offer (no H.264 video?)"), null);
            return;
        }
        Log.i(TAG, "whep " + v.token.substring(0, 6) + ": watching from " + from.getHostAddress());
        out.send(201, "application/sdp", bytes(answer), new String[]{"Location", "/whep/" + v.token});
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
