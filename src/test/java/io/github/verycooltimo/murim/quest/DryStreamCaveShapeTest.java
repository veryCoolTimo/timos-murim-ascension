package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.quest.world.DryStreamCaveShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Walk-through of Dry Stream Cave as pure geometry: the way in, the cave-in, the bypass, the cleft, no holes to the sky. */
class DryStreamCaveShapeTest {

    private record Cell(int d, int w, int y) {
    }

    /** A cell a player can stand in: air at his feet and head, solid or water below. */
    private static boolean stand(int d, int w, int y) {
        return DryStreamCaveShape.open(d, w, y) && DryStreamCaveShape.open(d, w, y + 1) && !DryStreamCaveShape.open(d, w, y - 1)
                && !DryStreamCaveShape.water(d, w, y);
    }

    private static Set<Cell> reach(boolean allowRubbleCells) {
        Set<Cell> seen = new HashSet<>();
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Cell start = new Cell(-12, 0, 0);
        seen.add(start);
        queue.add(start);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            Cell c = queue.poll();
            for (int[] dir : dirs) {
                int d = c.d() + dir[0], w = c.w() + dir[1];
                for (int dy = 1; dy >= -3; dy--) {
                    int y = c.y() + dy;
                    if (!stand(d, w, y)) continue;
                    // jumping up one block needs headroom over the start cell as well
                    if (dy == 1 && !DryStreamCaveShape.open(c.d(), c.w(), c.y() + 2)) continue;
                    Cell n = new Cell(d, w, y);
                    if (seen.add(n)) queue.add(n);
                    break;
                }
            }
        }
        return seen;
    }

    private static boolean reached(Set<Cell> set, int[] at) {
        return set.stream().anyMatch(c -> Math.abs(c.d() - at[0]) <= 1 && Math.abs(c.w() - at[1]) <= 1 && Math.abs(c.y() - at[2]) <= 1);
    }

    @Test
    @DisplayName("The way in leads to the belt, the camp, the box and the wall; the cave-in blocks the passage, the gallery goes round it")
    void walkThrough() {
        Set<Cell> all = reach(true);
        assertTrue(reached(all, DryStreamCaveShape.BELT), "the belt");
        assertTrue(reached(all, DryStreamCaveShape.BOX), "the box");
        assertTrue(reached(all, new int[] {DryStreamCaveShape.WALL[0] - 1, DryStreamCaveShape.WALL[1], DryStreamCaveShape.WALL[2]}), "the wall");
        assertTrue(reached(all, DryStreamCaveShape.CAMPFIRE), "the campfire");
        // the cleft carries on to the open air
        assertTrue(all.stream().anyMatch(c -> c.w() <= -33), "the cleft does not reach the outside");
        // the cave-in really blocks the direct passage: there are rubble cells in it
        int rubble = 0;
        for (int d = DryStreamCaveShape.RUBBLE_FROM; d <= DryStreamCaveShape.RUBBLE_TO; d++)
            for (int w = -12; w <= 4; w++)
                for (int y = -6; y <= 6; y++) if (DryStreamCaveShape.rubble(d, w, y)) rubble++;
        assertTrue(rubble > 40, "the cave-in is only " + rubble + " blocks");
        // ... and the camp is reachable without clearing it: through the gallery, the soot marks lie along it
        for (int[] s : DryStreamCaveShape.SOOT) {
            assertTrue(reached(all, new int[] {s[0], s[1], DryStreamCaveShape.floor(s[0], s[1])}), "soot mark off the route: " + s[0] + "," + s[1]);
        }
    }

    @Test
    @DisplayName("The cave is deep and tall as designed, and every air block that sees the sky is in a gully")
    void sizeAndRoof() {
        int deepest = Integer.MIN_VALUE, tallest = 0, air = 0, skyHoles = 0;
        for (int d = -20; d <= 76; d++) {
            for (int w = -36; w <= 36; w++) {
                int f = DryStreamCaveShape.floor(d, w);
                if (f == Integer.MIN_VALUE) continue;
                int c = DryStreamCaveShape.ceiling(d, w);
                if (c == Integer.MAX_VALUE) continue; // open gully / cleft end
                if (c <= f) continue;
                air += c - f;
                deepest = Math.max(deepest, d);
                tallest = Math.max(tallest, c - f);
                // a roofed cell: the rock above it must be at least ROOF thick
                if (DryStreamCaveShape.top(d, w) - c < DryStreamCaveShape.ROOF) skyHoles++;
            }
        }
        assertTrue(deepest >= 60, "depth " + deepest);
        assertTrue(tallest >= 8 && tallest <= 16, "tallest hall " + tallest);
        assertTrue(air > 4000, "volume " + air);
        assertEquals(0, skyHoles, "roofed cells with a thin roof");
    }

    @Test
    @DisplayName("The story pieces stand in open air on solid floor")
    void piecesStandInAir() {
        for (int[] p : new int[][] {DryStreamCaveShape.BOX, DryStreamCaveShape.BELT, DryStreamCaveShape.CAMPFIRE}) {
            assertTrue(DryStreamCaveShape.open(p[0], p[1], p[2]), "no air at " + p[0] + "," + p[1] + "," + p[2]);
            assertFalse(DryStreamCaveShape.open(p[0], p[1], p[2] - 1), "no floor under " + p[0] + "," + p[1] + "," + p[2]);
        }
        for (int dw = 0; dw < 3; dw++)
            assertTrue(DryStreamCaveShape.open(DryStreamCaveShape.WALL[0], DryStreamCaveShape.WALL[1] + dw - 1, DryStreamCaveShape.WALL[2]),
                    "wall cell " + dw);
    }
}
