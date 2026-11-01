package com.benten.senyas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

/** Progress bar with a white marker at the 85% pass threshold. */
public class ScoreBarView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;
    private int score = 0;

    public ScoreBarView(Context c, AttributeSet a) {
        super(c, a);
        d = getResources().getDisplayMetrics().density;
        track.setColor(ContextCompat.getColor(c, R.color.dark_stroke));
        fill.setColor(ContextCompat.getColor(c, R.color.sun_yellow));
        marker.setColor(0xFFFFFFFF);
    }

    public void setScore(int s) {
        score = Math.max(0, Math.min(100, s));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), rad = h / 2f;
        r.set(0, 0, w, h);
        canvas.drawRoundRect(r, rad, rad, track);
        if (score > 0) {
            r.set(0, 0, Math.max(h, w * score / 100f), h);
            canvas.drawRoundRect(r, rad, rad, fill);
        }
        float mx = w * 0.85f;
        canvas.drawRect(mx - 1.5f * d, -2 * d, mx + 1.5f * d, h + 2 * d, marker);
    }
}
