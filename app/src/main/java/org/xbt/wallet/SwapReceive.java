package org.xbt.wallet;

import org.json.JSONObject;
import lnrpc.LightningOuterClass;

/** Validate the displayed BTC invoice against the wallet's own XBT receive invoice. */
final class SwapReceive {
    static String hash(byte[] bytes) {
        StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT,"%02x", b & 255)); return out.toString();
    }
    static void validate(JSONObject record, JSONObject reply, LightningOuterClass.PayReq decoded) throws Exception {
        if (!"xbt-wallet-receive-v1".equals(reply.getString("format"))
            || !record.getString("provider").equals(reply.getString("provider"))
            || !record.getString("environment").equals(reply.getString("environment"))
            || !record.getString("hash").equals(reply.getString("payment_hash"))
            || record.getLong("sats")*1000 != reply.getLong("xbt_msat")
            || !reply.getString("id").matches("[a-f0-9]{64}") || reply.getLong("btc_msat") <= 0
            || reply.getLong("expires_at") <= 0) throw new Exception("Controller returned mismatched receive terms. Its invoice was not displayed.");
        JSONObject prior = record.optJSONObject("reply");
        if (prior != null) for (String field : new String[]{"id","payment_hash","xbt_msat","btc_msat","expires_at"})
            if (!prior.get(field).equals(reply.get(field))) throw new Exception("Controller changed the saved swap terms. Its invoice was not displayed.");
        if (reply.has("invoice")) {
            if (decoded == null || !decoded.getPaymentHash().equals(record.getString("hash"))
                || decoded.getNumMsat() != reply.getLong("btc_msat")
                || decoded.containsFeatures(512) || decoded.containsFeatures(513) || decoded.containsFeatures(514) || decoded.containsFeatures(515)
                || decoded.getTimestamp()+decoded.getExpiry() != reply.getLong("expires_at"))
                throw new Exception("Controller invoice does not match the BTC amount, payment hash, network features or expiry.");
            if (prior != null && prior.has("invoice") && !prior.getString("invoice").equals(reply.getString("invoice")))
                throw new Exception("Controller changed the payment invoice. Check the saved swap before proceeding.");
        }
    }
}
