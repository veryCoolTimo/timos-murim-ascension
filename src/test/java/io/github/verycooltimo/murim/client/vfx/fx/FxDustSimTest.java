package io.github.verycooltimo.murim.client.vfx.fx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The dust simulation is pure maths: deterministic, finite, bounded by its duration, and it behaves like thrown, slowing, swelling dust. */
class FxDustSimTest {
    @Test void sameCallGivesSamePuffs() {
        for (var use : FxDustSim.Use.values()) assertEquals(FxDustSim.at(use, .8), FxDustSim.at(use, .8), use.name());
    }

    @Test void everyUseStartsQuietEndsEmptyAndStaysFinite() {
        for (var use : FxDustSim.Use.values()) {
            assertFalse(FxDustSim.at(use, .4).isEmpty(), use + " shows something early");
            assertTrue(FxDustSim.at(use, FxDustSim.duration(use) + 1).isEmpty(), use + " ends");
            for (double t = 0; t < FxDustSim.duration(use); t += .1)
                for (var p : FxDustSim.at(use, t)) {
                    assertTrue(Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()), use + " position at " + t);
                    assertTrue(p.size() > 0 && p.erode() >= 0 && p.erode() <= 1 && (use == FxDustSim.Use.COLUMN_DARK || use == FxDustSim.Use.DARK_PALE || p.y() >= -1e-6), use + " state at " + t);
                }
        }
    }

    @Test void landingBurstIsThrownOutAndSlowsDown() {
        var early = FxDustSim.at(FxDustSim.Use.LANDING, .15).stream().filter(p -> p.kind() == 0).toList();
        var late = FxDustSim.at(FxDustSim.Use.LANDING, 1.2).stream().filter(p -> p.kind() == 0).toList();
        assertFalse(early.isEmpty());
        assertFalse(late.isEmpty());
        double speedEarly = early.stream().mapToDouble(p -> Math.hypot(p.vx(), p.vz())).average().orElseThrow();
        double speedLate = late.stream().mapToDouble(p -> Math.hypot(p.vx(), p.vz())).average().orElseThrow();
        assertTrue(speedLate < speedEarly * .8, "drag slows the clumps");
        double sizeEarly = early.stream().mapToDouble(FxDustSim.Puff::size).average().orElseThrow();
        double sizeLate = late.stream().mapToDouble(FxDustSim.Puff::size).average().orElseThrow();
        assertTrue(sizeLate > sizeEarly * 1.15, "clumps swell");
        double reachEarly = early.stream().mapToDouble(p -> Math.hypot(p.x(), p.z())).max().orElseThrow();
        double reachLate = late.stream().mapToDouble(p -> Math.hypot(p.x(), p.z())).max().orElseThrow();
        assertTrue(reachLate > reachEarly, "the ring spreads");
    }

    @Test void erosionGrowsWithAge() {
        double last = -1;
        for (double t : new double[]{.3, .8, 1.3, 1.8}) {
            var column = FxDustSim.at(FxDustSim.Use.COLUMN, t);
            double mean = column.stream().mapToDouble(FxDustSim.Puff::erode).average().orElse(0);
            assertTrue(mean >= last - 1e-9, "mean erosion does not fall");
            last = mean;
        }
    }

    @Test void wakeRunsAlongTheCutToBothSidesAndStaysLow() {
        var wake = FxDustSim.at(FxDustSim.Use.WAKE, .4).stream().filter(p -> p.kind() == FxDustSim.BANK).toList();
        assertTrue(wake.stream().anyMatch(p -> p.vx() > .2) && wake.stream().anyMatch(p -> p.vx() < -.2));
        assertTrue(wake.stream().allMatch(p -> p.y() < .9), "a low bank");
    }

    @Test void darkColumnIsMostlySpatter() {
        var dark = FxDustSim.at(FxDustSim.Use.COLUMN_DARK, .6);
        long matter = dark.stream().filter(p -> p.kind() >= FxDustSim.ARC || p.kind() == FxDustSim.CHIP).count();
        assertTrue(matter > 200, "hundreds of streaks and droplets, not a cloud");
        assertTrue(dark.stream().anyMatch(p -> p.kind() == FxDustSim.ARC), "a few big arcs");
        assertTrue(dark.stream().filter(p -> p.kind() == FxDustSim.CLUMP).count() <= 6, "the clot stays small");
        assertTrue(FxDustSim.at(FxDustSim.Use.COLUMN, .6).stream().allMatch(p -> p.kind() == FxDustSim.CLUMP));
    }

    @Test void wakeIsALowBankOfSegmentsThatLieAlongTheGroundAndRunOutward() {
        var wake = FxDustSim.at(FxDustSim.Use.WAKE, .5).stream().filter(p -> p.kind() == FxDustSim.BANK).toList();
        assertTrue(wake.size() >= 10, "a bank of overlapping segments");
        assertTrue(wake.stream().allMatch(p -> !Double.isNaN(p.heading()) && Math.abs(Math.sin(p.heading())) < 1e-6), "every segment lies flat along the ground");
        assertTrue(wake.stream().allMatch(p -> Math.cos(p.heading()) * Math.signum(p.x()) > 0 || Math.abs(p.x()) < .05), "each runs away from the centre");
        assertTrue(wake.stream().allMatch(p -> p.aspect() >= 1.5), "long against their height");
        assertEquals(2, wake.stream().filter(p -> p.taper() > .5).count(), "one thin tail at the outer end of each side");
        assertTrue(FxDustSim.at(FxDustSim.Use.WAKE, .5).stream().noneMatch(p -> p.kind() == FxDustSim.TONGUE), "no smooth streaks");
    }

    @Test void landingWedgesDifferInLengthAndLeanAndSomeAreHooked() {
        var wedges = FxDustSim.at(FxDustSim.Use.LANDING, .3).stream().filter(p -> p.kind() == FxDustSim.TONGUE && p.layer() == 1 && p.size() * p.aspect() * 2 > 1.0).toList();
        double minLen = wedges.stream().mapToDouble(p -> p.size() * p.aspect() * 2).min().orElseThrow(), maxLen = wedges.stream().mapToDouble(p -> p.size() * p.aspect() * 2).max().orElseThrow();
        assertTrue(maxLen > 1.5 * minLen, "lengths vary");
        double minLean = wedges.stream().mapToDouble(p -> Math.abs(Math.PI / 2 - p.heading())).min().orElseThrow(), maxLean = wedges.stream().mapToDouble(p -> Math.abs(Math.PI / 2 - p.heading())).max().orElseThrow();
        assertTrue(maxLean - minLean > .5, "leans vary");
        assertTrue(wedges.stream().anyMatch(p -> Math.abs(p.taper()) > .3), "some have a hooked tip");
    }

    @Test void landingHasFewBigWedgesOutOfADarkMass() {
        var landing = FxDustSim.at(FxDustSim.Use.LANDING, .3);
        long wedges = landing.stream().filter(p -> p.kind() == FxDustSim.TONGUE && p.layer() == 1 && p.size() * p.aspect() * 2 > 1.0).count();
        assertTrue(wedges >= 8 && wedges <= 18, "seven or eight big wedges a side, not forty bristles: " + wedges);
        assertTrue(landing.stream().anyMatch(p -> p.layer() == 0 && p.kind() == FxDustSim.TONGUE && p.tint() < .8), "the dark mass is there");
    }

    @Test void columnIsASetOfBigMassesOfDifferentSizes() {
        var column = FxDustSim.at(FxDustSim.Use.COLUMN, 1.6);
        assertTrue(column.size() >= 4 && column.size() <= 6);
        double max = column.stream().mapToDouble(FxDustSim.Puff::size).max().orElseThrow(), min = column.stream().mapToDouble(FxDustSim.Puff::size).min().orElseThrow();
        assertTrue(max > 1.6 && max > min * 2, "one very big mass and small ones");
        assertTrue(column.stream().allMatch(p -> p.taper() > 0), "every clump is a mass of many lobes");
    }

    @Test void landingCraterIsLowInTheMiddleAndHighOnTheSides() {
        var tongues = FxDustSim.at(FxDustSim.Use.LANDING, .4).stream().filter(p -> p.kind() == FxDustSim.TONGUE && p.layer() == 1).toList();
        double mid = tongues.stream().filter(p -> Math.abs(p.x()) < .35).mapToDouble(p -> p.size() * p.aspect()).average().orElse(0);
        double side = tongues.stream().filter(p -> Math.abs(p.x()) > .6).mapToDouble(p -> p.size() * p.aspect()).average().orElse(0);
        assertTrue(side > mid * 1.5, "tongues are longer on the sides");
    }
}
