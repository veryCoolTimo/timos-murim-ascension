package io.github.verycooltimo.murim.client.vfx.bt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BtFaceFracTest {
    @Test
    void theLabFractureGivesCracksPeelsAndABurst() {
        BtFaceFrac f = new BtFaceFrac();
        assertTrue(f.segs.size() > 40, "cracks: " + f.segs.size());
        // three plates by the cheek, then up to four and two more in the second cracking
        assertEquals(3, f.peels.stream().filter(p -> p.stage() == 1).count());
        assertTrue(f.peels.size() >= 5, "peels: " + f.peels.size());
        assertTrue(f.burst.size() > 10, "burst: " + f.burst.size());
        for (var s : f.segs) {
            double[] p = s.pts();
            for (int i = 0; i < p.length; i += 3) {
                assertTrue(p[i] > BtFaceFrac.RX - 2 && p[i] < BtFaceFrac.RX + BtFaceFrac.RW + 2 && p[i + 1] > BtFaceFrac.RY - 2
                        && p[i + 1] < BtFaceFrac.RY + BtFaceFrac.RH + 2, "crack point outside the face rect");
            }
        }
        // the first front reaches the cheek before the second cracking starts
        assertTrue(f.segs.stream().filter(s -> s.stage() == 1).allMatch(s -> s.pts()[2] < 20.4 + 1e-9 || s.pts()[2] >= 20.4));
    }

    @Test
    void theFractureIsTheSameEveryTime() {
        BtFaceFrac a = new BtFaceFrac(), b = new BtFaceFrac();
        assertEquals(a.segs.size(), b.segs.size());
        assertEquals(a.peels.get(0).cx(), b.peels.get(0).cx(), 1e-12);
    }
}
