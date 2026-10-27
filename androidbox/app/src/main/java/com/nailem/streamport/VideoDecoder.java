package com.nailem.streamport;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.nailem.streamport.airplay.Limits;
import com.nailem.streamport.airplay.Screen;
import com.nailem.streamport.airplay.Session;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * One hardware H.264 decoder drawing straight onto a tile's surface. Frames arrive on the session's network
 * thread and are handed to this decoder's own thread; nothing here blocks the network.
 */
public final class VideoDecoder implements Session.VideoSink {

    private static final String TAG = "StreamPort";
    /** About four seconds at 30 fps. Past that the decoder is stuck; drop and wait for the next keyframe. */
    private static final int MAX_PENDING = 120;

    /** At most one decoder restart per second, however often a sender changes its codec config. */
    private static final long RESTART_GAP_MS = 1000;

    private final Session session;
    private final Surface surface;
    private final Screen.SizeListener sizeListener;
    private final Handler mainHandler;
    private final HandlerThread thread;
    private final Handler handler;

    // Owned by the decoder thread.
    private MediaCodec codec;
    private byte[] sps;
    private byte[] pps;
    private boolean waitKey = true;
    private final ArrayDeque<Integer> freeInputs = new ArrayDeque<>();
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private long pendingBytes;
    private boolean released;
    private long lastRestart;
    private boolean restartQueued;
    private int failures;
    private long failuresSince;
    // Frame counts for the 5-second log line (frames in, frames shown, frames skipped to catch up).
    private int framesIn;
    private int framesOut;
    private int framesSkipped;
    private long statsSince = android.os.SystemClock.uptimeMillis();

    public VideoDecoder(Session session, Surface surface, Screen.SizeListener sizeListener, Handler mainHandler) {
        this.session = session;
        this.surface = surface;
        this.sizeListener = sizeListener;
        this.mainHandler = mainHandler;
        thread = new HandlerThread("decode-" + session.id);
        thread.start();
        handler = new Handler(thread.getLooper());
        session.attachSink(this);
    }

    @Override
    public void onCodecConfig(byte[] newSps, byte[] newPps) {
        handler.post(() -> {
            if (released) return;
            if (codec != null && Arrays.equals(newSps, sps) && Arrays.equals(newPps, pps)) return;
            sps = newSps;
            pps = newPps;
            long wait = lastRestart + RESTART_GAP_MS - android.os.SystemClock.uptimeMillis();
            if (wait <= 0) {
                restart();
            } else if (!restartQueued) {
                // Applied with whatever config is latest when the second is up.
                restartQueued = true;
                stopCodec();
                handler.postDelayed(() -> {
                    restartQueued = false;
                    restart();
                }, wait);
            } else {
                stopCodec();
            }
        });
    }

    @Override
    public void onFrame(byte[] annexB, boolean keyframe) {
        handler.post(() -> {
            if (released || codec == null) return;
            if (waitKey) {
                if (!keyframe) return;
                waitKey = false;
            }
            pending.add(annexB);
            pendingBytes += annexB.length;
            framesIn++;
            // Two of this sender's largest packets: 4 MB at 1080p, 16 MB at 4K (see Limits.maxStreamsFor).
            if (pending.size() > MAX_PENDING || pendingBytes > 2L * Limits.maxPacket(session.resolution)) {
                Log.w(TAG, "decoder " + session.id + " fell behind; waiting for a keyframe");
                framesSkipped += pending.size();
                pending.clear();
                pendingBytes = 0;
                waitKey = true;
                return;
            }
            feed();
        });
    }

    private void feed() {
        while (!freeInputs.isEmpty() && !pending.isEmpty()) {
            int index = freeInputs.poll();
            byte[] frame = pending.poll();
            pendingBytes -= frame.length;
            try {
                ByteBuffer b = codec.getInputBuffer(index);
                if (b == null || frame.length > b.capacity()) {
                    codec.queueInputBuffer(index, 0, 0, 0, 0);
                    waitKey = true;
                    pending.clear();
                    pendingBytes = 0;
                    return;
                }
                b.clear();
                b.put(frame);
                codec.queueInputBuffer(index, 0, frame.length, System.nanoTime() / 1000, 0);
            } catch (IllegalStateException e) {
                Log.w(TAG, "decoder " + session.id + " input: " + e);
                handler.post(() -> failed(false));
                return;
            }
        }
    }

