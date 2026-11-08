package io.github.verycooltimo.murim.mastery;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OriginalRulesTest {
    @Test void grantsExactlyOneStarAcrossEveryBoundary() {
        for (int star = 0; star < 12; star++) {
            var before = new TechniqueProgress(star, 7, 11, 42, 12);
            var after = OriginalRules.read(before, false);
            assertEquals(star + 1, after.stars());
            assertEquals(7, after.progress());
            assertEquals(11, after.unprocessed());
            assertSame(after, OriginalRules.read(after, true));
        }
    }
    @Test void capNeverGrantsAndShallowManuscriptCannotLoseItsReward() {
        var capped = TechniqueProgress.learned(12, 12);
        assertSame(capped, OriginalRules.read(capped, false));
        var shallow = OriginalRules.read(TechniqueProgress.learned(4, 4), false);
        assertEquals(5, shallow.stars());
        assertEquals(5, shallow.cap());
    }
}
