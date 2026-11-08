package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.combat.FoundationForms;
import io.github.verycooltimo.murim.technique.Styles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The numbers of the training yard's dummies (Track C, docs/design/research/hua-main-1810.md section 6). */
class YardRulesTest {

    @Test
    @DisplayName("Three different forms among the last four hits are varied; one form over and over is not")
    void varietyNeedsThreeForms() {
        assertFalse(YardRules.varied(List.of("f:0", "f:0", "f:0", "f:0")));
        assertFalse(YardRules.varied(List.of("f:0", "f:1", "f:0", "f:1")));
        assertTrue(YardRules.varied(List.of("f:0", "f:1", "f:2")));
        assertTrue(YardRules.varied(List.of("f:0", "f:0", "f:1", "f:2")));
        // only the last four are looked at: an old variety does not count
        assertFalse(YardRules.varied(List.of("f:0", "f:1", "f:2", "f:2", "f:2", "f:2")));
    }

    @Test
    @DisplayName("The run keeps four hits and starts over after a long pause")
    void rememberKeepsTheWindowAndBreaksOnAPause() {
        List<String> run = List.of();
        for (int i = 0; i < 6; i++) {
            run = YardRules.remember(run, "f:" + i, 5);
        }
        assertEquals(List.of("f:2", "f:3", "f:4", "f:5"), run);
        assertEquals(List.of("f:9"), YardRules.remember(run, "f:9", YardRules.RUN_BREAK_TICKS + 1));
    }

    @Test
    @DisplayName("Varied forms lift the fatigue floor from 0.10 to 0.25; fresh hits are whole either way")
    void fatigueFloorFollowsTheVariety() {
        assertEquals(1.0D, YardRules.fatigue(0, false), 1.0E-9);
        assertEquals(1.0D, YardRules.fatigue(39, true), 1.0E-9);
        double plain = YardRules.fatigue(10_000, false);
        double varied = YardRules.fatigue(10_000, true);
        assertEquals(YardRules.FLOOR_PLAIN, plain, 0.01D);
        assertEquals(YardRules.FLOOR_VARIED, varied, 0.01D);
        assertTrue(YardRules.fatigue(100, true) > YardRules.fatigue(100, false));
        // never below the floor, never rising with more hits
        double last = 1.0D;
        for (int hits = 0; hits < 400; hits += 10) {
            double now = YardRules.fatigue(hits, true);
            assertTrue(now <= last + 1.0E-9 && now >= YardRules.FLOOR_VARIED);
            last = now;
        }
    }

    @Test
    @DisplayName("Forty varied hits give far more practice than forty of one form once the day wears on")
    void varietyPaysAfterTheFreshHits() {
        double plain = 0.0D;
        double varied = 0.0D;
        for (int hit = 0; hit < 200; hit++) {
            plain += MasteryPacing.dummyWorth(hit, false);
            varied += MasteryPacing.dummyWorth(hit, true);
        }
        assertTrue(varied > plain * 1.10D, "varied " + varied + " plain " + plain);
        // the plain curve is the old one: the same as the dummy before the yard existed
        for (int hit : new int[] {0, 40, 80, 200}) {
            assertEquals(MasteryPacing.worth(MasteryPacing.Kind.TRAINING, 0, 0, hit), MasteryPacing.dummyWorth(hit, false), 1.0E-9);
        }
    }
}
