package com.nailem.streamport.webrtc;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.util.Log;

import com.nailem.streamport.airplay.Limits;

import org.webrtc.EglBase;
import org.webrtc.EncodedImage;
import org.webrtc.HardwareVideoDecoderFactory;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SoftwareVideoEncoderFactory;
import org.webrtc.VideoCodecInfo;
import org.webrtc.VideoDecoder;
import org.webrtc.VideoCodecStatus;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one WebRTC engine of the app, started the first time someone shares from a browser (it loads 6.5 MB of
 * native code, so nobody pays for it until then). Receive only: the TV never captures, encodes or sends media.
 */
final class WebRtcEngine {

    private static final String TAG = "StreamPort";
    private static WebRtcEngine instance;

    final EglBase egl;
    final PeerConnectionFactory factory;
    /** For work that must not run inside WebRTC callbacks (closing and disposing peer connections). */
    final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "webrtc-worker"));

    static synchronized WebRtcEngine get(Context context) {
        if (instance == null) instance = new WebRtcEngine(context.getApplicationContext());
        return instance;
    }

    private WebRtcEngine(Context context) {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions());
        egl = EglBase.create();
        // Plays the primary browser's audio (tab or system sound). Nothing records: no microphone is opened
        // because no local audio track is ever created.
        JavaAudioDeviceModule adm = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .setUseStereoOutput(true)
                .createAudioDeviceModule();
        factory = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(new BoxDecoderFactory(egl.getEglBaseContext()))
                // Required by the factory; never used, since the TV sends nothing.
                .setVideoEncoderFactory(new SoftwareVideoEncoderFactory())
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory();
        adm.release();
        Log.i(TAG, "WebRTC engine ready");
    }

    /**
     * Hardware decoders only (H.264, VP9 and AV1 on this Amlogic chip), each behind a size guard. No software
     * fallback: libvpx or dav1d would decode whatever size a hostile sender claims, in a 32-bit process on a 2 GB
     * box, while a hardware decoder simply refuses what it can't take. VP8 is software-only here, so it is not
     * offered; browsers then use H.264. The ".secure" decoders are for protected content and need a secure
     * surface: never used.
     */
    private static final class BoxDecoderFactory implements VideoDecoderFactory {
        private final HardwareVideoDecoderFactory hardware;

        BoxDecoderFactory(EglBase.Context eglContext) {
            hardware = new HardwareVideoDecoderFactory(eglContext, BoxDecoderFactory::usable);
        }

        private static boolean usable(MediaCodecInfo info) {
            String name = info.getName();
            return info.isHardwareAccelerated() && !name.contains(".secure");
        }

        @Override
        public VideoDecoder createDecoder(VideoCodecInfo codec) {
            VideoDecoder hw = hardware.createDecoder(codec);
            return hw != null ? new SizeGuard(hw) : null;
        }

        @Override
        public VideoCodecInfo[] getSupportedCodecs() {
            return hardware.getSupportedCodecs();
        }
    }

    /**
     * Refuses oversize pictures before the decoder sees them: every keyframe carries its size, and anything over
     * the receiver's resolution limit is not decoded at all (the screen's own check then disconnects it).
     */
    private static final class SizeGuard implements VideoDecoder {
        private final VideoDecoder inner;
        /** Admitted once its first keyframe fitted; its own size is then counted in the hub. */
        private boolean started;

        SizeGuard(VideoDecoder inner) {
            this.inner = inner;
        }

        private static boolean tooBig(int w, int h) {
            return w > 0 && h > 0 && !Limits.sizeAllowed(Limits.resolution(), w, h);
        }

        @Override
        public VideoCodecStatus initDecode(Settings settings, Callback callback) {
            if (tooBig(settings.width, settings.height)) return VideoCodecStatus.ERR_SIZE;
            return inner.initDecode(settings, callback);
        }

        @Override
        public VideoCodecStatus decode(EncodedImage frame, DecodeInfo info) {
            if (frame.frameType == EncodedImage.FrameType.VideoFrameKey && !started
                    && frame.encodedWidth > 0 && com.nailem.streamport.airplay.Hub.get().decodedPixels()
                    + (long) frame.encodedWidth * frame.encodedHeight > com.nailem.streamport.airplay.Hub.get().decodeBudget()) {
                // A new stream that won't fit next to the others: don't touch the hardware decoder at all.
                Log.w(TAG, "refused a " + frame.encodedWidth + "x" + frame.encodedHeight + " stream: the decoders are full");
                return VideoCodecStatus.ERR_SIZE;
            }
            if (frame.frameType == EncodedImage.FrameType.VideoFrameKey) started = true;
            if (frame.frameType == EncodedImage.FrameType.VideoFrameKey && tooBig(frame.encodedWidth, frame.encodedHeight)) {
                Log.w(TAG, "refused a " + frame.encodedWidth + "x" + frame.encodedHeight + " keyframe (over the limit)");
                return VideoCodecStatus.ERR_SIZE;
            }
            return inner.decode(frame, info);
        }

        @Override
        public VideoCodecStatus release() {
            return inner.release();
        }

        @Override
        public String getImplementationName() {
            return inner.getImplementationName();
        }
    }
}
