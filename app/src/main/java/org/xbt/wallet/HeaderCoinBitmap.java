package org.xbt.wallet;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Prefilter the detailed coin to its actual display pixels, avoiding minification sparkle. */
final class HeaderCoinBitmap {
    private static Bitmap cached;
    static synchronized Bitmap atSize(Resources resources, int pixels) {
        pixels=Math.max(1,pixels);
        if(cached!=null && cached.getWidth()==pixels)return cached;
        BitmapFactory.Options options=new BitmapFactory.Options(); options.inScaled=false;
        Bitmap image=BitmapFactory.decodeResource(resources,R.drawable.header_gold_coin,options);
        // A single 20:1 bilinear resize samples too few source pixels. Repeated
        // half-size passes integrate the fine engraving instead of aliasing it.
        while(image.getWidth()/2 >= pixels) {
            Bitmap next=Bitmap.createScaledBitmap(image,image.getWidth()/2,image.getHeight()/2,true);
            if(next!=image)image.recycle();
            image=next;
        }
        Bitmap fitted=Bitmap.createScaledBitmap(image,pixels,pixels,true);
        if(fitted!=image)image.recycle();
        // Existing header views may still reference the previous cached size.
        cached=fitted;
        return fitted;
    }
}
