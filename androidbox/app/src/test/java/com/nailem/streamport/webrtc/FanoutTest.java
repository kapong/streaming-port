package com.nailem.streamport.webrtc;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.webrtc.EncodedImage;
import org.webrtc.VideoEncoder;
import org.webrtc.VideoFrame;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class FanoutTest {

    private long now;
    private final List<Runnable> posted = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();

    @Before
    public void fakeTime() {
        now = 10_000;
        Fanout.clock = () -> now;
        Fanout.later = (r, ms) -> {
            posted.add(r);
            delays.add(ms);
        };
    }

    @After
    public void drain() {
        runPosted(); // leaves no request pending for the next test
        Fanout.encoderStarted(null);
    }

    private void runPosted() {
        List<Runnable> run = new ArrayList<>(posted);
        posted.clear();
        delays.clear();
        for (Runnable r : run) r.run();
    }

    private static VideoFrame frame(long seq, boolean key) {
        return new VideoFrame(new Fanout.Payload(ByteBuffer.allocateDirect(8), key, seq, 1280, 720), 0, seq);
    }

    @Test
    public void heldUntilKeyframeAndAfterAGap() {
        List<String> out = new ArrayList<>();
        Fanout.PassEncoder enc = new Fanout.PassEncoder();
        enc.initEncode(null, new VideoEncoder.Callback() {
            @Override
            public void onEncodedFrame(EncodedImage image, VideoEncoder.CodecSpecificInfo info) {
                out.add(image.frameType == EncodedImage.FrameType.VideoFrameKey ? "K" : "d");
            }
        });
        enc.encode(frame(1, false), null); // joined mid-GOP: held
        enc.encode(frame(2, true), null);
        enc.encode(frame(3, false), null);
        enc.encode(frame(5, false), null); // 4 dropped before us: held from here
        enc.encode(frame(6, false), null);
        enc.encode(frame(7, true), null); // keyframe (with SPS/PPS) restarts
        enc.encode(frame(8, false), null);
        assertEquals(List.of("K", "d", "K", "d"), out);
    }

    @Test
    public void keyframeRequestsCoalesced() {
        AtomicInteger asked = new AtomicInteger();
        Fanout.encoderStarted(asked::incrementAndGet);
        Fanout.requestKeyframe();
        Fanout.requestKeyframe();
        Fanout.requestKeyframe();
        assertEquals(1, posted.size());
        runPosted();
        assertEquals(1, asked.get());

        now += 100; // within 500 ms of the last one: waits out the rest
        Fanout.requestKeyframe();
        Fanout.requestKeyframe();
        assertEquals(List.of(400L), delays);
        runPosted();
        assertEquals(2, asked.get());

        now += 600;
        Fanout.requestKeyframe();
        assertEquals(List.of(0L), delays);
    }
}
