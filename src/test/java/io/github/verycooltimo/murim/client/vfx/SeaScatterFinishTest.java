package io.github.verycooltimo.murim.client.vfx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeaScatterFinishTest {
    @Test void looseAtlasNeverSamplesBlossomsOrLancesAndOneThirdAreBacks() {
        int backs = 0;
        for (int slot = 0; slot < 300; slot++) {
            int cell = SeaScatterFinish.cell(slot);
            assertTrue(cell >= 0 && cell < 8);
            if (cell >= 4) backs++;
        }
        assertEquals(100, backs);
    }
    @Test void landingHoldIsThreeToEightSecondsWithIndependentExpiry() {
        boolean[] lifetimes = new boolean[161];
        for (int i = 0; i < 101; i++) {
            int life = SeaScatterFinish.landedLife(i);
            assertTrue(life >= 60 && life <= 160);
            lifetimes[life] = true;
            assertEquals(0, SeaScatterFinish.envelope(0, life));
            assertEquals(1, SeaScatterFinish.envelope(3, life));
            assertEquals(0, SeaScatterFinish.envelope(life, life));
        }
        for (int life = 60; life <= 160; life++) assertTrue(lifetimes[life]);
    }
    @Test void materialCeilingsIncreaseOnlyAfterPetalTier() {
        for (boolean sea : new boolean[]{false, true}) {
            int previous = 0;
            for (int tier = 0; tier <= 8; tier++) {
                int ceiling = SeaScatterFinish.finishQuads(tier, sea);
                if (tier < 3) assertEquals(0, ceiling);
                assertTrue(ceiling >= previous);
                assertTrue(ceiling <= 12200);
                previous = ceiling;
            }
        }
    }
    @Test void foregroundRespectsLadderAndDiameterLimits() {
        int previous = 0;
        for (int tier = 0; tier <= 8; tier++) {
            int count = SeaScatterFinish.nearCount(tier);
            assertTrue(count >= previous && count <= 6);
            if (tier < 3) assertEquals(0, count);
            else assertTrue(count >= 3);
            previous = count;
        }
        assertEquals(6, SeaScatterFinish.nearCount(8));
        for (int i = 0; i < 100; i++) {
            double diameter = SeaScatterFinish.nearSize(i) * 2;
            assertTrue(diameter >= .18 && diameter <= .4);
        }
        assertEquals(.7, SeaScatterFinish.depth(15));
        assertEquals(1, SeaScatterFinish.depth(2));
    }
}
