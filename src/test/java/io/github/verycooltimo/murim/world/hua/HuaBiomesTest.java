package io.github.verycooltimo.murim.world.hua;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which columns become Mount Hua and which its foothills (HuaBiomes#classify). */
class HuaBiomesTest {

    private final MountHuaShape shape = new MountHuaShape(20261005L);

    @Test
    void peaksSectAndRidgeAreTheMountain() {
        for (MountHuaPlan.Peak p : MountHuaPlan.PEAKS.subList(0, 5)) {
            assertEquals(HuaBiomes.Zone.MASSIF, HuaBiomes.classify(shape, p.u(), p.v()), p.name());
        }
        for (String id : new String[] {"training", "main_hall", "ancestors", "sect_gate"}) {
            MountHuaPlan.Zone z = MountHuaPlan.ZONES.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            assertEquals(HuaBiomes.Zone.MASSIF, HuaBiomes.classify(shape, z.u(), z.v()), id);
        }
    }

    @Test
    void gateValleyAndSatellitesAreFoothills() {
        assertEquals(HuaBiomes.Zone.FOOTHILLS, HuaBiomes.classify(shape, -106, -446), "approach valley");
        assertEquals(HuaBiomes.Zone.FOOTHILLS, HuaBiomes.classify(shape, -120, -600), "valley");
        for (MountHuaPlan.Peak p : MountHuaPlan.SATELLITES) {
            assertEquals(HuaBiomes.Zone.FOOTHILLS, HuaBiomes.classify(shape, p.u(), p.v()), p.name());
        }
    }

    @Test
    void outsideTheBeltIsUntouched() {
        assertEquals(HuaBiomes.Zone.NONE, HuaBiomes.classify(shape, 0, -1200));
        assertEquals(HuaBiomes.Zone.NONE, HuaBiomes.classify(shape, 950, 0));
        assertEquals(HuaBiomes.Zone.NONE, HuaBiomes.classify(shape, -880, 1000));
    }

    @Test
    void massifIsASizeableShareOfTheBelt() {
        int massif = 0;
        int foot = 0;
        for (double u = -900; u <= 900; u += 20) {
            for (double v = -1000; v <= 1080; v += 20) {
                switch (HuaBiomes.classify(shape, u, v)) {
                    case MASSIF -> massif++;
                    case FOOTHILLS -> foot++;
                    default -> {
                    }
                }
            }
        }
        assertTrue(massif > 300, "massif cells " + massif);
        assertTrue(foot > massif, "foothills " + foot + " vs massif " + massif);
    }

    /** Every column the terrain pass reshapes (weight above 0) is a Hua biome, not only the inner belt (26.10: jungle on the outer slopes). */
    @Test
    void everyReshapedColumnIsHua() {
        int checked = 0;
        for (double u = -900; u <= 900; u += 9) {
            for (double v = -1000; v <= 1080; v += 9) {
                if (shape.blend(u, v) > 0) {
                    checked++;
                    assertTrue(HuaBiomes.classify(shape, u, v) != HuaBiomes.Zone.NONE, "weight " + shape.blend(u, v) + " at " + u + ", " + v);
                }
            }
        }
        assertTrue(checked > 3000, "columns with a weight: " + checked);
    }

    /** The margin: a column whose weight is 0 but that lies within 8 blocks of one with a weight is Hua too, and one 40 blocks out is not. */
    @Test
    void theMarginRoundTheReshapedColumnsIsHua() {
        int inMargin = 0;
        for (double u = -900; u <= 900; u += 9) {
            for (double v = -1000; v <= 1080; v += 9) {
                if (shape.blend(u, v) <= 0 && shape.blend(u + HuaBiomes.MARGIN, v) > 0) {
                    inMargin++;
                    assertTrue(HuaBiomes.classify(shape, u, v) != HuaBiomes.Zone.NONE, "margin at " + u + ", " + v);
                }
            }
        }
        assertTrue(inMargin > 20, "margin columns: " + inMargin);
        assertEquals(HuaBiomes.Zone.NONE, HuaBiomes.classify(shape, 0, -1200));
    }
}
