package com.nailem.streamport.admin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AdminServerTest {

    @Test
    public void hostsThatReachTheBoxDirectlyAreAllowed() {
        assertTrue(AdminServer.hostAllowed("192.168.1.23:8089"));
        assertTrue(AdminServer.hostAllowed("192.168.1.23"));
        assertTrue(AdminServer.hostAllowed("[fe80::1%25wlan0]:8089"));
        assertTrue(AdminServer.hostAllowed("android.local:8089"));
    }

    /** A DNS-rebinding page arrives with its own domain in Host. */
    @Test
    public void otherNamesAreRefused() {
        assertFalse(AdminServer.hostAllowed("evil.example.com"));
        assertFalse(AdminServer.hostAllowed("evil.example.com:8089"));
        assertFalse(AdminServer.hostAllowed("192.168.1.23.evil.com"));
        assertFalse(AdminServer.hostAllowed("local"));
        assertFalse(AdminServer.hostAllowed(""));
        assertFalse(AdminServer.hostAllowed(null));
        assertFalse(AdminServer.hostAllowed("192.168.1.23:80a"));
    }

    @Test
    public void nestingIsMeasuredOutsideStrings() {
        org.junit.Assert.assertEquals(1, AdminServer.nesting("{\"pin\":\"[[[[\"}".getBytes()));
        org.junit.Assert.assertEquals(3, AdminServer.nesting("{\"a\":[{\"b\":1}]}".getBytes()));
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 400; i++) deep.append('[');
        org.junit.Assert.assertEquals(400, AdminServer.nesting(deep.toString().getBytes()));
    }
}
