package org.xbt.wallet;

import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import androidx.test.platform.app.InstrumentationRegistry;
import javax.net.ssl.*;
import java.security.*;
import java.security.cert.*;
import java.io.*;

/** Explicit disposable HTTPS fixture, using its CA only inside this test. */
public class ControllerIntegrationTest {
    @Test public void searchesAndChecksControllerWithPinnedIdentity() throws Exception {
        String encoded = InstrumentationRegistry.getArguments().getString("fixture_ca");
        assumeNotNull(encoded);
        byte[] pem = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
        java.security.cert.Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem));
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null); store.setCertificateEntry("fixture", cert);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");context.init(null, trust.getTrustManagers(), null);
        SSLSocketFactory previous = HttpsURLConnection.getDefaultSSLSocketFactory();
        String address = "https://localhost:19488";
        try {
            // Untrusted fixture CA must fail under normal production trust.
            try { SwapControllers.search(address,"mainnet",false);fail("Untrusted CA accepted"); } catch (javax.net.ssl.SSLException expected) {}
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
            java.util.List<SwapControllers.Provider> found=SwapControllers.search(address,"mainnet",false);
            assertEquals(1,found.size());
            assertEquals("Integration fixture",SwapControllers.inspect(address,"mainnet",found.get(0).identity,false).name);
            try { SwapControllers.inspect(address,"mainnet","03"+"cd".repeat(32),false);fail("Identity change accepted"); }
            catch(Exception expected) { assertTrue(expected.getMessage().contains("identity changed")); }
            assertTrue(SwapControllers.search(address,"regtest",false).isEmpty());
        } finally { HttpsURLConnection.setDefaultSSLSocketFactory(previous); }
    }
}
