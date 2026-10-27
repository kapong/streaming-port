package com.nailem.streamport.webrtc;

import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.nailem.streamport.airplay.Hub;
import com.nailem.streamport.airplay.Limits;
import com.nailem.streamport.airplay.NetStats;
import com.nailem.streamport.airplay.Screen;

import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.EglRenderer;
import org.webrtc.GlRectDrawer;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.RTCStats;
import org.webrtc.RtpCapabilities;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One browser (or other WHIP sender) sharing its screen. One way only: the TV answers the sender's offer as
 * receive-only and never sends media back. Lives until the sender DELETEs its WHIP resource, the connection
 * drops, or someone kicks it on the TV or the admin page.
 */
final class BrowserScreen implements Screen {

    private static final String TAG = "StreamPort";
    /** A dropped connection gets this long to come back (Wi-Fi roaming) before the screen is closed. */
    private static final long DISCONNECT_GRACE_MS = 8_000;

    private final WebRtcEngine engine;
    private final Hub hub;
    private final int id;
    private final String name;
    private final String model;
    private final InetAddress peer;
    final String token;
    /** Limits in force when this sender joined (lowering them later never cuts anyone off). */
    private final Limits.Resolution resolution = Limits.resolution();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile PeerConnection pc;
    private volatile VideoTrack videoTrack;
    private volatile AudioTrack audioTrack;
    private volatile boolean audible;
    private volatile boolean closed;
    private volatile boolean videoSeen;
    private volatile State state = State.PENDING;
    private volatile int width;
    private volatile int height;
    private final List<EglRenderer> renderers = new CopyOnWriteArrayList<>();
    private final List<SizeListener> sizeListeners = new CopyOnWriteArrayList<>();
    private final CountDownLatch gathered = new CountDownLatch(1);

    // Stats, refreshed every second while the screen is open.
    private volatile NetStats.Snapshot snapshot = NetStats.Snapshot.idle();
    private long lastBytes = -1;
    private long lastPacketsLost = -1;
    private long lastPacketsReceived = -1;
    private long lastStatsAt;
    private final Runnable statsTick = this::pollStats;
    private final Runnable disconnectTimeout = () -> {
        Log.i(TAG, "browser " + id() + ": connection lost");
        close();
    };

    /** Watches every frame for its size: tells the tiles, and enforces the resolution limit. */
    private final VideoSink sizeSink = new VideoSink() {
        @Override
        public void onFrame(VideoFrame frame) {
            videoSeen = true;
            int w = frame.getRotatedWidth();
            int h = frame.getRotatedHeight();
            if (w == width && h == height) return;
            if (!Limits.sizeAllowed(resolution, w, h) || !hub.fitsDecoder(BrowserScreen.this, w, h)) {
                Log.w(TAG, name + " sent " + w + "x" + h + ", over the " + resolution.key + " limit; disconnecting");
                close();
                return;
            }
            width = w;
            height = h;
            main.post(() -> {
                for (SizeListener l : sizeListeners) l.onVideoSize(w, h);
            });
            hub.onVideoChanged(BrowserScreen.this);
        }
    };

    BrowserScreen(WebRtcEngine engine, Hub hub, String name, String model, InetAddress peer, String token) {
        this.engine = engine;
        this.hub = hub;
        this.id = hub.nextId();
        this.name = name;
        this.model = model;
        this.peer = peer;
        this.token = token;
    }

