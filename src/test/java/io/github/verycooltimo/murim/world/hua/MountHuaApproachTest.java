package io.github.verycooltimo.murim.world.hua;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MountHuaApproachTest {
    @Test void landBeatsAShorterOceanApproach() {
        MountHuaSites.BiomeCost biomes = (x, z) -> x > 800 && x < 1800 ? 32 : 0;
        assertTrue(MountHuaSites.approachPenalty(0, 0, 3000, 0, biomes)
                > MountHuaSites.approachPenalty(0, 0, 0, 3400, biomes) + 1000);
    }

    @Test void bendsCanAvoidABayAndRiversAreCheap() {
        double bay = MountHuaSites.approachPenalty(0, 0, 3000, 0,
                (x, z) -> x > 1000 && x < 2000 && Math.abs(z) < 100 ? 32 : 0);
        assertTrue(bay < 20);
        double river = MountHuaSites.approachPenalty(0, 0, 3000, 0, (x, z) -> x > 1400 && x < 1450 ? 1 : 0);
        assertTrue(river < 100);
    }

    @Test void configuredDistanceBandIsRespectedIncludingReversedBounds() {
        assertEquals(3100, MountHuaSites.candidateDistance(0, 3100, 3600));
        assertEquals(3350, MountHuaSites.candidateDistance(0.5, 3100, 3600));
        assertTrue(MountHuaSites.candidateDistance(0.999999, 3100, 3600) < 3600);
        assertEquals(4000, MountHuaSites.candidateDistance(0.9, 4000, 3000));
    }
}
