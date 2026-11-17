package com.benten.senyas;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Checks the two camera entry points without opening a classroom or requesting permission. */
@RunWith(AndroidJUnit4.class)
public class CameraSessionLayoutTest {
    @Test
    public void sessionPageOffersCameraCheckEvenWhenThereAreNoBookings() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext();
            View page = LayoutInflater.from(context).inflate(R.layout.fragment_bookings, null);
            int id = context.getResources().getIdentifier("btn_camera_check", "id", context.getPackageName());
            assertNotEquals("A camera check must be reachable without joining a scheduled class", 0, id);
            View check = page.findViewById(id);
            assertNotNull(check);
            page.findViewById(R.id.empty_view).setVisibility(View.VISIBLE);
            assertEquals("Camera check must remain available in the empty sessions state",
                    View.VISIBLE, check.getVisibility());
            assertTrue("Camera check needs an accessible touch target",
                    check.getMinimumHeight() >= Ui.dp(context, 48));
        });
    }

    @Test
    public void classroomCameraIsLargeEnoughToFrameHandsAndUpperBody() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext();
            View page = LayoutInflater.from(context).inflate(R.layout.fragment_live_classroom, null);
            int width = Ui.dp(context, 360);
            page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            page.layout(0, 0, width, page.getMeasuredHeight());
            View camera = page.findViewById(R.id.classroom_camera);
            assertTrue("The local camera should fill the classroom content width",
                    camera.getMeasuredWidth() >= Ui.dp(context, 300));
            assertTrue("A small corner thumbnail cannot show signing clearly",
                    camera.getMeasuredHeight() >= Ui.dp(context, 240));
        });
    }

    private static Context themedContext() {
        return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_Senyas);
    }
}
