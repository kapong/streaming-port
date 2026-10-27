package com.nailem.streamport.rebroadcast;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Sends to everyone on the LAN through the box's hardware H.264 encoder (720p at 12 fps by default, see Config),
 * wrapped in MPEG-TS, to UDP multicast {@value #GROUP}:{@value #PORT}. Watch with VLC:
 * {@code udp://@239.255.42.42:5004}. TTL 1, so it never leaves the local network. Video only.
 * <p>
 * Runs whenever the admin page has it switched on. While nobody shares it sends a still picture (the idle screen,
 * {@link #setStill}), so whatever else the TV shows never goes out; while someone shares it sends the TV itself,
 * captured with MediaProjection ({@link #attach}, {@link #detach}).
 */
public final class Rebroadcaster {

    private static final String TAG = "StreamPort";

    public static final String GROUP = "239.255.42.42";
    public static final int PORT = 5004;
    public static final String URL = "udp://@" + GROUP + ":" + PORT;

    /** Screen content compresses well: 4 Mb/s at 1080p keeps text readable without crowding the Wi-Fi. */
    private static final int BITRATE_1080P = 4_000_000;
    /** 7 TS packets per datagram: 1316 bytes, under any LAN's MTU. */
    private static final int PACKETS_PER_DATAGRAM = 7;

    public interface Listener {
        /** The rebroadcast ended on its own (the system stopped the capture, or an error). */
        void onRebroadcastStopped();
    }

    /** The screen capture while someone shares; null while the still picture is sent. Capture thread. */
    private MediaProjection projection;
    private final Listener listener;
    /** The idle picture at the stream's size, RGBA; sent whenever there is no capture. Capture thread. */
    private ByteBuffer still;
    private final int width;
    private final int height;
    private final int fps;
    /** Frames come from the mirror display at up to the TV's 60 Hz; only every this many ms is one converted. */
    private final long frameMs;
    private long nextDue;
    private MediaCodec encoder;
    private android.media.ImageReader reader;
    private android.os.HandlerThread captureThread;
    private volatile android.os.Handler captureHandler;
    /** The last captured frame as I420, re-sent while the screen is still so late joiners get a picture. */
    private ByteBuffer lastY;
    private ByteBuffer lastU;
    private ByteBuffer lastV;
    /** The same frame's chroma interleaved (NV12), for encoders that want semi-planar input. */
    private ByteBuffer lastUV;
    private ByteBuffer scratchY;
    private boolean layoutLogged;
    /** The encoder wants interleaved chroma (NV12); learned from its first input image. */
    private volatile boolean semiPlanar;
    private long lastQueued;
    private static final long REPEAT_MS = 100;
    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (lastY != null && android.os.SystemClock.uptimeMillis() - lastQueued >= REPEAT_MS) queueLast();
            captureHandler.postDelayed(this, REPEAT_MS);
        }
    };
    private VirtualDisplay display;
    private MulticastSocket socket;
    private InetSocketAddress target;
    private Thread drain;
    private volatile boolean running;

    private final byte[] datagram = new byte[TsMuxer.PACKET * PACKETS_PER_DATAGRAM];
    private int filled;
    private byte[] csd;
    private long firstPtsUs = -1;
    private int statFrames;
    private long statBytes;
    private long statSince = android.os.SystemClock.uptimeMillis();

    /** {@code height} 720 or 1080 (16:9); {@code fps} what the network gets, which also bounds the capture cost. */
    public Rebroadcaster(Listener listener, int height, int fps) {
        this.listener = listener;
        this.height = height;
        this.width = height * 16 / 9;
        this.fps = fps;
        this.frameMs = 1000 / fps;
    }

    public void start() throws IOException {
        try {
            // libyuv (RGBA to YUV) is inside the WebRTC library, which is otherwise loaded only by browser sharing.
            System.loadLibrary("jingle_peerconnection_so");
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("libyuv not available: " + e);
        }
        socket = new MulticastSocket();
        socket.setTimeToLive(1);
        socket.setLoopbackMode(true); // true = don't loop back to the box itself
        NetworkInterface lan = lanInterface();
        if (lan != null) socket.setNetworkInterface(lan);
        target = new InetSocketAddress(InetAddress.getByName(GROUP), PORT);

        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        // YUV buffers we fill ourselves: the encoder's own RGB-to-YUV conversion (surface input) manages only about
        // 10 megapixels a second on this box, 5 fps at 1080p. libyuv does it in a few milliseconds per frame.
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        f.setInteger(MediaFormat.KEY_BIT_RATE, (int) ((long) BITRATE_1080P * width * height / (1920 * 1080)));
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // a viewer joining sees a picture within a second
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        captureThread = new android.os.HandlerThread("rebroadcast-capture");
        captureThread.start();
        captureHandler = new android.os.Handler(captureThread.getLooper());
        captureHandler.postDelayed(repeat, REPEAT_MS);
        running = true;
        drain = new Thread(this::drainLoop, "rebroadcast");
        drain.start();
        Log.i(TAG, "rebroadcast: live on " + URL + " (" + width + "x" + height + " at " + fps + " fps) via "
                + (lan != null ? lan.getName() : "default route"));
    }

    public boolean isRunning() {
        return running;
    }

    /** True while the TV itself is sent (someone shares), false while the still picture is. */
    public boolean isMirroring() {
        return mirroring;
    }

    private volatile boolean mirroring;

    /** The picture to send while nobody shares (any size; scaled to the stream's). Any thread; it is not kept. */
    public void setStill(android.graphics.Bitmap picture) {
        android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(picture, width, height, true);
        ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
        scaled.copyPixelsToBuffer(rgba);
        if (scaled != picture) scaled.recycle();
        rgba.rewind();
        android.os.Handler h = captureHandler;
        if (h == null) return;
        h.post(() -> {
            still = rgba;
            if (projection == null && running) sendStill();
        });
    }

    /** Someone shares: send the TV itself. Takes ownership of the projection. */
    public void attach(MediaProjection p, int densityDpi) {
        android.os.Handler h = captureHandler;
        if (h == null) {
            p.stop();
            return;
        }
        h.post(() -> {
            if (!running || projection != null) {
                p.stop();
                return;
            }
            projection = p;
            p.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    // The system ended the capture: back to the still picture, still on air.
                    if (projection == p) {
                        Log.i(TAG, "rebroadcast: capture stopped by the system");
                        releaseCapture();
                    }
                }
            }, captureHandler);
            // CPU_READ_OFTEN: cached memory. By default the GPU's output lands in uncached memory, and reading 8 MB
            // a frame from it cost most of a CPU core.
            reader = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 3,
                    android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN | android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            reader.setOnImageAvailableListener(this::onCapture, captureHandler);
            display = p.createVirtualDisplay("streamport-rebroadcast", width, height, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
            mirroring = true;
            Log.i(TAG, "rebroadcast: sending the TV");
        });
    }

    /** Nobody shares any more: back to the still picture. */
    public void detach() {
        android.os.Handler h = captureHandler;
        if (h != null) h.post(this::releaseCapture);
    }

    /** Capture thread. */
    private void releaseCapture() {
        if (display != null) {
            display.release();
            display = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            p.stop();
        }
        if (mirroring) Log.i(TAG, "rebroadcast: sending the idle screen");
        mirroring = false;
        nextDue = 0;
        if (running) sendStill();
    }

    /** Capture thread. */
    private void sendStill() {
        if (still == null) return;
        toYuv(still, width * 4);
        queueLast();
    }

    /** Idempotent. Stops capture, encoder and sending. */
    public synchronized void stop() {
        if (!running && encoder == null) return;
        running = false;
        android.os.Handler h = captureHandler;
        if (h != null) {
            // The capture objects belong to the capture thread; let it let go of them before it ends.
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            if (h.post(() -> {
                releaseCapture();
                done.countDown();
            })) {
                try {
                    done.await(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (drain != null && drain != Thread.currentThread()) {
            try {
                drain.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (IllegalStateException ignored) {
            }
            encoder.release();
            encoder = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
        captureHandler = null;
        if (socket != null) socket.close();
        Log.i(TAG, "rebroadcast: stopped");
    }

    /** Capture thread: RGBA frame from the mirror display to I420, then into the encoder. */
    private void onCapture(android.media.ImageReader r) {
        try {
            capture(r);
        } catch (Throwable e) {
            // Never take the whole app down over the rebroadcast: stop it and say so.
            Log.e(TAG, "rebroadcast: capture failed, stopping", e);
            new Thread(() -> {
                stop();
                listener.onRebroadcastStopped();
            }, "rebroadcast-stop").start();
        }
    }

    private void capture(android.media.ImageReader r) {
        android.media.Image img;
        try {
            img = r.acquireLatestImage();
        } catch (IllegalStateException e) {
            return;
        }
        if (img == null) return;
        try {
            if (!running) return;
            // The image is still taken (and closed) every time, so the mirror display never waits on us.
            long now = android.os.SystemClock.uptimeMillis();
            if (now < nextDue) return;
            // Keep the cadence (frames arrive on the TV's vsync), but don't catch up after a pause.
            nextDue = Math.max(nextDue + frameMs, now - frameMs / 2);
            android.media.Image.Plane rgba = img.getPlanes()[0];
            toYuv(rgba.getBuffer(), rgba.getRowStride());
        } finally {
            img.close();
        }
        queueLast();
    }

    /** RGBA (Android's RGBA_8888, libyuv's "ABGR") into the last-frame I420 (and NV12) buffers. Capture thread. */
    private void toYuv(ByteBuffer rgba, int rowStride) {
        if (lastY == null) {
            lastY = ByteBuffer.allocateDirect(width * height);
            lastU = ByteBuffer.allocateDirect(width * height / 4);
            lastV = ByteBuffer.allocateDirect(width * height / 4);
            lastUV = ByteBuffer.allocateDirect(width * height / 2);
            scratchY = ByteBuffer.allocateDirect(width * height);
        }
        org.webrtc.YuvHelper.ABGRToI420(rgba, rowStride, lastY, width,
                lastU, width / 2, lastV, width / 2, width, height);
        if (semiPlanar) {
            org.webrtc.YuvHelper.I420ToNV12(lastY, width, lastU, width / 2, lastV, width / 2,
                    scratchY, width, lastUV, width, width, height);
        }
    }

    /** Copies the last I420 frame into a free encoder input; if the encoder is busy the frame is skipped. */
    private void queueLast() {
        MediaCodec enc = encoder;
        if (enc == null || !running) return;
        try {
            int index = enc.dequeueInputBuffer(0);
            if (index < 0) return; // encoder busy: drop this frame rather than build up delay
            android.media.Image in = enc.getInputImage(index);
            if (in == null) {
                enc.queueInputBuffer(index, 0, 0, 0, 0);
                return;
            }
            android.media.Image.Plane[] planes = in.getPlanes();
            if (!layoutLogged) {
                layoutLogged = true;
                Log.i(TAG, "rebroadcast: encoder input Y stride " + planes[0].getRowStride() + ", chroma stride "
                        + planes[1].getRowStride() + " step " + planes[1].getPixelStride());
            }
            copyPlane(lastY, width, width, height, planes[0]);
            semiPlanar = planes[1].getPixelStride() == 2;
            if (semiPlanar && lastUV.position() == 0 && lastUV.get(0) == 0 && lastUV.get(1) == 0) {
                // First frame on an NV12 encoder: interleave now (later frames are interleaved at capture).
                org.webrtc.YuvHelper.I420ToNV12(lastY, width, lastU, width / 2, lastV, width / 2,
                        scratchY, width, lastUV, width, width, height);
            }
            if (semiPlanar) {
                copyInterleaved(planes[1], planes[2]);
            } else {
                copyPlane(lastU, width / 2, width / 2, height / 2, planes[1]);
                copyPlane(lastV, width / 2, width / 2, height / 2, planes[2]);
            }
            lastQueued = android.os.SystemClock.uptimeMillis();
            enc.queueInputBuffer(index, 0, width * height * 3 / 2, System.nanoTime() / 1000, 0);
        } catch (IllegalStateException e) {
            // Encoder stopping.
        }
    }

    /**
     * NV12 chroma, row by row. The U plane's buffer views the interleaved UV block but stops one byte short of its
     * end (that byte is the V plane's last); it is written through the V plane.
     */
    private void copyInterleaved(android.media.Image.Plane uPlane, android.media.Image.Plane vPlane) {
        ByteBuffer out = uPlane.getBuffer();
        int rowStride = uPlane.getRowStride();
        for (int y = 0; y < height / 2; y++) {
            int at = y * rowStride;
            int n = Math.min(width, out.limit() - at);
            if (n <= 0) break;
            lastUV.limit(y * width + n).position(y * width);
            out.position(at);
            out.put(lastUV);
            if (n < width) {
                ByteBuffer v = vPlane.getBuffer();
                v.put(v.limit() - 1, lastUV.get(y * width + width - 1));
            }
        }
        lastUV.clear();
    }

    /** One plane into the encoder's image, whatever its row and pixel strides (planar or interleaved chroma). */
    private static void copyPlane(ByteBuffer src, int srcStride, int width, int height, android.media.Image.Plane dst) {
        ByteBuffer out = dst.getBuffer();
        int rowStride = dst.getRowStride();
        int pixelStride = dst.getPixelStride();
        if (pixelStride == 1 && rowStride == srcStride) {
            src.position(0).limit(srcStride * height);
            out.position(0);
            out.put(src);
            return;
        }
        byte[] row = new byte[width];
        for (int y = 0; y < height; y++) {
            src.position(y * srcStride);
            src.get(row, 0, width);
            int base = y * rowStride;
            if (pixelStride == 1) {
                out.position(base);
                out.put(row, 0, width);
            } else {
                for (int x = 0; x < width; x++) {
                    int at = base + x * pixelStride;
                    if (at < out.limit()) out.put(at, row[x]);
                }
            }
        }
    }

    private void drainLoop() {
        TsMuxer muxer = new TsMuxer(this::onPacket);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (running) {
                int index = encoder.dequeueOutputBuffer(info, 100_000);
                if (index < 0) continue;
                ByteBuffer out = encoder.getOutputBuffer(index);
                if (out != null && info.size > 0) {
                    byte[] data = new byte[info.size];
                    out.position(info.offset);
                    out.get(data);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        csd = data; // SPS + PPS, Annex-B
                    } else {
                        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        if (key && csd != null) {
                            // Every keyframe carries SPS/PPS, so anyone can start watching from it.
                            byte[] withCsd = new byte[csd.length + data.length];
                            System.arraycopy(csd, 0, withCsd, 0, csd.length);
                            System.arraycopy(data, 0, withCsd, csd.length, data.length);
                            data = withCsd;
                        }
                        if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs;
                        long pts90k = 90_000 + (info.presentationTimeUs - firstPtsUs) * 9 / 100;
                        muxer.writeFrame(data, 0, data.length, pts90k, key);
                        flush(); // one frame, sent now: keeps latency at a frame or two
                        statFrames++;
                        statBytes += data.length;
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - statSince >= 5000) {
                            double secs = (now - statSince) / 1000.0;
                            Log.i(TAG, String.format(java.util.Locale.ROOT, "rebroadcast: %.1f fps, %.2f Mb/s",
                                    statFrames / secs, statBytes * 8 / secs / 1e6));
                            statFrames = 0;
                            statBytes = 0;
                            statSince = now;
                        }
                    }
                }
                encoder.releaseOutputBuffer(index, false);
            }
        } catch (IllegalStateException e) {
            if (running) Log.w(TAG, "rebroadcast: encoder stopped: " + e);
        }
    }

    private void onPacket(byte[] packet) {
        System.arraycopy(packet, 0, datagram, filled, TsMuxer.PACKET);
        filled += TsMuxer.PACKET;
        if (filled == datagram.length) flush();
    }

    private void flush() {
        if (filled == 0) return;
        try {
            socket.send(new DatagramPacket(datagram, filled, target));
        } catch (IOException e) {
            // A dropped datagram is a glitch for viewers, not a reason to stop.
        }
        filled = 0;
    }

    /** The Wi-Fi (or Ethernet) interface with the box's LAN address, so multicast doesn't go out a wrong route. */
    private static NetworkInterface lanInterface() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return ni;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
