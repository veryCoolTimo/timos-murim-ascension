package io.github.verycooltimo.murim.client.vfx.fx;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxStrokeMathTest {
    @Test void lengthsAccumulateAlongThePath() {
        double[] l = FxStrokeMath.lengths(List.of(new Vec3(0, 0, 0), new Vec3(3, 0, 0), new Vec3(3, 4, 0)));
        assertEquals(0, l[0], 1e-9);
        assertEquals(3, l[1], 1e-9);
        assertEquals(7, l[2], 1e-9);
    }

    @Test void sideIsAcrossThePathAndTheLineOfSight() {
        var path = List.of(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(2, 0, 0));
        Vec3 side = FxStrokeMath.side(path, 1, new Vec3(1, 0, 5), null);
        assertEquals(1, side.length(), 1e-9);
        assertEquals(0, side.dot(new Vec3(1, 0, 0)), 1e-9);
        assertEquals(0, side.dot(new Vec3(0, 0, 1)), 1e-9);
    }

    @Test void sideKeepsItsDirectionWhenThePathPointsAtTheCamera() {
        var path = List.of(new Vec3(0, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, 2));
        Vec3 previous = new Vec3(1, 0, 0);
        assertEquals(previous, FxStrokeMath.side(path, 1, new Vec3(0, 0, 9), previous));
    }

    @Test void sideDoesNotFlipBetweenNeighbours() {
        var path = List.of(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(2, 0, 0));
        Vec3 first = FxStrokeMath.side(path, 0, new Vec3(1, 0, 5), null);
        Vec3 flipped = FxStrokeMath.side(path, 1, new Vec3(1, 0, 5), first.scale(-1));
        assertTrue(flipped.dot(first.scale(-1)) > 0);
    }

    @Test void bezierStartsAndEndsOnItsControlPoints() {
        var curve = FxStrokeMath.bezier(List.of(new Vec3(0, 0, 0), new Vec3(1, 2, 0), new Vec3(2, 0, 0)), 10);
        assertEquals(11, curve.size());
        assertEquals(new Vec3(0, 0, 0), curve.get(0));
        assertEquals(2, curve.get(10).x, 1e-9);
        assertEquals(1, curve.get(5).y, 1e-9);
    }
}
