package org.xbt.wallet;
import java.math.BigDecimal;
import org.junit.Test;
import static org.junit.Assert.*;
public class BalanceDisplayTest {
    @Test public void satsAndXbtPreserveExactAmounts() {
        assertEquals("123,456,789 sats", BalanceDisplay.format(123456789,0,"USD",null));
        assertEquals("1.23456789 XBT", BalanceDisplay.format(123456789,1,"USD",null));
        assertEquals("0.00000001 XBT", BalanceDisplay.format(1,1,"USD",null));
        assertEquals("0 XBT", BalanceDisplay.format(0,1,"USD",null));
    }
    @Test public void fiatUsesQuoteAndCurrencyPrecision() {
        assertEquals("≈ 2.89 USD", BalanceDisplay.format(1000000,2,"USD",new BigDecimal("288.95")));
        assertEquals("≈ 158 JPY", BalanceDisplay.format(100000000,2,"JPY",new BigDecimal("158.33")));
        assertEquals("< 0.01 USD", BalanceDisplay.format(1,2,"USD",new BigDecimal("288.95")));
    }
    @Test public void noQuoteIsNotZeroMoney() {
        assertEquals("CAD —", BalanceDisplay.format(1000000,2,"CAD",null));
    }
}
