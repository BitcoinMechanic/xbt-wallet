package org.xbt.wallet;

import java.net.URI;

/** Validation shared by UI and tests. Amounts are integer XBT satoshis. */
public final class Policy {
    private Policy() {}
    public static long amount(String text) {
        if (!text.matches("[0-9]{1,16}")) throw new IllegalArgumentException("Enter a whole number of sats.");
        long value = Long.parseLong(text);
        if (value <= 0 || value > 2_100_000_000_000_000L) throw new IllegalArgumentException("Amount is outside the supported range.");
        return value;
    }
    public static String endpoint(String text) {
        String value = text.trim();
        if (!value.matches("(?:[a-zA-Z0-9.-]+|\\[[0-9a-fA-F:]+\\]):[0-9]{1,5}"))
            throw new IllegalArgumentException("Use host:port (or [IPv6]:port).");
        int port = Integer.parseInt(value.substring(value.lastIndexOf(':') + 1));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port.");
        return value;
    }
    public static String onionEndpoint(String text) {
        String endpoint = endpoint(text).toLowerCase(java.util.Locale.ROOT);
        if (!endpoint.matches("[a-z2-7]{56}\\.onion:[0-9]{1,5}"))
            throw new IllegalArgumentException("Use a v3 onion address followed by :port.");
        return endpoint;
    }
    public static String[] peer(String text) {
        String value = text.trim().replaceFirst("(?i)^lightning:", "");
        String[] parts = value.split("@", -1);
        if (parts.length != 2 || !parts[0].matches("(02|03)[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Use a compressed node public key followed by @host:port.");
        endpoint(parts[1]);
        return parts;
    }
    public static String feeUrl(String value) { return feeUrl(value, false); }
    public static String feeUrl(String value, boolean tor) {
        URI uri = URI.create(value.trim());
        boolean onion = tor && "http".equals(uri.getScheme()) && uri.getHost() != null && uri.getHost().matches("[a-z2-7]{56}\\.onion");
        if ((!"https".equals(uri.getScheme()) && !onion) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Use an HTTPS XBT fee-estimator URL.");
        if (value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("Invalid URL.");
        return uri.toASCIIString();
    }
    public static boolean invoiceAllowed(boolean xbtFeature, long amountMsat, long timestamp, long expiry, long now) {
        return xbtFeature && amountMsat > 0 && timestamp >= 0 && expiry > 0
                && timestamp <= Long.MAX_VALUE - expiry && now < timestamp + expiry;
    }
}
