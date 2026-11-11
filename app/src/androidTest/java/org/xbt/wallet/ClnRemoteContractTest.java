package org.xbt.wallet;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import lnrpc.LightningOuterClass.*;
import routerrpc.RouterOuterClass;

/** Mutation contracts use a fake transport; never sends funds or changes any node. */
public class ClnRemoteContractTest {
    private static final String HASH="ab".repeat(32), ID="02"+"cd".repeat(32);
    @Test public void invoiceAndPaymentPreserveUnitsAndFeeLimit() throws Exception {
        ClnRemote node=new ClnRemote((method,args)->{
            if(method.equals("invoice")){
                assertEquals("any",args.getString("amount_msat"));assertTrue(args.getBoolean("exposeprivatechannels"));assertEquals("hello",args.getString("description"));
                return new JSONObject().put("bolt11","invoice-fixture").put("payment_hash",HASH);
            }
            assertEquals("pay",method);assertEquals(250100,args.getLong("amount_msat"));assertEquals("10000msat",args.getString("maxfee"));assertEquals(30,args.getInt("retry_for"));
            return new JSONObject().put("payment_hash",HASH).put("status","complete").put("amount_msat",250100).put("amount_sent_msat",251200).put("created_at",123);
        },"mainnet",ID);
        AddInvoiceResponse invoice=AddInvoiceResponse.parseFrom(node.rpc("addInvoice",Invoice.newBuilder().setMemo("hello").build()));assertEquals(HASH,ClnRemote.hex(invoice.getRHash()));
        Payment p=node.pay(RouterOuterClass.SendPaymentRequest.newBuilder().setPaymentRequest("fixture").setAmtMsat(250100).setFeeLimitSat(10).setTimeoutSeconds(30).build());
        assertEquals(Payment.PaymentStatus.SUCCEEDED,p.getStatus());assertEquals(1100,p.getFeeMsat());assertEquals(250100,p.getValueMsat());
    }
    @Test public void selectedCoinWithdrawalAndCooperativeCloseAreBounded() throws Exception {
        ClnRemote node=new ClnRemote((method,args)->{
            switch(method){
            case "withdraw":assertEquals("all",args.getString("satoshi"));assertEquals("2000perkb",args.getString("feerate"));assertEquals(HASH+":2",args.getJSONArray("utxos").getString(0));return new JSONObject().put("txid",HASH);
            case "listpeerchannels":return new JSONObject().put("channels",new JSONArray().put(new JSONObject().put("funding_txid",HASH).put("funding_outnum",2).put("channel_id","channel")));
            case "close":assertEquals(0,args.getLong("unilateraltimeout"));assertEquals("2000perkb",args.getJSONArray("feerange").getString(1));return new JSONObject().put("type","mutual").put("txids",new JSONArray().put(HASH));
            default:throw new AssertionError("Unexpected method "+method);
            }
        },"mainnet",ID);
        SendCoinsResponse sent=SendCoinsResponse.parseFrom(node.rpc("sendCoins",SendCoinsRequest.newBuilder().setAddr("address").setSendAll(true).setSatPerVbyte(2).setMinConfs(1).addOutpoints(OutPoint.newBuilder().setTxidStr(HASH).setOutputIndex(2)).build()));assertEquals(HASH,sent.getTxid());
        CloseStatusUpdate close=node.close(CloseChannelRequest.newBuilder().setChannelPoint(ChannelPoint.newBuilder().setFundingTxidStr(HASH).setOutputIndex(2)).setSatPerVbyte(2).build());assertTrue(close.hasClosePending());
    }
    @Test public void preparationIsDiscardedBeforeReviewAndFundingUsesSelection() throws Exception {
        java.util.List<String> calls=new java.util.ArrayList<>();
        ClnRemote node=new ClnRemote((method,args)->{
            calls.add(method);
            if(method.equals("txprepare")){assertEquals("1000sat",args.getJSONArray("outputs").getJSONObject(0).getString("address"));return new JSONObject().put("txid",HASH);}
            if(method.equals("txdiscard")){assertEquals(HASH,args.getString("txid"));return new JSONObject();}
            assertEquals("fundchannel",method);assertEquals("100000sat",args.getString("amount"));assertFalse(args.getBoolean("announce"));assertEquals(HASH+":2",args.getJSONArray("utxos").getString(0));return new JSONObject().put("txid",HASH).put("outnum",1);
        },"mainnet",ID);
        node.rpc("estimateFee",EstimateFeeRequest.newBuilder().putAddrToAmount("address",1000).build());
        assertEquals(java.util.Arrays.asList("txprepare","txdiscard"),calls);
        ChannelPoint c=ChannelPoint.parseFrom(node.rpc("openChannelSync",OpenChannelRequest.newBuilder().setNodePubkey(ClnRemote.bytes(ID)).setLocalFundingAmount(100000).setPrivate(true).setSatPerVbyte(2).addOutpoints(OutPoint.newBuilder().setTxidStr(HASH).setOutputIndex(2)).build()));assertEquals(1,c.getOutputIndex());
    }
}
