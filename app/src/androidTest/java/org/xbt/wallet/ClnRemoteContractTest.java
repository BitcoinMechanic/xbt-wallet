package org.xbt.wallet;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import lnrpc.LightningOuterClass.*;
import routerrpc.RouterOuterClass;

/** Mutation contracts use a fake transport; never sends funds or changes any node. */
public class ClnRemoteContractTest {
    private static final String HASH="ab".repeat(32), ID="02"+"cd".repeat(32);

    @Test public void invoiceLookupSupportsTextAndBinaryHashes() throws Exception {
        int[] calls={0};
        ClnRemote node=new ClnRemote((method,args)->{
            calls[0]++;
            assertEquals("listinvoices",method);
            assertEquals(HASH,args.getString("payment_hash"));
            return new JSONObject().put("invoices",new JSONArray().put(new JSONObject()
                .put("payment_hash",HASH).put("status","paid").put("amount_received_msat",1000000)));
        },"mainnet",ID);
        PaymentHash[] requests={
            PaymentHash.newBuilder().setRHashStr(HASH).build(),
            PaymentHash.newBuilder().setRHash(ClnRemote.bytes(HASH)).build(),
            PaymentHash.newBuilder().setRHashStr(HASH.toUpperCase(java.util.Locale.ROOT)).setRHash(ClnRemote.bytes(HASH)).build()
        };
        for(PaymentHash request:requests) {
            Invoice invoice=Invoice.parseFrom(node.rpc("lookupInvoice",request));
            assertEquals(Invoice.InvoiceState.SETTLED,invoice.getState());
            assertEquals(ClnRemote.bytes(HASH),invoice.getRHash());
        }
        assertEquals(3,calls[0]);
    }

    @Test public void invalidInvoiceHashesNeverReachNode() throws Exception {
        ClnRemote node=new ClnRemote((method,args)->{throw new AssertionError("Invalid hash reached node");},"mainnet",ID);
        PaymentHash[] requests={
            PaymentHash.getDefaultInstance(),
            PaymentHash.newBuilder().setRHashStr("ab").build(),
            PaymentHash.newBuilder().setRHashStr("z".repeat(64)).build(),
            PaymentHash.newBuilder().setRHash(ClnRemote.bytes("ab")).build(),
            PaymentHash.newBuilder().setRHashStr(HASH).setRHash(ClnRemote.bytes("cd".repeat(32))).build()
        };
        for(PaymentHash request:requests) {
            try { node.rpc("lookupInvoice",request); fail("Accepted invalid hash"); }
            catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Invoice lookup")); }
        }
    }

