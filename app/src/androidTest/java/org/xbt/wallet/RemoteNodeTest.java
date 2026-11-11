package org.xbt.wallet;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import org.json.*;
import java.io.*;
import lnrpc.LightningOuterClass.*;

/** Opt-in read-only probe. Fixture is copied privately, never supplied as shell arguments. */
public class RemoteNodeTest {
    @Test public void verifiesNodeAndReadsWalletWithoutEmbeddedRuntime() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("org.xbt.wallet.profiletest",context.getPackageName());
        File file=new File(context.getFilesDir(),"remote-fixture.json");assumeTrue(file.exists());
        JSONObject saved=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        RemoteConnection config=new RemoteConnection(saved.getString("endpoint"),saved.getString("rune"),saved.optString("certificate"),saved.getString("nodeId"));
        ClnRemote node=new ClnRemote(config,"mainnet");assertEquals(config.nodeId,node.verify());
        assertEquals(config.nodeId,GetInfoResponse.parseFrom(node.rpc("getInfo",GetInfoRequest.getDefaultInstance())).getIdentityPubkey());
        assertTrue(WalletBalanceResponse.parseFrom(node.rpc("walletBalance",WalletBalanceRequest.getDefaultInstance())).getTotalBalance()>=0);
        assertTrue(ListChannelsResponse.parseFrom(node.rpc("listChannels",ListChannelsRequest.getDefaultInstance())).getChannelsCount()>0);
        PendingChannelsResponse.parseFrom(node.rpc("pendingChannels",PendingChannelsRequest.getDefaultInstance()));
        ClosedChannelsResponse.parseFrom(node.rpc("closedChannels",ClosedChannelsRequest.getDefaultInstance()));
        ListUnspentResponse.parseFrom(node.rpc("listUnspent",ListUnspentRequest.newBuilder().setMinConfs(1).setMaxConfs(Integer.MAX_VALUE).build()));
        TransactionDetails.parseFrom(node.rpc("getTransactions",GetTransactionsRequest.getDefaultInstance()));
        ListPaymentsResponse.parseFrom(node.rpc("listPayments",ListPaymentsRequest.getDefaultInstance()));
        ListInvoiceResponse invoices=ListInvoiceResponse.parseFrom(node.rpc("listInvoices",ListInvoiceRequest.getDefaultInstance()));
        ListPeersResponse.parseFrom(node.rpc("listPeers",ListPeersRequest.getDefaultInstance()));
        if(invoices.getInvoicesCount()>0){
            Invoice i=invoices.getInvoices(0);
            if(!i.getPaymentRequest().isEmpty()){
                PayReq decoded=PayReq.parseFrom(node.rpc("decodePayReq",PayReqString.newBuilder().setPayReq(i.getPaymentRequest()).build()));
                assertTrue(decoded.containsFeatures(512)||decoded.containsFeatures(513));
                assertEquals(ClnRemote.hex(i.getRHash()),decoded.getPaymentHash());
                assertEquals(i.getRHash(),Invoice.parseFrom(node.rpc("lookupInvoice",PaymentHash.newBuilder().setRHash(i.getRHash()).build())).getRHash());
            }
        }
        try{new ClnRemote(new RemoteConnection(config.endpoint,config.rune,config.certificate,"02"+"ab".repeat(32)),"mainnet").verify();fail("Wrong identity accepted");}catch(Exception expected){assertTrue(expected.getMessage().contains("identity"));}
        try{new ClnRemote(config,"regtest").verify();fail("Wrong network accepted");}catch(Exception expected){assertTrue(expected.getMessage().contains("network"));}
        try{new ClnRemote(new RemoteConnection(config.endpoint,config.rune,"",config.nodeId),"mainnet").verify();fail("Private CA trusted without configuration");}catch(Exception expected){assertTrue(expected.getMessage().contains("TLS"));}
    }
}
