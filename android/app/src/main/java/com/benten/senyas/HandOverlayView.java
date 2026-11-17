package com.benten.senyas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.Collections;
import java.util.List;

/** Draws only landmarks actually returned by the hand detector, aligned with a FIT_CENTER preview. */
public class HandOverlayView extends View {
    private static final int[][] LINES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 4}, {0, 5}, {5, 6}, {6, 7}, {7, 8},
            {9, 10}, {10, 11}, {11, 12}, {13, 14}, {14, 15}, {15, 16},
            {17, 18}, {18, 19}, {19, 20}, {5, 9}, {9, 13}, {13, 17}, {0, 17}};
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private List<List<NormalizedLandmark>> hands = Collections.emptyList();
    private int imageWidth, imageHeight;

    public HandOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        line.setColor(ContextCompat.getColor(context, R.color.sun_yellow));
        line.setStrokeWidth(2.5f * density);
        line.setStrokeCap(Paint.Cap.ROUND);
        dot.setColor(0xFFFFFFFF);
        guide.setColor(0x78FFFFFF);
        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(2f * density);
        guide.setPathEffect(new DashPathEffect(new float[]{10f * density, 8f * density}, 0));
    }

    public void setHands(List<List<NormalizedLandmark>> detected, int width, int height) {
        hands = detected;
        imageWidth = width;
        imageHeight = height;
        invalidate();
    }

    public void clear() {
        hands = Collections.emptyList();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        rect.set(getWidth() * .12f, getHeight() * .12f, getWidth() * .88f, getHeight() * .88f);
        canvas.drawRoundRect(rect, 24 * density, 24 * density, guide);
        if (imageWidth <= 0 || imageHeight <= 0) return;
        float scale = Math.min(getWidth() / (float) imageWidth, getHeight() / (float) imageHeight);
        float width = imageWidth * scale, height = imageHeight * scale;
        float left = (getWidth() - width) / 2f, top = (getHeight() - height) / 2f;
        for (List<NormalizedLandmark> hand : hands) {
            if (hand.size() != 21) continue;
            for (int[] pair : LINES) {
                NormalizedLandmark a = hand.get(pair[0]), b = hand.get(pair[1]);
                canvas.drawLine(left + a.x() * width, top + a.y() * height,
                        left + b.x() * width, top + b.y() * height, line);
            }
            for (NormalizedLandmark point : hand) {
                canvas.drawCircle(left + point.x() * width, top + point.y() * height, 3.5f * density, dot);
            }
        }
    }
}
