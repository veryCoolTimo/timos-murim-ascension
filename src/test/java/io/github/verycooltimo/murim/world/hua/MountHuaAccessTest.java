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
    private static final int[][] APRONS = {
        {-65, 85}, {-50, 85}, {-35, 85}, // elder gates
        {36, 1}, {-24, -5}, {-35, 41}, {42, 75}, // treasury, bell, alchemy, scriptures
        {-71, 68}, {-55, 68}, {-60, 31} // dining, kitchen, dorm courtyard
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
            for (int u = -90; u < 58; u++) for (int v = -18; v < 116; v++) {
                Cell c = new Cell(u, v);
                boolean planned = MountHuaPlan.ZONES.stream().anyMatch(z -> !z.cave() && !z.id().equals("grove")
                        && Math.abs(uOffset(c, z)) < z.width() / 2.0 && Math.abs(vOffset(c, z)) < z.depth() / 2.0);
                if (!planned) for (double[] path : MountHuaPlan.PATHS) if (onPath(path, c)) { planned = true; break; }
                if (planned) terrain.put(c, shape.height(u + 0.5, v + 0.5));
            }
            for (int base : new int[] {67, 70}) {
                Map<Cell, Integer> cells = new HashMap<>();
                terrain.forEach((cell, height) -> cells.put(cell, worldY(height, base)));
                var pending = new ArrayDeque<Cell>();
                var seen = new HashSet<Cell>();
                Cell start = new Cell(6, 0);
                pending.add(start); seen.add(start);
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

    private static double uOffset(Cell c, MountHuaPlan.Zone z) { return c.u + 0.5 - z.u(); }
    private static double vOffset(Cell c, MountHuaPlan.Zone z) { return c.v + 0.5 - z.v(); }
}
