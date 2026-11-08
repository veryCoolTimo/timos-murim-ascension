package io.github.verycooltimo.murim.quest.world;

import io.github.verycooltimo.murim.quest.ClueBlock;
import io.github.verycooltimo.murim.registry.ModBlocks;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The ancestors' stele (quest 6): a ledge on the shoulder west of the North Peak pavilion, a few steps off the trail. The
 * stele stands turned a quarter against the steps (its back, with the old diagram, faces away from the way people come), a
 * groove in the paving runs from its base to a basin with a pale lid, and the fence along the trail has a broken section.
 * Nothing points at it: a player finds it by looking. Laid out in the mountain's local frame, the same at every rotation.
 */
public final class HuaStele {

    /** Centre of the ledge in the mountain's local block frame. */
    public static final int SU = -64, SV = -330;
    /** Half size of the ledge; the steps run south from it. */
    public static final int HALF = 7, STEPS = 9;

    /**
     * The stele and its side quest are parked (author 23.10: «remove the stele and its quest, keep the records, we will remake it»).
     * Worldgen places nothing while this is off; the quest JSON lives in docs/design/parked/stele-quest.json.
     */
    public static final boolean ENABLED = false;

    /** Columns this close (blocks) to a trail sample belong to the trail, not to the ledge or its steps (as {@code HuaTrailMend} counts the trail). */
    public static final double TRAIL_CLEAR = 2.1;

    private static boolean onTrail(MountHuaSite site, int x, int z) {
        double[] trail = site.shape().trailAt(site.centerU(x, z), site.centerV(x, z));
        return trail != null && trail[0] <= TRAIL_CLEAR;
    }

    private HuaStele() {
    }

    /** The ledge's frame: {@code p} east of the stele, {@code q} south of it, {@code y} above the standing level. */
    public record Frame(MountHuaSite site, int level) {
        public static Frame of(MountHuaSite site) {
            if (site == null || site.shape() == null) return null;
            double sum = 0;
            int n = 0;
            for (int p = -3; p <= 3; p += 3) for (int q = -3; q <= 3; q += 3) {
                sum += site.shape().height(SU + p + 0.5, SV + q + 0.5);
                n++;
            }
            return new Frame(site, (int) Math.round(site.worldY(sum / n)));
        }

        public int[] world(int p, int q) {
            int[] r = site.rotateDir(SU + p, SV + q);
            return new int[] {site.centerX() + r[0], site.centerZ() + r[1]};
        }

        public BlockPos pos(int p, int q, int y) {
            int[] xz = world(p, q);
            return new BlockPos(xz[0], level + y, xz[1]);
        }

        public int[] local(int x, int z) {
            int[] uv = site.stampBlock(x, z);
            return new int[] {uv[0] - SU, uv[1] - SV};
        }

        /** Direction in the world of the local direction (dp, dq). */
        public Direction dir(int dp, int dq) {
            int[] r = site.rotateDir(dp, dq);
            return Direction.fromDelta(r[0], 0, r[1]);
        }

        public BoundingBox box() {
            int[][] c = {world(-HALF - 2, -HALF - 2), world(HALF + 2, STEPS + HALF + 1)};
            return new BoundingBox(Math.min(c[0][0], c[1][0]) - 1, level - 30, Math.min(c[0][1], c[1][1]) - 1,
                    Math.max(c[0][0], c[1][0]) + 1, level + 14, Math.max(c[0][1], c[1][1]) + 1);
        }
    }

