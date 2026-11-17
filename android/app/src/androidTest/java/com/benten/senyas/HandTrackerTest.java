package com.benten.senyas;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class HandTrackerTest {
    @Test
    public void realHandsAreTrackedAndEmptyFramesClearThemWithoutAwardingXp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<List<List<NormalizedLandmark>>> observed = new AtomicReference<>();
        int startingXp = Repo.xp;
        try (HandTracker tracker = new HandTracker(context, false, new HandTracker.Listener() {
            @Override public void onHands(List<List<NormalizedLandmark>> hands, int width, int height) {
                observed.set(hands);
            }
            @Override public void onError() { fail("The real hand detector failed to initialize"); }
        })) {
            Bitmap blank = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
            tracker.track(blank);
            assertTrue("An empty image must not produce a hand", observed.get().isEmpty());

            try (InputStream image = InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open("right_hands.jpg")) {
                tracker.track(BitmapFactory.decodeStream(image));
            }
            assertEquals("Both hands in the reference image must be found", 2, observed.get().size());
            for (List<NormalizedLandmark> hand : observed.get()) assertEquals(21, hand.size());

            tracker.track(Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888));
            assertTrue("Old landmarks must disappear when hands leave", observed.get().isEmpty());
            assertEquals("An open hand is not evidence of an FSL sign", startingXp, Repo.xp);
        }
    }

    @Test
    public void frontCameraMirroringDimmerLightAndSmallerHandsRemainTrackable() throws Exception {
        Bitmap reference;
        try (InputStream image = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("right_hands.jpg")) {
            reference = BitmapFactory.decodeStream(image);
        }
        assertNotNull(reference);
        Matrix mirror = new Matrix();
        mirror.setScale(-1f, 1f);
        Bitmap mirrored = Bitmap.createBitmap(reference, 0, 0,
                reference.getWidth(), reference.getHeight(), mirror, true);
        Bitmap dimmer = Bitmap.createBitmap(reference.getWidth(), reference.getHeight(), Bitmap.Config.ARGB_8888);
        Paint dimPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        dimPaint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                .7f, 0, 0, 0, 0,
                0, .7f, 0, 0, 0,
                0, 0, .7f, 0, 0,
                0, 0, 0, 1, 0
        })));
        new Canvas(dimmer).drawBitmap(reference, 0, 0, dimPaint);
        Bitmap smaller = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(smaller);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(reference, null, new RectF(80, 112, 560, 368), new Paint(Paint.FILTER_BITMAP_FLAG));
        try {
            assertHandsThenEmpty("Mirrored front-camera image", mirrored);
            assertHandsThenEmpty("Dimmer indoor image", dimmer);
            assertHandsThenEmpty("Hands farther from the camera", smaller);
        } finally {
            reference.recycle();
            mirrored.recycle();
            dimmer.recycle();
            smaller.recycle();
        }
    }

    private static void assertHandsThenEmpty(String description, Bitmap input) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<List<List<NormalizedLandmark>>> observed = new AtomicReference<>();
        int startingXp = Repo.xp;
        Bitmap empty = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);
        empty.eraseColor(Color.WHITE);
        // A fresh tracker requires palm detection; earlier frames cannot lend it landmarks.
        try (HandTracker tracker = new HandTracker(context, false, new HandTracker.Listener() {
            @Override public void onHands(List<List<NormalizedLandmark>> hands, int width, int height) {
                observed.set(hands);
            }
            @Override public void onError() { fail(description + ": detector failed"); }
        })) {
            tracker.track(input);
            assertNotNull(description + ": no result", observed.get());
            assertFalse(description + ": visible hands should be detected", observed.get().isEmpty());
            for (List<NormalizedLandmark> hand : observed.get()) assertEquals(21, hand.size());
            tracker.track(empty);
            assertTrue(description + ": empty frames must clear hand landmarks", observed.get().isEmpty());
            assertEquals("Hand visibility must never award FSL practice credit", startingXp, Repo.xp);
        } finally {
            empty.recycle();
        }
    }
}