    private void restart() {
        stopCodec();
        if (sps == null || released) return;
        lastRestart = android.os.SystemClock.uptimeMillis();
        try {
            boolean known = Limits.sizeAllowed(session.resolution, session.sourceWidth(), session.sourceHeight());
            int w = known ? session.sourceWidth() : 1920;
            int h = known ? session.sourceHeight() : 1080;
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)));
            // A new SPS (the sender rotated) restarts the decoder, so buffers only need this stream's size.
            f.setInteger(MediaFormat.KEY_MAX_WIDTH, (w + 15) / 16 * 16);
            f.setInteger(MediaFormat.KEY_MAX_HEIGHT, (h + 15) / 16 * 16);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.min(3 << 20, Limits.maxPacket(session.resolution)));
            f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            f.setInteger(MediaFormat.KEY_PRIORITY, 0);
            MediaCodec c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            c.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec mc, int index) {
                    if (mc != codec) return;
                    freeInputs.add(index);
                    feed();
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec mc, int index, MediaCodec.BufferInfo info) {
                    if (mc != codec) return;
                    // Mirroring is live: show every frame the moment it is decoded.
                    mc.releaseOutputBuffer(index, true);
                    framesOut++;
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - statsSince >= 5000) {
                        double secs = (now - statsSince) / 1000.0;
                        Log.i(TAG, String.format(java.util.Locale.ROOT, "decoder %d: in %.1f fps, shown %.1f fps, skipped %d",
                                session.id, framesIn / secs, framesOut / secs, framesSkipped));
                        framesIn = 0;
                        framesOut = 0;
                        framesSkipped = 0;
                        statsSince = now;
                    }
                }

                @Override
                public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                    if (mc != codec) return;
                    Log.w(TAG, "decoder " + session.id + ": " + e.getDiagnosticInfo() + " (code " + e.getErrorCode() + ")");
                    // Never stop or release a codec from inside its own callback.
                    handler.post(() -> failed(noResources(e.getErrorCode())));
                }

                @Override
                public void onOutputFormatChanged(MediaCodec mc, MediaFormat format) {
                    int vw = format.getInteger(MediaFormat.KEY_WIDTH);
                    int vh = format.getInteger(MediaFormat.KEY_HEIGHT);
                    if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
                        vw = format.getInteger("crop-right") - format.getInteger("crop-left") + 1;
                    }
                    if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
                        vh = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1;
                    }
                    if (!Limits.sizeAllowed(session.resolution, vw, vh)) {
                        // The SPS asked for more than the limit allows, whatever the codec packet claimed.
                        Log.w(TAG, "decoder " + session.id + ": " + vw + "x" + vh + " is over the limit; disconnecting");
                        session.close();
                        return;
                    }
                    final int fw = vw;
                    final int fh = vh;
                    mainHandler.post(() -> sizeListener.onVideoSize(fw, fh));
                }
            }, handler);
            c.configure(f, surface, null, 0);
            codec = c;
            waitKey = true;
            c.start();
        } catch (Exception e) {
            Log.e(TAG, "decoder " + session.id + " would not start", e);
            boolean outOfDecoders = e instanceof MediaCodec.CodecException
                    && noResources(((MediaCodec.CodecException) e).getErrorCode());
            handler.post(() -> failed(outOfDecoders));
        }
    }

    /**
     * The decoder failed. Out of hardware decoders: this stream can't be shown, so its sender is disconnected
     * (retrying only reaches a bug in Android's ACodec that crashes the whole app). Other errors get three restarts
     * in ten seconds, then the same.
     */
    /** Android's code, or the OMX one Amlogic reports (OMX_ErrorInsufficientResources). */
    private static boolean noResources(int code) {
        return code == MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE || code == 0x80001000;
    }

    private void failed(boolean noResources) {
        stopCodec();
        if (released) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - failuresSince > 10_000) {
            failuresSince = now;
            failures = 0;
        }
        failures++;
        if (noResources || failures > 3) {
            Log.w(TAG, "decoder " + session.id + ": " + (noResources ? "no hardware decoder free" : "keeps failing")
                    + "; disconnecting " + session.name);
            session.close();
            return;
        }
        restart();
    }

    private void stopCodec() {
        freeInputs.clear();
        pending.clear();
        pendingBytes = 0;
        if (codec == null) return;
        MediaCodec c = codec;
        codec = null;
        try {
            c.stop();
        } catch (IllegalStateException ignored) {
        }
        c.release();
    }

    /** Detaches from the session and frees the hardware decoder. Call before the surface goes away. */
    public void release() {
        session.detachSink(this);
        handler.post(() -> {
            released = true;
            stopCodec();
        });
        thread.quitSafely();
        try {
            // The surface must outlive the codec using it.
            thread.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] withStartCode(byte[] nal) {
        byte[] out = new byte[nal.length + 4];
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }
}
