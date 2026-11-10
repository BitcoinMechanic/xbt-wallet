package org.xbt.wallet;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.util.HashSet;
import java.util.Set;

/** Exact URI amounts and conservative selected-input payment estimates. */
final class OnChainPayment {
    static final class Request {
        final String address;
        final Long amount;
        Request(String address, Long amount) { this.address = address; this.amount = amount; }
    }
    static String receiveRequest(String address, String amountText) {
        String value = amountText.trim();
        if (value.isEmpty()) return address;
        long sats = Policy.amount(value);
        return "bitcoin:" + address + "?amount=" + java.math.BigDecimal.valueOf(sats, 8).stripTrailingZeros().toPlainString();
    }
    static Request parse(String raw) {
        String value = raw.trim();
        if (!value.contains(":")) return new Request(value, null);
        URI uri = URI.create(value);
        if (!("bitcoin".equalsIgnoreCase(uri.getScheme()) || "xbt".equalsIgnoreCase(uri.getScheme())) || uri.getRawFragment() != null)
            throw new IllegalArgumentException("Use an XBT address or bitcoin:/xbt: payment URI.");
        String[] parts = uri.getRawSchemeSpecificPart().split("\\?", -1);
        if (parts.length > 2 || parts[0].isEmpty() || parts[0].startsWith("//")) throw new IllegalArgumentException("Invalid payment address.");
        Long amount = null;
        Set<String> keys = new HashSet<>();
        if (parts.length == 2 && !parts[1].isEmpty()) for (String pair : parts[1].split("&")) {
            String[] kv = pair.split("=", 2);
            String key = decode(kv[0]);
            String val = kv.length == 2 ? decode(kv[1]) : "";
            if (!keys.add(key)) throw new IllegalArgumentException("Duplicate payment parameter.");
            if (key.startsWith("req-")) throw new IllegalArgumentException("Unsupported required payment parameter: " + key);
            if (key.equals("amount")) {
                if (!val.matches("[0-9]+(\\.[0-9]{1,8})?")) throw new IllegalArgumentException("Invalid payment amount.");
                amount = new BigDecimal(val).movePointRight(8).longValueExact();
                Policy.amount(Long.toString(amount));
            }
        }
        return new Request(parts[0], amount);
    }
    private static String decode(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    static int previewOutputWeight(String address) {
        // Conservative recipient size until its address is supplied, for both fixed and max amounts.
        return address.trim().isEmpty() ? 172 : outputWeight(address);
    }
    static int outputWeight(String address) {
        String lower = address.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("bcrt1q") && lower.length() == 44) return 124;
        if ((lower.startsWith("bcrt1q") || lower.startsWith("bcrt1p")) && lower.length() == 64) return 172;
        if (lower.startsWith("bc1q") && lower.length() == 42) return 124;
        if ((lower.startsWith("bc1q") || lower.startsWith("bc1p")) && lower.length() == 62) return 172;
        if (address.matches("[1mn][1-9A-HJ-NP-Za-km-z]{25,34}")) return 136;
        if (address.matches("[23][1-9A-HJ-NP-Za-km-z]{25,34}")) return 128;
        throw new IllegalArgumentException("Enter an XBT recipient address.");
    }
    final long amount, fee, total, reserve;
    OnChainPayment(long selected, long available, long requiredReserve, long inputWeight, int count,
                   int outputWeight, long requested, long rate, boolean max) {
        if (count == 0 || selected <= 0) throw new IllegalArgumentException("Select at least one UTXO.");
        if (rate <= 0) throw new IllegalArgumentException("Enter a positive fee rate.");
        long weight = 42 + (count >= 253 ? 8 : 0) + inputWeight + outputWeight;
        // SendAll retains the full required reserve if other funds cannot cover it.
        reserve = max && available - selected < requiredReserve ? requiredReserve : 0;
        long noChangeFee = fee(weight, rate);
        if (max) {
            fee = fee(weight + (reserve > 0 ? 172 : 0), rate);
            amount = selected - reserve - fee;
        } else {
            amount = requested;
            long changeFee = fee(weight + 172, rate);
            if (selected < Math.addExact(amount, noChangeFee)) throw new IllegalArgumentException("Selection does not cover the amount and network fee.");
            // Sub-dust change is added to the miner fee by the wallet.
            fee = selected - amount - changeFee >= 330 ? changeFee : selected - amount;
        }
        if (amount < 546) throw new IllegalArgumentException("Recipient amount must be at least 546 sats.");
        total = Math.addExact(amount, fee);
        if (available - total < requiredReserve) throw new IllegalArgumentException("Keep enough on-chain funds for channel reserves.");
    }
    private static long fee(long weight, long rate) {
        return Math.multiplyExact(weight, Math.max(253, Math.multiplyExact(rate, 250))) / 1000;
    }
}
