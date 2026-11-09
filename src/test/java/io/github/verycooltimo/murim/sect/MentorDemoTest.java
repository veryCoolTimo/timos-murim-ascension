package io.github.verycooltimo.murim.sect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MentorDemoTest {
    @Test void asksOnlyWithMethodAndNoDantian() {
        assertTrue(MentorDemo.eligible(true, false, false));
        assertFalse(MentorDemo.eligible(false, false, false));
        assertFalse(MentorDemo.eligible(true, true, false));
        assertFalse(MentorDemo.eligible(true, false, true));
    }

    @Test void cooldownIsTwentySeconds() {
        assertTrue(MentorDemo.cooledDown(-1, 0));
        assertFalse(MentorDemo.cooledDown(100, 499));
        assertTrue(MentorDemo.cooledDown(100, 500));
    }

    @Test void showLastsEightSeconds() {
        assertTrue(MentorDemo.TOTAL >= 140 && MentorDemo.TOTAL <= 180);
        assertTrue(MentorDemo.radius(0) > MentorDemo.radius(80));
    }
}
