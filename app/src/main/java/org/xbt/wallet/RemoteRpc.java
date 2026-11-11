package org.xbt.wallet;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.*;
import java.util.*;

/** CLNrest transport: authenticated TLS or Tor-only onion HTTP; never follows redirects or retries writes. */
final class RemoteRpc {
    final RemoteConnection config;
    private final SSLSocketFactory tls;
    private static final int LIMIT = 8 * 1024 * 1024;
    RemoteRpc(RemoteConnection config) throws Exception {
        this.config = config;
        if (config.certificate.isEmpty()) tls = (SSLSocketFactory) SSLSocketFactory.getDefault();
        else {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null);
            Collection<? extends java.security.cert.Certificate> certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(config.certificate.getBytes(StandardCharsets.US_ASCII)));
            if (certificates.isEmpty()) throw new Exception("The server CA certificate is invalid.");
            int index = 0; for (java.security.cert.Certificate c : certificates) store.setCertificateEntry("ca-" + index++, c);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(store);
            SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(null, factory.getTrustManagers(), null); tls = ssl.getSocketFactory();
        }
    }
    JSONObject call(String method, JSONObject params) throws Exception {
        if (!method.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid node method");
        URI uri = URI.create(config.endpoint + "/v1/" + method);
        byte[] request = params.toString().getBytes(StandardCharsets.UTF_8);
        int status; byte[] body;
        String stage = "connect";
        try {
            if (uri.getScheme().equals("http")) {
                if (!config.onion) throw new Exception("Unencrypted direct node connections are not supported.");
                try (Socket socket = new Socket(TorTransport.socksProxy())) {
                    socket.connect(InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort() < 0 ? 80 : uri.getPort()), 30000);
                    socket.setSoTimeout(120000); stage = "request";
                    String headers = "POST " + uri.getRawPath() + " HTTP/1.1\r\nHost: " + uri.getRawAuthority()
                        + "\r\nRune: " + config.rune + "\r\nContent-Type: application/json\r\nAccept: application/json\r\nConnection: close\r\nContent-Length: " + request.length + "\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().write(request); socket.getOutputStream().flush();
                    stage = "response"; InputStream input = new BufferedInputStream(socket.getInputStream());
                    String line = line(input); String[] head = line.split(" ", 3);
                    if (head.length < 2 || !head[0].matches("HTTP/1\\.[01]")) throw new IOException("Invalid node HTTP response.");
                    status = Integer.parseInt(head[1]); Map<String,String> fields = new HashMap<>(); int total = line.length();
                    while (!(line = line(input)).isEmpty()) {
                        total += line.length(); if (total > 16384) throw new IOException("Node response headers are too large.");
                        int colon = line.indexOf(':'); if (colon < 1) throw new IOException("Invalid node headers.");
                        String key = line.substring(0,colon).toLowerCase(Locale.ROOT);
                        if (fields.put(key,line.substring(colon+1).trim()) != null && (key.equals("content-length") || key.equals("transfer-encoding"))) throw new IOException("Ambiguous HTTP response.");
                    }
                    if (fields.containsKey("transfer-encoding")) {
                        if (!fields.get("transfer-encoding").equalsIgnoreCase("chunked") || fields.containsKey("content-length")) throw new IOException("Unsupported HTTP framing.");
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        while (true) {
                            int count = Integer.parseInt(line(input).split(";",2)[0].trim(),16);
                            if (count == 0) break;
                            if (count < 0 || count > LIMIT-out.size()) throw new IOException("Node response too large.");
                            out.write(readExact(input,count)); if (!line(input).isEmpty()) throw new IOException("Invalid HTTP chunk.");
                        }
                        body = out.toByteArray();
                    } else if (fields.containsKey("content-length")) body = readExact(input, Integer.parseInt(fields.get("content-length")));
                    else body = SwapControllers.bounded(input,LIMIT);
                }
            } else {
                HttpsURLConnection connection = (HttpsURLConnection) (config.onion ? uri.toURL().openConnection(TorTransport.httpProxy()) : uri.toURL().openConnection());
                connection.setSSLSocketFactory(tls); // Default hostname verification remains enabled.
                connection.setConnectTimeout(30000); connection.setReadTimeout(120000); connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Rune",config.rune); connection.setRequestProperty("Content-Type","application/json"); connection.setRequestProperty("Accept","application/json");
                connection.setFixedLengthStreamingMode(request.length);
                try {
                    stage = "request"; try (OutputStream out = connection.getOutputStream()) { out.write(request); }
                    stage = "response"; status = connection.getResponseCode();
                    InputStream response = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    try (InputStream in = response) { body = in == null ? new byte[0] : SwapControllers.bounded(in,LIMIT); }
                } finally { connection.disconnect(); }
            }
        } catch (Exception e) {
            String detail = e instanceof SSLException ? "TLS validation failed. Check the hostname and server CA certificate." : e instanceof SocketTimeoutException ? "The node timed out." : clean(e.getMessage());
            throw new IOException("Remote node " + method + " failed at " + stage + ": " + detail + (stage.equals("connect") ? "" : " The request may have reached the node. Check its status before retrying."),e);
        }
        JSONObject response;
        try { response = new JSONObject(new String(body,StandardCharsets.UTF_8)); }
        catch (JSONException e) { throw new Exception("Remote node " + method + " returned invalid JSON (HTTP " + status + ")."); }
        if (status < 200 || status >= 300 || response.has("error")) {
            JSONObject error = response.optJSONObject("error"); if (error == null) error = response;
            throw new Exception("Core Lightning " + method + " failed (HTTP " + status + ", RPC " + error.optString("code","unknown") + "): " + clean(error.optString("message",status == 401 || status == 403 ? "Access denied. Check rune permissions." : "No RPC message returned.")));
        }
        return response;
    }
    private String clean(String message) { if (message == null) return "Connection interrupted."; String safe = message.replace(config.rune,"[credential]"); return safe.substring(0,Math.min(1500,safe.length())); }
    private static byte[] readExact(InputStream in,int size) throws IOException {
        if (size < 0 || size > LIMIT) throw new IOException("Node response too large.");
        byte[] bytes = new byte[size]; int offset=0,count;
        while (offset<size && (count=in.read(bytes,offset,size-offset))!=-1) offset+=count;
        if (offset != size) throw new EOFException("Incomplete node response."); return bytes;
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int c;
        while ((c=in.read())!=-1) { if (c=='\n') { byte[] bytes=out.toByteArray(); if(bytes.length==0 || bytes[bytes.length-1]!='\r') throw new IOException("Invalid HTTP line."); return new String(bytes,0,bytes.length-1,StandardCharsets.US_ASCII); } if(out.size()>8192) throw new IOException("HTTP line too long."); out.write(c); }
        throw new EOFException("Incomplete node headers.");
    }
}
