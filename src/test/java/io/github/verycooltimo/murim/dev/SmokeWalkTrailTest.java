package io.github.verycooltimo.murim.dev;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SmokeWalkTrailTest {
    private static final List<SmokeWalkTrail.Point> TRAIL = List.of(
            new SmokeWalkTrail.Point(0, 0, 0), new SmokeWalkTrail.Point(10, 5, 0),
            new SmokeWalkTrail.Point(10, 10, 10), new SmokeWalkTrail.Point(20, 15, 10));

    @Test void samplesIncomingSegmentEveryFourBlocksBeforeHighSwitchback() {
        var samples = SmokeWalkTrail.samples(new SmokeWalkTrail.Point(0, 140, 0),
                new SmokeWalkTrail.Point(20, 170, 0));
        assertEquals(6, samples.size());
        assertEquals(new SmokeWalkTrail.Point(4, 146, 0), samples.get(1));
        assertEquals(new SmokeWalkTrail.Point(20, 170, 0), samples.getLast());
    }

    @Test void resolvesFloatingAuthoredPointToSupportedFeet() {
        assertEquals(90, SmokeWalkTrail.ground(94.3197, y -> y == 89, y -> y >= 90));
        // A floor without two clear body blocks must not be selected.
        assertEquals(90, SmokeWalkTrail.ground(94, y -> y == 93 || y == 89,
                y -> y >= 90 && y != 95));
        assertEquals(94, SmokeWalkTrail.ground(94, y -> y == 86, y -> y >= 87));
    }

    @Test void advancesHorizontallyWithSixBlockVerticalTolerance() {
        var target = new SmokeWalkTrail.Point(10, 5, 0);
        assertTrue(SmokeWalkTrail.reached(new SmokeWalkTrail.Point(7.5, -1, 0), target));
        assertFalse(SmokeWalkTrail.reached(new SmokeWalkTrail.Point(7.49, 5, 0), target));
        assertFalse(SmokeWalkTrail.reached(new SmokeWalkTrail.Point(10, -1.01, 0), target));
        assertEquals(2, SmokeWalkTrail.steer(TRAIL, new SmokeWalkTrail.Point(8, 1, 0), 1).next());
        assertEquals(1, SmokeWalkTrail.steer(TRAIL, new SmokeWalkTrail.Point(8, -2, 0), 1).next());
    }

    @Test void emptyPathUsesHorizontalArrivalRegardlessOfHeight() {
        assertTrue(SmokeWalkTrail.horizontallyReached(
                new SmokeWalkTrail.Point(0, 90, 0), new SmokeWalkTrail.Point(2.5, 100, 0)));
        assertFalse(SmokeWalkTrail.horizontallyReached(
                new SmokeWalkTrail.Point(0, 90, 0), new SmokeWalkTrail.Point(2.51, 90, 0)));
    }

    @Test void startsWithPointAfterNearestAndLooksAheadTwoPoints() {
        var steering = SmokeWalkTrail.steer(TRAIL, TRAIL.getFirst(), -1);
        assertEquals(1, steering.next());
        assertEquals(0, steering.distance());
        assertTrue(steering.points().contains(TRAIL.get(1)));
        assertEquals(TRAIL.get(2), steering.points().getLast());
        assertFalse(steering.points().contains(TRAIL.get(3)));
    }

    @Test void advancesAtAndAfterPassingPointsWithoutGoingBackwards() {
        var first = SmokeWalkTrail.steer(TRAIL, TRAIL.get(1), 1);
        assertEquals(2, first.next());
        var passed = SmokeWalkTrail.steer(TRAIL, new SmokeWalkTrail.Point(10, 7, 4), first.next());
        assertEquals(2, passed.next());
        var second = SmokeWalkTrail.steer(TRAIL, TRAIL.get(2), passed.next());
        assertEquals(3, second.next());
        assertEquals(3, SmokeWalkTrail.steer(TRAIL, TRAIL.get(1), second.next()).next());
    }

    @Test void correctsLaterallyWithoutCuttingTheCorner() {
        var steering = SmokeWalkTrail.steer(TRAIL, new SmokeWalkTrail.Point(4, 2, 3), 1);
        assertEquals(3, steering.distance(), 1e-9);
        assertEquals(new SmokeWalkTrail.Point(5, 2.5, 0), steering.points().getFirst());
        assertTrue(steering.points().indexOf(TRAIL.get(1)) < steering.points().indexOf(TRAIL.get(2)));
    }

    @Test void measuresFourBlockBoundaryFromSegmentsRatherThanSparseVertices() {
        assertEquals(4, SmokeWalkTrail.steer(TRAIL, new SmokeWalkTrail.Point(5, 2.5, -4), -1).distance(), 1e-9);
        assertTrue(SmokeWalkTrail.steer(TRAIL, new SmokeWalkTrail.Point(5, 2.5, -4.01), -1).distance() > 4);
    }
}
