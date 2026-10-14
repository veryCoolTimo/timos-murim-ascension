package io.github.verycooltimo.murim.world.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

class TimberOffsetTest {

    @Test
    void clickZonesAreThirds() {
        assertEquals(TimberOffset.CENTER, TimberOffset.fromLocal(0.5, 0.5));
        assertEquals(TimberOffset.NORTH, TimberOffset.fromLocal(0.5, 0.1));
        assertEquals(TimberOffset.SOUTH_EAST, TimberOffset.fromLocal(0.9, 0.95));
        assertEquals(TimberOffset.WEST, TimberOffset.fromLocal(0.0, 0.5));
        // clicked the side of a wall: the coordinate across the face is 0 or 1 and gets clamped
        assertEquals(TimberOffset.NORTH_WEST, TimberOffset.fromLocal(-0.3, -0.01));
    }

    @Test
    void cycleVisitsEverySlotOnce() {
        Set<TimberOffset> seen = EnumSet.noneOf(TimberOffset.class);
        TimberOffset o = TimberOffset.CENTER;
        for (int i = 0; i < 9; i++) {
            seen.add(o);
            o = o.next();
        }
        assertEquals(9, seen.size());
        assertEquals(TimberOffset.CENTER, o);
    }

    @Test
    void rotationTurnsNorthToEast() {
        assertEquals(TimberOffset.EAST, TimberOffset.NORTH.rotate(Rotation.CLOCKWISE_90));
        assertEquals(TimberOffset.SOUTH_WEST, TimberOffset.NORTH_EAST.rotate(Rotation.CLOCKWISE_180));
        assertEquals(TimberOffset.NORTH_WEST, TimberOffset.NORTH_EAST.rotate(Rotation.COUNTERCLOCKWISE_90));
        assertEquals(TimberOffset.EAST, TimberOffset.EAST.withAlong(Direction.Axis.Z, 0));
        assertEquals(1, TimberOffset.SOUTH_EAST.along(Direction.Axis.X));
    }

    @Test
    void edgeSlotsSitHalfAPixelOffTheFace() {
        assertEquals(3.0, TimberOffset.centrePx(-1));
        assertEquals(13.0, TimberOffset.centrePx(1));
    }
}
