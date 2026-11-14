package org.xbt.wallet;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.LinearInterpolator;
import java.util.Random;

/** A short, native-canvas launch cover. No video, network assets or wallet snapshot. */
public final class WalletOpeningView extends View {
    private final java.util.Map<String, Bitmap> leatherSurfaces = new java.util.HashMap<>();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path aperture = new Path();
    private final Camera camera = new Camera();
    private final Matrix transform = new Matrix();
    private final BitmapShader grain;
    private final Drawable logo;
    private final Runnable finished;
    private ValueAnimator animator;
    private float progress;
    private boolean started, ended;

    public WalletOpeningView(Context context, Runnable finished) {
        super(context); this.finished = finished;
        setClickable(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        grain = new BitmapShader(makeLeatherGrain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        Matrix grainScale = new Matrix(); grainScale.setScale(.48f, .48f);
        grain.setLocalMatrix(grainScale);
        logo = context.getDrawable(R.drawable.xbt_logo).mutate();
    }
    /** Cached, irregular pebbled hide: broad wear, recessed pores and tiny raised highlights. */
    private static Bitmap makeLeatherGrain() {
        Bitmap texture = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(texture);
        Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random random = new Random(713);
        for (int i = 0; i < 100; i++) {
            float x=random.nextFloat()*768, y=random.nextFloat()*768, r=25+random.nextFloat()*130;
            brush.setShader(new RadialGradient(x,y,r,i%3==0 ? 0x196e3b1d : 0x14000000,
                    Color.TRANSPARENT,Shader.TileMode.CLAMP));
            canvas.drawCircle(x,y,r,brush);
        }
        brush.setShader(null);
        for (int i = 0; i < 22000; i++) {
            float x=random.nextFloat()*768, y=random.nextFloat()*768;
            float w=.7f+random.nextFloat()*3.4f, h=.5f+random.nextFloat()*1.9f;
            brush.setColor(Color.argb(12+random.nextInt(26),10,5,2));
            canvas.drawOval(x,y,x+w,y+h,brush);
            brush.setColor(Color.argb(7+random.nextInt(19),228,187,128));
            canvas.drawOval(x,y-.45f,x+w*.8f,y+.15f,brush);
        }
        brush.setStyle(Paint.Style.STROKE); brush.setStrokeWidth(.45f);
        for(int i=0;i<1500;i++) {
            float x=random.nextFloat()*768,y=random.nextFloat()*768;
            Path crease=new Path(); crease.moveTo(x,y);
            crease.rQuadTo(2,-2,4+random.nextFloat()*5,random.nextFloat()*3-1);
            brush.setColor(0x180e0804); canvas.drawPath(crease,brush);
        }
        return texture;
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (started) return;
        started = true;
        if (!ValueAnimator.areAnimatorsEnabled()) { post(this::finish); return; }
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(2000);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> { progress = (float)a.getAnimatedValue(); invalidate(); });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { finish(); }
        });
        animator.start();
    }
    public void dispose() {
        ended = true;
        if (animator != null) { animator.removeAllListeners(); animator.cancel(); }
    }
    private void finish() {
        if (ended) return;
        ended = true; finished.run();
    }
    private static float ease(float value) {
        float t = Math.max(0, Math.min(1, value)); return t * t * (3 - 2 * t);
    }
    private void fill(int color) { paint.reset(); paint.setAntiAlias(true); paint.setColor(color); }
    private void leather(Canvas canvas, RectF box, float radius, boolean inside) {
        String key = box.width() + ":" + box.height() + ":" + radius + ":" + inside;
        Bitmap surface = leatherSurfaces.get(key);
        if (surface == null) {
            surface = Bitmap.createBitmap(Math.round((box.width()+8)*3),
                    Math.round((box.height()+12)*3),Bitmap.Config.ARGB_8888);
            Canvas cached = new Canvas(surface); cached.scale(3,3); cached.translate(4,4);
            drawLeather(cached,new RectF(0,0,box.width(),box.height()),radius,inside);
            leatherSurfaces.put(key,surface);
        }
        canvas.drawBitmap(surface,null,new RectF(box.left-4,box.top-4,box.right+4,box.bottom+8),bitmapPaint);
    }
    private void drawLeather(Canvas canvas, RectF box, float radius, boolean inside) {
        fill(0xff20140f);
        canvas.drawRoundRect(new RectF(box.left - 3, box.top + 4, box.right + 3, box.bottom + 7), radius, radius, paint);
        fill(0xffffffff);
        paint.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom,
            inside ? new int[]{0xff533827, 0xff39291f, 0xff493022} : new int[]{0xff765033, 0xff60402c, 0xff3d291f},
            null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(box, radius, radius, paint);
        paint.setShader(grain); canvas.drawRoundRect(box, radius, radius, paint);
        // Burnished edge: dark cut leather beneath a narrow, uneven rubbed highlight.
        fill(0xff24170f); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.4f);
        RectF rim = new RectF(box); rim.inset(1.2f,1.2f);
        canvas.drawRoundRect(rim,radius,radius,paint);
        paint.setStrokeWidth(.65f);
        paint.setShader(new LinearGradient(box.left,box.top,box.right,box.bottom,
                new int[]{0x998d6747,0x335c3924,0x88734d31},null,Shader.TileMode.CLAMP));
        rim.inset(1.4f,1.4f); canvas.drawRoundRect(rim,radius-1,radius-1,paint);
        // Stitch holes and waxed thread have separate depth, not a flat dashed outline.
        Path seam = new Path();
        RectF sewn = new RectF(box); sewn.inset(8,8);
        seam.addRoundRect(sewn,Math.max(2,radius-5),Math.max(2,radius-5),Path.Direction.CW);
        PathMeasure measure = new PathMeasure(seam,false);
        float[] from=new float[2], to=new float[2];
        for(float d=1; d<measure.getLength()-3; d+=5.1f) {
            measure.getPosTan(d,from,null); measure.getPosTan(d+2.7f,to,null);
            fill(0xb51c110b); paint.setStrokeWidth(1.6f); paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(from[0],from[1]+.45f,to[0],to[1]+.45f,paint);
            fill(inside ? 0xff927652 : 0xffaa8d63); paint.setStrokeWidth(.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(from[0],from[1],to[0],to[1],paint);
        }

    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float p = progress, opening = ease((p - .22f) / .43f), zoom = ease((p - .60f) / .40f);
        float base = Math.min(getWidth() / 390f, getHeight() / 650f);
        float scale = base * (1 + 5.5f * zoom);
        float cx = getWidth() / 2f, cy = getHeight() * .48f;
        int scene = canvas.save();
        // The actual screen beneath is revealed through the wallet, not a cached image.
        if (p > .70f) {
            float reveal = ease((p - .70f) / .30f);
            float halfW = getWidth() * .78f * reveal, halfH = getHeight() * .78f * reveal;
            aperture.reset();
            aperture.addRoundRect(new RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH), 36 * (1 - reveal), 36 * (1 - reveal), Path.Direction.CW);
            canvas.clipOutPath(aperture);
        }
        fill(0xff12120f); canvas.drawPaint(paint);
        fill(0xffffffff);
        paint.setShader(new RadialGradient(cx, cy, getWidth() * .8f, new int[]{0xff393026, 0xff12120f}, null, Shader.TileMode.CLAMP));
        canvas.drawPaint(paint);
        canvas.translate(cx, cy); canvas.scale(scale, scale);
        canvas.rotate(-3f * (1 - zoom));
        // Soft contact shadow rather than a bright floating-card outline.
        fill(0x55000000);
        for (int i = 12; i > 0; i--) {
            paint.setAlpha(3); canvas.drawRoundRect(new RectF(-151-i, -98-i/2f, 151+i, 108+i), 24, 24, paint);
        }
        RectF body = new RectF(-148, -99, 148, 99);
        leather(canvas, body, 18, true);
        // Interior card sleeves and rolled lip.
        for (int i = 0; i < 3; i++) {
            float y = -68 + i * 43;
            leather(canvas, new RectF(-125, y, 124, y + 56), 8, true);
            fill(0xffd0af7b); paint.setAlpha(60); paint.setStrokeWidth(1);
            canvas.drawLine(-112, y + 2, 110, y + 2, paint);
        }
        // Compressed leather at the hinge, with a soft crease shadow into the lining.
        fill(Color.WHITE);
        paint.setShader(new LinearGradient(-148,0,-120,0,
                new int[]{0xaa100905,0x330e0804,0x000e0804},null,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(-148,-94,-120,94),10,10,paint);
        // Cover unfolds around its spine, with perspective and a darker reverse.
        if (opening < .995f) {
            canvas.save(); canvas.translate(-148, 0);
            camera.save(); camera.setLocation(0, 0, -10); camera.rotateY(-155 * opening);
            camera.getMatrix(transform); camera.restore(); canvas.concat(transform);
            leather(canvas, new RectF(0, -99, 296, 99), 18, opening > .58f);
            if (opening < .58f) {
                canvas.save(); canvas.translate(148, -4);
                logo.setBounds(-84, -46, 84, 46);
                // Debossed leather branding rather than a bright printed graphic.
                logo.setColorFilter(0xffbd9263, PorterDuff.Mode.SRC_IN);
                logo.setAlpha(65); canvas.translate(.5f,1f); logo.draw(canvas);
                canvas.translate(-.5f,-1f); logo.setColorFilter(0xff25170f, PorterDuff.Mode.SRC_IN);
                logo.setAlpha(155); logo.draw(canvas); canvas.restore();
            }
            canvas.restore();
        }
        // Brass snap releases first; the short leather strap peels up and away.
        float release = ease((p - .06f) / .20f);
        if (p < .52f) {
            canvas.save(); canvas.translate(145 + release * 22, -release * 52);
            canvas.rotate(-release * 58);
            leather(canvas, new RectF(-54, -19, 17, 19), 10, false);
            fill(0xff241a12); canvas.drawCircle(-31, 1, 10, paint);
            paint.setShader(new LinearGradient(-40, -9, -23, 10, new int[]{0xffedcf8c, 0xffa0793c, 0xff654521}, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(-32, -1 - release * 4, 8.4f, paint);
            fill(0xffeed9a6); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(.65f);
            canvas.drawCircle(-32, -1 - release * 4, 6.1f, paint);
            canvas.restore();
        }
        canvas.restoreToCount(scene);
    }
}
