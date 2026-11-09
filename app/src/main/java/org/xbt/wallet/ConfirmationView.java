package org.xbt.wallet;

import android.content.Context;
import android.graphics.*;
import android.view.View;

final class ConfirmationView extends View {
    private final int confirmations, target;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    ConfirmationView(Context context, int confirmations, int target) {
        super(context); this.confirmations = Math.max(0, confirmations); this.target = Math.max(1, target);
        setContentDescription(this.confirmations + " of " + this.target + " confirmations");
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); float pad = 3, size = Math.min(getWidth(), getHeight());
        RectF circle = new RectF(pad, pad, size-pad, size-pad);
        paint.setColor(Color.rgb(52, 67, 57)); canvas.drawOval(circle, paint);
        paint.setColor(Color.rgb(184, 242, 124));
        canvas.drawArc(circle, -90, 360f * Math.min(confirmations, target) / target, true, paint);
    }
}
