package io.github.verycooltimo.murim.technique;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SixRulesTest {
    @Test void animationClockFollowsEveryServerHit() {
        for (int tier = 1; tier <= 4; tier++) {
            int[] server = SixRules.chain(tier), clip = SixRules.chain(1);
            for (int k = 0; k < 5; k++) assertEquals(8 + clip[k], SixRules.animationTick(1, tier, 8 + server[k], -1), 1E-6);
        }
        for (int trigger = 0; trigger < 14; trigger++) {
            assertEquals(24, SixRules.animationTick(4, 3, trigger + 34, trigger), 1E-6);
            assertEquals(24, SixRules.animationTick(4, 4, trigger + 34, trigger), 1E-6);
            assertEquals(34, SixRules.animationTick(4, 4, trigger + 44, trigger), 1E-6);
            assertEquals(58, SixRules.animationTick(4, 4, 68, trigger), 1E-6);
        }
        assertEquals(13, SixRules.animationTick(4, 4, 18, -1), 1E-6);
        assertEquals(24, SixRules.animationTick(4, 3, 40, 0), 1E-6);
    }
    @Test void giantWaitsForDrawnImpactEvenWhenPacketIsSixTicksLate() {
        for (int contact : new int[]{SixRules.WINDUP[3] - SixRules.FREEZE_TICK, SixRules.GIANT_END}) {
            for (int late = 0; late <= 6; late++) {
                var giant = new SixRules.GiantState(contact);
                for (int age = 0; age <= contact + late; age++)
                    assertEquals(2.6F, giant.scale(age));
                giant.impactFrameStarted();
                assertEquals(1, giant.scale(contact + late));
                assertEquals(1, giant.scale(contact + late + 1));
            }
            var missed = new SixRules.GiantState(contact);
            assertEquals(2.6F, missed.scale(contact + 9));
            assertEquals(1, missed.scale(contact + 10));
        }
        assertEquals(SixRules.COUNTER_SLASH + 1, SixRules.GIANT_END);
        assertEquals(3, SixRules.IMPACT_FRAME_TICKS);
    }

    @Test void counterWindupInterpolatesAcrossWholeHoldAndHitsStayOnTheirKeys() {
        int form = 4;
        for (int tier = 1; tier <= 4; tier++)
                for (int trigger = 0; trigger < SixRules.COUNTER_WINDOW; trigger++) {
            int offset = trigger;
            int start = tier == 4 ? 0 : SixRules.COUNTER_ANSWER + 4;
            int hit = SixRules.COUNTER_VERTICAL;
            int ready = 22;
            int first = 14;
            assertEquals(first, SixRules.animationTick(form, tier, offset + start, trigger));
            int quarter = Math.max(1, (hit - 2 - start) / 4);
            float progress = (SixRules.animationTick(form, tier, offset + start + quarter, trigger)
                    - first) / (ready - first);
            assertTrue(progress < quarter / (float) (hit - 2 - start), "ease-in must start slowly");
            float previous = first;
            for (int tick = start + 1; tick <= hit - 2; tick++) {
                float next = SixRules.animationTick(form, tier, offset + tick, trigger);
                assertTrue(next > previous, "wind-up must move throughout the hold");
                previous = next;
            }
            assertEquals(ready, previous);
            assertEquals(ready + 1, SixRules.animationTick(form, tier, offset + hit - 1, trigger));
            assertEquals(ready + 2, SixRules.animationTick(form, tier, offset + hit, trigger));
        }
        assertEquals(46, SixRules.total(3));
        assertEquals(68, SixRules.total(4));
    }
    @Test void masterFearHoldsTheWindupBeforeTwoTickContact() {
        int tier = SixRules.tier(12);
        assertEquals(12, SixRules.animationTick(3, tier, 6, -1));
        for (int age = 6; age <= 18; age++) {
            float clip = SixRules.animationTick(3, tier, age, -1);
            assertTrue(clip >= 12 && clip <= 12.3F, "wind-up hold at cast tick " + age);
        }
        assertEquals(12.3F, SixRules.animationTick(3, tier, 18, -1), 1E-5);
        assertEquals(14.15F, SixRules.animationTick(3, tier, 19, -1), 1E-5);
        assertEquals(16, SixRules.animationTick(3, tier, 20, -1));
        assertEquals(42, SixRules.animationTick(3, tier, 46, -1));
        assertEquals(46, SixRules.total(3));
    }
    @Test void everyScheduledBladeHitHasBoundedDistinctAuthorGeometry() {
        int[] counts = {1, 5, 0, 1, 2};
        assertEquals(counts.length, SixRules.ARCS.length);
        for (int form = 0; form < counts.length; form++) {
            assertEquals(counts[form], SixRules.ARCS[form].length, "form " + form);
            for (SixRules.Arc arc : SixRules.ARCS[form]) {
                assertNotEquals(arc.start(), arc.end());
                for (SixRules.Point point : new SixRules.Point[]{arc.start(), arc.control(), arc.end()}) {
                    assertTrue(Double.isFinite(point.right()) && Double.isFinite(point.up()) && Double.isFinite(point.forward()));
                    // Four-block authoring volume: [-2,2] right, [0,4] up, [0,4] forward.
                    assertTrue(Math.abs(point.right()) <= 2);
                    assertTrue(point.up() >= 0 && point.up() <= 4);
                    assertTrue(point.forward() >= 0 && point.forward() <= 4);
                }
            }
        }
        assertTrue(SixRules.ARCS[1][0].thrust());
        assertEquals(SixRules.ARCS[1][0].start().right(), SixRules.ARCS[1][0].end().right());
        assertEquals(SixRules.ARCS[1][0].start().up(), SixRules.ARCS[1][0].end().up());
        assertEquals(SixRules.ARCS[3][0], SixRules.ARCS[4][0]);
        assertEquals(new SixRules.Point(.9, 2.3, .6), SixRules.ARCS[0][0].start());
        assertEquals(new SixRules.Point(-1.8, 1, .6), SixRules.ARCS[4][1].end());
        assertEquals(24, SixRules.ARC_SEGMENTS);
        assertEquals(5, SixRules.HIT_LIFE);
    }
    @Test void visualTierWidthsGrowAndNovicesStayInvisible() {
        assertArrayEquals(new double[]{0, 0, .10, .16, .34}, SixRules.BLADE_WIDTH);
        for (double[] table : new double[][]{SixRules.BLADE_WIDTH, SixRules.WAVE_HALF_HEIGHT, SixRules.WAVE_RIBBON_WIDTH}) {
            assertEquals(0, table[1]);
            assertTrue(table[2] > table[1] && table[3] > table[2] && table[4] > table[3]);
        }
    }
    @Test void authorTablesStayLiteral() {
        assertArrayEquals(new int[]{1, 2, 4, 5, 6}, SixRules.UNLOCK);
        assertEquals(1, SixRules.tier(3)); assertEquals(2, SixRules.tier(4));
        assertEquals(2, SixRules.tier(6)); assertEquals(3, SixRules.tier(7));
        assertEquals(3, SixRules.tier(9)); assertEquals(4, SixRules.tier(10));
        assertArrayEquals(new int[]{0, 6, 12, 18, 25}, SixRules.chain(1));
        assertArrayEquals(new int[]{0, 5, 10, 15, 21}, SixRules.chain(2));
        assertArrayEquals(SixRules.chain(2), SixRules.chain(4));
        assertArrayEquals(new double[]{0, 0, 3.5, 5.5, 10}, SixRules.WAVE_RANGE);
        assertArrayEquals(new double[]{0, 0, .8, 1, 1.4}, SixRules.WAVE_WIDTH);
        assertEquals(1.8, Arrays.stream(SixRules.CHAIN_SHARE).sum(), 1E-9);
        assertEquals(1, SixRules.strikePower(1), 1E-9);
        assertEquals(1.24 * 1.15, SixRules.strikePower(4), 1E-9);
        assertEquals(1.18, SixRules.chainPower(4), 1E-9);
        assertArrayEquals(new int[]{50, 90, 70, 140, 160}, SixRules.COOLDOWN);
        assertEquals(14, SixRules.COUNTER_WINDOW); assertEquals(6, SixRules.COUNTER_EXPIRE);
        assertArrayEquals(new int[]{32, 46, 30, 46, 68},
                java.util.stream.IntStream.range(0, 5).map(SixRules::total).toArray());
    }
}
