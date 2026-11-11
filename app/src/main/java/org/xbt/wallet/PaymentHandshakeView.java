package org.xbt.wallet;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A small, finite pixel-art celebration, shown only after confirmed settlement. */
final class PaymentHandshakeView extends View {
    // O = outline, G/g = green sleeve, R/r = rope sleeve, A/a and B/b = hands.
    private static final String[] PIXELS = {
        "             OOOOOOO                    ",
        "          OOOAAAAAAAOO                  ",
        "        OOAAAAAAAAAAAOOOOOO            ",
        "       OAAAAAAAaaAAOOBBBBBBBO           ",
        " OOOOOOAAAAAAAaAAOOBBBBBBBBBOOOOOO      ",
        "OGGGGGOAAAAAAaAOOBBBBBBBBBBBORRRRRO     ",
        "OGGGGGOAAAAAAOOBBBBBBBBBBBBBO RRRRRO    ",
        "OGGGGGOAAAAAOBBBBbbbBBBBBBBBO RRRRRO    ",
        "OGGGGGOAAAAAOBBBOAAAbBBBBBBBO RRRRRO    ",
        "OGGGGGOAAAAAAOOOAAAAObBBBBBBO RRRRRO    ",
        "OGGGGGOAAAAAAAAAAAAAAObBBBBOO RRRRRO    ",
        "OGggggOAAAAAAAAAAAAAAAObBBO Or rrrRO    ",
        " OOOOOOAAAAAAaAAAAAAAAAOBO  OOOOOOO     ",
        "       OAAAAAAaAAAAAAAaAO               ",
        "        OOAAAAAaAAAAAaAO               ",
        "          OOAAAAaAAAaAO                ",
        "            OOAAAaAaAO                 ",
        "              OOOOOOO                  "
    };
    private final Paint paint = new Paint();
    private ValueAnimator animation;
    private float progress = 1f;

    PaymentHandshakeView(Context context) {
        super(context);
        setContentDescription("Payment successful. Pixel-art handshake.");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        animation = ValueAnimator.ofFloat(0f, 1f);
        animation.setDuration(2200);
        animation.setInterpolator(new android.view.animation.LinearInterpolator());
        animation.addUpdateListener(value -> { progress = (float) value.getAnimatedValue(); invalidate(); });
        animation.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (animation != null) { animation.cancel(); animation = null; }
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int pixel = Math.max(1, Math.min(getWidth() / 46, getHeight() / 30));
        int x = (getWidth() - 39 * pixel) / 2;
        int shake = progress < .8f ? Math.round((float) Math.sin(progress / .8f * Math.PI * 6) * 2) : 0;
        int y = (getHeight() - 18 * pixel) / 2 + shake * pixel;
        for (int row = 0; row < PIXELS.length; row++) {
            for (int col = 0; col < PIXELS[row].length(); col++) {
                int color;
                switch (PIXELS[row].charAt(col)) {
                    case 'O': color = 0xff35453b; break;
                    case 'G': color = 0xffa8efd5; break;
                    case 'g': color = 0xff65b894; break;
                    case 'R': color = 0xffd9bd8c; break;
                    case 'r': color = 0xffa58b62; break;
                    case 'A': color = 0xffedc798; break;
                    case 'a': color = 0xffbd8e66; break;
                    case 'B': color = 0xffbd875d; break;
                    case 'b': color = 0xff8d5d40; break;
                    default: continue;
                }
                paint.setColor(color);
                canvas.drawRect(x + col * pixel, y + row * pixel, x + (col + 1) * pixel, y + (row + 1) * pixel, paint);
            }
        }
        if (progress < .9f) {
            paint.setColor(((int) (255 * Math.min(1f, (.9f - progress) * 4)) << 24) | 0xa8efd5);
            int phase = (int) (progress * 10) % 2;
            sparkle(canvas, x + 5 * pixel, y - (3 + phase) * pixel, pixel);
            sparkle(canvas, x + 33 * pixel, y - (2 + phase) * pixel, pixel);
        }
    }

    private void sparkle(Canvas canvas, int x, int y, int pixel) {
        canvas.drawRect(x - pixel, y, x + 2 * pixel, y + pixel, paint);
        canvas.drawRect(x, y - pixel, x + pixel, y + 2 * pixel, paint);
    }
}
