package io.github.verycooltimo.murim.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Pure terrain policy for the dev walker; no world access or persistent state. */
public final class SmokeWalkHops {
    public static final int FAILURE_LIMIT = 6;
    private static final int[] LENGTHS = {32, 24, 16, 8};
    private static final int[] OFFSETS = {0, 8, -8, 16, -16, 24, -24};

    private SmokeWalkHops() { }

    public enum Fluid { LAND, WATER, LAVA, OCEAN }
    public record Surface(double y, Fluid fluid, String obstruction) {
        public Surface(double y, Fluid fluid, boolean blocked) { this(y, fluid, blocked ? "blocked_solid" : ""); }
        public Surface(double y, Fluid fluid) { this(y, fluid, ""); }
    }
    public enum Block { PASSABLE, LEAVES, TRUNK, SOLID, OBSTACLE }
    public record Ground(int y, String obstruction) { }

    public static String blocked(Block feet, Block head) {
        if (feet == Block.TRUNK || head == Block.TRUNK) return "blocked_trunk";
        if (feet == Block.SOLID || head == Block.SOLID || feet == Block.OBSTACLE || head == Block.OBSTACLE) return "blocked_solid";
        return feet == Block.LEAVES || head == Block.LEAVES ? "blocked_leaves" : "";
    }

    /** Search down from the heightmap and up from the player, preferring nearby clear ground. */
    public static Ground ground(int playerY, int heightmapY, java.util.function.IntFunction<Block> column) {
        Ground best = null;
        for (int y = playerY - 12; y <= playerY + 12; y++) {
            if (y > heightmapY || column.apply(y - 1) != Block.SOLID) continue;
            String reason = blocked(column.apply(y), column.apply(y + 1));
            if (best == null || (best.obstruction().isEmpty() == reason.isEmpty()
                    && Math.abs(y - playerY) < Math.abs(best.y() - playerY))
                    || (!best.obstruction().isEmpty() && reason.isEmpty())) best = new Ground(y, reason);
        }
        return best == null ? new Ground(playerY, blocked(column.apply(playerY), column.apply(playerY + 1)).isEmpty()
                ? "blocked_solid" : blocked(column.apply(playerY), column.apply(playerY + 1))) : best;
    }

    public static boolean cutLeaves(Block firstObstacle) { return firstObstacle == Block.LEAVES; }

    public record Hop(double x, double y, double z, boolean swimming) { }
    public record Selection(Hop hop, boolean fallback, Map<String, Integer> rejections) { }

    /** A grounded bush is walkable; only a supporting leaf over passable space needs descent. */
    public static boolean canopy(boolean onGround, boolean leavesBelow, boolean passableUnderLeaf) {
        return onGround && leavesBelow && passableUnderLeaf;
    }

    /** New low-water marks count as progress; jumping and returning to the same Y do not. */
    public record DescentProgress(double lowestY, long progressAt) {
        public DescentProgress sample(double y, long now) {
            return y < lowestY - .05 ? new DescentProgress(y, now) : this;
        }
        public boolean stalled(long now) { return now - progressAt >= 60; }
    }

    public static boolean descend(boolean canopy, boolean onGround, boolean leavesInside) {
        return canopy && (onGround || leavesInside);
    }

    public static boolean descending(boolean active, boolean canopy, boolean solidGround) {
        return !solidGround && (active || canopy);
    }

    public static String fallFailure(boolean descending, double fall, double health, double maxHealth) {
        if (descending && health <= maxHealth * .5) return "canopy_health_at_or_below_half";
        return fall > (descending ? 16 : 3)
                ? descending ? "fall_over_16_blocks" : "fall_over_3_blocks" : "";
    }

    public static boolean unsafeDrop(double fromY, double toY) { return fromY - toY > 3; }

    public static boolean steeringProgress(double startX, double startZ, double x, double z) {
        return Math.hypot(x - startX, z - startZ) >= 2;
    }
    /** Null means unloaded: callers must check before reading height, fluid or biome. */
    public interface Terrain { Surface at(int x, int z); }

    public static boolean exhausted(int consecutiveFailures) { return consecutiveFailures >= FAILURE_LIMIT; }

    public static Selection select(double x, double y, double z, double targetX, double targetZ,
                                   Terrain terrain, Predicate<Hop> reachable) {
        Map<String, Integer> rejections = new LinkedHashMap<>();
        double distance = Math.hypot(targetX - x, targetZ - z);
        if (distance < 1) return new Selection(null, true, Map.of("no_path", 1));
        double dx = (targetX - x) / distance, dz = (targetZ - z) / distance;
        List<Hop> candidates = new ArrayList<>();
        for (int length : LENGTHS) for (int offset : OFFSETS) {
            double hx = Math.floor(x + dx * Math.min(length, distance) - dz * offset) + .5;
            double hz = Math.floor(z + dz * Math.min(length, distance) + dx * offset) + .5;
            if (Math.hypot(targetX - hx, targetZ - hz) >= distance) continue;
            int samples = (int) Math.ceil(Math.hypot(hx - x, hz - z));
            double previousY = y;
            boolean safe = true, swimming = false;
            for (int i = 1; i <= samples; i++) {
                Surface surface = terrain.at((int) Math.floor(x + (hx - x) * i / samples),
                        (int) Math.floor(z + (hz - z) * i / samples));
                String reason = surface == null ? "unloaded"
                        : surface.fluid == Fluid.LAVA || surface.fluid == Fluid.OCEAN ? "water"
                        : unsafeDrop(previousY, surface.y) ? "drop" : !surface.obstruction.isEmpty() ? surface.obstruction : null;
                if (reason != null) {
                    rejections.merge(reason, 1, Integer::sum);
                    safe = false; break;
                }
                previousY = surface.y;
                swimming |= surface.fluid == Fluid.WATER;
            }
            if (safe) candidates.add(new Hop(hx, previousY, hz, swimming));
        }
        // Try every land detour before accepting an inland water crossing.
        candidates.sort(java.util.Comparator.comparing(Hop::swimming));
        for (Hop hop : candidates) {
            if (reachable.test(hop)) return new Selection(hop, false, Map.copyOf(rejections));
            rejections.merge("no_path", 1, Integer::sum);
        }
        return new Selection(candidates.isEmpty() ? null : candidates.getFirst(), true, Map.copyOf(rejections));
    }
}
