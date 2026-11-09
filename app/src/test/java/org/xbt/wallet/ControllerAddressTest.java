package org.xbt.wallet;
import org.junit.Test;
import static org.junit.Assert.*;

public class ControllerAddressTest {
    @Test public void canonicalOrigins() {
        assertEquals("https://example.com", ControllerAddress.origin(" HTTPS://EXAMPLE.COM:443/ "));
        assertEquals("https://example.com:8443", ControllerAddress.origin("https://example.com:8443/"));
        String onion = "http://" + "a".repeat(56) + ".onion";
        assertEquals(onion, ControllerAddress.origin(onion));
        assertTrue(ControllerAddress.onion(onion));
    }
    @Test public void refusesCredentialAndTransportConfusion() {
        for (String url : new String[]{"http://example.com", "https://user:secret@example.com", "https://example.com?token=x",
                "https://example.com/#x", "https://example.com/api", "https://example.com:0", "https://example.com:65536",
                "http://short.onion", "https://short.onion", "https://example.com\\@evil.com", "https://example.com/%2e%2e", "file:///tmp/file"}) {
            try { ControllerAddress.origin(url); fail("Accepted " + url); } catch (IllegalArgumentException expected) {}
        }
    }
}
