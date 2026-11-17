package com.benten.senyas;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.VideoView;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class SignLessonVideoTest {
    @Test public void replayRecoversFromADecoderError() throws Exception {
        TestAccounts.student();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            scenario.onActivity(activity -> activity.open(SignLessonFragment.newInstanceForWord("Magandang umaga", "Good morning")));
            waitForPlaying(scenario);
            scenario.onActivity(activity -> findVideo(activity.findViewById(R.id.nav_host))
                    .setVideoURI(Uri.parse("file:///missing-sign-lesson.mov")));
            long deadline = System.currentTimeMillis() + 10000;
            AtomicBoolean error = new AtomicBoolean();
            while (System.currentTimeMillis() < deadline && !error.get()) {
                scenario.onActivity(activity -> error.set(activity.findViewById(R.id.tv_video_status).isShown()));
                Thread.sleep(100);
            }
            assertTrue("The bad URI must reach the real playback error callback", error.get());
            scenario.onActivity(activity -> activity.findViewById(R.id.btn_replay).performClick());
            waitForPlaying(scenario);
        }
    }

    @Test public void salamatReadyOpensPracticeCameraAndWatchReturnsToRealDemo() throws Exception {
        TestAccounts.student();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context.getPackageName(), android.Manifest.permission.CAMERA);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            scenario.onActivity(activity -> activity.open(SignLessonFragment.newInstance(0, 3)));
            waitForPlaying(scenario);
            scenario.onActivity(activity -> activity.findViewById(R.id.btn_practice).performClick());
            long deadline = System.currentTimeMillis() + 25000;
            AtomicBoolean streaming = new AtomicBoolean();
            while (System.currentTimeMillis() < deadline && !streaming.get()) {
                scenario.onActivity(activity -> {
                    PreviewView camera = activity.findViewById(R.id.camera_preview);
                    streaming.set(camera != null && camera.getPreviewStreamState().getValue() == PreviewView.StreamState.STREAMING);
                });
                Thread.sleep(100);
            }
            assertTrue("Ready must show the practice camera without closing the app", streaming.get());
            scenario.onActivity(activity -> {
                assertEquals("Salamat", ((TextView) activity.findViewById(R.id.tv_word)).getText().toString());
                activity.findViewById(R.id.btn_watch).performClick();
            });
            waitForPlaying(scenario);
        }
    }

    @Test public void everySupportedWordHasItsCorrectDecodableDatasetClip() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Expected IDs come directly from the publisher's labels.csv, not from the mapping code.
        String[][] expected = {{"Magandang umaga", "fsl105_0"}, {"Magandang gabi", "fsl105_2"},
                {"Kumusta", "fsl105_3"}, {"Salamat", "fsl105_7"}, {"Walang anuman", "fsl105_8"},
                {"Hindi", "fsl105_14"}, {"Oo", "fsl105_15"}, {"Tatay", "fsl105_52"}, {"Nanay", "fsl105_53"}};
        for (String[] entry : expected) {
            int resource = LessonVideos.forWord(entry[0]);
            assertEquals(entry[0], entry[1], context.getResources().getResourceEntryName(resource));
            try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                retriever.setDataSource(context, Uri.parse("android.resource://" + context.getPackageName() + "/" + resource));
                assertTrue("Clip should contain the full demonstration", Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) >= 3500);
                Bitmap frame = retriever.getFrameAtTime(1000000);
                assertNotNull("Real video frame required for " + entry[0], frame);
                assertTrue("Signer must remain visible", frame.getWidth() >= 360 && frame.getHeight() >= 360);
                frame.recycle();
            }
        }
        assertEquals(0, LessonVideos.forWord("Bili"));
        assertEquals(0, LessonVideos.forWord(null));
    }

    @Test public void goodMorningPlaysAnOfflineVideoAndSurvivesRecreation() throws Exception {
        TestAccounts.student();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            scenario.onActivity(activity -> activity.open(SignLessonFragment.newInstanceForWord("Magandang umaga", "Good morning")));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertNotNull("A sign lesson needs a real video to copy", findVideo(activity.findViewById(R.id.nav_host))));
            waitForPlaying(scenario);
            try (FileOutputStream file = new FileOutputStream(new File(context.getExternalFilesDir(null), "fsl-video-restored.png"))) {
                assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, file));
            }
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.chip_slow).performClick();
                activity.findViewById(R.id.btn_replay).performClick();
            });
            waitForPlaying(scenario);
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.recreate();
            waitForPlaying(scenario);
            scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
        }
    }

    @Test public void unsupportedSignDoesNotPresentAnInventedDemonstration() throws Exception {
        TestAccounts.student();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            scenario.onActivity(activity -> activity.open(SignLessonFragment.newInstance(0, 1)));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertFalse("Unavailable video must not offer fake replay", activity.findViewById(R.id.btn_replay).isEnabled());
                assertTrue("The missing video must be explained", containsText(activity.findViewById(R.id.nav_host), "No verified video"));
            });
        }
    }

    private static void waitForPlaying(ActivityScenario<MainActivity> scenario) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            AtomicBoolean playing = new AtomicBoolean();
            scenario.onActivity(activity -> {
                VideoView video = findVideo(activity.findViewById(R.id.nav_host));
                playing.set(video != null && video.isPlaying() && video.getDuration() > 1000
                        && video.getCurrentPosition() >= 500);
            });
            if (playing.get()) return;
            Thread.sleep(100);
        }
        fail("Bundled FSL video did not start playing");
    }

    private static VideoView findVideo(View view) {
        if (view instanceof VideoView) return (VideoView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                VideoView found = findVideo(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++)
                if (containsText(group.getChildAt(index), text)) return true;
        }
        return false;
    }
}
