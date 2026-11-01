package com.senyas.cv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.senyas.ml.Feedback;
import com.senyas.ml.Landmarks;

/**
 * Draws over the camera preview: the camera guide, each hand's skeleton, and a correction
 * arrow. Finger colours follow the practice feedback (green = matches, red = adjust), and every
 * colour cue also has a written message on screen, so nothing depends on colour alone.
 *
 * Maps MediaPipe's image coordinates onto the preview, which fills the view (centre-crop) and is
 * mirrored for the front camera.
 */
public final class HandOverlayView extends View {
    private static final int GOOD = Color.parseColor("#5BD08F");
    private static final int FIX = Color.parseColor("#FF8A7A");
    private static final int NEUTRAL = Color.parseColor("#FFC83A");

    private final Paint bone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint joint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();
    private final RectF guideRect = new RectF();

    private HandFrame frame;
    private int imageWidth = 1, imageHeight = 1;
    private float guideTop, guideBottom;
    private boolean mirror = true;
    private boolean[] fingerOk;
    @Nullable
    private float[] adviceHand;
    /** The guide in camera-image units {left, top, right, bottom}; written on draw, read by the scorer. */
    @Nullable
    private volatile float[] guideInImage;
    private Feedback.Arrow hint = Feedback.Arrow.NONE;

