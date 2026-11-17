package com.benten.senyas;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class MultiDateAvailabilityTest {
    private Lesson lesson() {
        return new Lesson(1, "Lesson", "Tutor", "T", "Category", 1, 400, 60, true, "", 0, 0, false);
    }
    @Test public void explicitDatesOfferEverySelectedDayAndKeepLegacyFirstDate() {
        Lesson lesson = lesson();
        LessonAvailability.setDays(lesson, Arrays.asList("Sunday, 8 November 2026", "Friday, 6 November 2026", "Sunday, 8 November 2026"));
        assertEquals(Arrays.asList("Friday, 6 November 2026", "Sunday, 8 November 2026"), lesson.days);
        assertEquals("Friday, 6 November 2026", lesson.day);
        assertTrue(LessonAvailability.offers(lesson, Instant.parse("2026-11-06T07:00:00Z").toEpochMilli(), 60));
        assertTrue(LessonAvailability.offers(lesson, Instant.parse("2026-11-08T08:00:00Z").toEpochMilli(), 60));
        assertFalse(LessonAvailability.offers(lesson, Instant.parse("2026-11-07T07:00:00Z").toEpochMilli(), 60));
    }
    @Test public void absentExplicitDatesPreserveLegacyAvailability() {
        Lesson lesson = lesson();
        lesson.day = "Friday, 6 November 2026";
        assertEquals(Collections.singletonList(LocalDate.of(2026, 11, 6)), LessonAvailability.days(lesson));
        assertTrue(LessonAvailability.offers(lesson, Instant.parse("2026-11-06T07:00:00Z").toEpochMilli(), 60));
        LessonAvailability.setDays(lesson, Collections.emptyList());
        assertTrue(LessonAvailability.days(lesson).isEmpty());
        assertFalse(LessonAvailability.offers(lesson, Instant.parse("2026-11-06T07:00:00Z").toEpochMilli(), 60));
    }
    @Test public void commonTimesMustBeFutureOnEverySelectedDateAndInvalidMembersFailClosed() {
        long now = Instant.parse("2026-11-05T07:00:00Z").toEpochMilli();
        assertEquals(LessonAvailability.Error.NONE, LessonAvailability.validate(Arrays.asList(
                "Friday, 6 November 2026", "Sunday, 8 November 2026"), "9:15 am, 11:45 am", 60, now));
        assertEquals(LessonAvailability.Error.PAST, LessonAvailability.validate(Arrays.asList(
                "Thursday, 5 November 2026", "Sunday, 8 November 2026"), "9:15 am", 60, now));
        assertEquals(LessonAvailability.Error.DATE, LessonAvailability.validate(Arrays.asList(
                "Friday, 6 November 2026", "31 February 2027"), "9:15 am", 60, now));
        Lesson lesson = lesson();
        lesson.days.addAll(Arrays.asList("Friday, 6 November 2026", "invalid"));
        assertFalse(LessonAvailability.offers(lesson, Instant.parse("2026-11-06T07:00:00Z").toEpochMilli(), 60));
    }
}
