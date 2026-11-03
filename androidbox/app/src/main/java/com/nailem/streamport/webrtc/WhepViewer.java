package com.nailem.streamport.webrtc;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.RtpCapabilities;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One WHEP viewer of the TV screen: send only, video only, the shared encode ({@link Fanout}). Holds one of
 * {@link Fanout}'s viewer places from creation to {@link #close}.
 */
final class WhepViewer {

    private static final String TAG = "StreamPort";
    /**
     * LAN only: host candidates, no STUN or TURN. A STUN server for viewers beyond the LAN goes here
     * ({@code PeerConnection.IceServer.builder("stun:...").createIceServer()}).
     */
    private static final List<PeerConnection.IceServer> ICE_SERVERS = Collections.emptyList();
    /**
     * The shared encoder's rate is fixed ({@link Fanout#encodeBitrate}, peaks above it). If libwebrtc's target for a
     * peer is below it, its frame dropper throws away input frames before PassEncoder sees them, which breaks the
     * H.264 reference chain (gap, then wait for an IDR): measured ~0.6 fps from a 300 kb/s start. So the target is
     * floored well above the shared rate (2x, start 3x, cap 5x; at least the 4/6/10 Mb/s measured fine at 720p);
     * congestion then shows as delay or loss, not a lower rate, which is why viewers are capped.
     */
    private static final int MIN_BPS = 4_000_000;
    private static final int START_BPS = 6_000_000;
    private static final int MAX_BPS = 10_000_000;
    /** An answered viewer that hasn't connected by then never will. */
    private static final long CONNECT_MS = 15_000;
    /** ICE "disconnected" is often a passing Wi-Fi hiccup; only this long of it ends the viewer. */
    private static final long DISCONNECTED_MS = 10_000;

    private static final Handler main = new Handler(Looper.getMainLooper());

    private final WebRtcEngine engine;
    private final InetAddress from;
    final String token;
    private volatile PeerConnection pc;
    private boolean added;
    private boolean closed;
    private final CountDownLatch gathered = new CountDownLatch(1);
    private final Runnable giveUp;
    private volatile PeerConnection.PeerConnectionState state;

    /** {@code from} has just been given a place ({@link Fanout#admit}); {@link #close} gives it back. */
    WhepViewer(WebRtcEngine engine, String token, InetAddress from) {
        this.engine = engine;
        this.token = token;
        this.from = from;
        giveUp = () -> {
            if (state == PeerConnection.PeerConnectionState.CONNECTED) return;
            Log.i(TAG, "whep " + token.substring(0, 6) + ": no connection, giving up");
            engine.worker.execute(this::close);
        };
    }

    /** The answer with all candidates, or null. */
    String answer(String offerSdp) throws InterruptedException {
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(ICE_SERVERS);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
        config.keyType = PeerConnection.KeyType.ECDSA;
        pc = engine.factory.createPeerConnection(config, observer);
        if (pc == null) return null;
        Step remote = new Step();
        pc.setRemoteDescription(remote, new SessionDescription(SessionDescription.Type.OFFER, offerSdp));
        if (!remote.await()) return null;
        int enc = Fanout.encodeBitrate();
        int minBps = Math.max(MIN_BPS, 2 * enc);
        int maxBps = Math.max(MAX_BPS, 5 * enc);
        boolean video = false;
        for (RtpTransceiver t : pc.getTransceivers()) {
            if (t.getMediaType() != MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO || video) {
                t.setDirection(RtpTransceiver.RtpTransceiverDirection.INACTIVE);
                continue;
            }
            video = true;
            RtpSender sender = t.getSender();
            sender.setTrack(Fanout.track(engine), false);
            sender.setStreams(Collections.singletonList("tv"));
            t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY);
            onlyH264(t);
            RtpParameters params = sender.getParameters();
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION;
            for (RtpParameters.Encoding e : params.encodings) {
                e.minBitrateBps = minBps;
                e.maxBitrateBps = maxBps;
            }
            sender.setParameters(params);
        }
        if (!video) return null;
        pc.setBitrate(minBps, Math.max(START_BPS, 3 * enc), maxBps); // start the estimate at a LAN rate, not 300 kb/s
        Step create = new Step();
        pc.createAnswer(create, new MediaConstraints());
        if (!create.await()) return null;
        Step local = new Step();
        pc.setLocalDescription(local, create.created);
        if (!local.await()) return null;
        gathered.await(3, TimeUnit.SECONDS);
        SessionDescription answer = pc.getLocalDescription();
        if (answer == null) return null;
        synchronized (this) {
            if (closed) return null;
            added = true;
        }
        Fanout.added(this);
        // Only now: answering itself can take longer than this (the waits above), and the viewer can't connect
        // before it has the answer. A connection that came first anyway is checked by giveUp.
        main.postDelayed(giveUp, CONNECT_MS);
        return answer.description;
    }

    private void onlyH264(RtpTransceiver t) {
        RtpCapabilities caps = engine.factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO);
        List<RtpCapabilities.CodecCapability> keep = new ArrayList<>();
        for (RtpCapabilities.CodecCapability c : caps.codecs) {
            if ("H264".equalsIgnoreCase(c.name) || "rtx".equalsIgnoreCase(c.name)) keep.add(c);
        }
        try {
            t.setCodecPreferences(keep);
        } catch (RuntimeException e) {
            Log.w(TAG, "whep: codec preferences not applied: " + e);
        }
    }

    /** Idempotent. Any thread but WebRTC's own callbacks (it disposes on the engine's worker). */
    void close() {
        boolean wasAdded;
        synchronized (this) {
            if (closed) return;
            closed = true;
            wasAdded = added;
        }
        main.removeCallbacks(giveUp);
        if (wasAdded) Fanout.removed(this);
        Fanout.release(from);
        PeerConnection p = pc;
        pc = null;
        if (p != null) engine.worker.execute(p::dispose);
    }

    private static final class Step implements SdpObserver {
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean ok;
        volatile SessionDescription created;

        boolean await() throws InterruptedException {
            return done.await(5, TimeUnit.SECONDS) && ok;
        }

        @Override
        public void onCreateSuccess(SessionDescription sdp) {
            created = sdp;
            ok = true;
            done.countDown();
        }

        @Override
        public void onSetSuccess() {
            ok = true;
            done.countDown();
        }

        @Override
        public void onCreateFailure(String error) {
            Log.w(TAG, "whep: " + error);
            done.countDown();
        }

        @Override
        public void onSetFailure(String error) {
            Log.w(TAG, "whep: " + error);
            done.countDown();
        }
    }

    private final PeerConnection.Observer observer = new PeerConnection.Observer() {
        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState s) {
            Log.i(TAG, "whep " + token.substring(0, 6) + ": " + s);
            state = s;
            switch (s) {
                case CONNECTED:
                    main.removeCallbacks(giveUp);
                    break;
                case DISCONNECTED:
                    main.removeCallbacks(giveUp);
                    main.postDelayed(giveUp, DISCONNECTED_MS);
                    break;
                case FAILED:
                case CLOSED:
                    engine.worker.execute(WhepViewer.this::close);
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.countDown();
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState s) {
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
        }

        @Override
        public void onAddStream(MediaStream stream) {
        }

        @Override
        public void onRemoveStream(MediaStream stream) {
        }

        @Override
        public void onDataChannel(DataChannel channel) {
            channel.close();
        }

        @Override
        public void onRenegotiationNeeded() {
        }

        @Override
        public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
        }
    };
}
