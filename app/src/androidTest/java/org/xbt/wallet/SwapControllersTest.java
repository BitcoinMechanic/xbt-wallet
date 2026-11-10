package org.xbt.wallet;

import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Read-only parser/transport tests: never starts, resets or pays from a wallet. */
public class SwapControllersTest {
    private JSONObject info() throws Exception {
        return new JSONObject().put("format", "xbt-swap-provider-v1").put("name", "Example")
            .put("provider", "02"+"ab".repeat(32)).put("environment", "mainnet")
            .put("endpoints", new JSONArray().put("https://example.com"))
            .put("pairs", new JSONArray().put("XBT/BTC"));
    }
    @Test public void validatesNetworkProtocolAndAddresses() throws Exception {
        assertEquals("Example", new SwapControllers.Provider(info(), "mainnet").name);
        for (JSONObject bad : new JSONObject[]{info().put("environment","regtest"), info().put("format","other"),
                info().put("provider","fake"), info().put("endpoints",new JSONArray().put("http://example.com")),
                info().put("pairs",new JSONArray().put("BTC/LTC"))}) {
            try { new SwapControllers.Provider(bad,"mainnet"); fail("Accepted invalid controller"); }
            catch (Exception expected) {}
        }
        try { SwapControllers.bounded(new ByteArrayInputStream(new byte[65537]),65536); fail(); }
        catch(IOException expected) {}
    }
    @Test public void onionUsesUnresolvedSocksAndParsesChunkedJson() throws Exception { onionTransport(false); }
    @Test public void receivePostUsesTorWithExactPrivateBody() throws Exception { onionTransport(true); }
    @Test public void unavailableSocksProxyReportsNoRequestSent() throws Exception {
        int port;
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port=listener.getLocalPort(); }
        try {
            SwapControllers.onionRequest(new URI("http://"+"a".repeat(56)+".onion/v1/receive"),
                new Proxy(Proxy.Type.SOCKS,new InetSocketAddress(InetAddress.getLoopbackAddress(),port)),
                new JSONObject().put("action","status"),"a".repeat(64));
            fail("Closed SOCKS listener accepted a request");
        } catch(IOException expected) {
            assertTrue(expected.getMessage(),expected.getMessage().contains("request was not sent"));
            assertTrue(expected.getMessage().contains("Stage: connect"));
            assertFalse(expected.getMessage().contains("a".repeat(64)));
        }
    }
    private void onionTransport(boolean post) throws Exception {
        String host = "a".repeat(56)+".onion";
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> observed = executor.submit(() -> {
                try(Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream in = new DataInputStream(socket.getInputStream()); OutputStream out = socket.getOutputStream();
                    assertEquals(5,in.readUnsignedByte()); int methods=in.readUnsignedByte(); in.readFully(new byte[methods]);
                    out.write(new byte[]{5,0}); out.flush();
                    assertEquals(5,in.readUnsignedByte()); assertEquals(1,in.readUnsignedByte()); in.readByte();
                    assertEquals(3,in.readUnsignedByte()); byte[] name = new byte[in.readUnsignedByte()];in.readFully(name);assertEquals(80,in.readUnsignedShort());
                    out.write(new byte[]{5,0,0,1,127,0,0,1,0,80});out.flush();
                    BufferedReader reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.US_ASCII));
                    assertEquals((post ? "POST /v1/receive" : "GET /v1/provider/info")+" HTTP/1.1",reader.readLine());
                    String line; int length=0;boolean authorized=false;
                    while((line=reader.readLine())!=null && !line.isEmpty()) {
                        if (line.startsWith("Content-Length: ")) length=Integer.parseInt(line.substring(16));
                        if (line.equals("Authorization: Bearer "+"a".repeat(64))) authorized=true;
                    }
                    if (post) { assertTrue(authorized);char[] body=new char[length];int offset=0;while(offset<length)offset+=reader.read(body,offset,length-offset);assertEquals("{\"action\":\"status\"}",new String(body)); }
                    else assertFalse(authorized);
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n2\r\n{}\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));out.flush();
                    return new String(name,StandardCharsets.US_ASCII);
                }
            });
            try {
                byte[] data=SwapControllers.onionRequest(new URI("http://"+host+(post?"/v1/receive":"/v1/provider/info")),new Proxy(Proxy.Type.SOCKS,new InetSocketAddress(InetAddress.getLoopbackAddress(),listener.getLocalPort())),post?new JSONObject().put("action","status"):null,post?"a".repeat(64):"");
                assertEquals("{}",new String(data,StandardCharsets.UTF_8));assertEquals(host,observed.get(10,TimeUnit.SECONDS));
            } finally { executor.shutdownNow(); }
        }
    }
}
