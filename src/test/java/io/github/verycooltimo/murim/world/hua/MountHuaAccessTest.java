package io.github.verycooltimo.murim.world.hua;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure terrain regression; the offline Python check additionally resolves the assembled buildings. */
class MountHuaAccessTest {
    private record Cell(int u, int v) { }
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /**
     * Cells two blocks in front of each entrance, taken from the end of the matching 3-wide stub in
     * {@link MountHuaPlan#PATHS}. They moved with the 15.10 plan rework (pads sized to boxes + eaves, elders and
     * ancestors four south, camp and dorms west, treasury path from the north).
     */
    private static final int[][] APRONS = {
        {-65, 98}, {-50, 98}, {-35, 98}, // elder gates
        {44, -1}, {-24, -5}, {-48, 38}, {53, 75}, // treasury, bell, alchemy, scriptures
        {-71, 65}, {-55, 65}, {-73, 23} // dining, kitchen, dorm courtyard
    };

    private static boolean onPath(double[] path, Cell cell) {
        return MountHuaShape.pathAt(path, cell.u + 0.5, cell.v + 0.5)[0] <= MountHuaPlan.pathHalfWidth(path);
    }

    private static int worldY(double nominal, int base) {
        return (int) Math.round(base + nominal * (314 - base) / MountHuaPlan.SUMMIT);
    }

    @Test
    void allDoorApronsAreLevelAndConnectedWithoutTwoBlockJumps() {
        for (long seed : new long[] {20261003L, 7, 1510}) {
            var shape = new MountHuaShape(seed);
            Map<Cell, Double> terrain = new HashMap<>();
            for (int u = -110; u < 70; u++) for (int v = -32; v < 122; v++) {
                Cell c = new Cell(u, v);
                boolean planned = MountHuaPlan.clearFootprint(u + 0.5, v + 0.5);
                if (!planned) for (double[] path : MountHuaPlan.PATHS) if (onPath(path, c)) { planned = true; break; }
                if (planned) terrain.put(c, surface(shape, u + 0.5, v + 0.5));
            }
            for (int base : new int[] {67, 70}) {
                Map<Cell, Integer> cells = new HashMap<>();
                terrain.forEach((cell, height) -> cells.put(cell, worldY(height, base)));
                var pending = new ArrayDeque<Cell>();
                var seen = new HashSet<Cell>();
                // Two seeds: the training square, and the sect gate pad. cafbaf08 (organic pads, "needs follow-up")
                // dropped the plan's stair between them ({14,-19 -> 14,-11}); that approach is the author's hand-built
                // ceremonial gate axis, and a regrade must not carve over it, so the pads are seeded separately.
                for (Cell start : new Cell[] {new Cell(6, 0), new Cell(14, -24)}) {
                    pending.add(start); seen.add(start);
                }
                while (!pending.isEmpty()) {
                    Cell c = pending.removeFirst();
                    for (int[] d : DIRECTIONS) {
                        Cell n = new Cell(c.u + d[0], c.v + d[1]);
                        if (cells.containsKey(n) && !seen.contains(n) && Math.abs(cells.get(c) - cells.get(n)) <= 1) {
                            seen.add(n); pending.add(n);
                        }
                    }
                }
                for (int[] apron : APRONS) {
                    assertTrue(cells.containsKey(new Cell(apron[0], apron[1])), "Apron centre off every pad/path " + apron[0] + "," + apron[1]);
                    int height = cells.get(new Cell(apron[0], apron[1]));
                    for (int du = -1; du <= 1; du++) for (int dv = -1; dv <= 1; dv++) {
                        Cell cell = new Cell(apron[0] + du, apron[1] + dv);
                        assertTrue(seen.contains(cell), "Disconnected apron " + cell + " seed=" + seed);
                        assertEquals(height, cells.get(cell).intValue(), "Sloping apron " + cell);
                    }
                }
                for (double[] path : MountHuaPlan.PATHS) {
                    if (!MountHuaPlan.accessPath(path)) continue;
                    assertEquals(3, MountHuaPlan.pathWidth(path));
                    for (Cell c : cells.keySet()) if (onPath(path, c)) {
                        for (int[] d : DIRECTIONS) {
                            Cell n = new Cell(c.u + d[0], c.v + d[1]);
                            if (cells.containsKey(n) && onPath(path, n)) {
                                assertTrue(Math.abs(cells.get(c) - cells.get(n)) <= 1, "Two-block tread " + c + " -> " + n);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Walking surface of a column: pads and wide or access flights are already in {@code shape.height}; a narrow path
     * over ground more than three blocks lower is a plank bridge at the path's stepped level (MountHuaChunkWriter#bridge),
     * which {@code shape.height} leaves at the ground, so the path level is used there.
     */
    private static double surface(MountHuaShape shape, double u, double v) {
        double h = shape.height(u, v);
        if (MountHuaPlan.clearFootprint(u, v)) return h;
        for (double[] path : MountHuaPlan.PATHS) {
            double[] q = MountHuaShape.pathAt(path, u, v);
            if (q[0] <= MountHuaPlan.pathHalfWidth(path)) return q[1];
        }
        return h;
    }
}
