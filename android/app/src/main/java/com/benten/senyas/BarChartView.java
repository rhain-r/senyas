package com.benten.senyas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.core.content.ContextCompat;

/**
 * Small bar chart. Values below the optional pass mark are drawn as outlined bars and the rest as
 * filled bars, so the meaning never depends on colour alone. A value of -1 means "not tried" (shown as a dash).
 */
public class BarChartView extends View {

    private int[] values = new int[0];
    private String[] labels = new String[0];
    private int max = 100, threshold = -1;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valueText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thrText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float d;

    public BarChartView(Context c, AttributeSet a) {
        super(c, a);
        d = getResources().getDisplayMetrics().density;
        float sp11 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics());
        int accent = ContextCompat.getColor(c, R.color.accent);
        int primary = ContextCompat.getColor(c, R.color.text_primary);
        int secondary = ContextCompat.getColor(c, R.color.text_secondary);

        fill.setColor(accent);
        stroke.setColor(accent);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f * d);
        valueText.setColor(primary);
        valueText.setTextAlign(Paint.Align.CENTER);
        valueText.setTextSize(sp11);
        valueText.setFakeBoldText(true);
        labelText.setColor(secondary);
        labelText.setTextAlign(Paint.Align.CENTER);
        labelText.setTextSize(sp11);
        thrText.setColor(secondary);
        thrText.setTextAlign(Paint.Align.RIGHT);
        thrText.setTextSize(sp11);
        dash.setColor(secondary);
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(1.5f * d);
        dash.setPathEffect(new DashPathEffect(new float[]{8f * d, 6f * d}, 0));
        axis.setColor(secondary);
        axis.setStrokeWidth(1f * d);
    }

    public void setData(int[] values, String[] labels, int max, int threshold) {
        this.values = values;
        this.labels = labels;
        this.max = Math.max(1, max);
        this.threshold = threshold;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = values.length;
        float w = getWidth(), h = getHeight();
        float left = 8 * d, right = 8 * d, top = 22 * d, bottom = 26 * d;
        float chartH = h - top - bottom, baseline = h - bottom;
        if (n == 0 || chartH <= 0) return;

        float slot = (w - left - right) / n;
        float barW = Math.min(slot * 0.62f, 36 * d);

        canvas.drawLine(left, baseline, w - right, baseline, axis);
        if (threshold >= 0) {
            float y = baseline - chartH * threshold / max;
            canvas.drawLine(left, y, w - right, y, dash);
            canvas.drawText(threshold + "%", w - right, y - 4 * d, thrText);
        }

        for (int i = 0; i < n; i++) {
            float cx = left + slot * (i + 0.5f);
            int v = values[i];
            if (v < 0) {
                canvas.drawText("–", cx, baseline - 6 * d, valueText);
            } else if (v == 0) {
                canvas.drawText("0", cx, baseline - 6 * d, valueText);
            } else {
                float top2 = baseline - chartH * Math.min(v, max) / max;
                rect.set(cx - barW / 2, top2, cx + barW / 2, baseline);
                boolean passed = threshold < 0 || v >= threshold;
                if (passed) {
                    canvas.drawRoundRect(rect, 4 * d, 4 * d, fill);
                } else {
                    rect.inset(d, d);
                    canvas.drawRoundRect(rect, 4 * d, 4 * d, stroke);
                }
                canvas.drawText(String.valueOf(v), cx, top2 - 5 * d, valueText);
            }
            if (i < labels.length) canvas.drawText(labels[i], cx, h - 8 * d, labelText);
        }
    }
}
