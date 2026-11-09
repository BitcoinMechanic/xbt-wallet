package org.xbt.wallet;

import android.app.Instrumentation;
import android.content.*;
import android.os.IBinder;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import lnrpc.LightningOuterClass;
import lnrpc.Stateservice;

/** Runs only against the local emulator's isolated XBT regtest peer. */
public class NodeSmokeTest {
    @Test public void embeddedWalletStartsAndReturnsAnAddress() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertEquals("regtest", context.getSharedPreferences("wallet", 0).getString("network", "regtest"));
        context.getSharedPreferences("wallet", 0).edit().putString("mode", "easy").putString("network", "regtest").putString("syncPeer", "10.0.2.2:19444").commit();
        instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CompletableFuture<NodeService> service = new CompletableFuture<>();
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) { service.complete(((NodeService.LocalBinder)b).service()); }
            public void onServiceDisconnected(ComponentName n) {}
        };
        context.bindService(new Intent(context, NodeService.class), connection, Context.BIND_AUTO_CREATE);
        try {
            NodeService node = service.get(15, TimeUnit.SECONDS);
            long deadline = System.currentTimeMillis() + 90000;
            while (!node.rpcReady && System.currentTimeMillis() < deadline) Thread.sleep(500);
            assertTrue(node.status, node.rpcReady);
            Stateservice.WalletState state = node.walletState();
            while (state == Stateservice.WalletState.WAITING_TO_START && System.currentTimeMillis() < deadline) {
                Thread.sleep(500); state = node.walletState();
            }
            if (state == Stateservice.WalletState.NON_EXISTING) node.createWallet();
            else if (state == Stateservice.WalletState.LOCKED) node.unlock();
            while (node.walletState() != Stateservice.WalletState.SERVER_ACTIVE && System.currentTimeMillis() < deadline) Thread.sleep(500);
            assertEquals(Stateservice.WalletState.SERVER_ACTIVE, node.walletState());
            LightningOuterClass.GetInfoResponse info = LightningOuterClass.GetInfoResponse.parseFrom(node.rpc("getInfo", LightningOuterClass.GetInfoRequest.getDefaultInstance()));
            assertEquals("regtest", info.getChains(0).getNetwork());
            String address = LightningOuterClass.NewAddressResponse.parseFrom(node.rpc("newAddress", LightningOuterClass.NewAddressRequest.newBuilder().setType(LightningOuterClass.AddressType.WITNESS_PUBKEY_HASH).build())).getAddress();
            assertTrue(address, address.startsWith("bcrt1"));
            assertNotNull(new Vault(context).get("seed"));
            context.getSharedPreferences("wallet", 0).edit().putString("deposit", address).commit();
            android.util.Log.i("XbtSmoke", "regtest deposit address=" + address);
        } finally { context.unbindService(connection); }
    }
}
