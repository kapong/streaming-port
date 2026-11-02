package com.nailem.streamport;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.nailem.streamport.airplay.RtspServer;

import java.io.IOException;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/**
 * Announces the receiver over mDNS: {@code _airplay._tcp} (what Screen Mirroring looks for) and {@code _raop._tcp}
 * (the matching AirTunes record senders expect beside it).
 * <p>
 * Through its own responder (JmDNS), not Android's: from Android 14 the system responder keeps a service name
 * unique across all of the box's service types, so {@code _airplay._tcp} under the box's own name (which the TV
 * remote service already uses for {@code _androidtvremote2._tcp}) never finishes probing, and fails silently.
 * JmDNS answers on the active network's IPv4 address under its own host name, and moves when that address does.
 */
final class Advertiser {

    private static final String TAG = "StreamPort";

    private final Context context;
    private final WifiManager.MulticastLock multicastLock;
    /** One thread, in order: JmDNS blocks while it probes and while it says goodbye. Idles out when unused. */
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    // Worker thread only.
    private JmDNS jmdns;
    private String address = "";
    private RtspServer server;
    private int port;

    Advertiser(Context context) {
        this.context = context.getApplicationContext();
        multicastLock = this.context.getSystemService(WifiManager.class).createMulticastLock("streamport-mdns");
        multicastLock.setReferenceCounted(false);
        worker.allowCoreThreadTimeOut(true);
    }

    void start(RtspServer server, int port) {
        worker.execute(() -> {
            close();
            this.server = server;
            this.port = port;
            open();
        });
    }

    /** Re-announces on the new address when the box's IPv4 address changed (or it just came online). */
    void onNetworkChanged() {
        worker.execute(() -> {
            if (server == null) return;
            String now = IdleView.ipv4(context);
            String was = address;
            if (now.equals(was)) return;
            Log.i(TAG, "mDNS: address " + (was.isEmpty() ? "none" : was) + " -> " + (now.isEmpty() ? "none" : now));
            close();
            open();
        });
    }

    void stop() {
        worker.execute(() -> {
            close();
            server = null;
        });
    }

    private void open() {
        String ip = IdleView.ipv4(context);
        if (ip.isEmpty()) {
            Log.w(TAG, "mDNS: no IPv4 address yet; advertising once the network is up");
            return;
        }
        String pk = hex(server.publicKey());
        String features = RtspServer.featuresTxt();

        Map<String, String> airplay = new LinkedHashMap<>();
        airplay.put("deviceid", server.deviceId());
        airplay.put("features", features);
        airplay.put("flags", "0x" + Integer.toHexString(RtspServer.STATUS_FLAGS));
        airplay.put("model", RtspServer.MODEL);
        airplay.put("pk", pk);
        airplay.put("pw", "false");
        airplay.put("rhd", "5.6.0.0");
        airplay.put("srcvers", RtspServer.SOURCE_VERSION);
        airplay.put("vv", "2");

        Map<String, String> raop = new LinkedHashMap<>();
        raop.put("am", RtspServer.MODEL);
        raop.put("ch", "2");
        raop.put("cn", "1,3");
        raop.put("da", "true");
        raop.put("et", "0,3,5");
        raop.put("ek", "1");
        raop.put("ft", features);
        raop.put("md", "0,1,2");
        raop.put("pk", pk);
        raop.put("sf", "0x" + Integer.toHexString(RtspServer.STATUS_FLAGS));
        raop.put("sm", "false");
        raop.put("sr", "44100");
        raop.put("ss", "16");
        raop.put("sv", "false");
        raop.put("tp", "UDP");
        raop.put("txtvers", "1");
        raop.put("vn", "65537");
        raop.put("vs", RtspServer.SOURCE_VERSION);

        // A host name of its own (StreamPort-<device id>.local), so it never contends with Android's.
        String host = "StreamPort-" + server.deviceId().replace(":", "");
        multicastLock.acquire();
        try {
            jmdns = JmDNS.create(InetAddress.getByName(ip), host); // a literal: no DNS lookup
            address = ip;
            register(ServiceInfo.create("_airplay._tcp.local.", server.name(), port, 0, 0, airplay));
            register(ServiceInfo.create("_raop._tcp.local.",
                    server.deviceId().replace(":", "") + "@" + server.name(), port, 0, 0, raop));
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "mDNS: could not advertise on " + ip + ": " + e);
            close();
        }
    }

    private void register(ServiceInfo info) throws IOException {
        jmdns.registerService(info);
        // JmDNS renames on a conflict with another device ("Name (2)"); log what it settled on.
        Log.i(TAG, "mDNS: " + info.getName() + " " + info.getType() + " on " + address + " (" + jmdns.getHostName() + ")");
    }

    private void close() {
        if (jmdns != null) {
            try {
                jmdns.close(); // unregisters (goodbye packets) and stops its threads
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "mDNS close: " + e);
            }
            jmdns = null;
        }
        address = "";
        if (multicastLock.isHeld()) multicastLock.release();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
