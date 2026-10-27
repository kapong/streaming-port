package com.nailem.streamport;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import com.nailem.streamport.airplay.RtspServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Announces the receiver over mDNS through Android's own responder: {@code _airplay._tcp} (what Screen Mirroring
 * looks for) and {@code _raop._tcp} (the matching AirTunes record senders expect beside it).
 */
final class Advertiser {

    private static final String TAG = "StreamPort";

    private final NsdManager nsd;
    private final List<NsdManager.RegistrationListener> registered = new ArrayList<>();

    Advertiser(Context context) {
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    void start(RtspServer server, int port) {
        String pk = hex(server.publicKey());
        String features = RtspServer.featuresTxt();

        NsdServiceInfo airplay = new NsdServiceInfo();
        airplay.setServiceName(server.name());
        airplay.setServiceType("_airplay._tcp");
        airplay.setPort(port);
        airplay.setAttribute("deviceid", server.deviceId());
        airplay.setAttribute("features", features);
        airplay.setAttribute("flags", "0x" + Integer.toHexString(RtspServer.STATUS_FLAGS));
        airplay.setAttribute("model", RtspServer.MODEL);
        airplay.setAttribute("pk", pk);
        airplay.setAttribute("pw", "false");
        airplay.setAttribute("rhd", "5.6.0.0");
        airplay.setAttribute("srcvers", RtspServer.SOURCE_VERSION);
        airplay.setAttribute("vv", "2");
        register(airplay);

        NsdServiceInfo raop = new NsdServiceInfo();
        raop.setServiceName(server.deviceId().replace(":", "") + "@" + server.name());
        raop.setServiceType("_raop._tcp");
        raop.setPort(port);
        raop.setAttribute("am", RtspServer.MODEL);
        raop.setAttribute("ch", "2");
        raop.setAttribute("cn", "1,3");
        raop.setAttribute("da", "true");
        raop.setAttribute("et", "0,3,5");
        raop.setAttribute("ek", "1");
        raop.setAttribute("ft", features);
        raop.setAttribute("md", "0,1,2");
        raop.setAttribute("pk", pk);
        raop.setAttribute("sf", "0x" + Integer.toHexString(RtspServer.STATUS_FLAGS));
        raop.setAttribute("sm", "false");
        raop.setAttribute("sr", "44100");
        raop.setAttribute("ss", "16");
        raop.setAttribute("sv", "false");
        raop.setAttribute("tp", "UDP");
        raop.setAttribute("txtvers", "1");
        raop.setAttribute("vn", "65537");
        raop.setAttribute("vs", RtspServer.SOURCE_VERSION);
        register(raop);
    }

    private void register(NsdServiceInfo info) {
        NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo s) {
                Log.i(TAG, "mDNS: " + s.getServiceName() + " " + info.getServiceType());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo s, int error) {
                Log.w(TAG, "mDNS register failed " + info.getServiceType() + ": " + error);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo s) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo s, int error) {
            }
        };
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
        registered.add(l);
    }

    void stop() {
        for (NsdManager.RegistrationListener l : registered) {
            try {
                nsd.unregisterService(l);
            } catch (IllegalArgumentException ignored) {
            }
        }
        registered.clear();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
