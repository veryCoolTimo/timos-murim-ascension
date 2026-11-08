package io.github.verycooltimo.murim.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.profile.DantianProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The capture stand's {@code MURIM_CAPTURE_WALL} profile must sit at the wall of the rank it ends up in. */
class DevSetupWallTest {

    /** The stand seeds the dantian before the wall step, so the base is awakened. */
    private static final DantianProfile AWAKENED = DantianProfile.INITIAL.withTags("pure", "six_harmonies");

    @Test
    @DisplayName("MURIM_CAPTURE_WALL puts the pool at the wall for every rank, not only rank 0")
    void wallProfileIsAtTheWallOfItsRank() {
        for (int rank = 0; rank <= 4; rank++) {
            DantianProfile at = DevSetupEvents.wallProfile(AWAKENED, rank);
            assertEquals(rank, at.rank());
            assertTrue(Realm.atWall(at), "rank " + rank + ": pool " + at.pool() + " below wall " + Realm.wall(at));
            assertEquals(at.maxCirculating(), at.circulating(), 1.0E-9, "rank " + rank + ": circulating not full");
        }
    }
}
