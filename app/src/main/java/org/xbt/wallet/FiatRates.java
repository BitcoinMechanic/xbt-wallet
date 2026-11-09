package org.xbt.wallet;

import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

final class FiatRates {
    static final String[] CURRENCIES = {"USD", "AUD", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "EUR", "GBP", "HKD", "HUF", "IDR", "ILS", "INR", "ISK", "JPY", "KRW", "MXN", "MYR", "NOK", "NZD", "PHP", "PLN", "RON", "SEK", "SGD", "THB", "TRY", "ZAR"};
    static JSONObject fetch(boolean useTor) throws Exception {
        JSONObject response = get("https://neoxa.exchange/api/exchange/ticker/BTCB2_USDC", useTor);
        if (!response.getBoolean("success") || !response.getString("pair").equals("BTCB2_USDC"))
            throw new Exception("XBT price unavailable");
        JSONObject ticker = response.getJSONObject("ticker");
        long now = System.currentTimeMillis();
        long computed = ticker.getLong("computedAt");
        if (computed < now - 900000 || computed > now + 300000) throw new Exception("XBT quote is stale");
        BigDecimal usd = new BigDecimal(ticker.getString("lastPrice"));
        if (usd.signum() <= 0) throw new Exception("Invalid XBT price");
        JSONObject result = new JSONObject().put("USD", usd.toPlainString()).put("timestamp", computed);
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
    private static JSONObject get(String url, boolean useTor) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) (useTor ? new URL(url).openConnection(TorTransport.httpProxy()) : new URL(url).openConnection());
        connection.setConnectTimeout(useTor ? 30000 : 10000); connection.setReadTimeout(useTor ? 30000 : 10000);
        connection.setRequestProperty("Accept", "application/json");
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
