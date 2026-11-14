package org.xbt.wallet;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class FiatSourceTest {
    @Test public void customPathsAcceptSecondsAndMillisecondsWithoutChangingPrice() throws Exception {
        long now = 1800000000000L;
        for (long time : new long[]{now, now / 1000}) {
            JSONObject response = new JSONObject().put("data", new JSONObject().put("price", "256.8100").put("time", time));
            JSONObject quote = FiatRates.quote(response, "data.price", "data.time", now);
            assertEquals("256.8100", quote.getString("USD"));
            assertEquals(now, quote.getLong("timestamp"));
        }
    }
    @Test public void rejectsBadOrStaleQuotes() throws Exception {
        long now = 1800000000000L;
        for (String price : new String[]{"0", "-1", "NaN", "1e100000"}) {
            JSONObject response = new JSONObject().put("price", price).put("time", now);
            assertThrows(Exception.class, () -> FiatRates.quote(response, "price", "time", now));
        }
        for (long time : new long[]{0, now - 900001, now + 300001}) {
            JSONObject response = new JSONObject().put("price", "250").put("time", time);
            assertThrows(Exception.class, () -> FiatRates.quote(response, "price", "time", now));
        }
        assertThrows(Exception.class, () -> FiatRates.quote(new JSONObject().put("price", "250"), "price", "time", now));
    }
    @Test public void sourceRequiresSecureTransportAndExplicitFields() {
        assertEquals("https://example.com/api?pair=XBT_USD", FiatRates.sourceUrl("https://example.com/api?pair=XBT_USD"));
        for (String url : new String[]{"http://example.com/api", "file:///tmp/rate", "https://user:password@example.com", "https://example.com/#price"})
            assertThrows(IllegalArgumentException.class, () -> FiatRates.sourceUrl(url));
        assertEquals("ticker.lastPrice", FiatRates.jsonPath("ticker.lastPrice"));
        assertThrows(IllegalArgumentException.class, () -> FiatRates.jsonPath(""));
    }
}
