package com.benten.senyas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/** Finger-drawing whiteboard with a sample equation shown until cleared. */
public class WhiteboardView extends View {
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Path> paths = new ArrayList<>();
    private Path current;
    private boolean showSample = true;

    public WhiteboardView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        int navy = ContextCompat.getColor(c, R.color.navy);
        ink.setColor(navy);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(3.5f * d);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(navy);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22,
                getResources().getDisplayMetrics()));
        setContentDescription(c.getString(R.string.whiteboard_desc));
    }

    public void clear() {
        paths.clear();
        showSample = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (showSample) canvas.drawText(getContext().getString(R.string.whiteboard_sample), getWidth() / 2f, getHeight() * 0.4f, text);
        for (Path p : paths) canvas.drawPath(p, ink);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                current = new Path();
                current.moveTo(x, y);
                current.lineTo(x + 0.1f, y + 0.1f);
                paths.add(current);
                break;
            case MotionEvent.ACTION_MOVE:
                if (current != null) current.lineTo(x, y);
                break;
            case MotionEvent.ACTION_UP:
                performClick();
                current = null;
                break;
            case MotionEvent.ACTION_CANCEL:
                current = null;
                break;
            default:
                return false;
        }
        invalidate();
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
