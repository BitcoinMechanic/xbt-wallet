package org.xbt.wallet;

import android.content.*;
import android.os.IBinder;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.Assume;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import lnrpc.LightningOuterClass;

/** Opt-in read-only inspection of an already existing wallet. Never funds or alters channels. */
public class SpliceReadOnlyTest {
    @Test public void readPendingSplice() throws Exception {
        String peer = InstrumentationRegistry.getArguments().getString("splicePeer");
        Assume.assumeNotNull(peer);
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        android.app.Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CompletableFuture<NodeService> ready = new CompletableFuture<>();
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) { ready.complete(((NodeService.LocalBinder)b).service()); }
            public void onServiceDisconnected(ComponentName n) { }
        };
        context.bindService(new Intent(context, NodeService.class), connection, Context.BIND_AUTO_CREATE);
        try {
            NodeService node = ready.get(15, TimeUnit.SECONDS);
            long deadline = System.currentTimeMillis() + 90000;
            while (!node.rpcReady && System.currentTimeMillis() < deadline) Thread.sleep(500);
            while (node.walletState() != lnrpc.Stateservice.WalletState.SERVER_ACTIVE && System.currentTimeMillis() < deadline) Thread.sleep(500);
            LightningOuterClass.ListChannelsResponse channels = null;
            while (System.currentTimeMillis() < deadline) {
                try { channels = LightningOuterClass.ListChannelsResponse.parseFrom(node.rpc("listChannels", LightningOuterClass.ListChannelsRequest.getDefaultInstance())); break; }
                catch (Exception e) { Thread.sleep(500); }
            }
            assertNotNull(channels);
            for (var c : channels.getChannelsList()) if (c.getRemotePubkey().equals(peer)) {

                assertTrue("Existing channel must expose its pending splice after startup", c.hasPendingSplice());
                var p = c.getPendingSplice();
                var txs = LightningOuterClass.TransactionDetails.parseFrom(node.rpc("getTransactions", LightningOuterClass.GetTransactionsRequest.getDefaultInstance()));
                String wallet = "missing";
                for (var tx : txs.getTransactionsList()) if (tx.getTxHash().equals(p.getTxid())) wallet = "confirmations="+tx.getNumConfirmations();
                android.os.Bundle result = new android.os.Bundle();
                result.putString("stream", "HasPending="+c.hasPendingSplice()+" status="+c.getSpliceStatus()+" original="+c.getOriginalChannelPoint()+" lastOutcome="+c.getLastSplice().getOutcome()+" state="+p.getState()+" confirmations="+p.getConfirmations()+" target="+p.getLockDepth()+" localDelta="+p.getLocalDeltaSat()+" remoteDelta="+p.getRemoteDeltaSat()+" walletTransaction="+wallet+"\n");
                instrumentation.sendStatus(0, result);
                java.util.concurrent.atomic.AtomicReference<String> visible = new java.util.concurrent.atomic.AtomicReference<>("");
                long uiDeadline = System.currentTimeMillis() + 15000;
                while (System.currentTimeMillis() < uiDeadline) {
                    instrumentation.runOnMainSync(() -> visible.set(textOf(activity.getWindow().getDecorView())));
                    if (visible.get().contains("Splice pending") && visible.get().contains(SpliceStatus.progress(p))) break;
                    Thread.sleep(250);
                }
                assertTrue("Pending splice visible on Home: " + visible.get(), visible.get().contains("Splice pending") && visible.get().contains(SpliceStatus.progress(p)));
                instrumentation.runOnMainSync(() -> {
                    try {
                        var method = MainActivity.class.getDeclaredMethod("channelDetails", LightningOuterClass.Channel.class);
                        method.setAccessible(true); method.invoke(activity, c);
                        visible.set(textOf(activity.getWindow().getDecorView()));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                assertTrue(visible.get(), visible.get().contains("Capacity after splice:") && visible.get().contains(SpliceStatus.progress(p)));
                assertTrue(visible.get().contains(p.getTxid()));
                assertTrue(visible.get().contains("Refresh splice status"));
                assertFalse(visible.get().contains("Close Channel"));
                return;
            }
            fail("Requested existing peer not found");
        } finally { context.unbindService(connection); }
    }
    private static String textOf(android.view.View view) {
        if (view.getVisibility() != android.view.View.VISIBLE) return "";
        StringBuilder text = new StringBuilder();
        if (view instanceof android.widget.TextView) text.append(((android.widget.TextView)view).getText()).append("\n");
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) text.append(textOf(group.getChildAt(i)));
        }
        return text.toString();
    }
}
