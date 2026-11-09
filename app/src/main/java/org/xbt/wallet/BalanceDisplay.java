package org.xbt.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

final class BalanceDisplay {
    static String format(long sats, int unit, String currency, BigDecimal price) {
        if (unit == 0) return sats + " sats";
        BigDecimal coins = BigDecimal.valueOf(sats, 8);
        if (unit == 1) return coins.stripTrailingZeros().toPlainString() + " XBT";
        if (price == null) return currency + " —";
        int digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        BigDecimal value = coins.multiply(price);
        BigDecimal smallest = BigDecimal.ONE.movePointLeft(digits);
        if (value.signum() > 0 && value.compareTo(smallest) < 0)
            return "< " + smallest.toPlainString() + " " + currency;
        return "≈ " + value.setScale(digits, RoundingMode.HALF_UP).toPlainString() + " " + currency;
    }
}
