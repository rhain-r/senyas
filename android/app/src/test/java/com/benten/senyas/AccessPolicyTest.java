package com.benten.senyas;

import static org.junit.Assert.*;
import org.junit.Test;

public class AccessPolicyTest {
    @Test public void studentsCannotOpenTutorOrAdminConsoles() {
        assertTrue(AccessPolicy.allows(1, AccessPolicy.Area.LEARNER));
        assertFalse(AccessPolicy.allows(1, AccessPolicy.Area.TUTOR));
        assertFalse(AccessPolicy.allows(1, AccessPolicy.Area.ADMIN));
        assertFalse(AccessPolicy.allows(0, AccessPolicy.Area.ADMIN));
    }
    @Test public void tutorAndAdminRemainDistinct() {
        assertTrue(AccessPolicy.allows(2, AccessPolicy.Area.TUTOR));
        assertFalse(AccessPolicy.allows(2, AccessPolicy.Area.ADMIN));
        assertTrue(AccessPolicy.allows(3, AccessPolicy.Area.ADMIN));
        assertFalse(AccessPolicy.allows(3, AccessPolicy.Area.TUTOR));
        assertFalse(AccessPolicy.allows(-1, AccessPolicy.Area.SHARED));
    }
    @Test public void onlyTheBookedStudentOrTutorCanAttend() {
        assertTrue(AccessPolicy.canAttend(1, "student", "student", "tutor", 1000, 61000, false, 1000));
        assertTrue(AccessPolicy.canAttend(2, "tutor", "student", "tutor", 1000, 61000, false, 1000));
        assertFalse(AccessPolicy.canAttend(1, "other", "student", "tutor", 1000, 61000, false, 1000));
        assertFalse(AccessPolicy.canAttend(2, "student", "student", "tutor", 1000, 61000, false, 1000));
        assertFalse(AccessPolicy.canAttend(3, "tutor", "student", "tutor", 1000, 61000, false, 1000));
        assertFalse(AccessPolicy.canAttend(1, "", "", "tutor", 1000, 61000, false, 1000));
    }
    @Test public void joiningIsAllowedOnlyInsideTheScheduledWindow() {
        assertFalse(AccessPolicy.canAttend(1, "s", "s", "t", 1000, 61000, false, 999));
        assertTrue(AccessPolicy.canAttend(1, "s", "s", "t", 1000, 61000, false, 1000));
        assertTrue(AccessPolicy.canAttend(1, "s", "s", "t", 1000, 61000, false, 60999));
        assertFalse(AccessPolicy.canAttend(1, "s", "s", "t", 1000, 61000, false, 61000));
        assertFalse(AccessPolicy.canAttend(1, "s", "s", "t", 1000, 61000, true, 1000));
        assertFalse(AccessPolicy.canAttend(1, "s", "s", "t", 0, 0, false, 1000));
    }
}
