package org.xbt.wallet;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

public class HeaderCoinTest {
    private static java.lang.reflect.Field field(String name)throws Exception {
        var f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    @Test public void coinHeaderSlidesBothWays()throws Exception {
        var i=InstrumentationRegistry.getInstrumentation(); var c=i.getTargetContext();
        assertEquals("org.xbt.wallet.profiletest",c.getPackageName());
        MainActivity a=(MainActivity)i.startActivitySync(new android.content.Intent(c,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            i.runOnMainSync(()->{
                try {
                    var p=a.getSharedPreferences("header-preview-test",0);
                    p.edit().clear().putString("fiat-currency","USD").commit(); field("prefs").set(a,p);
                    field("fiatRates").set(a,new org.json.JSONObject().put("timestamp",System.currentTimeMillis()).put("USD","256.81"));
                    field("lastRatesAttempt").setLong(a,android.os.SystemClock.elapsedRealtime());
                    var page=MainActivity.class.getDeclaredMethod("page",String.class);page.setAccessible(true);page.invoke(a,"Home");
                    var header=(android.view.View)field("pageHeader").get(a);
                    int w=900,h=300;
                    header.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(h,1073741824));
                    header.layout(0,0,w,h);
                    var bitmap=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
                    var canvas=new android.graphics.Canvas(bitmap);canvas.drawColor(0xff12120f);header.draw(canvas);
                    try(var output=new java.io.FileOutputStream(new java.io.File(a.getExternalFilesDir(null),"header-coin.png"))){
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);
                    }
                    ((android.view.View)field("headerBrand").get(a)).performClick();
                    assertTrue(p.getBoolean("header-fiat-rate",false));
                }catch(Exception e){throw new AssertionError(e);}
            });
            Thread.sleep(650);i.waitForIdleSync();
            i.runOnMainSync(()->{
                try {
                    assertEquals(1f,field("headerSlide").getFloat(a),.001f);
                    ((android.view.View)field("headerRate").get(a)).performClick();
                }catch(Exception e){throw new AssertionError(e);}
            });
            Thread.sleep(650);i.waitForIdleSync();
            i.runOnMainSync(()->{
                try {assertEquals(0f,field("headerSlide").getFloat(a),.001f);}
                catch(Exception e){throw new AssertionError(e);}
            });
        }finally{i.runOnMainSync(a::finish);}
    }
}
