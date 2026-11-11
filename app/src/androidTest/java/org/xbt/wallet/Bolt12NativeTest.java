package org.xbt.wallet;

import android.content.*;
import android.os.IBinder;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import lnrpc.LightningOuterClass.*;
import lnrpc.Stateservice;
import offersrpc.OffersOuterClass.*;

/** Only a disposable regtest wallet. Creates requests, never sends payments. */
public class Bolt12NativeTest {
    private static android.view.View find(android.view.View view, java.util.function.Predicate<android.view.View> test) {
        if(test.test(view))return view;
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){
            android.view.View found=find(group.getChildAt(i),test);if(found!=null)return found;
        }
        return null;
    }
    @Test(timeout=150000) public void nativeOffersAndInvoiceExpirationRoundTrip() throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        assertEquals("org.xbt.wallet.profiletest",context.getPackageName());
        WalletProfiles.activate(context,WalletProfiles.LEGACY);
        WalletProfiles.settings(context,WalletProfiles.LEGACY).edit().putString("network","regtest")
            .putString("mode","easy").putBoolean("modeChoiceMade",true).putBoolean("dnsDiscoveryConfigured",true)
            .putString("syncPeer","10.0.2.2:19444").remove("receive-expiration-count").remove("receive-expiration-unit").commit();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CompletableFuture<NodeService> ready=new CompletableFuture<>();
        ServiceConnection connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName n,IBinder b){ready.complete(((NodeService.LocalBinder)b).service());}
            public void onServiceDisconnected(ComponentName n){}
        };
        context.bindService(new Intent(context,NodeService.class),connection,Context.BIND_AUTO_CREATE);
        NodeService node=ready.get(15,TimeUnit.SECONDS);
        try {
            long deadline=System.currentTimeMillis()+90000;
            while(!node.rpcReady&&System.currentTimeMillis()<deadline)Thread.sleep(200);
            assertTrue(node.status,node.rpcReady);
            Stateservice.WalletState state=node.walletState();
            while(state==Stateservice.WalletState.WAITING_TO_START&&System.currentTimeMillis()<deadline){Thread.sleep(200);state=node.walletState();}
            if(state==Stateservice.WalletState.NON_EXISTING)node.createWallet();
            else if(state==Stateservice.WalletState.LOCKED)node.unlock();
            while(node.walletState()!=Stateservice.WalletState.SERVER_ACTIVE&&System.currentTimeMillis()<deadline)Thread.sleep(200);
            assertEquals(Stateservice.WalletState.SERVER_ACTIVE,node.walletState());
            assertEquals("regtest",GetInfoResponse.parseFrom(node.rpc("getInfo",GetInfoRequest.getDefaultInstance())).getChains(0).getNetwork());
            CreateOfferResponse made=CreateOfferResponse.parseFrom(node.rpc("offersCreateOffer",CreateOfferRequest.newBuilder()
                .setDescription("Disposable BOLT12 test " + java.util.UUID.randomUUID()).setLabel("isolated-test").build()));
            assertTrue(made.getOffer().getBolt12().startsWith("lno1"));assertTrue(made.getOffer().getActive());
            assertEquals(0,made.getOffer().getAmountMsat());
            ListOffersResponse listed=ListOffersResponse.parseFrom(node.rpc("offersListOffers",ListOffersRequest.getDefaultInstance()));
            assertTrue(listed.getOffersList().stream().anyMatch(o->o.getOfferId().equals(made.getOffer().getOfferId())));
            node.rpc("offersDisableOffer",DisableOfferRequest.newBuilder().setOfferId(made.getOffer().getOfferId()).build());
            ListOffersResponse active=ListOffersResponse.parseFrom(node.rpc("offersListOffers",ListOffersRequest.newBuilder().setActiveOnly(true).build()));
            assertFalse(active.getOffersList().stream().anyMatch(o->o.getOfferId().equals(made.getOffer().getOfferId())));
            long expiry=InvoiceExpiration.seconds("5",3);
            AddInvoiceResponse invoice=AddInvoiceResponse.parseFrom(node.rpc("addInvoice",Invoice.newBuilder().setExpiry(expiry).setMemo("Expiration test").build()));
            PayReq decoded=PayReq.parseFrom(node.rpc("decodePayReq",PayReqString.newBuilder().setPayReq(invoice.getPaymentRequest()).build()));
            assertEquals(expiry,decoded.getExpiry());
            java.lang.reflect.Method receive=MainActivity.class.getDeclaredMethod("receiveLightning"); receive.setAccessible(true);
            android.widget.EditText[] count={null}; android.widget.Button[] toggle={null};
            instrumentation.runOnMainSync(()->{
                try { receive.invoke(activity); } catch(Exception e) { throw new AssertionError(e); }
                android.view.View root=activity.getWindow().getDecorView();
                count[0]=(android.widget.EditText)find(root,v->"Expiration number".contentEquals(v.getContentDescription()==null?"":v.getContentDescription()));
                toggle[0]=(android.widget.Button)find(root,v->v instanceof android.widget.Button && ((android.widget.Button)v).getText().toString().startsWith("Expiration ("));
                assertNotNull(count[0]);assertNotNull(toggle[0]);assertFalse(count[0].isShown());
                assertEquals("1",count[0].getText().toString()); toggle[0].performClick();
            });
            Thread.sleep(400);
            instrumentation.runOnMainSync(()->{
                assertTrue(count[0].isShown());assertTrue(count[0].getHeight()>0);count[0].setText("2");
                assertTrue(toggle[0].getText().toString().contains("2 hours"));toggle[0].performClick();
            });
            Thread.sleep(400);
            instrumentation.runOnMainSync(()->{
                assertFalse(count[0].isShown());
                try { receive.invoke(activity); } catch(Exception e) { throw new AssertionError(e); }
                android.widget.EditText restored=(android.widget.EditText)find(activity.getWindow().getDecorView(),v->"Expiration number".contentEquals(v.getContentDescription()==null?"":v.getContentDescription()));
                assertFalse(restored.isShown());assertEquals("2",restored.getText().toString());
            });
        } finally { node.stopForSwitch();context.unbindService(connection); }
    }
}
