package com.nailem.streamport.airplay;

import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.nailem.streamport.VideoDecoder;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One sender (iPhone, iPad or Mac) and its media sockets. Created at its first RTSP SETUP, closed when its
 * control connection drops, it sends a full TEARDOWN, or someone refuses or disconnects it on the TV.
 * <p>
 * The media sockets only accept the address the control connection came from, so another host on the network
 * can't push frames or audio into someone else's tile.
 */
public final class Session implements Screen {

    private static final String TAG = "StreamPort";

    private static final int HEADER = 128;
    private static final int TYPE_VIDEO = 0;
    private static final int TYPE_CODEC = 1;

    /** Where decoded-ready H.264 goes. Called on the session's video thread. */
    public interface VideoSink {
        void onCodecConfig(byte[] sps, byte[] pps);

        void onFrame(byte[] annexB, boolean keyframe);
    }

    /** Where decrypted audio access units go. Called on the session's audio thread. */
    public interface AudioSink {
        void onAudio(Session session, byte[] buf, int off, int len);

        void onAudioEnd(Session session);
    }

    public enum AudioCodec { AAC_ELD, AAC_LC, UNSUPPORTED }

    public final int id;
    /**
     * The limits in force when this sender joined. Lowering them on the admin page applies to newcomers and never
     * cuts off someone already mirroring.
     */
    public final Limits.Resolution resolution = Limits.resolution();
    /** Delivery quality of this sender's video, for the tile badge and the admin page. */
    public final NetStats stats = new NetStats();
    public final String name;
    public final String model;
    public final String deviceId;
    final InetAddress peer;

    private final Hub hub;
    private final byte[] streamMasterKey;
    private final byte[] eiv;
    private final List<Closeable> sockets = new ArrayList<>();
    private volatile boolean closed;
    private volatile State state = State.PENDING;

    // Video, guarded by "this".
    private byte[] sps;
    private byte[] pps;
    private final List<byte[]> gop = new ArrayList<>();
    private int gopBytes;
    private boolean gopValid;
    private VideoSink videoSink;
    private volatile int sourceWidth;
    private volatile int sourceHeight;

    private int videoGeneration;
    private Closeable videoSocket;
    private volatile boolean videoConnected;
    private volatile boolean videoFrameSeen;

    // Audio.
    private DatagramSocket audioData;
    private DatagramSocket audioControl;
    volatile AudioSink audioSink;
    volatile AudioCodec audioCodec = AudioCodec.UNSUPPORTED;
    volatile int samplesPerFrame = 480;
    volatile float volume = 1f;