    /**
     * Answers the sender's SDP offer. Blocks (at most a few seconds) until the answer, with all of the TV's
     * candidates, is ready: no trickle ICE. Returns null if the offer can't be used.
     */
    String answer(String offerSdp) throws InterruptedException {
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
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
        for (RtpTransceiver t : pc.getTransceivers()) {
            // One way: the TV only ever receives.
            t.setDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY);
            if (t.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) preferHardwareCodecs(t);
        }
        Step create = new Step();
        pc.createAnswer(create, new MediaConstraints());
        if (!create.await()) return null;
        Step local = new Step();
        pc.setLocalDescription(local, create.created);
        if (!local.await()) return null;
        // Host candidates on the LAN take a moment; the sender's own candidates are often mDNS names the TV
        // can't resolve, so ICE works the other way round: the sender reaches the TV's address.
        gathered.await(3, TimeUnit.SECONDS);
        SessionDescription answer = pc.getLocalDescription();
        if (answer == null) return null;
        // Start the sender near a LAN-sized rate instead of ramping up from a few hundred kb/s (1080p: 4 Mb/s
        // to start, 8 at most; scaled by pixels for 1440p and 4K).
        double scale = (double) resolution.width * resolution.height / (1920 * 1080);
        return SdpTuning.withBitrates(answer.description, (int) Math.min(12_000, 4_000 * scale), 1_000,
                (int) Math.min(25_000, 8_000 * scale));
    }

    /** H.264, VP9 and AV1 decode in hardware on this box; VP8 only in software. */
    private void preferHardwareCodecs(RtpTransceiver t) {
        RtpCapabilities caps = engine.factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO);
        List<RtpCapabilities.CodecCapability> ordered = new ArrayList<>();
        for (String want : new String[]{"H264", "VP9", "AV1", "VP8"}) {
            for (RtpCapabilities.CodecCapability c : caps.codecs) {
                if (want.equalsIgnoreCase(c.name)) ordered.add(c);
            }
        }
        for (RtpCapabilities.CodecCapability c : caps.codecs) {
            if (!ordered.contains(c)) ordered.add(c); // RTX, RED, FEC
        }
        try {
            t.setCodecPreferences(ordered);
        } catch (RuntimeException e) {
            Log.w(TAG, "browser " + id + ": codec preferences not applied: " + e);
        }
    }

    /** One SDP step (set or create), waited for at most 5 s. */
    private final class Step implements SdpObserver {
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
            Log.w(TAG, "browser " + id + ": " + error);
            done.countDown();
        }

        @Override
        public void onSetFailure(String error) {
            Log.w(TAG, "browser " + id + ": " + error);
            done.countDown();
        }
    }

    private final PeerConnection.Observer observer = new PeerConnection.Observer() {
        @Override
        public void onTrack(RtpTransceiver transceiver) {
            MediaStreamTrack track = transceiver.getReceiver().track();
            if (track instanceof VideoTrack && videoTrack == null) {
                VideoTrack v = (VideoTrack) track;
                videoTrack = v;
                v.addSink(sizeSink);
                for (EglRenderer r : renderers) v.addSink(r);
            } else if (track instanceof AudioTrack && audioTrack == null) {
                audioTrack = (AudioTrack) track;
                applyAudio();
            }
        }

        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState s) {
            switch (s) {
                case CONNECTED:
                    main.removeCallbacks(disconnectTimeout);
                    Log.i(TAG, "browser " + id + ": connected");
                    break;
                case DISCONNECTED:
                    main.postDelayed(disconnectTimeout, DISCONNECT_GRACE_MS);
                    break;
                case FAILED:
                case CLOSED:
                    main.post(BrowserScreen.this::close);
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
            // Not part of WHIP; a sender that opens one gets nothing from it.
            channel.close();
        }

        @Override
        public void onRenegotiationNeeded() {
        }

        @Override
        public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
        }
    };

    /** Starts polling stats once the screen is on the TV. */
    void started() {
        main.postDelayed(statsTick, 1000);
    }

    private void pollStats() {
        PeerConnection p = pc;
        if (closed || p == null) return;
        p.getStats(report -> {
            long bytes = 0;
            long lost = 0;
            long received = 0;
            double fps = 0;
            double jitter = 0;
            for (RTCStats s : report.getStatsMap().values()) {
                if (!"inbound-rtp".equals(s.getType()) || !"video".equals(s.getMembers().get("kind"))) continue;
                bytes += num(s.getMembers().get("bytesReceived"));
                lost += num(s.getMembers().get("packetsLost"));
                received += num(s.getMembers().get("packetsReceived"));
                fps = Math.max(fps, numD(s.getMembers().get("framesPerSecond")));
                jitter = Math.max(jitter, numD(s.getMembers().get("jitter")));
            }
            long now = System.nanoTime();
            if (lastBytes >= 0) {
                double seconds = (now - lastStatsAt) / 1e9;
                float mbps = (float) ((bytes - lastBytes) * 8 / seconds / 1e6);
                long lostNow = lost - lastPacketsLost;
                long gotNow = received - lastPacketsReceived;
                double loss = gotNow + lostNow > 0 ? (double) lostNow / (gotNow + lostNow) : 0;
                int jitterMs = (int) Math.round(jitter * 1000);
                NetStats.Quality q;
                if (gotNow == 0) q = NetStats.Quality.IDLE;
                else if (loss > 0.05 || jitterMs > 120) q = NetStats.Quality.POOR;
                else if (loss > 0.01 || jitterMs > 40) q = NetStats.Quality.FAIR;
                else q = NetStats.Quality.GOOD;
                snapshot = new NetStats.Snapshot(q, (float) fps, mbps, jitterMs);
            }
            lastBytes = bytes;
            lastPacketsLost = lost;
            lastPacketsReceived = received;
            lastStatsAt = now;
        });
        main.postDelayed(statsTick, 1000);
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0;
    }

    private static double numD(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }

    // ---- Screen ----

    @Override
    public int id() {
        return id;
    }

    @Override
    public Kind kind() {
        return Kind.BROWSER;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public InetAddress peer() {
        return peer;
    }

    @Override
    public String approvalKey() {
        return "browser@" + peer.getHostAddress();
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public void setState(State s) {
        state = s;
        applyAudio();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public int sourceWidth() {
        return width;
    }

    @Override
    public int sourceHeight() {
        return height;
    }

    @Override
    public NetStats.Snapshot stats() {
        return snapshot;
    }

    @Override
    public boolean hasVideo() {
        return videoSeen;
    }

    @Override
    public void setAudible(boolean on) {
        audible = on;
        applyAudio();
    }

    /** Heard only when primary and, with on-TV approval, only once approved (like AirPlay). */
    private void applyAudio() {
        AudioTrack a = audioTrack;
        if (a != null && !closed) {
            try {
                a.setEnabled(audible && state == State.ALLOWED);
            } catch (IllegalStateException ignored) {
                // Track already disposed by a close on another thread.
            }
        }
    }

    @Override
    public VideoOutput attachVideo(SurfaceTexture texture, SizeListener listener) {
        EglRenderer r = new EglRenderer("browser-" + id);
        r.init(engine.egl.getEglBaseContext(), EglBase.CONFIG_PLAIN, new GlRectDrawer());
        r.createEglSurface(texture);
        renderers.add(r);
        sizeListeners.add(listener);
        VideoTrack v = videoTrack;
        if (v != null && !closed) v.addSink(r);
        if (width > 0) listener.onVideoSize(width, height);
        return () -> detach(r, listener);
    }

    /** Main thread. Safe after close: close() already detached every renderer. */
    private void detach(EglRenderer r, SizeListener listener) {
        sizeListeners.remove(listener);
        if (!renderers.remove(r)) return;
        VideoTrack v = videoTrack;
        if (v != null && !closed) v.removeSink(r);
        r.release();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        hub.remove(this);
        // Renderers come off the track on the main thread (where tiles live), then the connection is disposed on
        // the worker: never inside a WebRTC callback, and never while a sink is still attached.
        main.post(() -> {
            main.removeCallbacks(statsTick);
            main.removeCallbacks(disconnectTimeout);
            VideoTrack v = videoTrack;
            for (EglRenderer r : renderers) {
                if (v != null) v.removeSink(r);
                r.release();
            }
            renderers.clear();
            if (v != null) v.removeSink(sizeSink);
            PeerConnection p = pc;
            engine.worker.execute(() -> {
                if (p != null) {
                    p.close();
                    p.dispose();
                }
                Log.i(TAG, "browser " + id + " (" + name + ") closed");
            });
        });
    }
}
