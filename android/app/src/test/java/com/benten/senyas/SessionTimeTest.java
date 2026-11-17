package com.benten.senyas;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.TimeZone;
import org.junit.Test;

public class SessionTimeTest {
    @Test public void bookingDatesUsePhilippineTimeRegardlessOfDeviceZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertEquals(Instant.parse("2026-11-05T07:00:00Z").toEpochMilli(),
                    SessionTime.startAt(LocalDate.of(2026, 11, 5), 15 * 60));
        } finally { TimeZone.setDefault(original); }
    }
    @Test public void oldDisplayLabelsCanBeMigratedWithoutAllowingUnknownTimes() {
        assertEquals(Instant.parse("2026-10-19T07:00:00Z").toEpochMilli(),
                SessionTime.parseStart("Mon, 19 October 2026", "3:00–4:00 pm"));
        assertEquals(Instant.parse("2026-10-19T03:30:00Z").toEpochMilli(),
                SessionTime.parseStart("Monday, 19 October 2026", "11:30 am–12:30 pm"));
        assertEquals(0, SessionTime.parseStart("unknown", "unknown"));
        assertEquals(0, SessionTime.startAt(LocalDate.of(2026, 11, 5), 1440));
    }
    @Test public void invalidDurationsAndOverflowFailClosed() {
        assertEquals(61000, SessionTime.endAt(1000, 1));
        assertEquals(0, SessionTime.endAt(1000, 0));
        assertEquals(0, SessionTime.endAt(Long.MAX_VALUE - 1000, 60));
    }
}
