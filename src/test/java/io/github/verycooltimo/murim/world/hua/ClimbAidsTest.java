package io.github.verycooltimo.murim.world.hua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** The climb's own blocks cover every route riser (author 06.10: our climbable blocks instead of vines). */
class ClimbAidsTest {

    @Test
    void everyRouteRiserIsClimbableToTheFoot() {
        Random r = new Random(1);
        int holds = 0;
        int moss = 0;
        for (int rise = 2; rise <= ClimbAids.ROUTE_RISE; rise++) {
            for (int i = 0; i < 2000; i++) {
                boolean[] cracked = new boolean[rise];
                for (int k = 0; k < rise; k++) {
                    cracked[k] = r.nextInt(5) == 0;
                }
                ClimbAids.Aid[] aids = ClimbAids.riser(rise, r.nextLong(), cracked);
                assertEquals(rise, aids.length, "rise " + rise + " must be covered down to the foot");
                for (int k = 0; k < rise; k++) {
                    assertNotNull(aids[k]);
                    if (cracked[k]) {
                        assertEquals(ClimbAids.Aid.HOLD, aids[k], "a crack in the wall is a handhold");
                    }
                    if (aids[k] == ClimbAids.Aid.HOLD) {
                        holds++;
                    } else {
                        moss++;
                    }
                }
            }
        }
        // A natural mix, not one block everywhere.
        double share = holds / (double) (holds + moss);
        assertTrue(share > 0.3 && share < 0.7, "handhold share " + share);
    }

    @Test
    void tallWallsOnlyGetShortStrandsThatNeverReachTheFoot() {
        Random r = new Random(2);
        int strands = 0;
        for (int i = 0; i < 4000; i++) {
            int rise = 7 + r.nextInt(30);
            ClimbAids.Aid[] aids = ClimbAids.riser(rise, r.nextLong(), new boolean[0]);
            assertTrue(aids.length <= 3 && aids.length < rise - 3, "strand of " + aids.length + " on a " + rise + " wall");
            for (ClimbAids.Aid a : aids) {
                assertEquals(ClimbAids.Aid.MOSS, a);
            }
            strands += aids.length > 0 ? 1 : 0;
        }
        assertTrue(strands > 100 && strands < 500, "strands on " + strands + " of 4000 walls");
        assertEquals(0, ClimbAids.riser(1, 0L, new boolean[1]).length, "a one-block step needs nothing");
    }
}
