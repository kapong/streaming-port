package com.nailem.streamport.airplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.net.InetAddress;

public class HubTest {

    private static Screen screen(Screen.Kind kind, String ip) throws Exception {
        InetAddress peer = InetAddress.getByName(ip);
        Screen.State[] state = {Screen.State.PENDING};
        boolean[] closed = {false};
        return (Screen) Proxy.newProxyInstance(HubTest.class.getClassLoader(), new Class<?>[] {Screen.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "kind": return kind;
                        case "peer": return peer;
                        case "approvalKey": return "dev@" + ip;
                        case "state": return state[0];
                        case "setState": state[0] = (Screen.State) a[0]; return null;
                        case "isClosed": return closed[0];
                        case "close": closed[0] = true; Hub.get().remove((Screen) p); return null;
                        case "equals": return p == a[0];
                        case "hashCode": return System.identityHashCode(p);
                        default:
                            Class<?> r = m.getReturnType();
                            return r == int.class ? 0 : r == boolean.class ? false : null;
                    }
                });
    }

    @After
    public void reset() {
        Hub.get().disconnectAll();
        Hub.get().setAskBeforeShowing(false, false);
    }

    /** ask=always: an approval lasts as long as that share; any new share, even a reconnect mid-share, is asked. */
    @Test
    public void askAlwaysRemembersNoApproval() throws Exception {
        Hub hub = Hub.get();
        hub.setAskBeforeShowing(true, false);
        Screen remembered = screen(Screen.Kind.AIRPLAY, "10.9.0.1");
        assertTrue(hub.add(remembered));
        hub.approve(remembered);
        remembered.close();

        hub.setAskBeforeShowing(true, true); // forgets the approval above
        Screen first = screen(Screen.Kind.AIRPLAY, "10.9.0.1");
        assertTrue(hub.add(first));
        assertEquals(Screen.State.PENDING, first.state());
        hub.approve(first);

        Screen reconnect = screen(Screen.Kind.AIRPLAY, "10.9.0.1"); // overlaps the approved screen
        assertTrue(hub.add(reconnect));
        assertEquals(Screen.State.PENDING, reconnect.state());
        first.close();
        reconnect.close();

        Screen browser = screen(Screen.Kind.BROWSER, "10.9.0.2");
        assertTrue(hub.add(browser));
        assertEquals(Screen.State.PENDING, browser.state());
    }

    /** Turning ask off lets in a sender still waiting for OK. */
    @Test
    public void askOffLetsWaitingIn() throws Exception {
        Hub hub = Hub.get();
        hub.setAskBeforeShowing(true, false);
        Screen waiting = screen(Screen.Kind.AIRPLAY, "10.9.0.3");
        assertTrue(hub.add(waiting));
        assertEquals(Screen.State.PENDING, waiting.state());
        hub.setAskBeforeShowing(false, false);
        assertEquals(Screen.State.ALLOWED, waiting.state());
    }
}
