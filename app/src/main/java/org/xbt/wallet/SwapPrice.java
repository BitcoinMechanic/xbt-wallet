package org.xbt.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Independent market comparison of the complete quoted exchange amounts. */
final class SwapPrice {
    private static final BigDecimal WARNING_RATIO = new BigDecimal("1.05");
    final BigDecimal costRatio;

    // Both amounts use the same subunit (for example millisatoshis).
    SwapPrice(long paid, long received, boolean payingBtc, BigDecimal btcPerXbt) {
        if (paid <= 0 || received <= 0 || btcPerXbt == null || btcPerXbt.signum() <= 0)
            throw new IllegalArgumentException("Invalid swap amounts or market price.");
        BigDecimal paidValue = BigDecimal.valueOf(paid);
        BigDecimal receivedValue = BigDecimal.valueOf(received);
        if (payingBtc) receivedValue = receivedValue.multiply(btcPerXbt);
        else paidValue = paidValue.multiply(btcPerXbt);
        costRatio = paidValue.divide(receivedValue, 24, RoundingMode.HALF_UP);
    }

    static void requireFresh(long timestamp, long now) {
        if (timestamp <= 0 || timestamp < now - 300000 || timestamp > now + 60000)
            throw new IllegalArgumentException("Neoxa market price is stale or has an invalid timestamp.");
    }
    boolean warning() { return costRatio.compareTo(WARNING_RATIO) > 0; }
    String percent() {
        return costRatio.subtract(BigDecimal.ONE).movePointRight(2)
            .setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
