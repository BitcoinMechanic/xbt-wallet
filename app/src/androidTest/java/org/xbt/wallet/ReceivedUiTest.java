package org.xbt.wallet;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ScrollView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic presentation only: never creates invoices, swaps or payments. */
public class ReceivedUiTest {
    private static Object field(MainActivity a,String name) throws Exception {
        var f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);
    }
    private static void invoke(MainActivity a,String method,Class<?> type,Object value) throws Exception {
        var m=MainActivity.class.getDeclaredMethod(method,type);m.setAccessible(true);m.invoke(a,value);
    }
    private static TextView find(View v,String text) {
        if(v instanceof TextView && text.contentEquals(((TextView)v).getText()))return (TextView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){
            TextView r=find(((ViewGroup)v).getChildAt(i),text);if(r!=null)return r;
        }
        return null;
    }
    @Test public void receiptCopiesPreservesScrollAndShowsAnimatedSuccess() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        var busy=MainActivity.class.getDeclaredField("busy");busy.setAccessible(true);
        var prefs=(android.content.SharedPreferences)field(activity,"prefs");
        int oldUnit=prefs.getInt("display-unit",0);
        boolean wasBusy=busy.getBoolean(activity);
        String invoice="lnbc1"+"a".repeat(1800);
        JSONObject record=new JSONObject().put("hash","a".repeat(64)).put("sats",415185)
            .put("market_accepted","unavailable-0").put("reply",new JSONObject()
                .put("invoice",invoice).put("btc_msat",1000000).put("state","waiting_for_payment")
                .put("expires_at",System.currentTimeMillis()/1000+600));
        try {
            instrumentation.runOnMainSync(()->{try{
                busy.setBoolean(activity,true);
                prefs.edit().putInt("display-unit",0).commit();
                invoke(activity,"showSwapReceive",JSONObject.class,record);
            }catch(Exception e){throw new AssertionError(e);}});
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{try{
                TextView swapAmount=find(activity.getWindow().getDecorView(),"Receive 415,185 sats");assertNotNull(swapAmount);
                swapAmount.performClick();assertEquals(1,prefs.getInt("display-unit",-1));
                assertTrue(swapAmount.getText().toString().contains("XBT"));
                ScrollView scroll=(ScrollView)field(activity,"pageScroll");scroll.scrollTo(0,300);
                assertEquals(300,scroll.getScrollY());
                invoke(activity,"showSwapReceive",JSONObject.class,new JSONObject(record.toString()));
                assertSame(scroll,field(activity,"pageScroll"));
                assertEquals(300,scroll.getScrollY());
                find(activity.getWindow().getDecorView(),invoice).performClick();
                assertEquals(invoice,activity.getSystemService(android.content.ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString());
                invoke(activity,"showSwapReceive",JSONObject.class,record.put("last_error","Synthetic status update"));
            }catch(Exception e){throw new AssertionError(e);}});
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{try{
                assertEquals(300,((ScrollView)field(activity,"pageScroll")).getScrollY());
                prefs.edit().putInt("display-unit",0).commit();
                invoke(activity,"showSwapReceive",JSONObject.class,record.put("received",true).put("received_sats",415185));
                assertEquals("Received",field(activity,"screen"));
                TextView amount=find(activity.getWindow().getDecorView(),"Received 415,185 sats");assertNotNull(amount);
                amount.performClick();assertEquals(1,prefs.getInt("display-unit",-1));
                assertTrue(amount.getText().toString().contains("XBT"));
                if(android.animation.ValueAnimator.areAnimatorsEnabled())assertEquals(View.INVISIBLE,find(activity.getWindow().getDecorView(),"✓").getVisibility());
            }catch(Exception e){throw new AssertionError(e);}});
            android.os.SystemClock.sleep(750);
            instrumentation.runOnMainSync(()->assertEquals(View.VISIBLE,find(activity.getWindow().getDecorView(),"✓").getVisibility()));
            instrumentation.runOnMainSync(()->{try{
                var method=MainActivity.class.getDeclaredMethod("showPaymentSuccessful");method.setAccessible(true);method.invoke(activity);
                assertEquals("Payment successful",field(activity,"screen"));
                TextView check=find(activity.getWindow().getDecorView(),"✓");assertNotNull(check);
                assertEquals("Payment successful",check.getContentDescription());
                if(android.animation.ValueAnimator.areAnimatorsEnabled())assertEquals(View.INVISIBLE,check.getVisibility());
            }catch(Exception e){throw new AssertionError(e);}});
            android.os.SystemClock.sleep(750);
            instrumentation.runOnMainSync(()->assertEquals(View.VISIBLE,find(activity.getWindow().getDecorView(),"✓").getVisibility()));
            JSONObject outgoing=new JSONObject().put("hash","b".repeat(64)).put("btc_msat",1000000)
                .put("recipient","02"+"3".repeat(64)).put("provider","02"+"4".repeat(64))
                .put("last_error","Synthetic status detail. ".repeat(40));
            instrumentation.runOnMainSync(()->{try{
                invoke(activity,"showSwapSend",JSONObject.class,outgoing);
                assertNotNull(find(activity.getWindow().getDecorView(),"Cancel swap"));
            }catch(Exception e){throw new AssertionError(e);}});
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{try{
                ((ScrollView)field(activity,"pageScroll")).scrollTo(0,300);
                invoke(activity,"showSwapSend",JSONObject.class,new JSONObject(outgoing.toString()));
            }catch(Exception e){throw new AssertionError(e);}});
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{try{
                assertEquals(300,((ScrollView)field(activity,"pageScroll")).getScrollY());
            }catch(Exception e){throw new AssertionError(e);}});
        } finally {
            instrumentation.runOnMainSync(()->{try{prefs.edit().putInt("display-unit",oldUnit).commit();busy.setBoolean(activity,wasBusy);}catch(Exception e){throw new AssertionError(e);}});
        }
    }
}
