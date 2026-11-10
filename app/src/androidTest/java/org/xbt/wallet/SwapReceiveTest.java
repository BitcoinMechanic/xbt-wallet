package org.xbt.wallet;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONObject;
import lnrpc.LightningOuterClass;

/** Pure reply validation; no wallet creation, invoices or payments. */
public class SwapReceiveTest {
    @Test public void pollsPendingButNotFinishedOrFailedRequests() throws Exception {
        assertTrue(SwapReceive.needsRefresh(record()));
        // A passed invoice deadline still needs a final authoritative outcome.
        assertTrue(SwapReceive.needsRefresh(record().put("reply",reply().put("expires_at",1).put("terminal",false))));
        assertFalse(SwapReceive.needsRefresh(record().put("reply",reply().put("terminal",true))));
        assertFalse(SwapReceive.needsRefresh(record().put("received",true)));
        assertFalse(SwapReceive.needsRefresh(record().put("dismissed",true)));
        assertFalse(SwapReceive.needsRefresh(record().put("last_error","Connection timed out")));
        assertFalse(SwapReceive.needsRefresh(new JSONObject()));
    }
    private JSONObject record() throws Exception { return new JSONObject().put("hash","a".repeat(64)).put("provider","02"+"b".repeat(64)).put("environment","mainnet").put("sats",500000); }
    private JSONObject reply() throws Exception { return new JSONObject().put("format","xbt-wallet-receive-v1").put("id","c".repeat(64)).put("payment_hash","a".repeat(64)).put("provider","02"+"b".repeat(64)).put("environment","mainnet").put("xbt_msat",500000000).put("btc_msat",2000000).put("expires_at",1900000600).put("invoice","signed invoice validated by node"); }
    private LightningOuterClass.PayReq.Builder invoice() { return LightningOuterClass.PayReq.newBuilder().setPaymentHash("a".repeat(64)).setNumMsat(2000000).setTimestamp(1900000000).setExpiry(600); }
    @Test public void validatesReceiptBindingAndRejectsSubstitution() throws Exception {
        SwapReceive.validate(record(),reply(),invoice().build());
        for (JSONObject bad : new JSONObject[]{reply().put("payment_hash","d".repeat(64)),reply().put("environment","regtest"),reply().put("xbt_msat",499999000),reply().put("provider","03"+"f".repeat(64)),reply().put("btc_msat",3000000),reply().put("expires_at",1900000601)}) {
            try { SwapReceive.validate(record(),bad,invoice().build()); fail("accepted altered terms"); } catch(Exception expected) {}
        }
        JSONObject previous=record().put("reply",reply());
        try { SwapReceive.validate(previous,reply().put("id","e".repeat(64)),invoice().build()); fail(); } catch(Exception expected) {}
        try { SwapReceive.validate(record(),reply(),invoice().putFeatures(512,LightningOuterClass.Feature.newBuilder().build()).build()); fail(); } catch(Exception expected) {}
    }
    @Test public void pendingReplyNeedsNoInvoiceAndPreservesTerms() throws Exception {
        JSONObject r=reply();r.remove("invoice");SwapReceive.validate(record(),r,null);
        JSONObject old=record().put("reply",r);
        SwapReceive.validate(old,reply(),invoice().build());
    }
}
