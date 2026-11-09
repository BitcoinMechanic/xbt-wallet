package org.xbt.wallet;
import org.junit.Test;
import static org.junit.Assert.*;
public class PolicyTest {
    @Test public void rejectsAmbiguousOrOverflowingAmounts() {
        for (String value : new String[]{"-1", "0", "1.5", " 1", "2100000000000001", "99999999999999999999"}) {
            try { Policy.amount(value); fail(value); } catch (IllegalArgumentException expected) {}
        }
        assertEquals(2100000000000000L, Policy.amount("2100000000000000"));
    }
    @Test public void peerEndpointsCannotInjectNodeArguments() {
        for (String value : new String[]{"host:0", "host:65536", "host:1 --bitcoin.mainnet", "https://host:443"}) {
            try { Policy.endpoint(value); fail(value); } catch (IllegalArgumentException expected) {}
        }
        assertEquals("[::1]:19444", Policy.endpoint("[::1]:19444"));
    }
    @Test public void invoicesRequireChainAmountAndUnexpiredTime() {
        assertTrue(Policy.invoiceAllowed(true, 1000, 100, 10, 109));
        assertFalse(Policy.invoiceAllowed(false, 1000, 100, 10, 109));
        assertFalse(Policy.invoiceAllowed(true, 0, 100, 10, 109));
        assertFalse(Policy.invoiceAllowed(true, 1000, 100, 10, 110));
        assertFalse(Policy.invoiceAllowed(true, 1000, Long.MAX_VALUE, 10, 110));
    }
    @org.junit.Test public void validatesOnionEndpoint() {
        String peer = "oamf2472ggqj6srxyboxxnuvsdgrlyqjx22ieeurxqommu2zohjbyzyd.onion:8333";
        org.junit.Assert.assertEquals(peer, Policy.onionEndpoint(peer));
        for (String invalid : new String[]{"localhost:9050", "short.onion:8333", peer + " --tor.active", peer.replace(":8333", ":99999")}) {
            try { Policy.onionEndpoint(invalid); org.junit.Assert.fail(invalid); }
            catch (IllegalArgumentException expected) {}
        }
    }
    @org.junit.Test public void onionFeesRequireTor() {
        String url = "http://mempool5nxspkxjk3n5afqh7zswbv4i324z76ltmw3cvfmniw45mnhad.onion/api/v1/fees/precise";
        org.junit.Assert.assertEquals(url, Policy.feeUrl(url, true));
        try { Policy.feeUrl(url, false); org.junit.Assert.fail(); } catch (IllegalArgumentException expected) {}
        try { Policy.feeUrl("http://mempool.guide/api/v1/fees/precise", true); org.junit.Assert.fail(); } catch (IllegalArgumentException expected) {}
    }
}
