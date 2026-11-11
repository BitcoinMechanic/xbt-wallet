package org.xbt.wallet;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Start9/Zeus CLNrest QR imports. Never retain credentials in the endpoint URL. */
final class RemoteConnection {
    final String endpoint, rune, certificate, nodeId;
    final boolean onion;
    RemoteConnection(String endpoint, String rune, String certificate, String nodeId) {
        URI uri;
        try { uri = URI.create(endpoint.trim()); } catch (Exception e) { throw new IllegalArgumentException("Enter a valid node API URL."); }
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535 || (!uri.getPath().isEmpty() && !uri.getPath().equals("/")))
            throw new IllegalArgumentException("Use the node API origin, including protocol and port, without credentials or a path.");
        onion = host.toLowerCase(Locale.ROOT).endsWith(".onion");
        if (onion && !host.matches("[a-z2-7]{56}\\.onion")) throw new IllegalArgumentException("Use a v3 onion address.");
        if (!"https".equals(uri.getScheme()) && !(onion && "http".equals(uri.getScheme())))
            throw new IllegalArgumentException("Use HTTPS for direct connections. HTTP is supported only for encrypted Tor onion connections.");
        this.endpoint = uri.getScheme() + "://" + uri.getRawAuthority();
        this.rune = rune.trim(); this.certificate = certificate.trim(); this.nodeId = nodeId.trim().toLowerCase(Locale.ROOT);
        if (this.rune.isEmpty() || !this.rune.matches("[A-Za-z0-9_+/=-]{1,8192}")) throw new IllegalArgumentException("Enter a valid Core Lightning rune.");
        if (!this.nodeId.isEmpty() && !this.nodeId.matches("0[23][0-9a-f]{64}")) throw new IllegalArgumentException("Node ID must be a 66-character compressed public key.");
    }
    static RemoteConnection parse(String input) {
        String raw = input.trim();
        if (raw.length() > 32768) throw new IllegalArgumentException("Connection QR is too large.");
        if (raw.startsWith("clnrest://http://")) raw = raw.replaceFirst("clnrest://", "");
        else if (raw.startsWith("clnrest://")) raw = raw.replaceFirst("clnrest://", "https://");
        else if (raw.startsWith("clnrest+http://") || raw.startsWith("clnrest+https://")) raw = raw.substring(8);
        else throw new IllegalArgumentException("Scan a Core Lightning CLNrest connection QR from Start9. A peer URI is not an authenticated wallet connection.");
        URI uri;
        try { uri = URI.create(raw); } catch (Exception e) { throw new IllegalArgumentException("Invalid connection QR."); }
        Map<String,String> values = new HashMap<>();
        if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
            if (pair.isEmpty()) continue;
            String[] parts = pair.split("=", 2);
            // URI query values use percent encoding; a literal + belongs to base64.
            String key = decode(parts[0]), value = parts.length == 2 ? decode(parts[1]) : "";
            if (values.put(key, value) != null) throw new IllegalArgumentException("Duplicate connection parameter.");
            if (!Arrays.asList("rune", "cert", "certs", "nodeid", "protocol").contains(key)) throw new IllegalArgumentException("Unsupported connection parameter: " + key);
        }
        String protocol = values.getOrDefault("protocol", uri.getScheme());
        String cert = values.getOrDefault("cert", "");
        if (values.containsKey("certs")) throw new IllegalArgumentException("This QR includes client certificates. Use the Start9 CLNrest rune connection QR, or enter its URL, rune and server CA certificate manually.");
        if (!cert.isEmpty() && !cert.contains("BEGIN CERTIFICATE")) {
            try { cert = new String(Base64.getUrlDecoder().decode(cert), StandardCharsets.UTF_8); }
            catch (Exception e) { throw new IllegalArgumentException("Invalid QR certificate."); }
        }
        if (uri.getRawUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException("Invalid connection QR.");
        return new RemoteConnection(protocol + "://" + uri.getRawAuthority() + uri.getRawPath(), values.getOrDefault("rune", ""), cert, values.getOrDefault("nodeid", ""));
    }
    private static String decode(String value) { try { return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8"); } catch (java.io.UnsupportedEncodingException e) { throw new AssertionError(e); } }
}
