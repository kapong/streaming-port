package com.nailem.streamport.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class HttpsOnlyTest {

    @Test
    public void plainHttpRequestsRedirectToTheSamePathOnHttps() {
        assertEquals("https://192.168.1.5:9443/whip?name=a%20b",
                HttpsOnly.location("GET /whip?name=a%20b HTTP/1.1", "192.168.1.5:9443"));
        assertEquals("https://192.168.1.5:8089/", HttpsOnly.location("GET / HTTP/1.0", "192.168.1.5:8089"));
        // Absolute-form and protocol-relative targets go to the front page, never elsewhere.
        assertEquals("https://192.168.1.5:8089/",
                HttpsOnly.location("GET http://evil.example/x HTTP/1.1", "192.168.1.5:8089"));
        assertEquals("https://192.168.1.5:8089/", HttpsOnly.location("GET //evil.example/x HTTP/1.1", "192.168.1.5:8089"));
        assertNull(HttpsOnly.location("GET /\u0001 HTTP/1.1", "192.168.1.5:8089"));
        assertNull(HttpsOnly.location("hello", "192.168.1.5:8089"));
        assertNull(HttpsOnly.location(null, "192.168.1.5:8089"));
    }
}
