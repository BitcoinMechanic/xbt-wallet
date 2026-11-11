package org.xbt.wallet;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONObject;
import lnrpc.LightningOuterClass;

/** Pure validation only; never starts a node or touches wallet data. */
public class SwapSendTest {
    private JSONObject record() throws Exception { return new JSONObject().put("hash","a".repeat(64)).put("provider","02"+"b".repeat(64)).put("environment","mainnet").put("btc_msat",2000000).put("recipient_expiry",1900003600); }
    private JSONObject fees() throws Exception { return new JSONObject().put("markup_bps",100).put("provider_fee_msat",5000000).put("provider_fee_asset","xbt").put("routing_fee_msat",1000).put("routing_fee_asset","btc").put("price_source","neoxa.exchange").put("price_checked_at",1900000000000L); }
    private JSONObject reply() throws Exception { return new JSONObject().put("format","xbt-wallet-send-v1").put("id","c".repeat(64)).put("payment_hash","a".repeat(64)).put("provider","02"+"b".repeat(64)).put("environment","mainnet").put("btc_msat",2000000).put("xbt_msat",505000000).put("expires_at",1900000600).put("approved",true).put("terminal",false).put("blocked",false).put("fees",fees()).put("invoice","node-decoded invoice"); }
    private LightningOuterClass.PayReq.Builder invoice() { return LightningOuterClass.PayReq.newBuilder().setPaymentHash("a".repeat(64)).setDestination("02"+"b".repeat(64)).setNumMsat(505000000).setTimestamp(1900000000).setExpiry(600).putFeatures(512,LightningOuterClass.Feature.newBuilder().build()); }
    private void reject(JSONObject r, JSONObject q, LightningOuterClass.PayReq i) throws Exception {
        try { SwapSend.validate(r,q,i); fail("Accepted changed swap terms"); } catch (Exception expected) { }
    }
    @Test public void bindsQuoteToOriginalBtcInvoiceAndXbtProvider() throws Exception {
        SwapSend.validate(record(),reply(),invoice().build());
        for (JSONObject bad : new JSONObject[]{reply().put("payment_hash","d".repeat(64)), reply().put("provider","03"+"e".repeat(64)), reply().put("environment","regtest"),reply().put("btc_msat",3000000),reply().put("xbt_msat",506000000),reply().put("expires_at",1900000601),reply().put("blocked",true),reply().put("terminal",true),reply().put("approved",false)}) reject(record(),bad,invoice().build());
        reject(record(),reply(),invoice().clearFeatures().build());
        reject(record(),reply(),invoice().setDestination("03"+"e".repeat(64)).build());
    }
    @Test public void quoteNeedsNoInvoiceButAcceptanceCannotChangeTerms() throws Exception {
        JSONObject quote=reply();quote.remove("invoice");quote.put("approved",false);
        SwapSend.validate(record(),quote,null);
        JSONObject saved=record().put("reply",quote);
        SwapSend.validate(saved,reply(),invoice().build());
        reject(saved,reply().put("id","d".repeat(64)),invoice().build());
        reject(saved,reply().put("fees",fees().put("markup_bps",200)),invoice().build());
        reject(record().put("reply",reply()),reply().put("invoice","different invoice"),invoice().build());
    }
    @Test public void rejectsFractionalAndMisleadingFeeTerms() throws Exception {
        reject(record(),reply().put("xbt_msat",505000000.5),invoice().build());
        reject(record(),reply().put("fees",fees().put("provider_fee_asset","btc")),invoice().build());
        reject(record(),reply().put("fees",fees().put("provider_fee_msat",-1)),invoice().build());
        reject(record(),reply().put("fees",fees().put("markup_bps",0)),invoice().build());
        reject(record(),reply().put("fees",fees().put("markup_bps",501)),invoice().build());
    }
}
