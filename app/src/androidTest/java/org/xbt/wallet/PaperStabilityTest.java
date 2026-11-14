package org.xbt.wallet;

import android.graphics.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

public class PaperStabilityTest {
    private Bitmap render(android.graphics.drawable.Drawable d) {
        Bitmap image=Bitmap.createBitmap(320,72,Bitmap.Config.ARGB_8888);
        d.setBounds(0,0,320,72);d.draw(new Canvas(image));return image;
    }
    @Test public void recreatedSurfacesKeepTheirFinishButDifferentButtonsVary() {
        Bitmap background=render(new PaperBackgroundDrawable());
        for(int i=0;i<10;i++)render(new PaperDrawable(0xff30271e,14,0xff81694b,false,i));
        assertTrue(background.sameAs(render(new PaperBackgroundDrawable())));
        Bitmap first=render(new PaperDrawable(0xff30271e,14,0xff81694b,false,120));
        assertTrue(first.sameAs(render(new PaperDrawable(0xff30271e,14,0xff81694b,false,120))));
        assertFalse(first.sameAs(render(new PaperDrawable(0xff30271e,14,0xff81694b,false,121))));
    }
    @Test public void repeatedPageBuildKeepsButtonGradient() throws Exception {
        var i=InstrumentationRegistry.getInstrumentation();var c=i.getTargetContext();
        assertEquals("org.xbt.wallet.profiletest",c.getPackageName());
        var a=(MainActivity)i.startActivitySync(new android.content.Intent(c,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        i.runOnMainSync(()->{
            try {
                var page=MainActivity.class.getDeclaredMethod("page",String.class);page.setAccessible(true);
                var button=MainActivity.class.getDeclaredMethod("button",String.class,Runnable.class);button.setAccessible(true);
                page.invoke(a,"Home");
                var first=(android.widget.Button)button.invoke(a,"Receive",(Runnable)()->{});
                Bitmap before=render(first.getBackground());
                page.invoke(a,"Advanced");
                button.invoke(a,"Elsewhere",(Runnable)()->{});
                page.invoke(a,"Home");
                var second=(android.widget.Button)button.invoke(a,"Receive",(Runnable)()->{});
                assertTrue(before.sameAs(render(second.getBackground())));
            } catch(Exception e) { throw new AssertionError(e); }
            finally { a.finish(); }
        });
    }
}
