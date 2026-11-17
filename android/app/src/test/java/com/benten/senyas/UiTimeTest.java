package com.benten.senyas;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class UiTimeTest {
    @Test public void sessionEndingAtMidnightDisplaysAmInsteadOfNoon() {
        assertEquals("11:00 pm–12:00 am", Ui.timeRange(23 * 60, 60));
        assertEquals("11:30 pm–12:30 am", Ui.timeRange(23 * 60 + 30, 60));
        assertEquals("11:00 am–12:00 pm", Ui.timeRange(11 * 60, 60));
    }
}
