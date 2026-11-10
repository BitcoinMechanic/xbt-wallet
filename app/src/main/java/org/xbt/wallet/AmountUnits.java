package org.xbt.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact decimal input; fiat amounts round to the nearest whole satoshi. */
final class AmountUnits {
    static long sats(String input, int unit, BigDecimal price) {
        String value = input.trim();
        if (!value.matches("[0-9]+(?:\\.[0-9]+)?")) throw new IllegalArgumentException("Enter a positive amount.");
        BigDecimal amount = new BigDecimal(value);
        if (unit == 1) amount = amount.movePointRight(8);
        if (unit == 2) {
            if (price == null || price.signum() <= 0) throw new IllegalArgumentException("A current exchange rate is required. Try again shortly or use sats/XBT.");
            amount = amount.multiply(new BigDecimal("100000000")).divide(price, 0, RoundingMode.HALF_UP);
        }
        try { return Policy.amount(amount.setScale(0, RoundingMode.UNNECESSARY).toPlainString()); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Use whole sats or at most eight XBT decimal places."); }
    }
    static String input(long sats, int unit, BigDecimal price) {
        BigDecimal value = BigDecimal.valueOf(sats);
        if (unit != 0) value = value.movePointLeft(8);
        if (unit == 2) {
            if (price == null || price.signum() <= 0) throw new IllegalArgumentException("A current exchange rate is required. Try again shortly or use sats/XBT.");
            value = value.multiply(price);
        }
        return value.stripTrailingZeros().toPlainString();
    }
}
