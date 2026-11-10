package org.xbt.wallet;

import org.junit.Test;
import java.math.BigDecimal;
import static org.junit.Assert.*;

public class AmountUnitsTest {
    @Test public void convertsFiatAndCoinsWithoutFloatingPoint() {
        assertEquals(20000000, AmountUnits.sats("50", 2, new BigDecimal("250")));
        assertEquals(1, AmountUnits.sats("0.00000001", 1, null));
        assertEquals(123, AmountUnits.sats("123", 0, null));
        assertEquals(2, AmountUnits.sats("0.0000015", 2, new BigDecimal("100")));
        assertEquals(3, AmountUnits.sats("0.0000025", 2, new BigDecimal("100")));
    }
    @Test public void rejectsMissingRatesFractionalSatsAndOverflow() {
        for (String value : new String[]{"0", "-1", "1e9", "1.1", "2100000000000001", "999999999999999999999999"}) {
            try { AmountUnits.sats(value, 0, null); fail(value); } catch (IllegalArgumentException expected) {}
        }
        try { AmountUnits.sats("50", 2, null); fail(); } catch (IllegalArgumentException expected) {}
        try { AmountUnits.sats("50", 2, BigDecimal.ZERO); fail(); } catch (IllegalArgumentException expected) {}
        try { AmountUnits.sats("0.000000001", 1, null); fail(); } catch (IllegalArgumentException expected) {}
        try { AmountUnits.sats("0.000000001", 2, new BigDecimal("250")); fail(); } catch (IllegalArgumentException expected) {}
    }
    @Test public void currencyRoundTripKeepsExactSats() {
        BigDecimal price = new BigDecimal("257.123456789");
        for (long sats : new long[]{1, 99, 123456789, 2100000000000000L}) {
            for (int unit = 0; unit < 3; unit++) assertEquals(sats, AmountUnits.sats(AmountUnits.input(sats, unit, price), unit, price));
        }
    }
}
