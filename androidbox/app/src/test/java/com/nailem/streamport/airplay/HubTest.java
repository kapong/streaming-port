package com.nailem.streamport.airplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    /** ask=always: an approval lasts as long as that share; the next share from the same sender is asked again. */
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

        Screen reconnect = screen(Screen.Kind.AIRPLAY, "10.9.0.1"); // overlaps the approved screen: same share
        assertTrue(hub.add(reconnect));
        assertEquals(Screen.State.ALLOWED, reconnect.state());
        first.close();
        reconnect.close();

        Screen again = screen(Screen.Kind.AIRPLAY, "10.9.0.1");
        assertTrue(hub.add(again));
        assertEquals(Screen.State.PENDING, again.state());
        again.close();
        hub.setAskBeforeShowing(false, false);
    }
}
