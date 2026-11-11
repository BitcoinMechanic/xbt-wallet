package org.xbt.wallet;

/** The native invoice RPC accepts positive expirations up to 365 days. */
final class InvoiceExpiration {
    static final String[] UNITS = {"Seconds", "Minutes", "Hours", "Days", "Weeks"};
    static final long[] SECONDS = {1, 60, 3600, 86400, 604800};
    static long seconds(String count, int unit) {
        try {
            if (unit < 0 || unit >= SECONDS.length || !count.trim().matches("[0-9]+")) throw new IllegalArgumentException();
            long value = Math.multiplyExact(Long.parseLong(count.trim()), SECONDS[unit]);
            if (value < 1 || value > 365L * 86400) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Enter a positive whole-number expiration, no longer than 365 days.");
        }
    }
    static String describe(long seconds) {
        for (int unit = SECONDS.length - 1; unit >= 0; unit--) {
            if (seconds % SECONDS[unit] != 0) continue;
            long count = seconds / SECONDS[unit];
            String name = UNITS[unit].toLowerCase(java.util.Locale.ROOT);
            return count + " " + (count == 1 ? name.substring(0, name.length() - 1) : name);
        }
        throw new IllegalArgumentException("Invalid expiration");
    }
}
