package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.mastery.MasteryRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SevenStarsRulesTest {
    @Test void dodgeChoicePreservesSideThenBackPriority() {
        assertEquals(0, SevenStarsRules.firstFree(java.util.List.of(true, true, true, true)));
        assertEquals(1, SevenStarsRules.firstFree(java.util.List.of(false, true, true, true)));
        assertEquals(2, SevenStarsRules.firstFree(java.util.List.of(false, false, true, true)));
        assertEquals(3, SevenStarsRules.firstFree(java.util.List.of(false, false, false, true)));
        assertEquals(-1, SevenStarsRules.firstFree(java.util.List.of(false, false, false, false)));
    }
    @Test void dodgeStunScalesFromOneToOneAndAHalfSeconds() {
        assertEquals(20, SevenStarsRules.stunTicks(0));
        assertEquals(20, SevenStarsRules.stunTicks(1));
        assertEquals(30, SevenStarsRules.stunTicks(12));
        assertEquals(30, SevenStarsRules.stunTicks(99));
        for (int star = 2; star <= 12; star++) {
            assertTrue(SevenStarsRules.stunTicks(star) >= SevenStarsRules.stunTicks(star - 1));
        }
    }
    @Test void inclusiveLinkDodgeAndOpeningWindows() {
        for (int window : new int[] {6, 8, 10}) {
            assertFalse(SevenStarsRules.within(100, Long.MIN_VALUE, window));
            assertFalse(SevenStarsRules.within(99, 100, window));
            assertTrue(SevenStarsRules.within(100, 100, window));
            assertTrue(SevenStarsRules.within(100 + window, 100, window));
            assertFalse(SevenStarsRules.within(101 + window, 100, window));
        }
        assertEquals(7, SevenStarsRules.linkedOpener(24));
        assertEquals(3, SevenStarsRules.linkedOpener(10));
        for (var id : Styles.SIX_HARMONIES.forms()) assertTrue(SevenStarsRules.huaSword(id));
        for (var id : Styles.SEVEN_PLUM.forms()) assertTrue(SevenStarsRules.huaSword(id));
        for (var id : Styles.TWENTY_FOUR_PLUM.forms()) assertFalse(SevenStarsRules.huaSword(id));
        for (var id : Styles.TANG_DAGGERS.forms()) assertFalse(SevenStarsRules.huaSword(id));
    }
    @Test void authoredDistancesAndTiming() {
        int[] stars = {1, 3, 5, 8, 10, 12};
        double[] shift = {1.5, 1.7, 2, 2.2, 2.5, 2.8};
        double[] follow = {2, 2.5, 3, 3.5, 4, 4.5};
        for (int i = 0; i < stars.length; i++) {
            assertEquals(shift[i], SevenStarsRules.distance(stars[i], false));
            assertEquals(follow[i], SevenStarsRules.distance(stars[i], true));
        }
        assertEquals(2, SevenStarsRules.PREPARATION);
        assertEquals(4, SevenStarsRules.movement(false));
        assertEquals(6, SevenStarsRules.recovery(false));
        assertEquals(4, SevenStarsRules.movement(true));
        assertEquals(4, SevenStarsRules.recovery(true));
    }
    @Test void executionTiersUseTheAuthoredTwelveStarScale() {
        for (var form : Styles.SEVEN_STARS.forms()) {
            for (int stars = 0; stars <= 12; stars++) {
                assertEquals(stars, io.github.verycooltimo.murim.mastery.Arts.executionTier(form, stars));
            }
            assertEquals(12, io.github.verycooltimo.murim.mastery.Arts.executionTier(form, 99));
        }
    }
    @Test void plateausAndBoundaries() {
        for (boolean follow : new boolean[] {false, true}) {
            assertEquals(SevenStarsRules.distance(1, follow), SevenStarsRules.distance(0, follow));
            assertEquals(SevenStarsRules.distance(12, follow), SevenStarsRules.distance(99, follow));
            assertEquals(SevenStarsRules.distance(5, follow), SevenStarsRules.distance(7, follow));
            assertEquals(SevenStarsRules.distance(8, follow), SevenStarsRules.distance(9, follow));
        }
    }
    @Test void basicFootworkIsCheaperAndFasterToReadyThanLightningFlash() {
        for (int star = 0; star <= 12; star++) {
            assertTrue(SevenStarsRules.qi(star) > 0);
            assertTrue(SevenStarsRules.qi(star) < 1.4 * MasteryRules.costFactor(star, 12));
            assertTrue(SevenStarsRules.COOLDOWN < FootworkFamily.windEvadeCooldown(star));
        }
    }
}
