package org.xbt.wallet;

import org.junit.Test;
import static org.junit.Assert.*;

public class InvoiceExpirationTest {
    @Test public void convertsRequestedDurations() {
        assertEquals(3600, InvoiceExpiration.seconds("1", 2));
        assertEquals(7200, InvoiceExpiration.seconds("2", 2));
        assertEquals(432000, InvoiceExpiration.seconds("5", 3));
        assertEquals(120, InvoiceExpiration.seconds("2", 1));
        assertEquals(1209600, InvoiceExpiration.seconds("2", 4));
        assertEquals("1 hour", InvoiceExpiration.describe(3600));
        assertEquals("5 days", InvoiceExpiration.describe(432000));
    }
    @Test public void rejectsInvalidAndUnsupportedDurations() {
        for(String value:new String[]{"", "0", "-1", "1.5", "999999999999999999999999", "366"}) {
            try { InvoiceExpiration.seconds(value, 3); fail(value); } catch(IllegalArgumentException expected) {}
        }
        assertEquals(31536000, InvoiceExpiration.seconds("365", 3));
        try { InvoiceExpiration.seconds("1", -1); fail(); } catch(IllegalArgumentException expected) {}
    }
}
