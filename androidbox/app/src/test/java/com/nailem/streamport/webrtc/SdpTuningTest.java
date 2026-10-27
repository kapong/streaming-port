package com.nailem.streamport.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SdpTuningTest {

    private static final String ANSWER = String.join("\r\n",
            "v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111", "a=rtpmap:111 opus/48000/2", "a=fmtp:111 minptime=10;useinbandfec=1",
            "m=video 9 UDP/TLS/RTP/SAVPF 96 97 98", "a=rtpmap:96 H264/90000",
            "a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
            "a=rtpmap:97 rtx/90000", "a=fmtp:97 apt=96", "a=rtpmap:98 VP8/90000", "") ;

    @Test
    public void videoCodecsGetBitratesAudioAndRtxDoNot() {
        String out = SdpTuning.withBitrates(ANSWER, 4000, 1000, 8000);
        String p = "x-google-start-bitrate=4000;x-google-min-bitrate=1000;x-google-max-bitrate=8000";
        assertTrue(out.contains("a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f;" + p + "\r\n"));
        assertTrue("VP8 had no fmtp, so one is added", out.contains("a=rtpmap:98 VP8/90000\r\na=fmtp:98 " + p + "\r\n"));
        assertTrue(out.contains("a=fmtp:97 apt=96\r\n"));
        assertTrue(out.contains("a=fmtp:111 minptime=10;useinbandfec=1\r\n"));
        assertFalse(out.contains("\n\n"));
        assertEquals("H264 and VP8 only", 2, out.split("x-google-start-bitrate", -1).length - 1);
    }

    private static final String OFFER = String.join("\r\n",
            "v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE 0 1",
            "m=video 9 UDP/TLS/RTP/SAVPF 96", "a=rtpmap:96 H264/90000",
            "a=candidate:1 1 udp 2122260223 192.168.1.40 51000 typ host",
            "a=candidate:2 1 udp 2122260223 4f2b8d1e-1c2a-4bd2-9e4f-6c9c1d2e3f40.local 51001 typ host",
            "a=candidate:3 1 udp 1686052607 203.0.113.7 51002 typ srflx raddr 0.0.0.0 rport 0",
            "a=rid:hi send", "a=simulcast:send hi",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111", "a=rtpmap:111 opus/48000/2", "");

    @Test
    public void offerKeepsLanCandidatesAndDropsTheRest() {
        String out = SdpTuning.sanitizeOffer(OFFER);
        assertTrue(out.contains("192.168.1.40 51000"));
        assertTrue(out.contains(".local 51001"));
        assertFalse("public address dropped", out.contains("203.0.113.7"));
        assertFalse(out.contains("a=simulcast"));
        assertFalse(out.contains("a=rid:"));
    }

    @Test
    public void offerShapeIsEnforced() {
        org.junit.Assert.assertNull("data channel", SdpTuning.sanitizeOffer(OFFER + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"));
        org.junit.Assert.assertNull("two videos", SdpTuning.sanitizeOffer(OFFER + "m=video 9 UDP/TLS/RTP/SAVPF 97\r\n"));
        org.junit.Assert.assertNull("audio only", SdpTuning.sanitizeOffer(
                "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"));
        org.junit.Assert.assertNull("not SDP", SdpTuning.sanitizeOffer("hello"));
    }
}
