package io.github.verycooltimo.murim.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnimationTransitionsTest {
    @Test void dipperNormalFinishLeavesItsOwnedOutroPlaying() {
        assertFalse(AnimationTransitions.fadeFootworkOnFinish(true, true));
        assertFalse(AnimationTransitions.fadeFootworkOnFinish(true, false));
        assertTrue(AnimationTransitions.fadeFootworkOnFinish(false, true));
        assertFalse(AnimationTransitions.fadeFootworkOnFinish(false, false));
    }

    @Test void idleLayerHardStarts() {
        assertTrue(AnimationTransitions.start(false, () -> true, ticks -> {
            fail("Idle layer must start from rest"); return false;
        }));
    }

    @Test void activeLayerCrossfadesForThreeTicks() {
        assertTrue(AnimationTransitions.start(true, () -> {
            fail("Active layer must preserve its current pose"); return false;
        }, ticks -> {
            assertEquals(3, ticks); return true;
        }));
    }

    @Test void failedStartIsReported() {
        assertFalse(AnimationTransitions.start(false, () -> false, ticks -> true));
        assertFalse(AnimationTransitions.start(true, () -> true, ticks -> false));
    }
}
