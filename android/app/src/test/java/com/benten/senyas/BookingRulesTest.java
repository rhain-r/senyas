package com.benten.senyas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** Tutor availability, clash checks and what the text fields accept. */
public class BookingRulesTest {

    @Test
    public void openDatesFollowTheTutorsWeekdays() {
        int monWed = Availability.bit(DayOfWeek.MONDAY) | Availability.bit(DayOfWeek.WEDNESDAY);
        LocalDate sunday = LocalDate.of(2026, 11, 1);
        List<LocalDate> dates = Availability.nextDates(monWed, sunday, 14, 8);
        assertEquals(Arrays.asList(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 4),
                LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 11)), dates);
        assertTrue(Availability.nextDates(0, sunday, 14, 8).isEmpty());
    }

    @Test
    public void sessionsClashOnlyWhenTheyOverlap() {
        assertTrue(Availability.overlaps(15 * 60, 60, 15 * 60 + 30, 30));
        assertFalse(Availability.overlaps(15 * 60, 60, 16 * 60, 60));
        assertFalse(Availability.overlaps(14 * 60, 30, 14 * 60 + 30, 60));
    }

    @Test
    public void timesAreSortedUniqueAndWithinTeachingHours() {
        assertEquals(Arrays.asList(9 * 60, 15 * 60),
                Availability.normalize(Arrays.asList(15 * 60, 9 * 60, 15 * 60, 23 * 60, 2 * 60)));
    }

    @Test
    public void oldFreeTextSlotsKeepOnlyRealTimes() {
        assertEquals(Arrays.asList(15 * 60, 16 * 60), Availability.parseLegacy("3:00 pm, 4:00 pm"));
        assertTrue(Availability.parseLegacy("10:00 jdjuieje0! 'us+&#&($) _! \"! *;? 😊").isEmpty());
    }

    @Test
    public void cityNamesAreLettersNotJunk() {
        assertTrue(InputRules.isCity("Mandaluyong City"));
        assertTrue(InputRules.isCity("Las Piñas"));
        assertTrue(InputRules.isCity("Brgy. 176, Caloocan"));
        assertFalse(InputRules.isCity("10:00 jdjuieje0! 'us+&#&($)"));
        assertFalse(InputRules.isCity("Quezon City 😊"));
        assertFalse(InputRules.isCity("aaaaaaa"));
        assertFalse(InputRules.isCity("1234"));
    }

    @Test
    public void titlesNamesAndDescriptionsRejectEmojiAndSymbols() {
        assertTrue(InputRules.isTitle("Basic communication words"));
        assertTrue(InputRules.isTitle("Grade 7 Math: Fractions"));
        assertFalse(InputRules.isTitle("😊😊😊"));
        assertFalse(InputRules.isTitle("#$%&*"));
        assertTrue(InputRules.isPersonName("Juan Dela Cruz"));
        assertTrue(InputRules.isPersonName("Ma. Santos-Reyes"));
        assertFalse(InputRules.isPersonName("Juan123"));
        assertFalse(InputRules.isPersonName("J 😊"));
        assertTrue(InputRules.isDescription(""));
        assertTrue(InputRules.isDescription("Learn to greet and say thank you, explained fully in FSL."));
        assertFalse(InputRules.isDescription("hi 😊😊😊😊😊"));
        assertTrue(InputRules.isRate(600));
        assertFalse(InputRules.isRate(5));
    }
}
