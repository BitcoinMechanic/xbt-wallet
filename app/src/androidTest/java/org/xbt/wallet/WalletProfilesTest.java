package org.xbt.wallet;
import android.content.*;
import android.os.IBinder;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import lnrpc.LightningOuterClass.*;
import lnrpc.Stateservice;

/** Run phases in separate instrumentation invocations; only the isolated disposable app is allowed. */
public class WalletProfilesTest {
    @Test(timeout=150000) public void isolatedProfileLifecycle() throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context c=instrumentation.getTargetContext();
        assertEquals("org.xbt.wallet.profiletest",c.getPackageName());
        String phase=InstrumentationRegistry.getArguments().getString("phase","first");
        String id=WalletProfiles.LEGACY;
        if(phase.equals("second")||phase.equals("uninitialized")) { id=WalletProfiles.create(c);WalletProfiles.registry(c).edit().putString("test-"+phase,id).commit(); }
        else if(phase.equals("returnSecond")) id=WalletProfiles.registry(c).getString("test-second","");
        WalletProfiles.activate(c,id);
        SharedPreferences p=WalletProfiles.settings(c,id);p.edit().putString("mode","easy").putBoolean("modeChoiceMade",true).putString("network","regtest").putString("syncPeer","10.0.2.2:19444").commit();
        assertNotEquals(WalletProfiles.directory(c,WalletProfiles.LEGACY,"regtest"),WalletProfiles.directory(c,"f".repeat(32),"regtest"));
        instrumentation.runOnMainSync(() -> c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        CompletableFuture<NodeService> ready=new CompletableFuture<>();ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){ready.complete(((NodeService.LocalBinder)b).service());}public void onServiceDisconnected(ComponentName n){}};
        c.bindService(new Intent(c,NodeService.class),connection,Context.BIND_AUTO_CREATE);
        NodeService node=ready.get(15,TimeUnit.SECONDS);
        android.util.Log.i("ProfileTest","Service bound");
        try {
            long deadline=System.currentTimeMillis()+90000;while(!node.rpcReady && System.currentTimeMillis()<deadline)Thread.sleep(250);assertTrue(node.status,node.rpcReady);
            Stateservice.WalletState state=node.walletState();
            while(state==Stateservice.WalletState.WAITING_TO_START && System.currentTimeMillis()<deadline) { Thread.sleep(100);state=node.walletState(); }
            android.util.Log.i("ProfileTest","Initial state: "+state);
            if(phase.equals("uninitialized")){assertEquals(Stateservice.WalletState.NON_EXISTING,state);node.stopForSwitch();assertFalse(node.rpcReady);return;}
            if(state==Stateservice.WalletState.NON_EXISTING)node.createWallet();else if(state==Stateservice.WalletState.LOCKED)node.unlock();
            while(node.walletState()!=Stateservice.WalletState.SERVER_ACTIVE&&System.currentTimeMillis()<deadline)Thread.sleep(250);
            assertEquals(Stateservice.WalletState.SERVER_ACTIVE,node.walletState());
            android.util.Log.i("ProfileTest","Wallet active");
            String pub=GetInfoResponse.parseFrom(node.rpc("getInfo",GetInfoRequest.getDefaultInstance())).getIdentityPubkey();
            if(p.contains("test-identity"))assertEquals(p.getString("test-identity",""),pub);else p.edit().putString("test-identity",pub).commit();
            String seed=new Vault(c,id).get("seed");assertNotNull(seed);
            if(!id.equals(WalletProfiles.LEGACY)){assertNotEquals(new Vault(c,WalletProfiles.LEGACY).get("seed"),seed);assertNotEquals(WalletProfiles.settings(c,WalletProfiles.LEGACY).getString("test-identity",""),pub);}
            node.stopForSwitch();assertFalse(node.rpcReady);
        }finally{c.unbindService(connection);}
    }
}
