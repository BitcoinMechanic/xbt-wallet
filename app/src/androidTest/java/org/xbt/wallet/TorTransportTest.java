package org.xbt.wallet;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.net.*;
import static org.junit.Assert.*;

/** Explicit read-only integration probe; never creates or resets a wallet. */
public class TorTransportTest {
    @Test public void recoversLocalControlConnection() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TorTransport tor = new TorTransport();
        try {
            int port = tor.start(context, status -> android.util.Log.i("TorProbe", status));
            java.lang.reflect.Method connect = TorTransport.class.getDeclaredMethod("connectRecoveryControl", Context.class);
            connect.setAccessible(true); connect.invoke(tor, context);
            java.lang.reflect.Method info = TorTransport.class.getDeclaredMethod("info", org.torproject.jni.TorService.class, String.class);
            info.setAccessible(true);
            assertEquals(port, TorTransport.listenerPort((String) info.invoke(tor, null, "net/listeners/socks")));
            assertEquals("1", info.invoke(tor, null, "status/circuit-established"));
        } finally { tor.close(context); }
    }
    @Test public void reachesConfiguredOnionPeer() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String endpoint = Policy.onionEndpoint(context.getSharedPreferences("wallet", 0).getString("syncPeer", ""));
        TorTransport tor = new TorTransport();
        try {
            int port = tor.start(context, status -> android.util.Log.i("TorProbe", status));
            int separator = endpoint.lastIndexOf(':');
            try (Socket socket = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port)))) {
                socket.connect(InetSocketAddress.createUnresolved(endpoint.substring(0, separator), Integer.parseInt(endpoint.substring(separator + 1))), 60000);
                assertTrue(socket.isConnected());
                android.util.Log.i("TorProbe", "Onion P2P port reached through embedded Tor");
            }
        } finally { tor.close(context); }
    }
    @Test public void mempoolOnionProvidesFees() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TorTransport tor = new TorTransport();
        String host = "mempool5nxspkxjk3n5afqh7zswbv4i324z76ltmw3cvfmniw45mnhad.onion";
        try {
            int port = tor.start(context, status -> android.util.Log.i("TorProbe", status));
            try (Socket socket = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port)))) {
                socket.connect(InetSocketAddress.createUnresolved(host, 80), 60000); socket.setSoTimeout(60000);
                String request = "GET /api/v1/fees/precise HTTP/1.0\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                java.io.ByteArrayOutputStream response = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int read;
                while ((read = socket.getInputStream().read(buf)) != -1) {
                    response.write(buf, 0, read); assertTrue(response.size() < 65536);
                }
                String text = response.toString("UTF-8");
                assertTrue(text.startsWith("HTTP/1.1 200") || text.startsWith("HTTP/1.0 200"));
                org.json.JSONObject fees = new org.json.JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1));
                assertTrue(fees.getDouble("fastestFee") > 0); assertTrue(fees.getDouble("minimumFee") > 0);
                android.util.Log.i("TorProbe", "Onion fee API verified: " + fees.toString());
            }
        } finally { tor.close(context); }
    }
}
