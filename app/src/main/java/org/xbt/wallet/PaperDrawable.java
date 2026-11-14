package org.xbt.wallet;

import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import java.util.Random;

/** Stationary, cached paper grain with softly worn edges. No per-frame texture generation. */
public class PaperDrawable extends Drawable {
    private static final Bitmap GRAIN = makeGrain();
    // Stable identity supplied by the page, not allocation order across refreshes.
    private final long finishSeed;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grain = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private final Path clip = new Path();
    private final int color, border;
    private final float radius, density;
    private final boolean background, popupPadding;
    private int opacity = 255;
    private Shader wash, edge, glow;

    /** XML constructor for the dots menu. */
    public PaperDrawable() { this(0xff28231c, 18, 0xff81694b, false, true, 0x504f505550L); }
    public PaperDrawable(int color, int radius, int border, boolean background) {
        this(color, radius, border, background, false, 0x584254L);
    }
    public PaperDrawable(int color, int radius, int border, boolean background, long finishSeed) {
        this(color, radius, border, background, false, finishSeed);
    }
    private PaperDrawable(int color, int radius, int border, boolean background, boolean popupPadding, long finishSeed) {
        this.finishSeed = finishSeed;
        this.color = color; this.border = border; this.background = background; this.popupPadding = popupPadding;
        density = Resources.getSystem().getDisplayMetrics().density;
        this.radius = radius * density;
        BitmapShader texture = new BitmapShader(GRAIN, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        Random finish = new Random(finishSeed * 0x9e3779b97f4a7c15L);
        Matrix scale = new Matrix();
        float grainScale = density * (.58f + finish.nextFloat() * .16f);
        scale.setScale(grainScale, grainScale);
        scale.postRotate(finish.nextFloat() * 24f - 12f);
        scale.postTranslate(finish.nextFloat() * 512 * density, finish.nextFloat() * 512 * density);
        texture.setLocalMatrix(scale); grain.setShader(texture);
    }
    private static Bitmap makeGrain() {
        int size = 512;
        Bitmap texture = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[size * size];
        Random random = new Random(0x584254);
        for (int i = 0; i < pixels.length; i++) {
            int tone = random.nextInt(100);
            int alpha = 3 + random.nextInt(14);
            pixels[i] = tone < 49 ? Color.argb(alpha, 222, 200, 158) : Color.argb(alpha, 0, 0, 0);
        }
        texture.setPixels(pixels, 0, size, 0, 0, size, size);
        Canvas canvas = new Canvas(texture);
        Paint fibers = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Broad, low-contrast patches break up the otherwise uniform fibers.
        for (int i = 0; i < 65; i++) {
            float x = random.nextFloat() * size, y = random.nextFloat() * size;
            float radius = 12 + random.nextFloat() * 70;
            fibers.setShader(new RadialGradient(x, y, radius,
                    i % 3 == 0 ? 0x12c9b182 : 0x18000000, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, radius, fibers);
        }
        fibers.setShader(null);
        for (int i = 0; i < 2300; i++) {
            float x = random.nextFloat() * size, y = random.nextFloat() * size;
            fibers.setColor(i % 3 == 0 ? 0x24d5ba89 : 0x38000000);
            fibers.setStrokeWidth(.35f + random.nextFloat() * .65f);
            canvas.drawLine(x, y, x + 1 + random.nextFloat() * 5, y + random.nextFloat() - .5f, fibers);
        }
        return texture;
    }
    private static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) * (1-t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1-t) + Color.green(b) * t),
                Math.round(Color.blue(a) * (1-t) + Color.blue(b) * t));
    }
    @Override protected void onBoundsChange(Rect rect) {
        bounds.set(rect); clip.reset(); clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        Random finish = new Random(finishSeed * 0x9e3779b97f4a7c15L);
        float startX = rect.left + rect.width() * finish.nextFloat();
        float endX = rect.left + rect.width() * finish.nextFloat();
        float warmth = background ? .035f : .09f + finish.nextFloat() * .07f;
        float shade = background ? .18f : .10f + finish.nextFloat() * .10f;
        wash = new LinearGradient(startX, rect.top, endX, Math.max(rect.top + 1, rect.bottom),
                new int[]{blend(color, 0xffa08053, warmth), color,
                        blend(color, Color.BLACK, shade)},
                new float[]{0, .30f + finish.nextFloat() * .35f, 1}, Shader.TileMode.CLAMP);
        glow = new RadialGradient(rect.left + rect.width() * finish.nextFloat(),
                rect.top + rect.height() * finish.nextFloat(),
                Math.max(1, Math.max(rect.width(), rect.height()) * (.55f + finish.nextFloat() * .45f)),
                background ? 0x0cceb17b : 0x15dfbb83, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        edge = new LinearGradient(startX, rect.top, endX, Math.max(rect.top + 1, rect.bottom),
                new int[]{blend(border, 0xffedcf94, .12f), blend(border, color, .25f),
                        blend(border, Color.BLACK, .40f), blend(border, color, .15f)},
                new float[]{0, .25f + finish.nextFloat() * .15f, .70f + finish.nextFloat() * .15f, 1},
                Shader.TileMode.CLAMP);
    }
    @Override public void draw(Canvas canvas) {
        if (bounds.isEmpty()) return;
        int save = canvas.save(); canvas.clipPath(clip);
        paint.setStyle(Paint.Style.FILL); paint.setShader(wash); paint.setAlpha(opacity);
        canvas.drawRect(bounds, paint);
        paint.setShader(glow); canvas.drawRect(bounds, paint);
        grain.setAlpha(Math.round(opacity * (background ? .55f : .9f)));
        canvas.drawRect(bounds, grain);
        paint.setShader(null);
        if (border != 0) {
            RectF rim = new RectF(bounds); rim.inset(density * .65f, density * .65f);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density);
            paint.setShader(edge); paint.setAlpha(opacity);
            canvas.drawRoundRect(rim, Math.max(0, radius-density), Math.max(0, radius-density), paint);

        }
        paint.setStyle(Paint.Style.FILL); canvas.restoreToCount(save);
    }
    @Override public boolean getPadding(Rect padding) {
        int p = popupPadding ? Math.round(8 * density) : 0;
        padding.set(p, p, p, p); return p != 0;
    }
    @Override public void setAlpha(int alpha) { opacity = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); grain.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
