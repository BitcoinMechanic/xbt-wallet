package org.xbt.wallet;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

public class WalletOpeningTest {
    @Test public void renderOpeningFramesWithoutStartingANode() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        instrumentation.runOnMainSync(() -> {
            try {
                WalletOpeningView view = new WalletOpeningView(context, () -> {});
                view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, 600, 1000);
                var field = WalletOpeningView.class.getDeclaredField("progress"); field.setAccessible(true);
                for (float progress : new float[]{0, .38f, .62f, .82f, 1f}) {
                    field.setFloat(view, progress);
                    Bitmap frame = Bitmap.createBitmap(600, 1000, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(frame); canvas.drawColor(0xff12120f);
                    Paint text = new Paint(Paint.ANTI_ALIAS_FLAG); text.setColor(0xffe0c18c); text.setTextSize(32);
                    canvas.drawText("On Chain", 36, 250, text); canvas.drawText("Lightning", 36, 345, text);
                    view.draw(canvas);
                    try (FileOutputStream stream = new FileOutputStream(new File(context.getFilesDir(), "wallet-opening-" + Math.round(progress * 100) + ".png"))) { frame.compress(Bitmap.CompressFormat.PNG, 100, stream); }
                    if (progress == 1) assertEquals(0xff12120f, frame.getPixel(300, 500));
                }
                view.dispose();
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }
}
