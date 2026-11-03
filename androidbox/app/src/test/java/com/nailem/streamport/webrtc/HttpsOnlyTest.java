package com.nailem.streamport.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.net.InetAddress;

import org.junit.Test;

public class HttpsOnlyTest {

    private static final String BOX = "192.168.1.5:8089";

    @Test
    public void plainHttpRequestsRedirectToTheSamePathOnHttps() throws Exception {
        assertEquals("https://192.168.1.5:9443/whip?name=a%20b",
                HttpsOnly.location("GET /whip?name=a%20b HTTP/1.1", "192.168.1.5:9443"));
        assertEquals("https://" + BOX + "/", HttpsOnly.location("GET / HTTP/1.0", BOX));
        // Absolute-form, protocol-relative and asterisk targets go to the front page, never elsewhere.
        assertEquals("https://" + BOX + "/", HttpsOnly.location("GET http://evil.example/x HTTP/1.1", BOX));
        assertEquals("https://" + BOX + "/", HttpsOnly.location("GET //evil.example/x HTTP/1.1", BOX));
        assertEquals("https://" + BOX + "/", HttpsOnly.location("GET * HTTP/1.1", BOX));
        // A backslash lands in the path: the authority stays the box's.
        assertEquals("https://" + BOX + "/\\evil.example", HttpsOnly.location("GET /\\evil.example HTTP/1.1", BOX));
        assertNull(HttpsOnly.location("PRI * HTTP/2.0", BOX));
        assertNull(HttpsOnly.location("GET /\u0001 HTTP/1.1", BOX));
        assertNull(HttpsOnly.location("hello", BOX));
        assertNull(HttpsOnly.location(null, BOX));
        // No Host header: the address the client dialled.
        assertEquals(BOX, HttpsOnly.authority(null, InetAddress.getByName("192.168.1.5"), 8089));
        assertEquals("[fe80:0:0:0:0:0:0:1]:8089", HttpsOnly.authority(null, InetAddress.getByName("fe80::1%1"), 8089));
    }
}
