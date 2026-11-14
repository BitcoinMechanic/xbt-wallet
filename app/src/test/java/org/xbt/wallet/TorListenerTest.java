package org.xbt.wallet;

import org.junit.Test;
import static org.junit.Assert.*;

public class TorListenerTest {
    @Test public void acceptsOnlyValidLoopbackListeners() throws Exception {
        assertEquals(9050, TorTransport.listenerPort("\"127.0.0.1:9050\""));
        for (String value : new String[]{"", "\"0.0.0.0:9050\"", "\"127.0.0.1:0\"", "\"127.0.0.1:65536\""}) {
            try { TorTransport.listenerPort(value); fail(value); }
            catch (java.io.IOException expected) { }
        }
    }
}
