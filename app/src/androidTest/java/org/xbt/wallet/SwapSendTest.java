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
    @Test public void dismissedUncreatedInvoiceCanBeExplicitlyReused() throws Exception {
        JSONObject r=new JSONObject().put("dismissed",true).put("hash","a".repeat(64))
            .put("recipient_invoice","original invoice").put("last_error","old error");
        SwapSend.reopenUncreated(r,false);
        assertFalse(r.has("dismissed"));assertFalse(r.has("last_error"));
        assertEquals("original invoice",r.getString("recipient_invoice"));
        for (boolean paid : new boolean[]{false,true}) {
            JSONObject unsafe=new JSONObject().put("dismissed",true);
            if (!paid) unsafe.put("reply",new JSONObject().put("id","recorded"));
            try { SwapSend.reopenUncreated(unsafe,paid);fail("Reopened a recorded attempt"); }
            catch (Exception expected) { assertTrue(unsafe.getBoolean("dismissed")); }
        }
    }
    @Test public void cancellationOfUncreatedRequestsRequiresAuthoritativeNotFound() throws Exception {
        JSONObject r=new JSONObject().put("last_error","No route");
        assertTrue(SwapSend.canCancel(r,false));assertFalse(SwapSend.canCancel(r,true));
        assertFalse(SwapSend.dismissUncreatedCancellation(r,false,"Code: wallet_send_not_found"));
        r.put("cancel_pending",true);
        assertFalse(SwapSend.dismissUncreatedCancellation(r,false,"Code: controller_timeout"));
        assertFalse(SwapSend.dismissUncreatedCancellation(r,true,"Code: wallet_send_not_found"));
        r.put("reply",new JSONObject().put("id","c".repeat(64)));
        assertFalse(SwapSend.dismissUncreatedCancellation(r,false,"Code: wallet_send_not_found"));
        r.remove("reply");
        assertTrue(SwapSend.dismissUncreatedCancellation(r,false,"No request.\nCode: wallet_send_not_found"));
        assertTrue(r.getBoolean("dismissed"));assertFalse(r.has("cancel_pending"));assertFalse(r.has("last_error"));
        assertFalse(SwapSend.canCancel(r,false));assertEquals("Not created",SwapSend.historyState(r,""));
        assertFalse(SwapSend.canCancel(new JSONObject().put("reply",new JSONObject().put("terminal",true).put("can_cancel",true)),false));
        assertFalse(SwapSend.canCancel(new JSONObject().put("reply",new JSONObject().put("approved",true).put("can_cancel",false)),false));
    }
    @Test public void sentPaymentDoesNotClaimSwapSettlementUntilConfirmed() throws Exception {
        JSONObject record=new JSONObject().put("reply",new JSONObject().put("state","waiting_for_payment"));
        assertEquals("Payment sent",SwapSend.historyState(record,"Payment succeeded"));
        assertEquals("waiting for payment",SwapSend.historyState(record,""));
        record.getJSONObject("reply").put("state","settled").put("terminal",true);
        assertEquals("settled",SwapSend.historyState(record,"Payment succeeded"));
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
