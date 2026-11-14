package org.xbt.wallet;

import android.content.Intent;
import android.widget.PopupMenu;
import android.widget.Button;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class BackgroundNavigationTest {
    private static java.lang.reflect.Field field(String name) throws Exception {
        var f=MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void call(MainActivity a, String name, String... value) throws Exception {
        var m=value.length==0 ? MainActivity.class.getDeclaredMethod(name)
            : MainActivity.class.getDeclaredMethod(name,String.class);
        m.setAccessible(true); m.invoke(a,(Object[])value);
    }
    private static Button find(View v, String label) {
        if(v instanceof Button && ((Button)v).getText().toString().equals(label)) return (Button)v;
        if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) {
            Button b=find(((ViewGroup)v).getChildAt(i),label); if(b!=null)return b;
        }
        return null;
    }
    @Test public void slowRefreshDoesNotSwallowMenuOrNavigationAndCannotUnlockAnOperation() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        assertEquals("org.xbt.wallet.profiletest",instrumentation.getTargetContext().getPackageName());
        var activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        NodeService fake=new NodeService();
        CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1), done=new CountDownLatch(1);
        Object[] original=new Object[1];
        try {
            instrumentation.runOnMainSync(() -> { try {
                original[0]=field("node").get(activity); field("node").set(activity,fake);
                field("lastRatesAttempt").setLong(activity,android.os.SystemClock.elapsedRealtime());
                call(activity,"page","Home");
                Class<?> job=Class.forName("org.xbt.wallet.MainActivity$Job");
                Object slow=Proxy.newProxyInstance(job.getClassLoader(),new Class[]{job},(p,m,args)->{
                    started.countDown(); assertTrue(release.await(15,TimeUnit.SECONDS)); return null;
                });
                var work=MainActivity.class.getDeclaredMethod("homeRefreshWork",job);work.setAccessible(true);work.invoke(activity,slow);
                assertTrue(field("refreshingHome").getBoolean(activity));
                assertFalse(field("busy").getBoolean(activity));
                call(activity,"more");
                PopupMenu menu=(PopupMenu)field("walletPopup").get(activity); assertNotNull(menu);
                menu.getMenu().performIdentifierAction(4,0);
                assertEquals("Swap controllers",field("screen").get(activity));
                find(activity.getWindow().getDecorView(),"Add controller").performClick();
                assertEquals("Add controller",field("screen").get(activity));
                // Finishing a poll must never release a separately queued payment lock.
                field("busy").setBoolean(activity,true);
            } catch(Exception e){throw new AssertionError(e);} });
            assertTrue(started.await(3,TimeUnit.SECONDS)); release.countDown();
            fake.io.execute(()->done.countDown()); assertTrue(done.await(3,TimeUnit.SECONDS));
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{try{
                assertFalse(field("refreshingHome").getBoolean(activity));
                assertTrue(field("busy").getBoolean(activity));
            }catch(Exception e){throw new AssertionError(e);}});
        } finally {
            release.countDown(); fake.io.shutdownNow();
            instrumentation.runOnMainSync(()->{try{
                field("node").set(activity,original[0]); field("busy").setBoolean(activity,false);activity.finish();
            }catch(Exception e){throw new AssertionError(e);}});
        }
    }
}
