package org.xbt.wallet;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteConnectionTest {
    private static final String ONION="a".repeat(56)+".onion";
    @Test public void importsStart9TlsAndTorWithoutCredentialInEndpoint() {
        RemoteConnection tls=RemoteConnection.parse("clnrest+https://node.local:3010/?rune=abc_def%3D%3D");
        assertEquals("https://node.local:3010",tls.endpoint);assertEquals("abc_def==",tls.rune);assertFalse(tls.onion);
        RemoteConnection tor=RemoteConnection.parse("clnrest+http://"+ONION+":3010?rune=abc+");
        assertTrue(tor.onion);assertEquals("abc+",tor.rune);
        assertEquals(tor.endpoint,RemoteConnection.parse("clnrest://http://"+ONION+":3010?rune=abc+").endpoint);
    }
    @Test public void supportsLegacyAndIpv6() {
        assertEquals("https://[fd00::1]:3010",RemoteConnection.parse("clnrest://[fd00::1]:3010?rune=abc").endpoint);
        assertEquals("http://"+ONION,RemoteConnection.parse("clnrest://"+ONION+"?rune=abc&protocol=http").endpoint);
    }
    @Test public void rejectsAmbiguousOrUnsafeImports() {
        for(String uri:new String[]{"clnrest+http://192.168.1.1?rune=abc", "clnrest://node.local?rune=abc&rune=def", "clnrest://node.local?rune=abc%0d%0aRune:evil", "clnrest://user@node.local?rune=abc", "clnrest://node.local/path?rune=abc", "clnrest://node.local?rune=abc#fragment", "clnrest://short.onion?rune=abc", "lndconnect://node.local?macaroon=abc"}) {
            try { RemoteConnection.parse(uri); fail("Accepted unsafe import"); } catch(IllegalArgumentException expected) {}
        }
    }
    @Test public void validatesOptionalIdentityAndSeparatesProfiles() {
        new RemoteConnection("https://node.local","abc","","02"+"ab".repeat(32));
        try {new RemoteConnection("https://node.local","abc","","bad");fail();}catch(IllegalArgumentException expected){}
        assertEquals("wallet",WalletProfiles.settingsName("default"));assertEquals("vault",WalletProfiles.vaultName("default"));assertEquals("xbt-wallet-v1",WalletProfiles.keyAlias("default"));
        String id="a".repeat(32);assertNotEquals(WalletProfiles.settingsName("default"),WalletProfiles.settingsName(id));assertNotEquals(WalletProfiles.vaultName(id),WalletProfiles.vaultName("default"));assertNotEquals(WalletProfiles.keyAlias(id),WalletProfiles.keyAlias("default"));
        for(String invalid:new String[]{"../default","","node-mainnet",null}){try{WalletProfiles.valid(invalid);fail();}catch(IllegalArgumentException expected){}}
    }
}