    @Test public void unconfirmedDepositsReachBalancesOutputsAndReceiptDetection() throws Exception {
        JSONObject coin = new JSONObject().put("txid", HASH).put("output", 0)
            .put("amount_msat", 12500000).put("address", "receive-address")
            .put("scriptpubkey", "0014" + "ab".repeat(20)).put("status", "unconfirmed");
        ClnRemote node = new ClnRemote((method,args) -> {
            switch (method) {
            case "getinfo": return new JSONObject().put("blockheight", 100);
            case "listfunds": return new JSONObject().put("outputs", new JSONArray().put(coin));
            case "listtransactions": return new JSONObject().put("transactions", new JSONArray().put(
                new JSONObject().put("hash", HASH).put("blockheight", coin.optInt("blockheight"))
                    .put("inputs", new JSONArray()).put("outputs", new JSONArray().put(new JSONObject().put("index", 0)))));
            default: throw new AssertionError(method);
            }
        }, "mainnet", ID);
        WalletBalanceResponse balance = WalletBalanceResponse.parseFrom(node.rpc("walletBalance", WalletBalanceRequest.getDefaultInstance()));
        assertEquals(12500, balance.getTotalBalance()); assertEquals(12500, balance.getUnconfirmedBalance()); assertEquals(0, balance.getConfirmedBalance());
        ListUnspentResponse outputs = ListUnspentResponse.parseFrom(node.rpc("listUnspent", ListUnspentRequest.newBuilder().setMinConfs(0).build()));
        assertEquals(1, outputs.getUtxosCount()); assertEquals(0, outputs.getUtxos(0).getConfirmations());
        assertEquals(0, ListUnspentResponse.parseFrom(node.rpc("listUnspent", ListUnspentRequest.newBuilder().setMinConfs(1).build())).getUtxosCount());
        TransactionDetails txs = TransactionDetails.parseFrom(node.rpc("getTransactions", GetTransactionsRequest.getDefaultInstance()));
        assertTrue(DepositDetection.received("receive-address", java.util.Collections.emptySet(), txs));
        coin.put("status", "confirmed").put("blockheight", 100);
        balance = WalletBalanceResponse.parseFrom(node.rpc("walletBalance", WalletBalanceRequest.getDefaultInstance()));
        assertEquals(12500, balance.getTotalBalance()); assertEquals(0, balance.getUnconfirmedBalance()); assertEquals(12500, balance.getConfirmedBalance());
    }
    @Test public void bolt12CreationListingAndDisableUseNativeOfferRpc() throws Exception {
        java.util.List<String> calls=new java.util.ArrayList<>();
        ClnRemote node=new ClnRemote((method,args)->{
            calls.add(method);
            JSONObject offer=new JSONObject().put("offer_id",HASH).put("bolt12","lno1fixture").put("active",true).put("created",true).put("label","Private label");
            switch(method) {
            case "offer":
                assertEquals("500000msat",args.getString("amount")); assertEquals("Coffee",args.getString("description"));
                assertEquals("Private label",args.getString("label")); assertEquals("Shop",args.getString("issuer"));
                assertFalse(args.has("absolute_expiry")); return offer;
            case "listoffers":return new JSONObject().put("offers",new JSONArray().put(offer));
            case "decode":return new JSONObject().put("valid",true).put("type","bolt12 offer").put("offer_id",HASH)
                .put("offer_amount_msat","500000msat").put("offer_description","Coffee").put("offer_issuer","Shop");
            case "disableoffer":assertEquals(HASH,args.getString("offer_id"));return new JSONObject();
            default:throw new AssertionError(method);
            }
        },"mainnet",ID);
        offersrpc.OffersOuterClass.CreateOfferResponse created=offersrpc.OffersOuterClass.CreateOfferResponse.parseFrom(node.rpc("offersCreateOffer",
            offersrpc.OffersOuterClass.CreateOfferRequest.newBuilder().setAmountMsat(500000).setDescription("Coffee").setIssuer("Shop").setLabel("Private label").build()));
        assertEquals(500000,created.getOffer().getAmountMsat());assertTrue(created.getCreated());
        offersrpc.OffersOuterClass.ListOffersResponse listed=offersrpc.OffersOuterClass.ListOffersResponse.parseFrom(node.rpc("offersListOffers",offersrpc.OffersOuterClass.ListOffersRequest.getDefaultInstance()));
        assertEquals("Coffee",listed.getOffers(0).getDescription());assertEquals(500000,listed.getOffers(0).getAmountMsat());
        node.rpc("offersDisableOffer",offersrpc.OffersOuterClass.DisableOfferRequest.newBuilder().setOfferId(created.getOffer().getOfferId()).build());
        assertEquals(java.util.Arrays.asList("offer","listoffers","decode","disableoffer"),calls);
    }
    @Test public void amountlessOfferAndCustomInvoiceExpirationArePreserved() throws Exception {
        ClnRemote node=new ClnRemote((method,args)->{
            if(method.equals("offer")) {
                assertEquals("any",args.getString("amount"));
                return new JSONObject().put("offer_id",HASH).put("bolt12","lno1fixture").put("active",true).put("created",true);
            }
            assertEquals("invoice",method); assertEquals(432000,args.getLong("expiry"));
            return new JSONObject().put("bolt11","fixture").put("payment_hash",HASH);
        },"mainnet",ID);
        node.rpc("offersCreateOffer",offersrpc.OffersOuterClass.CreateOfferRequest.getDefaultInstance());
        node.rpc("addInvoice",Invoice.newBuilder().setExpiry(InvoiceExpiration.seconds("5",3)).build());
    }
    @Test public void invoiceRoutingChoicesAreHonored() throws Exception {
        final int[] calls = {0};
        ClnRemote node=new ClnRemote((method,args)->{
            calls[0]++;
            assertEquals("invoice",method);
            assertFalse(args.getBoolean("exposeprivatechannels"));
            return new JSONObject().put("bolt11","invoice-fixture").put("payment_hash",HASH);
        },"mainnet",ID);
        node.rpc("addInvoice",Invoice.newBuilder().setPrivate(false).build());
        try {
            node.rpc("addInvoice",Invoice.newBuilder().setIsBlinded(true).build());
            fail("Unsupported blinded invoices must not silently become ordinary invoices");
        } catch(Exception expected) { assertTrue(expected.getMessage().contains("Blinded paths")); }
        assertEquals(1,calls[0]);
    }
    @Test public void invoiceAndPaymentPreserveUnitsAndFeeLimit() throws Exception {
        ClnRemote node=new ClnRemote((method,args)->{
            if(method.equals("invoice")){
                assertEquals("any",args.getString("amount_msat"));assertTrue(args.getBoolean("exposeprivatechannels"));assertEquals("hello",args.getString("description"));
                return new JSONObject().put("bolt11","invoice-fixture").put("payment_hash",HASH);
            }
            assertEquals("pay",method);assertEquals(250100,args.getLong("amount_msat"));assertEquals("10000msat",args.getString("maxfee"));assertEquals(30,args.getInt("retry_for"));
            return new JSONObject().put("payment_hash",HASH).put("status","complete").put("amount_msat",250100).put("amount_sent_msat",251200).put("created_at",123);
        },"mainnet",ID);
        AddInvoiceResponse invoice=AddInvoiceResponse.parseFrom(node.rpc("addInvoice",Invoice.newBuilder().setMemo("hello").setPrivate(true).build()));assertEquals(HASH,ClnRemote.hex(invoice.getRHash()));
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
    @Test public void forceCloseUsesShortTimeoutAndNoCooperativeFeeOverride() throws Exception {
        ClnRemote node = new ClnRemote((method, args) -> {
            if (method.equals("listpeerchannels")) return new JSONObject().put("channels", new JSONArray().put(
                new JSONObject().put("funding_txid", HASH).put("funding_outnum", 2).put("channel_id", "channel")));
            assertEquals("close", method);
            assertEquals("channel", args.getString("id"));
            assertEquals(1, args.getInt("unilateraltimeout"));
            assertFalse(args.has("feerange"));
            assertFalse(args.has("force_lease_closed"));
            return new JSONObject().put("type", "unilateral").put("txids", new JSONArray().put(HASH));
        }, "mainnet", ID);
        Channel channel = Channel.newBuilder().setChannelPoint(HASH + ":2").build();
        CloseChannelRequest request = MainActivity.channelCloseRequest(channel, 99, true);
        assertTrue(request.getForce());
        assertEquals(0, request.getSatPerVbyte()); assertEquals(0, request.getMaxFeePerVbyte());
        assertTrue(node.close(request).hasClosePending());
        CloseChannelRequest cooperative = MainActivity.channelCloseRequest(channel, 3, false);
        assertFalse(cooperative.getForce()); assertEquals(3, cooperative.getSatPerVbyte()); assertEquals(3, cooperative.getMaxFeePerVbyte());
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
