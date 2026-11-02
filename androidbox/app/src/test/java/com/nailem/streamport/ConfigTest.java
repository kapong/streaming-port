package com.nailem.streamport;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ConfigTest {

    @Test
    public void cleanNameKeepsTheNameOneMdnsLabel() {
        assertEquals("Room 3 2", Config.cleanName("Room 3.2"));
        assertEquals("Room 3", Config.cleanName("Room 3."));
        assertEquals("Room", Config.cleanName("_ _Room"));
        assertEquals("Roomก", Config.cleanName(" Room​ก\n "));
    }
}
