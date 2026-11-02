package com.nailem.streamport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class ConfigTest {

    @Test
    public void cleanNameKeepsTheNameOneMdnsLabel() {
        assertEquals("Room 3 2", Config.cleanName("Room 3.2"));
        assertEquals("Room 3", Config.cleanName("Room 3."));
        assertEquals("Room", Config.cleanName("_ _Room"));
        assertEquals("Roomก", Config.cleanName(" Room​ก\n "));
    }

    /** "always" is stored beside the old boolean, so a box set with ask=true before reads as it did. */
    @Test
    public void askTakesAlways() {
        Map<String, Object> store = new HashMap<>();
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {SharedPreferences.class, SharedPreferences.Editor.class}, (p, m, a) -> {
                    String n = m.getName();
                    if (n.startsWith("get")) return store.getOrDefault((String) a[0], a[1]);
                    if (n.startsWith("put")) store.put((String) a[0], a[1]);
                    return n.equals("edit") || n.startsWith("put") ? p : n.equals("commit") ? true : null;
                });
        ContextWrapper c = new ContextWrapper(null) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return prefs;
            }
        };
        store.put("ask", true); // stored by an older version
        assertTrue(Config.ask(c));
        assertFalse(Config.askAlways(c));
        Config.set(c, "ask", "always");
        assertTrue(Config.ask(c));
        assertTrue(Config.askAlways(c));
        Config.set(c, "ask", "false");
        assertFalse(Config.ask(c));
        assertFalse(Config.askAlways(c));
        assertThrows(IllegalArgumentException.class, () -> Config.set(c, "ask", "alway"));
    }
}
