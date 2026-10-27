package com.nailem.streamport;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import com.nailem.streamport.airplay.Session;

import java.nio.ByteBuffer;

/**
 * Plays the audible session's AAC audio: MediaCodec decode, AudioTrack out. Runs on that session's audio
 * thread; one session at a time (the hub drops everyone else's audio before it gets here).
 */
final class AudioPlayer implements Session.AudioSink {

    private static final String TAG = "StreamPort";
    private static final int RATE = 44100;

    private Session current;
    /** Session whose decoder failed to open; skipped until its codec changes, instead of retrying per packet. */
    private Session failed;
    private Session.AudioCodec failedCodec;
    private long failedAt;
    private Session.AudioCodec codecType;
    private MediaCodec codec;
    private AudioTrack track;
    private float volume = -1;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private byte[] pcm = new byte[8192];

    @Override
    public synchronized void onAudio(Session s, byte[] buf, int off, int len) {
        // A failed decoder is retried after 5 s: a burst of bad packets mustn't mute a sender for good.
        if (s == failed && s.audioCodec() == failedCodec && System.currentTimeMillis() - failedAt < 5000) return;
        try {
            if (s != current || s.audioCodec() != codecType || codec == null) {
                open(s);
            }
            if (s.volume() != volume) {
                volume = s.volume();
                track.setVolume(volume);
            }
            int in = codec.dequeueInputBuffer(5_000);
            if (in >= 0) {
                ByteBuffer b = codec.getInputBuffer(in);
                if (b != null && len <= b.capacity()) {
                    b.clear();
                    b.put(buf, off, len);
                    codec.queueInputBuffer(in, 0, len, System.nanoTime() / 1000, 0);
                } else {
                    codec.queueInputBuffer(in, 0, 0, 0, 0);
                }
            }
            drain();
        } catch (Exception e) {
            Log.w(TAG, "audio decode: " + e);
            close();
            failed = s;
            failedCodec = s.audioCodec();
            failedAt = System.currentTimeMillis();
        }
    }

    private void drain() {
        while (true) {
            int out = codec.dequeueOutputBuffer(info, 0);
            if (out < 0) return;
            ByteBuffer b = codec.getOutputBuffer(out);
            if (b != null && info.size > 0) {
                if (pcm.length < info.size) pcm = new byte[info.size];
                b.position(info.offset);
                b.get(pcm, 0, info.size);
                track.write(pcm, 0, info.size);
            }
            codec.releaseOutputBuffer(out, false);
        }
    }

    private void open(Session s) throws Exception {
        close();
        byte[] asc;
        switch (s.audioCodec()) {
            case AAC_ELD:
                // AudioSpecificConfig: ER AAC-ELD, 44.1 kHz, stereo, 480 samples per frame.
                asc = new byte[]{(byte) 0xF8, (byte) 0xE8, 0x50, 0x00};
                break;
            case AAC_LC:
                asc = new byte[]{0x12, 0x10};
                break;
            default:
                throw new IllegalStateException("unsupported audio " + s.audioCodec());
        }
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
        f.setByteBuffer("csd-0", ByteBuffer.wrap(asc));
        f.setInteger(MediaFormat.KEY_IS_ADTS, 0);
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(f, null, null, 0);
        codec.start();
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(min * 2)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        current = s;
        codecType = s.audioCodec();
        volume = -1;
        Log.i(TAG, "audio: playing " + s.name + " (" + codecType + ")");
    }

    @Override
    public synchronized void onAudioEnd(Session s) {
        if (s == current) close();
    }

    synchronized void release() {
        close();
    }

    private void close() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
            }
            codec.release();
            codec = null;
        }
        if (track != null) {
            track.pause();
            track.flush();
            track.release();
            track = null;
        }
        current = null;
        codecType = null;
    }
}
