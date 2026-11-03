package com.nailem.streamport.webrtc;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.webrtc.CapturerObserver;
import org.webrtc.EncodedImage;
import org.webrtc.JavaI420Buffer;
import org.webrtc.TimestampAligner;
import org.webrtc.VideoCodecInfo;
import org.webrtc.VideoCodecStatus;
import org.webrtc.VideoEncoder;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * The TV's one H.264 encode (the Rebroadcaster's hardware MediaCodec) fanned out to every WHEP viewer's
 * PeerConnection, without libwebrtc ever encoding. All shared watch state lives here: there is one TV.
 * <p>
 * How: each encoded access unit is wrapped in a stand-in {@link VideoFrame.Buffer} ({@link Payload}) and pushed into
 * one shared {@link VideoSource}. libwebrtc hands that same Java object to each PeerConnection's encoder, which is a
 * {@link PassEncoder}: it ignores pixels and returns the payload's bytes as the "encoded" frame. libwebrtc then does
 * only per-peer RTP packetization, pacing, SRTP and RTCP. This leans on libwebrtc passing the Java buffer through
 * untouched (stream-webrtc-android 1.3.9, pinned): if it ever asks for pixels instead, watching stops with an
 * error ({@link #fail}) rather than sending black.
 * <p>
 * A PassEncoder sends nothing until it has a keyframe (join), and whenever it sees a gap in the sequence (libwebrtc
 * dropped a frame before encode()), it goes back to waiting for one. Joins and PLI/FIR ask the shared encoder for a
 * keyframe, coalesced to at most one per {@link #KEYFRAME_MIN_MS}.
 */
public final class Fanout {

    private static final String TAG = "StreamPort";
    private static final long KEYFRAME_MIN_MS = 500;
    public static final int DEFAULT_MAX_VIEWERS = 20;

    /** Viewers came or went: start or stop the shared encoder. Main thread. */
    public interface ViewerListener {
        void onViewers(int count);
    }

    private static volatile ViewerListener viewerListener;
    /** The running encoder's "make the next frame an IDR"; null while none runs. */
    private static final AtomicReference<Runnable> keyframeRequester = new AtomicReference<>();
    private static volatile CapturerObserver observer;
    private static VideoSource source;
    private static VideoTrack track;
    /** Answered viewers by their WHEP resource token. */
    private static final Map<String, WhepViewer> viewers = new ConcurrentHashMap<>();
    private static final ViewerSlots slots = new ViewerSlots(DEFAULT_MAX_VIEWERS);
    /** Set once libwebrtc stopped passing our buffers through; watching stays off until the app restarts. */
    private static final AtomicBoolean broken = new AtomicBoolean();
    private static final AtomicLong seq = new AtomicLong();
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** Keyframe-request timing; tests replace both. */
    static LongSupplier clock = SystemClock::uptimeMillis;
    static BiConsumer<Runnable, Long> later = (r, ms) -> main.postDelayed(r, ms);
    private static volatile int encodeBitrate = 1_800_000;
    private static long lastKeyRequest;
    private static boolean keyPending;

    private static final AtomicLong gated = new AtomicLong();
    private static final AtomicLong gaps = new AtomicLong();
    private static final AtomicLong keyRequests = new AtomicLong();
    private static long statSince = SystemClock.uptimeMillis();

    private Fanout() {
    }

    public static void setViewerListener(ViewerListener l) {
        viewerListener = l;
    }

    public static void setMaxViewers(int max) {
        slots.setMax(max);
    }

    /** The shared encode's configured bitrate, which each viewer's send rate is floored from. */
    public static void setEncodeBitrate(int bps) {
        encodeBitrate = bps;
    }

    static int encodeBitrate() {
        return encodeBitrate;
    }

    /** How many are watching the TV now. */
    public static int viewers() {
        return viewers.size();
    }

    /** An encoder started; {@code requestKeyframe} makes its next frame an IDR. Any thread. */
    public static void encoderStarted(Runnable requestKeyframe) {
        keyframeRequester.set(requestKeyframe);
    }

    /** That encoder stopped (a newer one may already have taken its place). Any thread. */
    public static void encoderStopped(Runnable requestKeyframe) {
        keyframeRequester.compareAndSet(requestKeyframe, null);
    }

    /** Ends every viewer's stream (the picture is gone). Any thread. */
    public static void closeAll(String why) {
        if (viewers.isEmpty()) return;
        Log.w(TAG, "whep: closing " + viewers.size() + " viewers: " + why);
        for (WhepViewer v : new ArrayList<>(viewers.values())) v.close();
    }

    static boolean isBroken() {
        return broken.get();
    }

    static ViewerSlots.Result admit(InetAddress from) {
        return slots.take(from);
    }

    static void release(InetAddress from) {
        slots.give(from);
    }

    static int maxViewers() {
        return slots.max();
    }

    static WhepViewer find(String token) {
        return viewers.get(token);
    }

    static void added(WhepViewer v) {
        viewers.put(v.token, v);
        changed("joined");
    }

    static void removed(WhepViewer v) {
        if (viewers.remove(v.token) != null) changed("left");
    }

    private static void changed(String what) {
        Log.i(TAG, "whep: viewer " + what + ", " + viewers.size() + " now");
        main.post(() -> {
            ViewerListener l = viewerListener;
            if (l != null) l.onViewers(viewers.size());
        });
    }

    /**
     * libwebrtc wanted pixels from a payload, or gave an encoder something else: the pass-through no longer holds
     * (a libwebrtc change?), and what viewers would get is black or garbage. Say so loudly and stop watching.
     */
    static void fail(String what) {
        if (!broken.compareAndSet(false, true)) return;
        Log.e(TAG, "whep: libwebrtc no longer passes the shared encode through (" + what
                + "); watching is off until the app restarts");
        closeAll("pass-through broken");
    }

    /** The one track every viewer's sender carries. */
    static synchronized VideoTrack track(WebRtcEngine engine) {
        if (track == null) {
            source = engine.factory.createVideoSource(true);
            track = engine.factory.createVideoTrack("tv", source);
            CapturerObserver o = source.getCapturerObserver();
            o.onCapturerStarted(true);
            observer = o;
        }
        return track;
    }

    /** One encoded access unit from the shared encoder (Annex-B; keyframes carry SPS/PPS). Encoder drain thread. */
    public static void publish(byte[] annexB, boolean key, int width, int height) {
        CapturerObserver o = observer;
        if (o == null || viewers.isEmpty() || broken.get()) return;
        ByteBuffer direct = ByteBuffer.allocateDirect(annexB.length);
        direct.put(annexB).flip();
        Payload p = new Payload(direct, key, seq.incrementAndGet(), width, height);
        o.onFrameCaptured(new VideoFrame(p, 0, TimestampAligner.getRtcTimeNanos()));
        logStats();
    }

    /** Coalesced, rate-limited keyframe request to the shared encoder. Any thread. */
    static void requestKeyframe() {
        keyRequests.incrementAndGet();
        synchronized (Fanout.class) {
            if (keyPending) return;
            keyPending = true;
            later.accept(Fanout::sendKeyRequest, Math.max(0, lastKeyRequest + KEYFRAME_MIN_MS - clock.getAsLong()));
        }
    }

    private static void sendKeyRequest() {
        synchronized (Fanout.class) {
            keyPending = false;
            lastKeyRequest = clock.getAsLong();
        }
        Runnable r = keyframeRequester.get();
        if (r != null) r.run();
    }

    private static void logStats() {
        long now = SystemClock.uptimeMillis();
        synchronized (Fanout.class) {
            if (now - statSince < 5000) return;
            statSince = now;
        }
        Log.i(TAG, "whep: " + viewers.size() + " viewers, last 5 s: " + gated.getAndSet(0) + " frames held for a "
                + "keyframe, " + gaps.getAndSet(0) + " gaps, " + keyRequests.getAndSet(0) + " keyframe requests");
    }

    /** An already encoded frame dressed up as a video frame buffer. Pixels are never read. */
    static final class Payload implements VideoFrame.Buffer {
        final ByteBuffer data;
        final boolean key;
        final long seq;
        private final int width;
        private final int height;

        Payload(ByteBuffer data, boolean key, long seq, int width, int height) {
            this.data = data;
            this.key = key;
            this.seq = seq;
            this.width = width;
            this.height = height;
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public VideoFrame.I420Buffer toI420() {
            fail("toI420");
            return JavaI420Buffer.allocate(width, height); // libwebrtc needs some buffer; viewers are closed
        }

        @Override
        public void retain() {
        }

        @Override
        public void release() {
        }

        @Override
        public VideoFrame.Buffer cropAndScale(int cropX, int cropY, int cropWidth, int cropHeight, int scaleWidth,
                                              int scaleHeight) {
            // The source always "adapts", to the same size: pass the bytes on. Anything else can't be done to them.
            if (cropX == 0 && cropY == 0 && cropWidth == width && cropHeight == height && scaleWidth == width
                    && scaleHeight == height) {
                return this;
            }
            fail("cropAndScale to " + scaleWidth + "x" + scaleHeight);
            return this;
        }
    }

    /** Every PeerConnection gets one; they share nothing but the payloads. */
    static final class EncoderFactory implements VideoEncoderFactory {
        private static final VideoCodecInfo H264;

        static {
            Map<String, String> p = new HashMap<>();
            p.put(VideoCodecInfo.H264_FMTP_PROFILE_LEVEL_ID, VideoCodecInfo.H264_CONSTRAINED_BASELINE_3_1);
            p.put(VideoCodecInfo.H264_FMTP_LEVEL_ASYMMETRY_ALLOWED, "1");
            p.put(VideoCodecInfo.H264_FMTP_PACKETIZATION_MODE, "1");
            H264 = new VideoCodecInfo("H264", p, new ArrayList<>());
        }

        @Override
        public VideoEncoder createEncoder(VideoCodecInfo info) {
            return "H264".equalsIgnoreCase(info.name) ? new PassEncoder() : null;
        }

        @Override
        public VideoCodecInfo[] getSupportedCodecs() {
            return new VideoCodecInfo[]{H264};
        }
    }

    /** Hands the shared encoder's bytes to libwebrtc as if it had just encoded them. */
    static final class PassEncoder implements VideoEncoder {
        private Callback callback;
        private boolean needKey = true;
        private long lastSeq = -1;

        @Override
        public VideoCodecStatus initEncode(Settings settings, Callback callback) {
            this.callback = callback;
            needKey = true;
            lastSeq = -1;
            requestKeyframe(); // a newcomer must not wait for the next GOP
            return VideoCodecStatus.OK;
        }

        @Override
        public VideoCodecStatus release() {
            callback = null;
            return VideoCodecStatus.OK;
        }

        @Override
        public VideoCodecStatus encode(VideoFrame frame, EncodeInfo info) {
            Callback cb = callback;
            if (cb == null) return VideoCodecStatus.OK;
            if (!(frame.getBuffer() instanceof Payload)) {
                fail("encoder got a " + frame.getBuffer().getClass().getSimpleName());
                return VideoCodecStatus.OK;
            }
            Payload p = (Payload) frame.getBuffer();
            if (info != null && info.frameTypes != null) {
                for (EncodedImage.FrameType t : info.frameTypes) {
                    if (t == EncodedImage.FrameType.VideoFrameKey && !p.key) {
                        requestKeyframe(); // PLI/FIR from this viewer
                        break;
                    }
                }
            }
            if (lastSeq >= 0 && p.seq != lastSeq + 1 && !p.key) {
                // libwebrtc dropped a frame before us: what follows references it. Wait for the next IDR.
                gaps.incrementAndGet();
                needKey = true;
                requestKeyframe();
            }
            lastSeq = p.seq;
            if (needKey && !p.key) {
                gated.incrementAndGet();
                return VideoCodecStatus.OK; // nothing encoded for this frame; libwebrtc skips it
            }
            needKey = false;
            EncodedImage image = EncodedImage.builder()
                    .setBuffer(p.data.duplicate(), null)
                    .setEncodedWidth(p.getWidth())
                    .setEncodedHeight(p.getHeight())
                    .setCaptureTimeNs(frame.getTimestampNs())
                    .setFrameType(p.key ? EncodedImage.FrameType.VideoFrameKey : EncodedImage.FrameType.VideoFrameDelta)
                    .setRotation(0)
                    .createEncodedImage();
            cb.onEncodedFrame(image, new CodecSpecificInfoH264());
            return VideoCodecStatus.OK;
        }

        @Override
        public VideoCodecStatus setRateAllocation(BitrateAllocation allocation, int framerate) {
            return VideoCodecStatus.OK; // one encoder for everyone: its rate is fixed
        }

        @Override
        public ScalingSettings getScalingSettings() {
            return ScalingSettings.OFF;
        }

        @Override
        public String getImplementationName() {
            return "SharedMediaCodecFanout";
        }

        @Override
        public boolean isHardwareEncoder() {
            return true;
        }
    }
}
