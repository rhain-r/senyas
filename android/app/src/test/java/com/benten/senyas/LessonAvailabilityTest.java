package com.benten.senyas;

import static org.junit.Assert.*;
import java.time.LocalDate;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.junit.Test;

public class LessonAvailabilityTest {
    @Test public void newLessonsOfferAnUpcomingPhilippineDateInsteadOfAnExpiredDemoDate() {
        Lesson lesson = new Lesson(1, "Lesson", "Tutor", "T", "Category", 1, 400, 60,
                true, "Description", 0, 0, false);
        LocalDate offered = LocalDate.parse(lesson.day,
                DateTimeFormatter.ofPattern("EEEE, d MMMM uuuu", Locale.ENGLISH));
        assertTrue("Initial availability must be in the future", offered.isAfter(LocalDate.now(SessionTime.ZONE)));
    }

    @Test public void availabilityRejectsMalformedDuplicateAndOutOfRangeTimes() {
        for (String invalid : new String[]{"Eydhhehw", "13:00 pm", "0:30 am", "3:60 pm",
                "3:00 pm,", "3:00 pm, 3:00 PM", "3:00", "3:00 pm junk"}) {
            assertTrue(invalid, LessonAvailability.parseSlots(invalid).isEmpty());
        }
        assertArrayEquals(new Integer[]{0, 555, 720, 945},
                LessonAvailability.parseSlots("3:45 PM, 12:00 am, 9:15 am, 12:00 pm").toArray(new Integer[0]));
    }

    @Test public void tutorMustChooseARealDateAndFutureSlotsThatFitBeforeMidnight() {
        long now = Instant.parse("2026-11-05T07:00:00Z").toEpochMilli(); // 3 pm PHT
        assertEquals(LessonAvailability.Error.DATE,
                LessonAvailability.validate("31 February 2027", "3:00 pm", 60, now));
        assertEquals(LessonAvailability.Error.PAST,
                LessonAvailability.validate("Thursday, 5 November 2026", "3:00 pm", 60, now));
        assertEquals(LessonAvailability.Error.PAST,
                LessonAvailability.validate("Wednesday, 4 November 2026", "4:00 pm", 60, now));
        assertEquals(LessonAvailability.Error.TIME,
                LessonAvailability.validate("Friday, 6 November 2026", "11:30 pm", 60, now));
        assertEquals(LessonAvailability.Error.NONE,
                LessonAvailability.validate("Friday, 6 November 2026", "11:00 pm", 60, now));
    }

    @Test public void bookingMustMatchTheTutorsPublishedDateAndStartTime() {
        Lesson lesson = new Lesson(1, "Lesson", "Tutor", "T", "Category", 1, 400, 60,
                true, "Description", 0, 0, false);
        lesson.day = "Friday, 6 November 2026";
        lesson.slots = "9:15 am, 11:45 am";
        long nineFifteen = Instant.parse("2026-11-06T01:15:00Z").toEpochMilli();
        assertTrue(LessonAvailability.offers(lesson, nineFifteen, 60));
        assertFalse(LessonAvailability.offers(lesson, nineFifteen + 60000, 60));
        assertFalse(LessonAvailability.offers(lesson, nineFifteen + 86400000, 60));
        lesson.slots = "invalid";
        assertFalse(LessonAvailability.offers(lesson, nineFifteen, 60));
    }
}