    public static void apply(WorldGenLevel level, ChunkPos cp, MountHuaSite site) {
        if (!ENABLED) return;
        Frame f = Frame.of(site);
        if (f == null) return;
        BoundingBox box = f.box();
        if (box.maxX() < cp.getMinBlockX() || box.minX() > cp.getMaxBlockX() || box.maxZ() < cp.getMinBlockZ() || box.minZ() > cp.getMaxBlockZ()) return;
        ChunkAccess chunk = level.getChunk(cp.x, cp.z);
        int maxY = level.getMaxBuildHeight() - 2;
        for (int x = Math.max(cp.getMinBlockX(), box.minX()); x <= Math.min(cp.getMaxBlockX(), box.maxX()); x++) {
            for (int z = Math.max(cp.getMinBlockZ(), box.minZ()); z <= Math.min(cp.getMaxBlockZ(), box.maxZ()); z++) {
                int[] l = f.local(x, z);
                int p = l[0], q = l[1];
                int ground = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15);
                boolean ledge = Math.abs(p) <= HALF && Math.abs(q) <= HALF;
                int step = q - HALF; // 1.. on the stairs
                boolean stairs = Math.abs(p) <= 1 && step >= 1 && step <= STEPS;
                if (!ledge && !stairs) continue;
                // The trail wins: the last steps of the run end on the trail bed, 15 blocks under the ledge, and this filled their
                // columns to the ledge's height, a plug of rock in the trail (author's world 23.10, x=-3728 z=-410..-408).
                if (onTrail(site, x, z)) continue;
                int top = ledge ? f.level() - 1 : f.level() - 1 - step;
                BlockState surface = stairs ? stair(f) : surface(p, q);
                column(level, x, z, ground, top, surface, ledge && (Math.abs(p) == HALF || Math.abs(q) == HALF) || stairs, maxY);
            }
        }
        decor(level, cp, f);
    }

    /** Fills or cuts one column so that its top solid block is at {@code top}. */
    private static void column(WorldGenLevel level, int x, int z, int ground, int top, BlockState surface, boolean wall, int maxY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = Math.min(ground, top) - 1; y < top; y++) {
            pos.set(x, y, z);
            level.setBlock(pos, (wall ? ModBlocks.HUA_GRANITE : ModBlocks.HUA_GRANITE_DARK).get().defaultBlockState(), 2);
        }
        level.setBlock(pos.set(x, top, z), surface, 2);
        for (int y = top + 1; y <= Math.min(maxY, Math.max(ground, top) + 10); y++) {
            pos.set(x, y, z);
            if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static BlockState stair(Frame f) {
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    private static BlockState surface(int p, int q) {
        double n = (DryStreamCaveShape.noise(p, q, 77) + 1) / 2;
        Block b = n < 0.55 ? ModBlocks.POLISHED_HUA_GRANITE.get() : n < 0.8 ? ModBlocks.HUA_GRANITE_PALE.get()
                : n < 0.92 ? Blocks.MOSSY_STONE_BRICKS : ModBlocks.HUA_GRANITE_MOSSY.get();
        return b.defaultBlockState();
    }

    private static boolean in(ChunkPos cp, BlockPos p) {
        return p.getX() >= cp.getMinBlockX() && p.getX() <= cp.getMaxBlockX() && p.getZ() >= cp.getMinBlockZ() && p.getZ() <= cp.getMaxBlockZ();
    }

    private static void put(WorldGenLevel level, ChunkPos cp, BlockPos p, BlockState s) {
        if (in(cp, p)) level.setBlock(p, s, 2);
    }

    private static void decor(WorldGenLevel level, ChunkPos cp, Frame f) {
        BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState polished = Blocks.POLISHED_ANDESITE.defaultBlockState();
        // the steps: stairs facing the ledge, the middle of the run grown over with moss
        Direction up = f.dir(0, -1);
        for (int k = 1; k <= STEPS; k++) {
            for (int p = -1; p <= 1; p++) {
                BlockPos at = f.pos(p, HALF + k, -1 - k);
                if (onTrail(f.site(), at.getX(), at.getZ())) continue;
                BlockState s = (DryStreamCaveShape.noise(p, k, 5) > 0.4 ? Blocks.MOSSY_STONE_BRICK_STAIRS : Blocks.STONE_BRICK_STAIRS)
                        .defaultBlockState().setValue(StairBlock.FACING, up);
                put(level, cp, at, s);
            }
        }
        // the plinth, and four old mount stones at the corners of a square
        for (int p = -1; p <= 2; p++) for (int q = -2; q <= 2; q++) put(level, cp, f.pos(p, q, 0), brick);
        for (int sx : new int[] {-3, 3}) for (int sq : new int[] {-3, 3}) put(level, cp, f.pos(sx, sq, 0), Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
        // the stele itself: two layers, front (facing the stairs' side) and back
        for (int q = -1; q <= 1; q++) {
            for (int y = 1; y <= 4; y++) {
                put(level, cp, f.pos(0, q, y), polished);
                put(level, cp, f.pos(1, q, y), polished);
            }
            put(level, cp, f.pos(0, q, 5), Blocks.POLISHED_ANDESITE_SLAB.defaultBlockState());
            put(level, cp, f.pos(1, q, 5), Blocks.POLISHED_ANDESITE_SLAB.defaultBlockState());
        }
        if (in(cp, f.pos(0, 0, 2))) ClueBlock.place(level, f.pos(0, 0, 2), "stele_front");
        if (in(cp, f.pos(0, 0, 1))) ClueBlock.place(level, f.pos(0, 0, 1), "stele_low");
        if (in(cp, f.pos(1, 0, 2))) ClueBlock.place(level, f.pos(1, 0, 2), "stele_back");
        if (in(cp, f.pos(-1, 0, 0))) ClueBlock.place(level, f.pos(-1, 0, 0), "stele_base");
        // the groove from the base to the basin, and the basin with its pale lid
        for (int p = 3; p <= 5; p++) {
            put(level, cp, f.pos(p, 0, -1), Blocks.AIR.defaultBlockState());
            put(level, cp, f.pos(p, 0, -2), Blocks.SMOOTH_STONE.defaultBlockState());
        }
        for (int p = 5; p <= 7; p++) for (int q = -1; q <= 1; q++) {
            if (p == 6 && q == 0) continue;
            put(level, cp, f.pos(p, q, -1), Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        }
        put(level, cp, f.pos(6, 0, -2), Blocks.SMOOTH_STONE.defaultBlockState());
        if (in(cp, f.pos(6, 0, -1))) ClueBlock.place(level, f.pos(6, 0, -1), "stele_lid");
        // the fence: a low wall round the ledge; the south side has a broken section where the steps come up
        BlockState wall = Blocks.COBBLESTONE_WALL.defaultBlockState();
        for (int i = -HALF; i <= HALF; i++) {
            put(level, cp, f.pos(i, -HALF, 0), wall);
            put(level, cp, f.pos(-HALF, i, 0), wall);
            put(level, cp, f.pos(HALF, i, 0), wall);
            boolean gap = i >= -2 && i <= 2;
            if (!gap && DryStreamCaveShape.noise(i, 3, 12) > -0.5) put(level, cp, f.pos(i, HALF, 0), wall);
        }
        // fallen pieces of the broken fence
        put(level, cp, f.pos(-2, HALF, 0), Blocks.COBBLESTONE.defaultBlockState());
        put(level, cp, f.pos(2, HALF - 1, 0), Blocks.MOSSY_COBBLESTONE_SLAB.defaultBlockState());
        put(level, cp, f.pos(-3, HALF + 1, -1), Blocks.COBBLESTONE_SLAB.defaultBlockState());
    }

    static BlockState wall(Block b) {
        return b.defaultBlockState().setValue(WallBlock.UP, true);
    }
}
