package io.github.verycooltimo.murim.world.hua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Seed-local, immutable grading tiles. No Minecraft classes; shared by previews and worldgen. */
final class HuaTerraceGrade {
    static final int REACH = 24;
    private record Node(int cell, double height) { }
    private final List<Tile> tiles = new ArrayList<>();

    HuaTerraceGrade(MountHuaShape shape) {
        // Merge overlapping envelopes before sampling: chunk order cannot leave seams between pads.
        List<int[]> regions = new ArrayList<>();
        for (var z : MountHuaPlan.ZONES) {
            if (z.cave() || z.id().equals("grove")) continue;
            int[] r = {(int) Math.floor(z.u() - z.width() / 2.0) - REACH,
                    (int) Math.floor(z.v() - z.depth() / 2.0) - REACH,
                    (int) Math.ceil(z.u() + z.width() / 2.0) + REACH,
                    (int) Math.ceil(z.v() + z.depth() / 2.0) + REACH};
            boolean merged;
            do {
                merged = false;
                for (int i = 0; i < regions.size(); i++) {
                    int[] q = regions.get(i);
                    if (r[0] <= q[2] && q[0] <= r[2] && r[1] <= q[3] && q[1] <= r[3]) {
                        r = new int[] {Math.min(r[0], q[0]), Math.min(r[1], q[1]),
                                Math.max(r[2], q[2]), Math.max(r[3], q[3])};
                        regions.remove(i);
                        merged = true;
                        break;
                    }
                }
            } while (merged);
            regions.add(r);
        }
        for (int[] r : regions) tiles.add(new Tile(shape, r));
    }

    double height(double u, double v) {
        int x = (int) Math.floor(u), z = (int) Math.floor(v);
        for (Tile tile : tiles) {
            if (x >= tile.x0 && x < tile.x0 + tile.width && z >= tile.z0 && z < tile.z0 + tile.depth) {
                int cell = (z - tile.z0) * tile.width + x - tile.x0;
                if (tile.distance[cell] < REACH) return tile.heights[cell];
            }
        }
        return Double.NaN;
    }

    private static final class Tile {
        final int x0, z0, width, depth;
        final double[] heights;
        final double[] distance;

        Tile(MountHuaShape shape, int[] r) {
            x0 = r[0]; z0 = r[1]; width = r[2] - x0; depth = r[3] - z0;
            int size = width * depth;
            heights = new double[size];
            distance = new double[size];
            Arrays.fill(distance, Double.POSITIVE_INFINITY);
            double[] upper = new double[size], negativeLower = new double[size];
            Arrays.fill(upper, Double.POSITIVE_INFINITY);
            Arrays.fill(negativeLower, Double.POSITIVE_INFINITY);
            for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
                int i = z * width + x;
                double u = x0 + x + 0.5, v = z0 + z + 0.5;
                // Two-high rock courses retain the existing wandering rim and noise patches.
                heights[i] = 2 * Math.floor(shape.ungradedTerraces(u, v) / 2);
                double fixed = Double.NaN;
                for (var zone : MountHuaPlan.ZONES) {
                    if (!zone.cave() && !zone.id().equals("grove")) {
                        distance[i] = Math.min(distance[i], Math.hypot(
                                Math.max(0, Math.abs(u - zone.u()) - zone.width() / 2.0),
                                Math.max(0, Math.abs(v - zone.v()) - zone.depth() / 2.0)));
                        if (MountHuaPlan.insidePad(zone, u, v)) fixed = zone.y();
                    }
                }
                if (Double.isNaN(fixed)) {
                    for (double[] path : MountHuaPlan.PATHS) {
                        double[] q = MountHuaShape.pathAt(path, u, v);
                        if (q[0] <= MountHuaPlan.pathHalfWidth(path)
                                && (MountHuaPlan.accessPath(path) || MountHuaPlan.pathWidth(path) > 4
                                || heights[i] >= q[1] - 3)) {
                            fixed = q[1];
                            break;
                        }
                        if (MountHuaShape.pathCheek(path, u, v)) {
                            fixed = q[1] + 1;
                            break;
                        }
                    }
                }
                if (!Double.isNaN(fixed)) {
                    upper[i] = fixed;
                    negativeLower[i] = -fixed;
                }
            }
            relax(upper);
            relax(negativeLower);
            relax(heights);
            for (int i = 0; i < size; i++) {
                if (-negativeLower[i] > upper[i] + 1e-8) {
                    throw new IllegalStateException("Terraces too close at " + (x0 + i % width) + "," + (z0 + i / width) + " bounds " + (-negativeLower[i]) + " > " + upper[i]);
                }
                heights[i] = Math.max(-negativeLower[i], Math.min(upper[i], heights[i]));
            }
        }

        /** Lower envelope of cones; the maximum/minimum of two such fields keeps the same slope. */
        private void relax(double[] field) {
            PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingDouble(Node::height));
            for (int i = 0; i < field.length; i++) if (Double.isFinite(field[i])) queue.add(new Node(i, field[i]));
            while (!queue.isEmpty()) {
                Node node = queue.remove();
                int i = node.cell();
                if (node.height() != field[i]) continue;
                int x = i % width, z = i / width;
                if (x > 0) offer(field, queue, i - 1, node.height() + cost(i, i - 1));
                if (x + 1 < width) offer(field, queue, i + 1, node.height() + cost(i, i + 1));
                if (z > 0) offer(field, queue, i - width, node.height() + cost(i, i - width));
                if (z + 1 < depth) offer(field, queue, i + width, node.height() + cost(i, i + width));
            }
        }

        private double cost(int a, int b) {
            double d = Math.min(distance[a], distance[b]);
            // Only the building banks are graded. The actual mountain between summit sites and
            // the sect must retain its steep faces and the existing training climb.
            return d <= 4 ? 2 : d <= 16 ? 16 : 64;
        }

        private static void offer(double[] field, PriorityQueue<Node> queue, int i, double h) {
            if (h < field[i]) {
                field[i] = h;
                queue.add(new Node(i, h));
            }
        }
    }
}
