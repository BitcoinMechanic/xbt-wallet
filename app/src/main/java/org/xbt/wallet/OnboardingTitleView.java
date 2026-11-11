package org.xbt.wallet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.view.Gravity;
import android.widget.TextView;

/** Centered rope-toned heading with a layout-stable, center-out reveal. */
final class OnboardingTitleView extends TextView {
    private float reveal = 1f;

    OnboardingTitleView(Context context) {
        super(context);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setTextColor(0xffffffff);
    }

    void setReveal(float value) { reveal = value; invalidate(); }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        getPaint().setShader(new LinearGradient(0, 0, Math.max(1, width), Math.max(1, height),
            new int[]{0xffd9bd8c, 0xfff4e5c7, 0xffe8d2a7, 0xffb59b72},
            new float[]{0f, .35f, .65f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (getLayout() == null) return;
        float textWidth = 0;
        for (int line = 0; line < getLayout().getLineCount(); line++)
            textWidth = Math.max(textWidth, getLayout().getLineWidth(line));
        float center = getWidth() / 2f;
        float half = (textWidth / 2f + 2) * reveal;
        int saved = canvas.save();
        if (reveal < 1f) canvas.clipRect(center - half, 0, center + half, getHeight());
        super.onDraw(canvas);
        canvas.restoreToCount(saved);
    }
}
