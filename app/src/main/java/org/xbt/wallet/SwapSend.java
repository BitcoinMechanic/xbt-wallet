package org.xbt.wallet;

import java.math.BigDecimal;
import org.json.JSONObject;
import lnrpc.LightningOuterClass;

/** Bind a swap quote and its payable XBT invoice to the exact original BTC recipient. */
final class SwapSend {
    static long integer(JSONObject object, String key) throws Exception {
        Object raw = object.get(key);
        if (!(raw instanceof Number)) throw new Exception("Invalid numeric swap term: " + key);
        return new BigDecimal(raw.toString()).longValueExact();
    }
    static void validate(JSONObject record, JSONObject reply, LightningOuterClass.PayReq invoice) throws Exception {
        long btc = integer(reply, "btc_msat"), xbt = integer(reply, "xbt_msat"), expiry = integer(reply, "expires_at");
        if (!"xbt-wallet-send-v1".equals(reply.getString("format"))
            || !record.getString("provider").equals(reply.getString("provider"))
            || !record.getString("environment").equals(reply.getString("environment"))
            || !record.getString("hash").equals(reply.getString("payment_hash"))
            || integer(record, "btc_msat") != btc || btc <= 0 || xbt <= 0 || xbt % 1000 != 0
            || expiry <= 0 || expiry > integer(record, "recipient_expiry")
            || !reply.getString("id").matches("[a-f0-9]{64}"))
            throw new Exception("Controller returned swap terms that do not match this BTC invoice.");
        JSONObject fees = reply.getJSONObject("fees");
        long bps = integer(fees, "markup_bps"), fee = integer(fees, "provider_fee_msat"), routing = integer(fees, "routing_fee_msat");
        if (bps < 0 || bps > 500 || fee < 0 || fee >= xbt || routing < 0
            || !"xbt".equals(fees.getString("provider_fee_asset")) || !"btc".equals(fees.getString("routing_fee_asset"))
            || (bps == 0 && fee != 0)) throw new Exception("Controller returned an invalid fee breakdown.");
        JSONObject prior = record.optJSONObject("reply");
        if (prior != null) {
            for (String key : new String[]{"id", "payment_hash", "btc_msat", "xbt_msat", "expires_at"})
                if (!prior.get(key).toString().equals(reply.get(key).toString())) throw new Exception("Controller changed the saved swap terms: " + key);
            JSONObject oldFees = prior.getJSONObject("fees");
            for (String key : new String[]{"markup_bps", "provider_fee_msat", "provider_fee_asset", "routing_fee_msat", "routing_fee_asset", "price_source", "price_checked_at"})
                if (!oldFees.get(key).toString().equals(fees.get(key).toString())) throw new Exception("Controller changed the saved swap fee breakdown.");
        }
        if (reply.has("invoice")) {
            if (invoice == null || !reply.getBoolean("approved") || reply.getBoolean("blocked") || reply.getBoolean("terminal")
                || !invoice.getPaymentHash().equals(record.getString("hash")) || invoice.getNumMsat() != xbt
                || !(invoice.containsFeatures(512) || invoice.containsFeatures(513))
                || invoice.containsFeatures(514) || invoice.containsFeatures(515)
                || !invoice.getDestination().equals(record.getString("provider"))
                || Math.addExact(invoice.getTimestamp(), invoice.getExpiry()) != expiry)
                throw new Exception("Swap payment invoice has a different amount, recipient, hash, chain or expiry. Payment blocked.");
            if (prior != null && prior.has("invoice") && !prior.getString("invoice").equals(reply.getString("invoice")))
                throw new Exception("Controller changed the saved payment invoice. Payment blocked.");
        }
    }
    static String sats(long msats) {
        return BigDecimal.valueOf(msats, 3).stripTrailingZeros().toPlainString();
    }
}
