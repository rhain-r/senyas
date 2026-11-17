package com.benten.senyas;

import static org.junit.Assert.*;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class RealFslFeedbackTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    private SequenceRecognizer.Prediction heldOut(int target) throws Exception {
        SequenceRecognizer engine;
        try (InputStream input = context().getAssets().open("fsl_recognizer.bin")) { engine = SequenceRecognizer.load(input); }
        SequenceRecognizer.Prediction completed = null;
        long usable = -1, last = 0;
        try (Scanner input = new Scanner(InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open("heldout_hello_features.txt")).useLocale(Locale.US)) {
            while (input.hasNextDouble()) {
                long timestamp = Math.round(input.nextDouble());
                float[] features = new float[156]; for (int i = 0; i < 156; i++) features[i] = input.nextFloat();
                if (usable >= 0 && timestamp - usable > 300) {
                    SequenceRecognizer.Prediction result = engine.finishAttempt(timestamp, target);
                    if (result.ready) completed = result;
                    usable = -1;
                }
                if ((features[66] >= .5f || features[133] >= .5f) && features[155] >= .5f) usable = timestamp;
                engine.addFrame(features, timestamp, target); last = timestamp;
            }
        }
        if (usable >= 0) {
            SequenceRecognizer.Prediction result = engine.finishAttempt(last + 400, target);
            if (result.ready) completed = result;
        }
        assertNotNull("A real held-out recording must produce a completed attempt", completed);
        return completed;
    }

    @Test public void heldOutHelloUpdatesTheExistingPanelAndNeverGradesTheNextSign() throws Exception {
        TestAccounts.student(); Repo.startPractice(0, 0);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context().getPackageName(), Manifest.permission.CAMERA);
        SequenceRecognizer.Prediction result = heldOut(3);
        assertTrue(result.completed); assertTrue(result.accepted);
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(new Intent(context(), MainActivity.class))) {
            screen.onActivity(activity -> {
                activity.selectTab(R.id.nav_practice);
                activity.getSupportFragmentManager().executePendingTransactions();
                PracticeFragment page = (PracticeFragment) activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);
                page.onSignFeedback(3, result);
                assertEquals(activity.getString(R.string.score_matched), ((TextView) activity.findViewById(R.id.tv_score_title)).getText().toString());
                assertTrue(Repo.practiceBest[0] >= 85); assertEquals(10, Repo.xp);
                page.onSignFeedback(3, result); assertEquals(10, Repo.xp);
                activity.findViewById(R.id.btn_skip).performClick();
                page.onSignFeedback(3, result);
                assertEquals(-1, Repo.practiceBest[1]); assertEquals(10, Repo.xp);
            });
        }
    }

    @Test public void helloCannotBeGradedAsThankYou() throws Exception {
        SequenceRecognizer.Prediction wrong = heldOut(7);
        assertFalse(wrong.accepted); assertTrue(wrong.scorePercent < 85);
    }

    @Test public void actualVideoPixelsFlowThroughHandsBodyAndTheShippedModel() throws Exception {
        File video = new File(context().getCacheDir(), "fsl-integration.mov");
        try (InputStream input = context().getResources().openRawResource(R.raw.fsl105_3);
             OutputStream output = new FileOutputStream(video)) {
            byte[] bytes = new byte[8192]; int count; while ((count = input.read(bytes)) >= 0) output.write(bytes, 0, count);
        }
        AtomicBoolean matched = new AtomicBoolean();
        try (HandTracker tracker = new HandTracker(context(), false, new HandTracker.Listener() {
            public void onHands(List<List<NormalizedLandmark>> hands, int width, int height) { }
            public void onError() { throw new AssertionError("Hand tracking failed on a known reference recording"); }
            @Override public void onFeedback(int target, int revision, SequenceRecognizer.Prediction result) {
                if (result.completed && result.accepted && result.topClassId == 3) matched.set(true);
            }
            @Override public void onFeedbackUnavailable(int target, int revision, boolean unsupported) {
                throw new AssertionError("The shipped Hello model must load and run");
            }
        }); MediaMetadataRetriever frames = new MediaMetadataRetriever()) {
            tracker.setTarget(3, 1); frames.setDataSource(video.getAbsolutePath());
            long duration = Long.parseLong(frames.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            for (long timestamp = 0; timestamp < duration; timestamp += 125) {
                Bitmap frame = frames.getFrameAtTime(timestamp * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
                assertNotNull(frame); tracker.track(frame, timestamp); frame.recycle();
            }
            for (long t = duration; t < duration + 750; t += 125)
                tracker.track(Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888), t);
            assertTrue("Real reference video must produce accepted feedback through the actual landmark pipeline", matched.get());
        } finally { video.delete(); }
    }
}
