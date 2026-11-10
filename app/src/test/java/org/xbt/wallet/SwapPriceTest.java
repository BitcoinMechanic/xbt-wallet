package org.xbt.wallet;

import java.math.BigDecimal;
import org.junit.Test;
import static org.junit.Assert.*;

public class SwapPriceTest {
    private static final BigDecimal MARKET = new BigDecimal("0.004");
    @Test public void checksActualCostInBothDirections() {
        assertEquals("0.00", new SwapPrice(4000, 1000000, true, MARKET).percent());
        assertEquals("0.00", new SwapPrice(1000000, 4000, false, MARKET).percent());
        assertEquals("6.00", new SwapPrice(4240, 1000000, true, MARKET).percent());
        assertEquals("6.00", new SwapPrice(1060000, 4000, false, MARKET).percent());
        assertTrue(new SwapPrice(4240, 1000000, true, MARKET).warning());
        assertTrue(new SwapPrice(1060000, 4000, false, MARKET).warning());
    }
    @Test public void thresholdIsStrictAndNotRoundedForDecision() {
        assertFalse(new SwapPrice(4200, 1000000, true, MARKET).warning());
        assertTrue(new SwapPrice(4200001, 1000000000, true, MARKET).warning());
        assertFalse(new SwapPrice(3800, 1000000, true, MARKET).warning());
    }
    @Test public void staleAndFuturePricesCannotPassAsCurrent() {
        long now = 1000000;
        SwapPrice.requireFresh(now - 300000, now);
        for (long timestamp : new long[]{0, now - 300001, now + 60001}) {
            try { SwapPrice.requireFresh(timestamp, now); fail("Accepted invalid timestamp"); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test(expected=IllegalArgumentException.class) public void zeroMarketIsNotSafe() {
        new SwapPrice(1, 1, true, BigDecimal.ZERO);
    }
}
