package org.xbt.wallet;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import lnrpc.LightningOuterClass.Channel;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelDetailsUiTest {
    private static java.lang.reflect.Field field(String name) throws Exception {
        var f=MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static TextView find(View v, String text) {
        if(v instanceof TextView && ((TextView)v).getText().toString().equals(text)) return (TextView)v;
        if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) {
            TextView match=find(((ViewGroup)v).getChildAt(i),text); if(match!=null)return match;
        }
        return null;
    }
    @Test public void channelAmountsCycleAndOfflineForceCloseIsAvailable() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        assertEquals("org.xbt.wallet.profiletest",instrumentation.getTargetContext().getPackageName());
        var activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->{try {
            var original=field("prefs").get(activity);
            var prefs=activity.getSharedPreferences("channel-details-test",0);
            prefs.edit().clear().putString("fiat-currency","Off").putInt("display-unit",0).commit();
            field("prefs").set(activity,prefs);
            try {
                var method=MainActivity.class.getDeclaredMethod("channelDetails",Channel.class); method.setAccessible(true);
                Channel channel=Channel.newBuilder().setPrivate(true).setActive(false).setCapacity(100000000)
                    .setLocalBalance(40000000).setRemoteBalance(60000000).setChannelPoint("00".repeat(32)+":0").build();
                method.invoke(activity,channel);
                View root=activity.getWindow().getDecorView();
                assertNotNull(find(root,"Offline · Private channel"));
                TextView capacity=find(root,"100,000,000 sats capacity"); assertNotNull(capacity);
                capacity.performClick(); assertEquals(1,prefs.getInt("display-unit",-1));
                assertFalse(capacity.getText().toString().contains("sats"));
                capacity.performClick(); assertEquals(0,prefs.getInt("display-unit",-1));
                assertTrue(find(root,"Force close").isEnabled());
                assertFalse(find(root,"Close Channel").isEnabled());
                method.invoke(activity,channel.toBuilder().setPrivate(false).build());
                assertNotNull(find(activity.getWindow().getDecorView(),"Offline · Public channel"));
            } finally {field("prefs").set(activity,original);activity.finish();}
        } catch(Exception e){throw new AssertionError(e);} });
    }
}
