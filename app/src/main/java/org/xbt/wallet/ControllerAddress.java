package org.xbt.wallet;

import java.net.URI;
import java.util.Locale;

/** Controller origins contain no credentials, payment data, paths or queries. */
final class ControllerAddress {
    static String origin(String input) {
        try {
            if (input == null || input.length() > 2048) throw new Exception();
            URI uri = new URI(input.trim());
            String host = uri.getHost(), scheme = uri.getScheme();
            if (host == null || scheme == null) throw new Exception();
            host = host.toLowerCase(Locale.ROOT); scheme = scheme.toLowerCase(Locale.ROOT);
            boolean onion = host.matches("[a-z2-7]{56}\\.onion");
            if (host.endsWith(".onion") && !onion) throw new Exception();
            if (!scheme.equals("https") && !(scheme.equals("http") && onion)) throw new Exception();
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) throw new Exception();
            int port = uri.getPort();
            if (port == 0 || port > 65535 || port < -1) throw new Exception();
            if (port == (scheme.equals("https") ? 443 : 80)) port = -1;
            return new URI(scheme, null, host, port, null, null, null).toASCIIString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Use an HTTPS address or a v3 onion address, without a path, credentials or query.");
        }
    }
    static boolean onion(String origin) { return URI.create(origin).getHost().endsWith(".onion"); }
}