    Session(Hub hub, int id, String name, String model, String deviceId, InetAddress peer,
            byte[] streamMasterKey, byte[] eiv) {
        this.hub = hub;
        this.id = id;
        this.name = name;
        this.model = model;
        this.deviceId = deviceId;
        this.peer = peer;
        this.streamMasterKey = streamMasterKey;
        this.eiv = eiv;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public Kind kind() {
        return Kind.AIRPLAY;
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
        // The device ID is whatever the sender claims, so it is paired with the address.
        return deviceId == null ? null : deviceId + "@" + peer.getHostAddress();
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public void setState(State s) {
        state = s;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public NetStats.Snapshot stats() {
        return stats.snapshot();
    }

    @Override
    public void setAudible(boolean audible) {
        AudioSink old = audioSink;
        audioSink = audible ? hub.audioSink() : null;
        if (!audible && old != null) old.onAudioEnd(this);
    }

    /** AirPlay video goes through our own hardware decoder straight onto the tile's texture. */
    @Override
    public VideoOutput attachVideo(SurfaceTexture texture, SizeListener listener) {
        Surface surface = new Surface(texture);
        VideoDecoder decoder = new VideoDecoder(this, surface, listener, new Handler(Looper.getMainLooper()));
        return () -> {
            decoder.release();
            surface.release();
        };
    }

    /** Video size from the sender's codec packet; 0 until the first one. The decoder's output size wins. */
    @Override
    public int sourceWidth() {
        return sourceWidth;
    }

    @Override
    public int sourceHeight() {
        return sourceHeight;
    }

    public float volume() {
        return volume;
    }

    public AudioCodec audioCodec() {
        return audioCodec;
    }

    public int samplesPerFrame() {
        return samplesPerFrame;
    }

    /** Attaches a decoder and replays the codec config plus frames since the last keyframe. */
    public void attachSink(VideoSink sink) {
        synchronized (this) {
            videoSink = sink;
            if (sink == null || sps == null) return;
            sink.onCodecConfig(sps, pps);
            if (gopValid) {
                for (int i = 0; i < gop.size(); i++) {
                    sink.onFrame(gop.get(i), i == 0);
                }
            }
        }
    }

    public synchronized void detachSink(VideoSink sink) {
        if (videoSink == sink) videoSink = null;
    }

    /** Closes every socket; the threads reading them end and the hub forgets the session. */
    @Override
    public void close() {
        synchronized (sockets) {
            if (closed) return;
            closed = true;
            for (Closeable c : sockets) {
                try {
                    c.close();
                } catch (IOException ignored) {
                }
            }
            sockets.clear();
        }
        AudioSink a = audioSink;
        if (a != null) a.onAudioEnd(this);
        hub.remove(this);
    }

    private void track(Closeable c) throws IOException {
        synchronized (sockets) {
            if (closed) {
                c.close();
                throw new IOException("session closed");
            }
            sockets.add(c);
        }
    }

    /** The sender's RTSP connection: closing the session (kick, refuse) hangs it up too. */
    void trackControl(Closeable control) {
        try {
            track(control);
        } catch (IOException ignored) {
            // Already closed; track() closed the socket.
        }
    }

    /**
     * True once the sender sent real video (a codec or frame packet); {@link Hub} closes sessions that don't,
     * so a script can't hold places with a connected but empty stream.
     */
    @Override
    public boolean hasVideo() {
        return videoConnected && videoFrameSeen;
    }

    private void untrack(Closeable c) {
        if (c == null) return;
        synchronized (sockets) {
            sockets.remove(c);
        }
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    // ---- video ----

    /** Opens the mirroring data port and returns its number. One connection, from the sender only. */
    int startVideo(String streamConnectionId) throws Exception {
        StreamCiphers.Video cipher = new StreamCiphers.Video(streamMasterKey, streamConnectionId);
        ServerSocket server = new ServerSocket();
        track(server);
        int generation;
        synchronized (this) {
            // A new video SETUP replaces the old stream; its thread must not end the session when it stops.
            untrack(videoSocket);
            videoSocket = server;
            generation = ++videoGeneration;
        }
        server.bind(new InetSocketAddress(0), 1);
        server.setSoTimeout(15_000);
        Thread t = new Thread(() -> {
            Socket s = null;
            try {
                while (true) {
                    s = server.accept();
                    if (s.getInetAddress().equals(peer)) break;
                    Log.w(TAG, "video: refused connection from " + s.getInetAddress());
                    s.close();
                }
                untrack(server);
                track(s);
                synchronized (this) {
                    if (generation != videoGeneration) {
                        untrack(s);
                        return;
                    }
                    videoSocket = s;
                }
                videoConnected = true;
                s.setReceiveBufferSize(1 << 20);
                // iOS sends a heartbeat at least every second while mirroring; 30 s of silence means it's gone.
                s.setSoTimeout(30_000);
                readVideo(s.getInputStream(), cipher);
            } catch (SocketTimeoutException e) {
                Log.i(TAG, "video: sender went quiet, " + name);
            } catch (Exception e) {
                if (!closed) Log.i(TAG, "video: " + name + " ended: " + e);
            } finally {
                boolean current;
                synchronized (this) {
                    current = generation == videoGeneration;
                }
                if (current) close();
            }
        }, "video-" + id);
        t.start();
        return server.getLocalPort();
    }

    private void readVideo(InputStream raw, StreamCiphers.Video cipher) throws Exception {
        DataInputStream in = new DataInputStream(raw);
        byte[] header = new byte[HEADER];
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        byte[] skip = new byte[64 * 1024];
        while (!closed) {
            in.readFully(header);
            long size = h.getInt(0) & 0xFFFFFFFFL;
            int type = header[4] & 0xFF;
            if (size > Limits.maxPacket(resolution)) {
                throw new IOException("video packet too big: " + size);
            }
            stats.onPacket(h.getLong(8), (int) size, type == TYPE_VIDEO);
            if (type == TYPE_VIDEO || type == TYPE_CODEC) {
                videoFrameSeen = true;
                byte[] payload = new byte[(int) size];
                in.readFully(payload);
                if (type == TYPE_VIDEO) {
                    // Only type 0 is encrypted; decrypting anything else would shift the keystream.
                    cipher.decrypt(payload, 0, payload.length);
                    onVideoPacket(payload);
                } else {
                    onCodecPacket(header, h, payload);
                }
            } else {
                long left = size;
                while (left > 0) {
                    int n = in.read(skip, 0, (int) Math.min(skip.length, left));
                    if (n < 0) throw new IOException("eof");
                    left -= n;
                }
            }
        }
    }

    private void onCodecPacket(byte[] header, ByteBuffer h, byte[] p) {
        // avcC-style: 6 bytes of profile/level/flags, u16 SPS length, SPS, u8 PPS count, u16 PPS length, PPS.
        if (p.length < 8) return;
        int spsLen = ((p[6] & 0xFF) << 8) | (p[7] & 0xFF);
        int ppsAt = 8 + spsLen;
        if (spsLen == 0 || ppsAt + 3 > p.length) return;
        int ppsLen = ((p[ppsAt + 1] & 0xFF) << 8) | (p[ppsAt + 2] & 0xFF);
        if (ppsLen == 0 || ppsAt + 3 + ppsLen > p.length) return;
        byte[] newSps = Arrays.copyOfRange(p, 8, ppsAt);
        byte[] newPps = Arrays.copyOfRange(p, ppsAt + 3, ppsAt + 3 + ppsLen);
        float w = h.getFloat(56);
        float hh = h.getFloat(60);
        if (!(w >= 16 && w <= 8192 && hh >= 16 && hh <= 8192)) {
            w = h.getFloat(16);
            hh = h.getFloat(20);
        }
        if (w >= 16 && w <= 8192 && hh >= 16 && hh <= 8192) {
            int nw = Math.round(w);
            int nh = Math.round(hh);
            if (!Limits.sizeAllowed(resolution, nw, nh)) {
                // The box's video decoder is shared by all four tiles; nobody gets to take it with a 4K stream.
                Log.w(TAG, name + " sent " + nw + "x" + nh + ", over the " + resolution.key
                        + " limit; disconnecting");
                close();
                return;
            }
            if (!hub.fitsDecoder(this, nw, nh)) {
                Log.w(TAG, name + " sends " + nw + "x" + nh + " but the TV's decoders are full; disconnecting");
                close();
                return;
            }
            if (nw != sourceWidth || nh != sourceHeight) {
                sourceWidth = nw;
                sourceHeight = nh;
                hub.onVideoChanged(this);
            }
        }
        synchronized (this) {
            if (Arrays.equals(newSps, sps) && Arrays.equals(newPps, pps)) return;
            sps = newSps;
            pps = newPps;
            gop.clear();
            gopBytes = 0;
            gopValid = false;
            if (videoSink != null) videoSink.onCodecConfig(sps, pps);
        }
    }

    /** Video TEARDOWN: the sender stopped mirroring but may keep the session (it usually ends it next). */
    synchronized void stopVideo() {
        videoGeneration++;
        untrack(videoSocket);
        videoSocket = null;
    }

    private void onVideoPacket(byte[] payload) {
        if (payload.length == 0) return;
        // AVCC (4-byte big-endian lengths) to Annex-B (start codes), in place. A bad length means a bad
        // decrypt or a hostile sender; drop the frame rather than hand the decoder garbage.
        boolean keyframe = false;
        int i = 0;
        while (i + 4 <= payload.length) {
            long n = ((payload[i] & 0xFFL) << 24) | ((payload[i + 1] & 0xFF) << 16)
                    | ((payload[i + 2] & 0xFF) << 8) | (payload[i + 3] & 0xFF);
            if (n <= 0 || i + 4 + n > payload.length) return;
            payload[i] = 0;
            payload[i + 1] = 0;
            payload[i + 2] = 0;
            payload[i + 3] = 1;
            if ((payload[i + 4] & 0x1F) == 5) keyframe = true;
            i += 4 + (int) n;
        }
        if (i != payload.length) return;
        synchronized (this) {
            if (keyframe) {
                gop.clear();
                gopBytes = 0;
                gopValid = true;
            }
            if (gopValid) {
                // Frames since the last keyframe, kept so a tile that attaches late can start at once.
                if (gopBytes + payload.length > 2L * Limits.maxPacket(resolution)) {
                    gop.clear();
                    gopBytes = 0;
                    gopValid = false;
                } else {
                    gop.add(payload);
                    gopBytes += payload.length;
                }
            }
            if (videoSink != null) videoSink.onFrame(payload, keyframe);
        }
    }

    // ---- audio ----

    /** Opens the realtime audio data and control ports. Returns {dataPort, controlPort}. */
    int[] startAudio(AudioCodec codec, int spf) throws Exception {
        if (eiv == null) throw new IllegalStateException("no eiv");
        StreamCiphers.Audio cipher = new StreamCiphers.Audio(streamMasterKey, eiv);
        stopAudio();
        audioCodec = codec;
        samplesPerFrame = spf;
        DatagramSocket data = new DatagramSocket(0);
        track(data);
        // Retransmit requests and sync packets arrive here. We play what arrives and never ask for resends, so
        // nothing reads it; the kernel drops what overflows the socket buffer.
        DatagramSocket control = new DatagramSocket(0);
        track(control);
        synchronized (this) {
            audioData = data;
            audioControl = control;
        }
        data.setReceiveBufferSize(256 * 1024);
        Thread t = new Thread(() -> readAudio(data, cipher), "audio-" + id);
        t.start();
        return new int[]{data.getLocalPort(), control.getLocalPort()};
    }

    private void readAudio(DatagramSocket socket, StreamCiphers.Audio cipher) {
        byte[] buf = new byte[2048];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        int lastSeq = -1;
        int senderPort = -1;
        int resyncSeq = -1;
        int resyncCount = 0;
        try {
            while (!closed) {
                pkt.setLength(buf.length);
                socket.receive(pkt);
                if (!peer.equals(pkt.getAddress()) || pkt.getLength() <= 12) continue;
                // Lock onto the sender's source port: a spoofed packet has to guess it as well as the address.
                if (senderPort < 0) senderPort = pkt.getPort();
                else if (pkt.getPort() != senderPort) continue;
                int seq = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
                if (lastSeq >= 0) {
                    // 16-bit sequence numbers wrap about every 12 minutes; compare modulo 2^16.
                    int ahead = (seq - lastSeq) & 0xFFFF;
                    if (ahead == 0 || ahead > 0x8000) continue;
                    if (ahead > 256) {
                        // A big jump is a real gap only if the next packets agree with it; one odd packet (or a
                        // spoofed one) must not push lastSeq ahead and make every genuine packet look stale.
                        if (resyncSeq >= 0 && ((seq - resyncSeq) & 0xFFFF) >= 1 && ((seq - resyncSeq) & 0xFFFF) <= 4) {
                            resyncCount++;
                        } else {
                            resyncCount = 1;
                        }
                        resyncSeq = seq;
                        if (resyncCount < 3) continue;
                    }
                    resyncCount = 0;
                    resyncSeq = -1;
                }
                lastSeq = seq;
                AudioSink sink = audioSink;
                // Nothing is heard from a sender until someone at the TV lets it in.
                if (sink == null || state != State.ALLOWED || audioCodec == AudioCodec.UNSUPPORTED) continue;
                int len = pkt.getLength() - 12;
                cipher.decrypt(buf, 12, len);
                sink.onAudio(this, buf, 12, len);
            }
        } catch (Exception e) {
            if (!closed && !socket.isClosed()) Log.i(TAG, "audio: " + name + " ended: " + e);
        }
    }

    void stopAudio() {
        synchronized (this) {
            untrack(audioData);
            untrack(audioControl);
            audioData = null;
            audioControl = null;
        }
        AudioSink a = audioSink;
        if (a != null) a.onAudioEnd(this);
        audioCodec = AudioCodec.UNSUPPORTED;
    }
}
