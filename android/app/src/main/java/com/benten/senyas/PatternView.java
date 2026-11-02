package com.benten.senyas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

/** Soft diamond (argyle) pattern used behind the home mascot. */
public class PatternView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path diamond = new Path();
    private final int base, tone;
    private final float step;

    public PatternView(Context c, AttributeSet a) {
        super(c, a);
        base = ContextCompat.getColor(c, R.color.home_pattern_a);
        tone = ContextCompat.getColor(c, R.color.home_pattern_b);
        step = 52f * c.getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Takes no height of its own: it just fills whatever size the header ends up with. */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(0, widthSpec), resolveSize(0, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(base);
        paint.setColor(tone);
        float half = step / 2f;
        int cols = (int) (getWidth() / half) + 2;
        int rows = (int) (getHeight() / half) + 2;
        for (int iy = 0; iy <= rows; iy++) {
            for (int ix = 0; ix <= cols; ix++) {
                if (((ix + iy) & 1) != 0) continue;
                float cx = ix * half, cy = iy * half;
                diamond.reset();
                diamond.moveTo(cx, cy - half);
                diamond.lineTo(cx + half, cy);
                diamond.lineTo(cx, cy + half);
                diamond.lineTo(cx - half, cy);
                diamond.close();
                canvas.drawPath(diamond, paint);
            }
        }
    }
}