    public HandOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        bone.setStyle(Paint.Style.STROKE);
        bone.setStrokeWidth(4 * d);
        bone.setStrokeCap(Paint.Cap.ROUND);
        joint.setStyle(Paint.Style.FILL);
        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(2 * d);
        guide.setColor(Color.argb(150, 255, 255, 255));
        guide.setPathEffect(new DashPathEffect(new float[]{12 * d, 8 * d}, 0));
        arrow.setStyle(Paint.Style.FILL);
        arrow.setColor(FIX);
    }

    /** Front camera previews are mirrored; set false for the back camera. */
    public void setMirror(boolean mirror) {
        this.mirror = mirror;
    }

    /** Keeps the drawn guide inside the part of the preview not covered by the top bar and panel. */
    public void setGuideInsets(float topPx, float bottomPx) {
        guideTop = topPx;
        guideBottom = bottomPx;
        invalidate();
    }

    public void setHands(HandFrame frame, int imageWidth, int imageHeight) {
        this.frame = frame;
        this.imageWidth = Math.max(1, imageWidth);
        this.imageHeight = Math.max(1, imageHeight);
        invalidate();
    }

    /** Per-finger colours and a direction arrow from practice feedback; null clears them. */
    public void setFeedback(@Nullable Feedback feedback) {
        fingerOk = feedback == null ? null : feedback.fingerOk;
        adviceHand = feedback == null ? null : feedback.dominant;
        hint = feedback == null ? Feedback.Arrow.NONE : feedback.arrow;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float inset = Math.min(w, h) * 0.06f;
        guideRect.set(inset, Math.max(inset, guideTop), w - inset, h - Math.max(inset, guideBottom));
        float r = 24 * getResources().getDisplayMetrics().density;
        canvas.drawRoundRect(guideRect, r, r, guide);
        if (imageWidth > 1 && imageHeight > 1) {
            // The preview fills the view and crops the rest, so the guide covers only part of the image.
            float scale = Math.max(w / imageWidth, h / imageHeight);
            float iw = imageWidth * scale, ih = imageHeight * scale;
            float ox = (w - iw) / 2f, oy = (h - ih) / 2f;
            guideInImage = new float[]{(guideRect.left - ox) / iw, (guideRect.top - oy) / ih,
                    (guideRect.right - ox) / iw, (guideRect.bottom - oy) / ih};
        }

        if (frame != null) {
            float scale = Math.max(w / imageWidth, h / imageHeight);
            float dx = (w - imageWidth * scale) / 2f;
            float dy = (h - imageHeight * scale) / 2f;
            int colored = handNearest(adviceHand);
            for (int i = 0; i < frame.hands.size(); i++) {
                float[] lm = frame.hands.get(i).landmarks;
                // The hand the advice is about gets practice colours; any other hand stays neutral.
                boolean[] ok = i == colored ? fingerOk : null;
                for (int[] b : Landmarks.BONES) {
                    bone.setColor(colorFor(ok, b[1]));
                    canvas.drawLine(px(lm, b[0], scale, dx), py(lm, b[0], scale, dy),
                            px(lm, b[1], scale, dx), py(lm, b[1], scale, dy), bone);
                }
                float jr = 5 * getResources().getDisplayMetrics().density;
                for (int k = 0; k < 21; k++) {
                    joint.setColor(colorFor(ok, k));
                    canvas.drawCircle(px(lm, k, scale, dx), py(lm, k, scale, dy), jr, joint);
                }
            }
        }
        drawArrow(canvas, w, h);
    }

    /**
     * The part of the camera image inside the drawn guide, {left, top, right, bottom} in image
     * units (centred, so the same mirrored or not), or null before the first frame is drawn.
     */
    @Nullable
    public float[] guideInImage() {
        return guideInImage;
    }

    /** Index of the drawn hand whose wrist is nearest the given hand's (the first hand if none). */
    private int handNearest(@Nullable float[] target) {
        if (target == null) return 0;
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < frame.hands.size(); i++) {
            float[] lm = frame.hands.get(i).landmarks;
            float dx = lm[0] - target[0], dy = lm[1] - target[1], d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private float px(float[] lm, int i, float scale, float dx) {
        float x = lm[i * 3];
        if (mirror) x = 1f - x;
        return x * imageWidth * scale + dx;
    }

    private float py(float[] lm, int i, float scale, float dy) {
        return lm[i * 3 + 1] * imageHeight * scale + dy;
    }

    /** Point index -> its finger (thumb = 0) -> green or red. */
    private static int colorFor(boolean[] ok, int point) {
        if (ok == null || point == 0) return ok == null ? NEUTRAL : Color.WHITE;
        int finger = (point - 1) / 4;
        return ok[finger] ? GOOD : FIX;
    }

    private void drawArrow(Canvas canvas, float w, float h) {
        Feedback.Arrow a = hint;
        // Arrows come in the mirrored preview's terms; an unmirrored (back camera) preview swaps left and right.
        if (!mirror) a = a == Feedback.Arrow.LEFT ? Feedback.Arrow.RIGHT : a == Feedback.Arrow.RIGHT ? Feedback.Arrow.LEFT : a;
        if (a == Feedback.Arrow.NONE) return;
        float s = 28 * getResources().getDisplayMetrics().density;
        float cx = w / 2f, cy = h / 2f;
        arrowPath.reset();
        switch (a) {
            case UP:
                cy = h * 0.18f;
                arrowPath.moveTo(cx, cy - s);
                arrowPath.lineTo(cx - s, cy + s * 0.4f);
                arrowPath.lineTo(cx + s, cy + s * 0.4f);
                break;
            case DOWN:
                cy = h * 0.82f;
                arrowPath.moveTo(cx, cy + s);
                arrowPath.lineTo(cx - s, cy - s * 0.4f);
                arrowPath.lineTo(cx + s, cy - s * 0.4f);
                break;
            case LEFT:
                cx = w * 0.15f;
                arrowPath.moveTo(cx - s, cy);
                arrowPath.lineTo(cx + s * 0.4f, cy - s);
                arrowPath.lineTo(cx + s * 0.4f, cy + s);
                break;
            case RIGHT:
                cx = w * 0.85f;
                arrowPath.moveTo(cx + s, cy);
                arrowPath.lineTo(cx - s * 0.4f, cy - s);
                arrowPath.lineTo(cx - s * 0.4f, cy + s);
                break;
            default:
                return;
        }
        arrowPath.close();
        canvas.drawPath(arrowPath, arrow);
    }
}
