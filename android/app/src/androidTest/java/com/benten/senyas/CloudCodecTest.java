package com.benten.senyas;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CloudCodecTest {
    @Test public void overlappingLessonsShareEpochMinuteLocksAndCancellationKeepsNeighbors() {
        long start = SessionTime.startAt(LocalDate.of(2027, 1, 8), 600);
        Map<String, Object> occupied = CloudCodec.reserveMinutes(new HashMap<>(), start, 60);
        assertEquals(60, occupied.size());
        try {
            CloudCodec.reserveMinutes(occupied, start + 30 * 60000L, 60);
            fail("A partially overlapping lesson must not reserve the tutor twice");
        } catch (IllegalStateException expected) { }
        Map<String, Object> adjacent = CloudCodec.reserveMinutes(occupied, start + 60 * 60000L, 30);
        Map<String, Object> cancelled = CloudCodec.releaseMinutes(adjacent, start, 60);
        assertEquals(30, cancelled.size());
        assertTrue(cancelled.containsKey(Long.toString(start / 60000L + 60)));
        assertEquals("tutor_" + CloudCodec.dayBucket(start), CloudCodec.dayId("tutor", start));
    }

    @Test public void listingPayloadCapturesDatesAndAlwaysReturnsTutorEditsToReview() {
        Lesson lesson = lesson();
        Map<String, Object> captured = CloudCodec.lessonPayload(lesson, 123, "tutor", "Tutor Name");
        lesson.title = "Changed after submission";
        lesson.days.clear();
        assertEquals("Original title", captured.get("title"));
        assertEquals(false, captured.get("reviewed"));
        Lesson restored = CloudCodec.lesson(captured);
        assertEquals(2, restored.days.size());
        assertEquals(4, ((java.util.List<?>) captured.get("offers")).size());
        assertEquals(123, restored.id);
        assertEquals("tutor", restored.tutorId);
    }

    @Test public void bookingPayloadContainsOnlyPaymentKindAndMaskedDigits() {
        Lesson lesson = lesson();
        long start = SessionTime.startAt(LocalDate.of(2027, 1, 8), 600);
        Map<String, Object> booking = CloudCodec.bookingPayload("SEN-TEST", lesson, "learner", "Learner",
                start, 30, 200, "Card", "1234");
        assertEquals("Card", booking.get("paymentKind"));
        assertEquals("1234", booking.get("paymentLast4"));
        assertFalse(booking.containsKey("payment"));
        assertFalse(booking.containsKey("cardNumber"));
        assertFalse(booking.containsKey("cvv"));
        assertEquals(start + 30 * 60000L, booking.get("endAtMillis"));
        assertEquals(200, booking.get("price"));
    }

    private Lesson lesson() {
        Lesson lesson = new Lesson(123, "Original title", "Tutor Name", "TN", Repo.CAT_TECH,
                1.5, 400, 60, true, "Description", 30, 0xff112233, false);
        lesson.tutorId = "tutor";
        lesson.days.add(LessonAvailability.formatDay(LocalDate.of(2027, 1, 8)));
        lesson.days.add(LessonAvailability.formatDay(LocalDate.of(2027, 1, 9)));
        lesson.slots = "10:00 am, 11:00 am";
        return lesson;
    }
}
