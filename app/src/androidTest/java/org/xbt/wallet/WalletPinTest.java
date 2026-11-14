package org.xbt.wallet;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

public class WalletPinTest {
    private static android.view.View find(android.view.View v, Class<?> type) {
        if(type.isInstance(v))return v;
        if(v instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)v).getChildCount();i++) {
            var found=find(((android.view.ViewGroup)v).getChildAt(i),type); if(found!=null)return found;
        }
        return null;
    }
    @Test public void coldStartRequiresPinAndUnlockReturnsToWallet() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var c=instrumentation.getTargetContext();
        assertEquals("org.xbt.wallet.profiletest",c.getPackageName());
        WalletPin.remove(c); WalletPin.set(c,"691347"); WalletPin.lock();
        var monitor=instrumentation.addMonitor(PinLockActivity.class.getName(),null,false);
        android.app.Activity main=null,lock=null;
        try {
            main=instrumentation.startActivitySync(new android.content.Intent(c,MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            lock=monitor.waitForActivityWithTimeout(5000); assertNotNull(lock);
            final android.app.Activity lockScreen=lock;
            final android.app.Activity wallet=main;
            instrumentation.runOnMainSync(()->{
                assertEquals(0f,wallet.getWindow().getDecorView().getAlpha(),0f);
                ((android.widget.EditText)find(lockScreen.getWindow().getDecorView(),android.widget.EditText.class)).setText("691347");
                ((android.widget.Button)find(lockScreen.getWindow().getDecorView(),android.widget.Button.class)).performClick();
            });
            long until=android.os.SystemClock.elapsedRealtime()+5000;
            while(WalletPin.locked(c)&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(50);
            instrumentation.waitForIdleSync(); assertFalse(WalletPin.locked(c));
            instrumentation.runOnMainSync(()->assertEquals(1f,wallet.getWindow().getDecorView().getAlpha(),0f));
        } finally {
            WalletPin.remove(c); instrumentation.removeMonitor(monitor);
            final android.app.Activity a=main,b=lock;
            instrumentation.runOnMainSync(()->{if(b!=null)b.finish(); if(a!=null)a.finish();});
        }
    }
    @Test public void pinAuthenticationTimeoutAndRetryThrottle() throws Exception {
        var c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("org.xbt.wallet.profiletest",c.getPackageName());
        WalletPin.remove(c);
        try {
            assertFalse(WalletPin.enabled(c));
            assertEquals(900000,WalletPin.timeout(c));
            try { WalletPin.set(c,"123"); fail(); } catch(Exception expected) {}
            WalletPin.set(c,"691347");
            assertTrue(WalletPin.enabled(c)); assertFalse(WalletPin.locked(c));
            assertFalse(WalletPin.prefs(c).getAll().containsValue("691347"));
            WalletPin.lock(); assertTrue(WalletPin.locked(c));
            assertFalse(WalletPin.verify(c,"691348"));
            assertTrue(WalletPin.verify(c,"691347"));
            // Checking credentials alone must not unlock the UI.
            assertTrue(WalletPin.locked(c));
            WalletPin.unlock(); assertFalse(WalletPin.locked(c));
            WalletPin.prefs(c).edit().putLong("timeout",1).commit();
            Thread.sleep(5); assertTrue(WalletPin.locked(c));
            WalletPin.touch(c); assertTrue(WalletPin.locked(c));
            WalletPin.prefs(c).edit().putBoolean("restart-only",true).commit();
            assertFalse(WalletPin.locked(c));
            WalletPin.lock(); assertTrue(WalletPin.locked(c));
            WalletPin.unlock(); Thread.sleep(5); assertFalse(WalletPin.locked(c));
            WalletPin.prefs(c).edit().putBoolean("restart-only",false).commit();
            assertTrue(WalletPin.locked(c));
            WalletPin.prefs(c).edit().putLong("timeout",60000).commit();
            for(int i=0;i<5;i++)assertFalse(WalletPin.verify(c,"000000"));
            try { WalletPin.verify(c,"691347"); fail("Must throttle even a correct guess"); }
            catch(Exception expected) { assertTrue(expected.getMessage().contains("Try again")); }
            WalletPin.prefs(c).edit().putLong("retry",0).commit();
            assertTrue(WalletPin.verify(c,"691347"));
            WalletPin.set(c,"582693");
            assertFalse(WalletPin.verify(c,"691347")); assertTrue(WalletPin.verify(c,"582693"));
        } finally { WalletPin.remove(c); }
        assertFalse(WalletPin.enabled(c)); assertFalse(WalletPin.locked(c));
    }
}
