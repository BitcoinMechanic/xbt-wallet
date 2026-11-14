package org.xbt.wallet;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.Assume;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/** Opt-in visual fixture: synthetic values, no wallet RPC or payment actions. */
public class PaperPreviewTest {
    private static Object invoke(MainActivity a, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true);
        return method.invoke(a, args);
    }
    @Test public void renderPaperSurfaces() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("paperPreview")));
        var i = InstrumentationRegistry.getInstrumentation();
        var a = (MainActivity)i.startActivitySync(new Intent(i.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        i.runOnMainSync(() -> {
            try {
                var busy = MainActivity.class.getDeclaredField("busy"); busy.setAccessible(true); busy.setBoolean(a, true);
                invoke(a, "page", new Class[]{String.class}, "Send on chain");
                invoke(a, "text", new Class[]{String.class}, "Address");
                invoke(a, "field", new Class[]{String.class,String.class}, "Bitcoin address", "");
                invoke(a, "text", new Class[]{String.class}, "Amount");
                invoke(a, "field", new Class[]{String.class,String.class}, "Optional", "125,000");
                invoke(a, "text", new Class[]{String.class}, "Label");
                invoke(a, "field", new Class[]{String.class,String.class}, "Optional", "");
                invoke(a, "button", new Class[]{String.class,Runnable.class}, "Review payment", (Runnable)()->{});
                invoke(a, "button", new Class[]{String.class,Runnable.class}, "Home", (Runnable)()->{});
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        Thread.sleep(900);
        i.runOnMainSync(() -> {
            try {
                capture(a, a.getWindow().getDecorView(), "paper-preview.png");
                invoke(a, "page", new Class[]{String.class}, "Home");
                invoke(a, "homeBalances", new Class[]{long.class,long.class,long.class}, 1250000L, 225000L, 750000L);
                invoke(a, "homeScanButton", new Class[]{});
                var channel = lnrpc.LightningOuterClass.Channel.newBuilder().setRemotePubkey("02" + "ab".repeat(32))
                    .setChannelPoint("preview:0").setActive(true).setLocalBalance(225000).setRemoteBalance(750000).build();
                invoke(a, "homeChannelStatus", new Class[]{lnrpc.LightningOuterClass.ListChannelsResponse.class,
                        lnrpc.LightningOuterClass.PendingChannelsResponse.class, java.util.Set.class, long.class},
                    lnrpc.LightningOuterClass.ListChannelsResponse.newBuilder().addChannels(channel).build(),
                    lnrpc.LightningOuterClass.PendingChannelsResponse.getDefaultInstance(), java.util.Collections.emptySet(), 1L);
                invoke(a, "animateHomeActions", new Class[]{int.class,boolean.class}, 0, true);
            } catch(Exception e) { throw new RuntimeException(e); }
        });
        Thread.sleep(600);
        i.runOnMainSync(() -> {
            try {
                capture(a, a.getWindow().getDecorView(), "paper-home-preview.png");
                invoke(a, "more", new Class[]{});
            } catch(Exception e) { throw new RuntimeException(e); }
        });
        Thread.sleep(500);
        i.runOnMainSync(() -> {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                var windows = android.view.inspector.WindowInspector.getGlobalWindowViews();
                try { capture(a, windows.get(windows.size()-1), "paper-menu-preview.png"); }
                catch(Exception e) { throw new RuntimeException(e); }
            }
        });
    }
    private static void capture(MainActivity a, android.view.View view, String file) throws Exception {
        Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image));
        try(var out = new FileOutputStream(new File(a.getCacheDir(), file))) {
            image.compress(Bitmap.CompressFormat.PNG,100,out);
        }
    }
}
