package org.xbt.wallet;

import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

final class FiatRates {
    static final String[] CURRENCIES = {"USD", "AUD", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "EUR", "GBP", "HKD", "HUF", "IDR", "ILS", "INR", "ISK", "JPY", "KRW", "MXN", "MYR", "NOK", "NZD", "PHP", "PLN", "RON", "SEK", "SGD", "THB", "TRY", "ZAR"};
    static final String DEFAULT_URL = "https://neoxa.exchange/api/exchange/ticker/BTCB2_USDC";
    static String sourceUrl(String value) {
        try {
            java.net.URI uri = new java.net.URI(value.trim());
            boolean onion = "http".equals(uri.getScheme()) && uri.getHost() != null
                && uri.getHost().matches("[a-z2-7]{56}\\.onion");
            if ((!"https".equals(uri.getScheme()) && !onion) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) throw new Exception();
            return uri.toASCIIString();
        } catch (Exception e) { throw new IllegalArgumentException("Enter an HTTPS price API URL, or an HTTP v3 onion URL."); }
    }
    static String jsonPath(String value) {
        String path = value.trim();
        if (!path.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*") || path.length() > 200)
            throw new IllegalArgumentException("Enter a JSON field path such as ticker.lastPrice.");
        return path;
    }
    private static Object at(JSONObject object, String path) throws Exception {
        Object value = object;
        for (String key : jsonPath(path).split("\\.")) value = ((JSONObject)value).get(key);
        return value;
    }
    static JSONObject quote(JSONObject response, String pricePath, String timestampPath, long now) throws Exception {
        BigDecimal usd = new BigDecimal(at(response, pricePath).toString());
        if (usd.signum() <= 0 || usd.precision() > 40 || Math.abs((long)usd.scale()) > 18)
            throw new Exception("Invalid XBT/USD price");
        long timestamp = new BigDecimal(at(response, timestampPath).toString()).longValueExact();
        // Accept Unix seconds and milliseconds, without inventing quote freshness.
        if (timestamp > 0 && timestamp < 100000000000L) timestamp = Math.multiplyExact(timestamp, 1000);
        if (timestamp < now - 900000 || timestamp > now + 300000) throw new Exception("XBT quote is stale or its timestamp is invalid");
        return new JSONObject().put("USD", usd.toPlainString()).put("timestamp", timestamp);
    }
    static JSONObject fetch(boolean useTor) throws Exception {
        return fetch(useTor, "", "ticker.lastPrice", "ticker.computedAt");
    }
    static JSONObject fetch(boolean useTor, String customUrl, String pricePath, String timestampPath) throws Exception {
        JSONObject response = get(customUrl.isEmpty() ? DEFAULT_URL : sourceUrl(customUrl), useTor);
        if ((customUrl.isEmpty() || customUrl.equals(DEFAULT_URL))
                && (!response.getBoolean("success") || !response.getString("pair").equals("BTCB2_USDC")))
            throw new Exception("XBT price unavailable or unexpected Neoxa market");
        long now = System.currentTimeMillis();
        JSONObject result = quote(response, pricePath, timestampPath, now);
        BigDecimal usd = new BigDecimal(result.getString("USD"));
        // A missing FX service must not suppress the available USD quote.
        try {
            JSONObject fx = get("https://api.frankfurter.dev/v1/latest?base=USD", useTor);
            if (!fx.getString("base").equals("USD")) throw new Exception("Invalid FX base");
            java.text.SimpleDateFormat date = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT);
            date.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            long age = now - date.parse(fx.getString("date")).getTime();
            if (age < -86400000L || age > 7 * 86400000L) throw new Exception("FX quote is stale");
            JSONObject rates = fx.getJSONObject("rates");
            for (String code : CURRENCIES) {
                if (code.equals("USD") || !rates.has(code)) continue;
                BigDecimal rate = new BigDecimal(rates.getString(code));
                if (rate.signum() > 0) result.put(code, usd.multiply(rate).toPlainString());
            }
        } catch (Exception ignored) { /* Show unavailable for other currencies. */ }
        return result;
    }
    static JSONObject swapMarket(boolean useTor) throws Exception {
        JSONObject response = get("https://neoxa.exchange/api/exchange/ticker/BTCB2_BTC", useTor);
        if (!response.getBoolean("success") || !"BTCB2_BTC".equals(response.getString("pair")))
            throw new Exception("Neoxa returned an unexpected market.");
        JSONObject ticker = response.getJSONObject("ticker");
        long timestamp = ticker.getLong("computedAt");
        SwapPrice.requireFresh(timestamp, System.currentTimeMillis());
        BigDecimal price = new BigDecimal(ticker.getString("lastPrice"));
        if (price.signum() <= 0) throw new Exception("Neoxa returned an invalid XBT/BTC price.");
        return new JSONObject().put("btc_per_xbt", price.toPlainString()).put("timestamp", timestamp);
    }
    private static JSONObject get(String url, boolean useTor) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) (useTor ? new URL(url).openConnection(TorTransport.httpProxy()) : new URL(url).openConnection());
        connection.setConnectTimeout(useTor ? 30000 : 10000); connection.setReadTimeout(useTor ? 30000 : 10000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new Exception("Rate service unavailable");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 262144) throw new Exception("Rate response too large");
                    bytes.write(buffer, 0, count);
                }
                return new JSONObject(bytes.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
}
